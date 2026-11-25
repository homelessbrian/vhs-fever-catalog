#!/usr/bin/env python3
"""Find Internet Archive uploads the catalog hasn't seen yet.

Asks the Archive for VHS Vault items, newest first, and stops as soon as a
whole page is already known, so a nightly run only reads what is new. Each
new item gets a quick first guess (format and kind) from its title and tags.
Nothing is downloaded and nothing is added to the catalog: the list is kept
on its own so a person decides when to bring a batch in.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import auto_sort  # noqa: E402

SEARCH = "https://archive.org/advancedsearch.php"
FIELDS = ["identifier", "title", "addeddate", "collection", "mediatype", "item_size", "language",
          "subject", "description", "num_files"]
UA = "VHS-FEVER-catalog/1.0 (+https://github.com/homelessbrian/vhs-fever-catalog)"


def fetch_page(query: str, page: int, rows: int) -> list[dict[str, Any]]:
    params = [("q", query), ("rows", rows), ("page", page), ("output", "json"), ("sort[]", "addeddate desc")]
    params += [("fl[]", f) for f in FIELDS]
    url = SEARCH + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)["response"]["docs"]
        except Exception:  # noqa: BLE001
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"Archive search failed on page {page}")


SCRAPE = "https://archive.org/services/search/v1/scrape"


def scrape_all(query: str, fields: list[str]) -> list[dict[str, Any]]:
    """Every matching page via the Archive's bulk listing (cursor-based, up to
    10,000 per call). Used once to list the whole collection quickly."""
    out: list[dict[str, Any]] = []
    cursor = None
    while True:
        params = [("q", query), ("fields", ",".join(fields)), ("count", "10000")]
        if cursor:
            params.append(("cursor", cursor))
        req = urllib.request.Request(SCRAPE + "?" + urllib.parse.urlencode(params), headers={"User-Agent": UA})
        for attempt in range(5):
            try:
                with urllib.request.urlopen(req, timeout=180) as r:
                    data = json.load(r)
                break
            except Exception:  # noqa: BLE001
                if attempt == 4:
                    raise
                time.sleep(5 * (attempt + 1))
        out += data.get("items") or []
        print(f"  listed {len(out)} of {data.get('total')}", file=sys.stderr)
        cursor = data.get("cursor")
        if not cursor:
            return out
        time.sleep(1)


def as_list(v: Any) -> list[str]:
    if v is None:
        return []
    return [str(x) for x in v] if isinstance(v, list) else [str(v)]


def first_guess(doc: dict[str, Any]) -> dict[str, Any]:
    """Quick format/kind guess from the item title, tags and collections."""
    title = str(doc.get("title") or "")
    text = " ".join([title] + as_list(doc.get("subject")))
    words = auto_sort.norm_words(text)
    fmt = "unknown"
    for rx, f, _strength in auto_sort.RIP_TAGS:
        if f != "UPSCALED" and rx.search(words):
            fmt = f
            break
    cols = set(as_list(doc.get("collection")))
    if auto_sort.COMMERCIAL.search(words) or "vhscommercials" in cols:
        kind = "commercials"
    elif (auto_sort.OFF_AIR.search(words) or auto_sort.WITH_ADS.search(words)
          or auto_sort.AIR_DATE_WORDS.search(words)):
        kind = "tv_recording"
    elif auto_sort.TRAILER.search(words) or "vhstrailers" in cols:
        kind = "trailers"
    elif auto_sort.MUSIC.search(words) or "vhsconcerts_music" in cols:
        kind = "music"
    elif "vhstvshows" in cols:
        kind = "tv_show"
    else:
        kind = "movie_or_unknown"
    foreign = auto_sort.foreign_reason(title)
    langs = [x.lower() for x in as_list(doc.get("language"))]
    if not foreign and langs and not any(x.startswith("en") for x in langs):
        foreign = "Archive item tagged " + ", ".join(langs)
    return {"format_guess": fmt, "kind_guess": kind, "not_english": foreign}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--query", default="collection:vhsvault AND mediatype:movies")
    ap.add_argument("--known", help="earlier new-items.jsonl (kept and extended)")
    ap.add_argument("--catalog-items", help="archive-items.jsonl of items already in the catalog")
    ap.add_argument("--output-dir", required=True)
    ap.add_argument("--rows", type=int, default=200)
    ap.add_argument("--max-pages", type=int, default=25, help="safety limit per run")
    ap.add_argument("--backfill-pages", type=int, default=10,
                    help="also read this many pages of older uploads not seen yet, to work through the backlog")
    ap.add_argument("--delay", type=float, default=1.0)
    ap.add_argument("--full", action="store_true", help="list the whole collection now with the bulk listing")
    a = ap.parse_args()

    known: dict[str, dict[str, Any]] = {}
    if a.known and Path(a.known).is_file():
        for line in Path(a.known).read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                known[row["identifier"]] = row
    in_catalog: set[str] = set()
    if a.catalog_items and Path(a.catalog_items).is_file():
        for line in Path(a.catalog_items).read_text(encoding="utf-8").splitlines():
            if line.strip():
                in_catalog.add(json.loads(line)["archive_identifier"])

    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    added, pages, stopped = 0, 0, "page limit reached"
    first_run = not known

    def take(docs: list[dict[str, Any]]) -> int:
        fresh = 0
        for d in docs:
            ident = d.get("identifier")
            if not ident or ident in known:
                continue
            fresh += 1
            known[ident] = {
                "identifier": ident, "title": d.get("title"), "added_to_archive": d.get("addeddate"),
                "first_seen": now, "collections": as_list(d.get("collection")),
                "size_bytes": d.get("item_size"), "files": d.get("num_files"),
                "url": f"https://archive.org/details/{ident}", **first_guess(d),
            }
        return fresh

    if a.full:
        docs = scrape_all(a.query, [f for f in FIELDS if f != "num_files"])
        added += take(docs)
        pages += 1
        stopped = "listed the whole collection"
        a.max_pages = 0
        a.backfill_pages = 0

    # 1) Newest uploads, until a page holds nothing new.
    for page in range(1, a.max_pages + 1):
        docs = fetch_page(a.query, page, a.rows)
        pages += 1
        if not docs:
            stopped = "no more results"
            break
        fresh = take(docs)
        added += fresh
        if fresh == 0 and not first_run:
            stopped = "caught up with items seen before"
            break
        time.sleep(a.delay)

    # 2) Backlog: older uploads than anything seen so far, a few pages a night.
    backfilled = 0
    dates = sorted(str(r.get("added_to_archive")) for r in known.values() if r.get("added_to_archive"))
    if dates and a.backfill_pages > 0:
        oldest = dates[0]
        older = f"({a.query}) AND addeddate:[* TO {oldest}]"
        for page in range(1, a.backfill_pages + 1):
            docs = fetch_page(older, page, a.rows)
            pages += 1
            if not docs:
                break
            backfilled += take(docs)
            time.sleep(a.delay)
    added += backfilled

    for ident, row in known.items():
        row["in_catalog"] = ident in in_catalog
    rows = sorted(known.values(), key=lambda r: str(r.get("added_to_archive") or ""), reverse=True)
    out = Path(a.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    with (out / "new-items.jsonl").open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    waiting = [r for r in rows if not r["in_catalog"]]
    from collections import Counter
    summary = {
        "checked_at": now, "pages_read": pages, "stopped_because": stopped,
        "new_this_run": added, "of_which_older_backlog": backfilled, "items_known": len(rows), "not_in_catalog_yet": len(waiting),
        "waiting_by_format_guess": dict(Counter(r["format_guess"] for r in waiting).most_common()),
        "waiting_by_kind_guess": dict(Counter(r["kind_guess"] for r in waiting).most_common()),
        "waiting_not_english": sum(1 for r in waiting if r.get("not_english")),
        "newest": [{"identifier": r["identifier"], "title": r["title"], "added": r["added_to_archive"]}
                   for r in rows[:10]],
    }
    (out / "new-items.summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
