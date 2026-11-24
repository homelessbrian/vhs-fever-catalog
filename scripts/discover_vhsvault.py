#!/usr/bin/env python3
"""Discover item metadata in the Internet Archive VHS Vault collection.

The script never downloads media files. Progress is checkpointed after each
page so an interrupted local run can be resumed with --resume.
"""
from __future__ import annotations

import argparse
import json
import os
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
USER_AGENT = "VHSFeverCatalogResearch/0.2 (metadata discovery; contact: repository owner)"


def request_page(cursor: str | None, count: int) -> dict:
    # Scraping API continuation requests must not resend count.
    params = {"q": f"collection:{COLLECTION}", "fields": FIELDS}
    if cursor:
        params["cursor"] = cursor
    else:
        params["count"] = count
    request = Request(
        f"{ENDPOINT}?{urlencode(params)}",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    for attempt in range(4):
        try:
            with urlopen(request, timeout=60) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
                raise ValueError("Unexpected Scraping API response: expected an object with an items array")
            return payload
        except HTTPError as exc:
            if exc.code == 429 or 500 <= exc.code < 600:
                retry_after = exc.headers.get("Retry-After")
                wait = max(2 ** attempt, int(retry_after)) if retry_after and retry_after.isdigit() else 2 ** attempt
            else:
                raise RuntimeError(f"Internet Archive returned HTTP {exc.code}: {exc.reason}") from exc
            if attempt == 3:
                raise RuntimeError(f"Internet Archive request failed after 4 attempts: HTTP {exc.code}") from exc
            time.sleep(wait)
        except (URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
            if attempt == 3:
                raise RuntimeError(f"Internet Archive request failed after 4 attempts: {exc}") from exc
            time.sleep(2 ** attempt)
    raise RuntimeError("Request failed unexpectedly")


def belongs_to_collection(item: dict) -> bool:
    collections = item.get("collection", [])
    if isinstance(collections, str):
        collections = [collections]
    return isinstance(collections, list) and COLLECTION in collections


def atomic_write_json(path: Path, payload: dict) -> None:
    temp_path = path.with_name(path.name + ".tmp")
    temp_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    os.replace(temp_path, path)


def load_existing_identifiers(path: Path) -> set[str]:
    identifiers: set[str] = set()
    if not path.exists():
        return identifiers
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Existing output has invalid JSON on line {line_number}") from exc
            identifier = item.get("identifier") if isinstance(item, dict) else None
            if isinstance(identifier, str) and identifier.strip():
                identifiers.add(identifier)
    return identifiers


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover VHS Vault metadata without downloading media.")
    parser.add_argument("--count", type=int, default=5000, help="Items per API page (minimum 100; default: 5000)")
    parser.add_argument("--max-pages", type=int, default=1, help="Maximum pages to fetch (default: 1)")
    parser.add_argument("--all", action="store_true", help="Follow cursors until the collection is exhausted")
    parser.add_argument("--resume", action="store_true", help="Resume from the saved checkpoint")
    parser.add_argument("--output", type=Path, default=Path("data/vhsvault-discovery.jsonl"))
    parser.add_argument("--delay", type=float, default=2.0, help="Pause between pages in seconds")
    args = parser.parse_args()

    if args.count < 100:
        parser.error("--count must be at least 100")
    if args.max_pages < 1:
        parser.error("--max-pages must be at least 1")
    if args.delay < 0:
        parser.error("--delay cannot be negative")

    checkpoint_path = args.output.with_suffix(".checkpoint.json")
    args.output.parent.mkdir(parents=True, exist_ok=True)

    if args.resume:
        if not checkpoint_path.exists():
            parser.error(f"No checkpoint found at {checkpoint_path}; run without --resume first")
        checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8"))
        if checkpoint.get("collection") != COLLECTION or checkpoint.get("count") != args.count:
            parser.error("Checkpoint collection or page size does not match this run")
        if not args.output.exists():
            parser.error(f"Checkpoint exists but output file is missing: {args.output}")
        cursor = checkpoint.get("cursor")
        page_count = int(checkpoint.get("pages_fetched", 0))
        rejected = int(checkpoint.get("rejected_or_duplicate_items", 0))
        reported_total = checkpoint.get("api_reported_total")
        seen = load_existing_identifiers(args.output)
        if len(seen) < int(checkpoint.get("unique_items_written", 0)):
            parser.error("Output file is missing records recorded in the checkpoint")
        output_mode = "a"
        print(f"Resuming after page {page_count}; {len(seen)} unique records already present", flush=True)
    else:
        if checkpoint_path.exists():
            parser.error(f"Checkpoint already exists at {checkpoint_path}; use --resume or move it aside")
        cursor = None
        page_count = 0
        rejected = 0
        reported_total = None
        seen = set()
        output_mode = "w"

    pages_this_run = 0
    max_pages_this_run = sys.maxsize if args.all else args.max_pages
    finished = False
    with args.output.open(output_mode, encoding="utf-8") as output:
        while pages_this_run < max_pages_this_run:
            payload = request_page(cursor, args.count)
            items = payload["items"]
            if not items:
                finished = True
                break

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

            output.flush()
            os.fsync(output.fileno())
            page_count += 1
            pages_this_run += 1
            reported_total = payload.get("total", reported_total)
            cursor = payload.get("cursor")
            finished = not bool(cursor)
            atomic_write_json(checkpoint_path, {
                "collection": COLLECTION,
                "count": args.count,
                "cursor": cursor,
                "pages_fetched": page_count,
                "unique_items_written": len(seen),
                "rejected_or_duplicate_items": rejected,
                "api_reported_total": reported_total,
                "updated_at": datetime.now(timezone.utc).isoformat(),
            })
            print(
                f"Page {page_count}: received {len(items)}, accepted {accepted_this_page}, "
                f"unique total {len(seen)}",
                flush=True,
            )
            if finished or pages_this_run >= max_pages_this_run:
                break
            time.sleep(args.delay)

    summary = {
        "collection": COLLECTION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "pages_fetched": page_count,
        "unique_items_written": len(seen),
        "rejected_or_duplicate_items": rejected,
        "api_reported_total_last_page": reported_total,
        "complete": finished,
        "checkpoint": str(checkpoint_path) if not finished else None,
        "output": str(args.output),
        "media_downloaded": False,
    }
    summary_path = args.output.with_suffix(".summary.json")
    atomic_write_json(summary_path, summary)
    if finished and checkpoint_path.exists():
        checkpoint_path.unlink()
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
