#!/usr/bin/env python3
"""Match individual Archive video filenames to TMDB in bounded, review-only batches.

Original filenames are never modified. Filename parsing only creates a search
interpretation (title + optional year), which is retained alongside the source.
"""
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

YEAR = r"(?:18|19|20)\d{2}"
YEAR_PREFIX = re.compile(rf"^\s*[\[(]?({YEAR})[\])]?[\s._-]+")
YEAR_SUFFIX = re.compile(rf"[\s._-]+[\[(]?({YEAR})[\])]?$")
KNOWN_TAGS = re.compile(
    r"\s*(?:\[(?:vhs|dvd|blu[\s-]?ray|variant|capture|rip|web[\s-]?dl|"
    r"part\s*\d+)[^\]]*\]|\((?:vhs|dvd|variant|capture|rip|part\s*\d+)[^)]*\))\s*$",
    re.IGNORECASE,
)


def filename_title(program: dict[str, Any]) -> tuple[str | None, str | None]:
    """Return a conservative title/year search interpretation from a source filename."""
    segments = program.get("segments") or []
    source_name = (segments[0] if segments else {}).get("file_name")
    raw = Path(str(source_name or program.get("title") or "")).stem.strip()
    if not raw:
        return None, None

    # Remove only known release/encoding annotations, never the source filename.
    previous = None
    while previous != raw:
        previous = raw
        raw = KNOWN_TAGS.sub("", raw).strip()

    year = None
    match = YEAR_PREFIX.match(raw)
    if match:
        year = match.group(1)
        raw = raw[match.end():]
    else:
        match = YEAR_SUFFIX.search(raw)
        if match:
            year = match.group(1)
            raw = raw[:match.start()]

    # Archive filenames commonly use dots/underscores as word separators.
    title = re.sub(r"[._]+", " ", raw)
    title = re.sub(r"\s+", " ", title).strip(" -_().[]")
    if len(re.sub(r"[^a-z0-9]", "", title.casefold())) < 3:
        return None, year
    return title, year


def enrich_programs(rows: list[dict[str, Any]], token: str | None,
                    delay: float = 0.05, max_lookups: int = 300) -> tuple[list[dict[str, Any]], dict[str, int]]:
    if max_lookups < 0:
        raise ValueError("max_lookups must be zero or greater")

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
        source_name = ((row.get("segments") or [{}])[0]).get("file_name")
        if not title:
            suggestion = {
                "status": "unusable_source_filename",
                "review_status": "needs_review",
                "source_filename": source_name,
            }
        elif not token:
            suggestion = {
                "status": "skipped_no_api_token", "search_title": title,
                "search_year": year, "source_filename": source_name,
                "review_status": "needs_review",
            }
        elif index not in selected:
            suggestion = {
                "status": "deferred_lookup_limit", "search_title": title,
                "search_year": year, "source_filename": source_name,
                "review_status": "needs_review",
            }
        else:
            key = (title.casefold(), year)
            if key not in cache:
                try:
                    cache[key] = find_tmdb_match({"title": title, "year": year}, token)
                except Exception as exc:
                    cache[key] = {
                        "status": "lookup_error", "error": str(exc)[:240],
                        "review_status": "needs_review",
                    }
                time.sleep(max(0, delay))
            suggestion = dict(cache[key])
            suggestion.update({
                "source": "individual_video_filename",
                "source_filename": source_name,
                "search_title": title,
                "search_year": year,
                "review_status": "needs_review",
            })

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
        notes = list(row.get("review_notes") or [])
        note = "TMDB title suggestion is based on this individual video filename; human confirmation required."
        if note not in notes:
            notes.append(note)
        row["review_notes"] = notes
        status = "matched" if suggestion.get("tmdb_id") else suggestion.get("status", "unknown")
        counts[status] = counts.get(status, 0) + 1
        output.append(row)
    return output, counts


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"Expected JSON object on line {line_number}")
            rows.append(value)
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--delay", type=float, default=0.05)
    parser.add_argument("--max-lookups", type=int, default=300)
    args = parser.parse_args()
    rows = read_jsonl(args.input)
    result, counts = enrich_programs(
        rows, os.environ.get("TMDB_API_READ_ACCESS_TOKEN"), args.delay, args.max_lookups
    )
    write_jsonl(args.output, result)
    # Read the file back immediately so malformed JSONL cannot reach later workflow steps.
    verified = read_jsonl(args.output)
    if len(verified) != len(result):
        raise RuntimeError("JSONL verification failed: output record count changed")
    summary = {
        "program_entries": len(result),
        "tmdb_lookup_counts": counts,
        "matching_level": "individual_video_filename",
        "human_approval_required": True,
        "max_unique_tmdb_lookups": args.max_lookups,
        "jsonl_verified": True,
    }
    args.output.with_suffix(".summary.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
