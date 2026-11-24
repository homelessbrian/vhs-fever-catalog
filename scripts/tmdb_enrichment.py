#!/usr/bin/env python3
"""TMDB-first title search and conservative, reviewable candidate scoring."""
from __future__ import annotations

import hashlib
import json
import os
import re
from difflib import SequenceMatcher
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from typing import Any, Callable

TMDB_API_BASE = "https://api.themoviedb.org/3"
DEFAULT_LANGUAGE = "en-US"
MAX_CANDIDATES = 10


class TMDBAPIError(RuntimeError):
    """A safe, credential-free TMDB API error."""


def normalize_title(value: Any) -> str:
    """Normalize title text for comparison without changing the stored source title."""
    if not isinstance(value, str):
        return ""
    value = value.casefold()
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return " ".join(value.split())


def _candidate_title(candidate: dict[str, Any], media_type: str) -> str:
    keys = ("title", "original_title") if media_type == "movie" else ("name", "original_name")
    for key in keys:
        value = candidate.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _candidate_year(candidate: dict[str, Any], media_type: str) -> int | None:
    date_value = candidate.get("release_date" if media_type == "movie" else "first_air_date")
    if not isinstance(date_value, str) or len(date_value) < 4:
        return None
    try:
        year = int(date_value[:4])
    except ValueError:
        return None
    return year if 1800 <= year <= 2200 else None


def score_tmdb_candidate(source: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    """Score one TMDB result and explain the evidence; never auto-confirm it."""
    media_type = candidate.get("media_type")
    if media_type not in {"movie", "tv"}:
        media_type = source.get("media_type") if source.get("media_type") in {"movie", "tv"} else "movie"

    source_title = normalize_title(source.get("title"))
    candidate_title = normalize_title(_candidate_title(candidate, media_type))
    similarity = SequenceMatcher(None, source_title, candidate_title).ratio() if source_title and candidate_title else 0.0
    evidence: list[str] = []

    if source_title and candidate_title and source_title == candidate_title:
        evidence.append("exact_title")
    elif similarity >= 0.92:
        evidence.append("near_title")
    elif similarity >= 0.75:
        evidence.append("similar_title")
    else:
        evidence.append("weak_title_similarity")

    source_year = source.get("year")
    if isinstance(source_year, bool) or not isinstance(source_year, int):
        source_year = None
    candidate_year = _candidate_year(candidate, media_type)
    year_match = source_year is not None and candidate_year is not None and source_year == candidate_year
    year_conflict = source_year is not None and candidate_year is not None and source_year != candidate_year
    if year_match:
        evidence.append("year_match")
    elif year_conflict:
        evidence.append("year_conflict")
    else:
        evidence.append("year_unavailable")

    source_type = source.get("media_type")
    type_conflict = source_type in {"movie", "tv"} and source_type != media_type
    if type_conflict:
        evidence.append("media_type_conflict")
    elif source_type in {"movie", "tv"}:
        evidence.append("media_type_match")

    score = round(similarity * 85)
    if year_match:
        score += 15
    elif year_conflict:
        score -= 25
    if type_conflict:
        score -= 40
    score = max(0, min(100, score))

    if similarity >= 0.92 and year_match and not type_conflict:
        confidence = "high"
    elif similarity >= 0.75 and not year_conflict and not type_conflict:
        confidence = "medium"
    else:
        confidence = "low"

    return {
        "score": score,
        "confidence": confidence,
        "evidence": evidence,
        "title_similarity": round(similarity, 4),
        "year": candidate_year,
        "media_type": media_type,
        "title": _candidate_title(candidate, media_type),
    }


def build_identity_match(source: dict[str, Any], candidates: list[dict[str, Any]]) -> dict[str, Any]:
    """Build schema-compatible review data; candidate matches are never auto-confirmed."""
    ranked: list[tuple[int, dict[str, Any]]] = []
    for candidate in candidates:
        if not isinstance(candidate, dict) or not candidate.get("id"):
            continue
        scored = score_tmdb_candidate(source, candidate)
        ranked.append((scored["score"], {
            "provider": "tmdb",
            "provider_id": str(candidate["id"]),
            "media_type": scored["media_type"],
            "title": scored["title"] or "Untitled TMDB result",
            "year": scored["year"],
            "confidence": scored["confidence"],
            "evidence": scored["evidence"],
        }))
    ranked.sort(key=lambda item: (-item[0], item[1]["title"].casefold(), item[1]["provider_id"]))
    review_candidates = [item[1] for item in ranked[:MAX_CANDIDATES]]
    if not review_candidates:
        return {
            "status": "unmatched",
            "provider": None,
            "provider_id": None,
            "media_type": None,
            "title": None,
            "year": None,
            "confidence": "none",
            "evidence": [],
            "candidates": [],
            "external_ids": {},
        }
    best = review_candidates[0]
    return {
        "status": "candidate",
        "provider": None,
        "provider_id": None,
        "media_type": None,
        "title": None,
        "year": None,
        "confidence": best["confidence"],
        "evidence": best["evidence"],
        "candidates": review_candidates,
        "external_ids": {},
    }


def _default_http_get(url: str, headers: dict[str, str], timeout: int) -> dict[str, Any]:
    request = Request(url, headers=headers, method="GET")
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        # Do not include request URLs or headers: either may contain credentials.
        raise TMDBAPIError(f"TMDB request failed with HTTP {exc.code}") from None
    except URLError as exc:
        raise TMDBAPIError(f"TMDB connection failed: {exc.reason}") from None
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise TMDBAPIError("TMDB returned an invalid JSON response") from None


class TMDBClient:
    """Small read-only TMDB v3 client; inject http_get for deterministic tests."""

    def __init__(
        self,
        bearer_token: str | None = None,
        *,
        http_get: Callable[[str, dict[str, str], int], dict[str, Any]] | None = None,
        timeout: int = 15,
    ) -> None:
        self.bearer_token = (bearer_token or os.environ.get("TMDB_BEARER_TOKEN") or "").strip()
        self.http_get = http_get or _default_http_get
        self.timeout = timeout

    def _get_json(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        if not self.bearer_token:
            raise ValueError("Set TMDB_BEARER_TOKEN before using the TMDB API")
        query = urlencode({key: value for key, value in params.items() if value is not None})
        url = f"{TMDB_API_BASE}{path}?{query}"
        payload = self.http_get(
            url,
            {"Authorization": f"Bearer {self.bearer_token}", "Accept": "application/json"},
            self.timeout,
        )
        if not isinstance(payload, dict):
            raise TMDBAPIError("TMDB returned an unexpected response")
        if payload.get("success") is False:
            raise TMDBAPIError("TMDB rejected the request")
        return payload

    def search_candidates(
        self,
        title: str,
        *,
        year: int | None = None,
        media_type: str | None = None,
        language: str = DEFAULT_LANGUAGE,
    ) -> list[dict[str, Any]]:
        """Search movies and TV when type is unknown; otherwise search the known type."""
        if not isinstance(title, str) or not title.strip():
            raise ValueError("A non-empty source title is required")
        if media_type not in {None, "movie", "tv"}:
            raise ValueError("media_type must be movie, tv, or None")

        types = [media_type] if media_type else ["movie", "tv"]
        results: list[dict[str, Any]] = []
        for kind in types:
            params: dict[str, Any] = {
                "query": title.strip(),
                "language": language,
                "include_adult": "false",
            }
            if year is not None:
                params["year" if kind == "movie" else "first_air_date_year"] = year
            payload = self._get_json(f"/search/{kind}", params)
            rows = payload.get("results", [])
            if not isinstance(rows, list):
                raise TMDBAPIError("TMDB search response did not contain a results array")
            for row in rows:
                if isinstance(row, dict):
                    result = dict(row)
                    result["media_type"] = kind
                    results.append(result)
        return results

    def get_title_details(
        self,
        media_type: str,
        provider_id: int | str,
        *,
        language: str = DEFAULT_LANGUAGE,
        include_image_language: str = "en,null",
    ) -> dict[str, Any]:
        """Fetch title details, external IDs, and image metadata in one request."""
        if media_type not in {"movie", "tv"}:
            raise ValueError("media_type must be movie or tv")
        try:
            numeric_id = int(provider_id)
        except (TypeError, ValueError):
            raise ValueError("provider_id must be a positive TMDB integer") from None
        if numeric_id < 1:
            raise ValueError("provider_id must be a positive TMDB integer")
        return self._get_json(
            f"/{media_type}/{numeric_id}",
            {
                "language": language,
                "append_to_response": "external_ids,images",
                "include_image_language": include_image_language,
            },
        )

    def get_configuration(self) -> dict[str, Any]:
        """Fetch TMDB image configuration once per client instance."""
        cached = getattr(self, "_configuration_cache", None)
        if cached is None:
            cached = self._get_json("/configuration", {})
            self._configuration_cache = cached
        return cached


def build_tmdb_metadata(details: dict[str, Any], media_type: str) -> dict[str, Any]:
    """Normalize useful TMDB title fields without mixing in source/archive metadata."""
    if media_type not in {"movie", "tv"}:
        raise ValueError("media_type must be movie or tv")
    title = details.get("title") if media_type == "movie" else details.get("name")
    original_title = details.get("original_title") if media_type == "movie" else details.get("original_name")
    date_field = "release_date" if media_type == "movie" else "first_air_date"
    date_value = details.get(date_field)
    year = None
    if isinstance(date_value, str) and len(date_value) >= 4:
        try:
            year = int(date_value[:4])
        except ValueError:
            year = None

    runtime = details.get("runtime") if media_type == "movie" else None
    if media_type == "tv":
        runtimes = details.get("episode_run_time")
        if isinstance(runtimes, list):
            runtime = next((value for value in runtimes if isinstance(value, int) and not isinstance(value, bool) and value > 0), None)

    external_ids = details.get("external_ids")
    if not isinstance(external_ids, dict):
        external_ids = {}
    genres = details.get("genres")
    if not isinstance(genres, list):
        genres = []

    return {
        "provider_id": str(details["id"]) if details.get("id") is not None else None,
        "media_type": media_type,
        "title": title if isinstance(title, str) else None,
        "original_title": original_title if isinstance(original_title, str) else None,
        "year": year,
        "release_or_first_air_date": date_value if isinstance(date_value, str) else None,
        "runtime_minutes": runtime if isinstance(runtime, int) and not isinstance(runtime, bool) else None,
        "overview": details.get("overview") if isinstance(details.get("overview"), str) else None,
        "original_language": details.get("original_language") if isinstance(details.get("original_language"), str) else None,
        "genres": [
            {"id": genre.get("id"), "name": genre.get("name")}
            for genre in genres if isinstance(genre, dict)
        ],
        "external_ids": {
            "imdb_id": external_ids.get("imdb_id")
            if isinstance(external_ids.get("imdb_id"), str) and external_ids.get("imdb_id").startswith("tt")
            else None
        },
        "poster_path": details.get("poster_path") if isinstance(details.get("poster_path"), str) else None,
        "backdrop_path": details.get("backdrop_path") if isinstance(details.get("backdrop_path"), str) else None,
    }


def _tmdb_image_url(base_url: str, sizes: Any, file_path: Any, preferred_size: str) -> str | None:
    if not isinstance(base_url, str) or not base_url.startswith("https://"):
        return None
    if not isinstance(file_path, str) or not file_path.startswith("/") or file_path.startswith("//"):
        return None
    if "://" in file_path or "\\\\" in file_path or "?" in file_path or "#" in file_path:
        return None
    if any(part == ".." for part in file_path.split("/")):
        return None
    allowed_sizes = sizes if isinstance(sizes, list) else []
    size = preferred_size if preferred_size in allowed_sizes else "original"
    if size != "original" and "original" not in allowed_sizes:
        return None
    return base_url.rstrip("/") + "/" + size + file_path


def build_tmdb_artwork_assets(
    details: dict[str, Any],
    configuration: dict[str, Any],
    *,
    display_status: str = "pending_review",
) -> list[dict[str, Any]]:
    """Build rights-aware poster/backdrop/logo asset records from TMDB image paths."""
    if display_status not in {"pending_review", "approved", "link_only", "blocked"}:
        raise ValueError("Unsupported artwork display_status")
    image_config = configuration.get("images") if isinstance(configuration, dict) else None
    if not isinstance(image_config, dict):
        return []
    base_url = image_config.get("secure_base_url")
    images = details.get("images") if isinstance(details.get("images"), dict) else {}
    groups = (
        ("tmdb_poster", "posters", "poster_path", "poster_sizes", "w500"),
        ("tmdb_backdrop", "backdrops", "backdrop_path", "backdrop_sizes", "w1280"),
        ("tmdb_logo", "logos", None, "logo_sizes", "original"),
    )
    assets: list[dict[str, Any]] = []
    for kind, list_key, primary_key, sizes_key, preferred_size in groups:
        entries = images.get(list_key)
        entries = entries if isinstance(entries, list) else []
        candidates: list[dict[str, Any]] = [
            item for item in entries if isinstance(item, dict)
        ]
        if primary_key and isinstance(details.get(primary_key), str):
            primary_path = details[primary_key]
            primary_item = next(
                (item for item in candidates if item.get("file_path") == primary_path),
                {"file_path": primary_path},
            )
            candidates = [primary_item] + [
                item for item in candidates if item.get("file_path") != primary_path
            ]
        seen_paths: set[str] = set()
        for item in candidates:
            path = item.get("file_path")
            if not isinstance(path, str) or path in seen_paths:
                continue
            seen_paths.add(path)
            url = _tmdb_image_url(base_url, image_config.get(sizes_key), path, preferred_size)
            if not url:
                continue
            asset_hash = hashlib.sha256(f"{kind}:{path}".encode("utf-8")).hexdigest()[:16]
            asset = {
                "asset_id": f"tmdb-{asset_hash}",
                "provider": "tmdb",
                "edition_id": None,
                "kind": kind,
                "source_url": url,
                "width": item.get("width") if isinstance(item.get("width"), int) else None,
                "height": item.get("height") if isinstance(item.get("height"), int) else None,
                "language": item.get("iso_639_1") if isinstance(item.get("iso_639_1"), str) else None,
                "attribution": "TMDB",
                "reuse_policy": "provider_terms",
                "display_status": display_status,
            }
            assets.append(asset)
            if len([a for a in assets if a["kind"] == kind]) >= 10:
                break
    return assets
