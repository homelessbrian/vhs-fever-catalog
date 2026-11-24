#!/usr/bin/env python3
"""Discover item identifiers in the Internet Archive VHS Vault collection.

This script downloads catalog metadata only. It never downloads media files.
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

ENDPOINT = "https://archive.org/services/search/v1/scrape"
COLLECTION = "vhsvault"
FIELDS = ",".join([
    "identifier", "title", "mediatype", "collection", "date", "year",
    "runtime", "description", "licenseurl", "access-restricted-item",
])
USER_AGENT = "VHSFeverCatalogResearch/0.1 (metadata discovery; contact: repository owner)"


def request_page(cursor: str | None, count: int) -> dict:
    params = {"q": f"collection:{COLLECTION}", "fields": FIELDS, "count": count}
    if cursor:
        params["cursor"] = cursor
    url = f"{ENDPOINT}?{urlencode(params)}"
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    for attempt in range(4):
        try:
            with urlopen(request, timeout=45) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
                raise ValueError("Unexpected Scraping API response: expected an object with an items array")
            return payload
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
            if attempt == 3:
                raise RuntimeError(f"Internet Archive request failed after 4 attempts: {exc}") from exc
            time.sleep(2 ** attempt)
    raise RuntimeError("Request failed unexpectedly")


def belongs_to_collection(item: dict) -> bool:
    collections = item.get("collection", [])
    if isinstance(collections, str):
        collections = [collections]
    if not isinstance(collections, list):
        return False
    return COLLECTION in collections


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover VHS Vault items without downloading media.")
    parser.add_argument("--count", type=int, default=100, help="Items per API page (minimum 100; default: 100)")
    parser.add_argument("--max-pages", type=int, default=1, help="Maximum pages to fetch (default: 1)")
    parser.add_argument("--all", action="store_true", help="Follow cursors until the collection is exhausted")
    parser.add_argument("--output", type=Path, default=Path("data/vhsvault-discovery.jsonl"))
    parser.add_argument("--delay", type=float, default=1.0, help="Pause between pages in seconds")
    args = parser.parse_args()

    if args.count < 100:
        parser.error("--count must be at least 100")
    if args.max_pages < 1:
        parser.error("--max-pages must be at least 1")

    max_pages = sys.maxsize if args.all else args.max_pages
    args.output.parent.mkdir(parents=True, exist_ok=True)
    seen: set[str] = set()
    page_count = 0
    rejected = 0
    cursor = None
    reported_total = None

    with args.output.open("w", encoding="utf-8") as output:
        while page_count < max_pages:
            payload = request_page(cursor, args.count)
            page_count += 1
            reported_total = payload.get("total", reported_total)
            items = payload["items"]
            accepted_this_page = 0

            for item in items:
                if not isinstance(item, dict) or not belongs_to_collection(item):
                    rejected += 1
                    continue
                identifier = item.get("identifier")
                if not isinstance(identifier, str) or not identifier.strip() or identifier in seen:
                    rejected += 1
                    continue
                seen.add(identifier)
                output.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")
                accepted_this_page += 1

            print(
                f"Page {page_count}: received {len(items)}, accepted {accepted_this_page}, "
                f"unique total {len(seen)}",
                flush=True,
            )
            cursor = payload.get("cursor")
            if not cursor or not items:
                break
            if page_count < max_pages:
                time.sleep(max(0.0, args.delay))

    summary = {
        "collection": COLLECTION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "pages_fetched": page_count,
        "unique_items_written": len(seen),
        "rejected_or_duplicate_items": rejected,
        "api_reported_total_last_page": reported_total,
        "output": str(args.output),
        "media_downloaded": False,
    }
    summary_path = args.output.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
