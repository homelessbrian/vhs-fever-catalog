#!/usr/bin/env python3
"""Small, metadata-only client for the Video Tapes dot io read API.

The client deliberately returns provider payloads unchanged: response records
are normalized only after the live API contract has been verified. It never
fetches sleeve image bytes.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable

API_BASE = "https://videotapes.io/api/v1"


class VideoTapesAPIError(RuntimeError):
    """Raised for API failures other than a withdrawn sleeve."""


class VideoTapesClient:
    def __init__(
        self,
        token: str | None = None,
        *,
        opener: Callable[..., Any] | None = None,
        sleep: Callable[[float], None] = time.sleep,
        max_retries: int = 2,
    ) -> None:
        self.token = token or os.environ.get("VIDEOTAPES_API_TOKEN")
        if not self.token:
            raise ValueError("VIDEOTAPES_API_TOKEN is required")
        self.opener = opener or urllib.request.urlopen
        self.sleep = sleep
        self.max_retries = max(0, max_retries)

    def _request(
        self,
        method: str,
        path: str,
        *,
        query: dict[str, str] | None = None,
        body: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        url = API_BASE + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        payload = None if body is None else json.dumps(body).encode("utf-8")
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
            "User-Agent": "VHS-FEVER-Catalog/0.4",
        }
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=payload, headers=headers, method=method)

        for attempt in range(self.max_retries + 1):
            try:
                with self.opener(request, timeout=25) as response:
                    decoded = json.loads(response.read().decode("utf-8"))
                if not isinstance(decoded, dict):
                    raise VideoTapesAPIError("API returned a non-object JSON response")
                return decoded
            except urllib.error.HTTPError as exc:
                if exc.code == 410 and path.startswith("/sleeves/"):
                    return {"status": "withdrawn", "http_status": 410}
                if exc.code == 429 and attempt < self.max_retries:
                    retry_after = exc.headers.get("Retry-After", "2")
                    try:
                        delay = max(0.0, float(retry_after))
                    except (TypeError, ValueError):
                        delay = 2.0
                    self.sleep(delay)
                    continue
                detail = exc.read().decode("utf-8", errors="replace")[:300]
                raise VideoTapesAPIError(f"Video Tapes API HTTP {exc.code}: {detail}") from exc
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                if attempt < self.max_retries and isinstance(exc, (urllib.error.URLError, TimeoutError)):
                    self.sleep(1.0 * (attempt + 1))
                    continue
                raise VideoTapesAPIError(f"Video Tapes API request failed: {exc}") from exc
        raise VideoTapesAPIError("Video Tapes API retry limit reached")

    def get_film(self, tmdb_id: int | str) -> dict[str, Any]:
        """Return film metadata and associated sleeve records."""
        return self._request("GET", f"/films/{urllib.parse.quote(str(tmdb_id), safe='')}")

    def get_preferred_sleeve(
        self, tmdb_id: int | str, region: str = "US"
    ) -> dict[str, Any]:
        return self._request(
            "GET",
            f"/films/{urllib.parse.quote(str(tmdb_id), safe='')}/preferred",
            query={"region": region},
        )

    def lookup_preferred_batch(self, tmdb_ids: list[int | str]) -> dict[str, Any]:
        """Look up preferred sleeves in one provider batch (maximum 500 IDs)."""
        if not tmdb_ids:
            raise ValueError("At least one TMDB ID is required")
        if len(tmdb_ids) > 500:
            raise ValueError("Video Tapes batch lookups support at most 500 TMDB IDs")
        ids = [int(value) for value in tmdb_ids]
        return self._request("POST", "/preferred/lookup", body={"tmdb_ids": ids})

    def get_sleeve(self, sleeve_id: int | str) -> dict[str, Any]:
        """Return sleeve metadata; a 410 response is represented as withdrawn."""
        return self._request(
            "GET", f"/sleeves/{urllib.parse.quote(str(sleeve_id), safe='')}"
        )


def image_reference(sleeve_id: int | str, part: str = "spread", width: int | None = None) -> str:
    """Build a provider image URL reference; never downloads or proxies the image."""
    if part not in {"spread", "front", "spine", "back"}:
        raise ValueError("part must be spread, front, spine, or back")
    query = {"part": part}
    if width is not None:
        if width < 1:
            raise ValueError("width must be positive")
        query["w"] = str(width)
    return f"{API_BASE}/sleeves/{urllib.parse.quote(str(sleeve_id), safe='')}/image?{urllib.parse.urlencode(query)}"
