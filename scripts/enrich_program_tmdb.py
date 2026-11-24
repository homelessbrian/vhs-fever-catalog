#!/usr/bin/env python3
"""Match individual Archive video filenames to TMDB in bounded, review-only batches."""
from __future__ import annotations
import argparse
import json
import os
import re
import time
from pathlib import Path
from typing import Any

try:
    from .enrich_metadata_artwork import find_tmdb_match
except ImportError:
    from enrich_metadata_artwork import find_tmdb_match

YEAR_PREFIX = re.compile(r"^\\s*[([]?((?:18|19|20)\\d{2})[)\\]. _-]+")
SUFFIX = re.compile(r"\\s*(?:\\[[^]]*(?:vhs|dvd|variant|capture|part\\s*\\d+)[^]]*\\]|\\([^()]*(?:vhs|dvd|variant|capture|part\\s*\\d+)[^()]*\\))\\s*$", re.I)


def filename_title(program: dict[str, Any]) -> tuple[str | None, str | None]:
    segments = program.get("segments") or []
    raw = Path(str((segments[0] if segments else {}).get("file_name") or program.get("title") or "")).stem
    raw = raw.replace("_", " ").strip()
    year = None
    match = YEAR_PREFIX.match(raw)
    if match:
        year = match.group(1)
        raw = raw[match.end():]
    raw = SUFFIX.sub("", raw).strip(" ._-()[]")
    raw = re.sub(r"\\s+", " ", raw)
    if len(re.sub(r"[^a-z0-9]", "", raw.casefold())) < 3:
        return None, year
    return raw, year


def enrich_programs(rows: list[dict[str, Any]], token: str | None,
                    delay: float = 0.05, max_lookups: int = 300) -> tuple[list[dict[str, Any]], dict[str, int]]:
    # Prioritize year-prefixed filenames, preserving the original catalog order in output.
    priorities = []
    for index, row in enumerate(rows):
        if row.get("entry_type") == "standalone":
            title, year = filename_title(row)
            if title:
                priorities.append((0 if year else 1, index, title, year))
    priorities.sort(key=lambda item: (item[0], item[1]))
    selected = {index for _, index, _, _ in priorities[:max_lookups]}

    cache: dict[tuple[str, str | None], dict[str, Any]] = {}
    counts: dict[str, int] = {}
    output = []
    for index, original in enumerate(rows):
        row = dict(original)
        if row.get("entry_type") != "standalone":
            output.append(row)
            continue
        title, year = filename_title(row)
        if not title:
            suggestion = {
                "status": "unusable_source_filename",
                "review_status": "needs_review",
                "source_filename": (row.get("segments") or [{}])[0].get("file_name"),
            }
        elif not token:
            suggestion = {"status": "skipped_no_api_token", "search_title": title,
                           "search_year": year, "review_status": "needs_review"}
        elif index not in selected:
            suggestion = {"status": "deferred_lookup_limit", "search_title": title,
                           "search_year": year, "review_status": "needs_review"}
        else:
            key = (title.casefold(), year)
            if key not in cache:
                if len(cache) >= max_lookups:
                    suggestion = {"status": "deferred_lookup_limit", "search_title": title,
                                  "search_year": year, "review_status": "needs_review"}
                else:
                    try:
                        # find_tmdb_match requires BOTH the query record and API token.
                        cache[key] = find_tmdb_match({"title": title, "year": year}, token)
                    except Exception as exc:
                        cache[key] = {"status": "lookup_error", "error": str(exc)[:240],
                                      "review_status": "needs_review"}
                    time.sleep(max(0, delay))
            if key in cache:
                suggestion = dict(cache[key])
            suggestion["source"] = "individual_video_filename"
            suggestion["search_title"] = title
            suggestion["search_year"] = year
            suggestion["review_status"] = "needs_review"

        if suggestion.get("tmdb_id"):
            suggestion.update({
                "status": "candidate", "provider": "tmdb",
                "provider_id": str(suggestion["tmdb_id"]),
                "confidence": suggestion.get("match_confidence", "medium"),
                "poster": suggestion.get("tmdb_poster"),
                "backdrop": suggestion.get("tmdb_backdrop"),
            })
        row["title_match_suggestion"] = suggestion
        row["matched_title_id"] = None
        row["review_required"] = True
        row.setdefault("review_notes", [])
        row["review_notes"] = list(row["review_notes"]) + [
            "TMDB title suggestion is based on this individual video filename; human confirmation required."
        ]
        status = "matched" if suggestion.get("tmdb_id") else suggestion.get("status", "unknown")
        counts[status] = counts.get(status, 0) + 1
        output.append(row)
    return output, counts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--delay", type=float, default=0.05)
    parser.add_argument("--max-lookups", type=int, default=300)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line.strip()]
    result, counts = enrich_programs(rows, os.environ.get("TMDB_API_READ_ACCESS_TOKEN"),
                                     args.delay, args.max_lookups)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\\n"
                                   for row in result), encoding="utf-8")
    summary = {
        "program_entries": len(result), "tmdb_lookup_counts": counts,
        "matching_level": "individual_video_filename", "human_approval_required": True,
        "max_unique_tmdb_lookups": args.max_lookups,
    }
    args.output.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2) + "\\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
