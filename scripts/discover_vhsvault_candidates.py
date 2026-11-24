#!/usr/bin/env python3
"""Discover popular VHS Vault movie records using Internet Archive metadata search.

Returns metadata only. Results are sorted by Archive downloads so the next
review pass starts with more discoverable items rather than an arbitrary page.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ENDPOINT = "https://archive.org/advancedsearch.php"
QUERY = "collection:vhsvault AND mediatype:movies"
FIELDS = ["identifier", "title", "mediatype", "collection", "date", "year",
          "runtime", "description", "subject", "licenseurl", "downloads",
          "access-restricted-item"]
USER_AGENT = "VHSFeverCatalogResearch/0.3 (metadata-only targeted discovery)"


def fetch_page(rows: int, page: int) -> dict:
    params = [
        ("q", QUERY), ("output", "json"), ("rows", str(rows)),
        ("page", str(page)), ("sort[]", "downloads desc"),
    ] + [("fl[]", field) for field in FIELDS]
    request = Request(ENDPOINT + "?" + urlencode(params),
                      headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    for attempt in range(4):
        try:
            with urlopen(request, timeout=60) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if not isinstance(payload, dict) or not isinstance(payload.get("response"), dict):
                raise ValueError("Unexpected Internet Archive Advanced Search response")
            return payload["response"]
        except HTTPError as exc:
            if exc.code != 429 and not 500 <= exc.code < 600:
                raise RuntimeError(f"Internet Archive returned HTTP {exc.code}") from exc
            wait = 2 ** attempt
        except (URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
            if attempt == 3:
                raise RuntimeError(f"Internet Archive search failed: {exc}") from exc
            wait = 2 ** attempt
        if attempt == 3:
            raise RuntimeError("Internet Archive search failed after 4 attempts")
        time.sleep(wait)
    raise RuntimeError("Search failed unexpectedly")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=1000)
    parser.add_argument("--page", type=int, default=1)
    parser.add_argument("--output", type=Path, default=Path("data/vhsvault-targeted.jsonl"))
    args = parser.parse_args()
    if not 1 <= args.rows <= 10000:
        parser.error("--rows must be between 1 and 10000")
    if args.page < 1:
        parser.error("--page must be at least 1")
    response = fetch_page(args.rows, args.page)
    docs = response.get("docs") or []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    seen = set()
    written = 0
    with args.output.open("w", encoding="utf-8") as output:
        for item in docs:
            identifier = item.get("identifier")
            collections = item.get("collection") or []
            if isinstance(collections, str):
                collections = [collections]
            if (not isinstance(identifier, str) or not identifier.strip()
                    or identifier in seen or "vhsvault" not in collections
                    or item.get("mediatype") != "movies"):
                continue
            seen.add(identifier)
            output.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")
            written += 1
    summary = {
        "query": QUERY, "sort": "downloads desc", "page": args.page,
        "requested_rows": args.rows, "archive_total": response.get("numFound"),
        "records_written": written, "generated_at": datetime.now(timezone.utc).isoformat(),
        "media_payloads_downloaded": False, "rights_determined": False,
        "note": "Popularity-ranked metadata discovery only; download count is not evidence of title identity, ownership, or reuse rights."
    }
    args.output.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
