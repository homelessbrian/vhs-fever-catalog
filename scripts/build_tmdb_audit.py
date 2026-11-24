#!/usr/bin/env python3
"""Build a compact, human-auditable report from the individual-program TMDB output."""
from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


YEAR_PREFIX = re.compile(r"^\s*\((\d{4})([a-z])\)", re.IGNORECASE)
PLAIN_YEAR_PREFIX = re.compile(r"^\s*\((\d{4})\)")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if line.strip():
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError(f"Expected object on line {number}")
                rows.append(value)
    return rows


def _suggestion(row: dict[str, Any]) -> dict[str, Any]:
    value = row.get("title_match_suggestion")
    return value if isinstance(value, dict) else {}


def _source_filename(row: dict[str, Any], suggestion: dict[str, Any]) -> str:
    if suggestion.get("source_filename"):
        return str(suggestion["source_filename"])
    for segment in row.get("segments") or []:
        if isinstance(segment, dict) and segment.get("file_name"):
            return str(segment["file_name"])
    return ""


def _candidate_year(suggestion: dict[str, Any]) -> str | None:
    release_date = suggestion.get("release_date") or suggestion.get("candidate_release_date")
    if release_date and re.match(r"^\d{4}", str(release_date)):
        return str(release_date)[:4]
    year = suggestion.get("release_year") or suggestion.get("candidate_year")
    return str(year)[:4] if year else None


def _candidate_id(suggestion: dict[str, Any]) -> str | None:
    value = suggestion.get("tmdb_id") or suggestion.get("provider_id")
    return str(value) if value not in (None, "") else None


def build_audit(rows: list[dict[str, Any]]) -> dict[str, Any]:
    matched = []
    godzilla = []
    counts: dict[str, int] = {}
    malformed_year_prefixes = []
    year_discrepancies = []
    candidate_sources: dict[str, list[dict[str, Any]]] = defaultdict(list)

    for row in rows:
        suggestion = _suggestion(row)
        status = str(suggestion.get("status") or "missing")
        counts[status] = counts.get(status, 0) + 1
        source = _source_filename(row, suggestion)
        search_title = str(suggestion.get("search_title") or "")
        candidate_id = _candidate_id(suggestion)

        if candidate_id:
            matched.append(row)
            candidate_sources[candidate_id].append({
                "candidate_id": candidate_id,
                "source_filename": source,
                "search_title": search_title,
                "candidate_title": suggestion.get("title") or suggestion.get("tmdb_title"),
                "release_date": suggestion.get("release_date") or suggestion.get("candidate_release_date"),
                "program_id": row.get("program_id"),
                "archive_identifier": row.get("archive_identifier") or row.get("identifier"),
            })

        if "godzilla" in (source + " " + search_title + " " + str(row.get("title") or "")).casefold():
            godzilla.append(row)

        malformed = YEAR_PREFIX.match(source)
        if malformed:
            malformed_year_prefixes.append({
                "source_filename": source,
                "prefix_year": malformed.group(1),
                "suffix_letter": malformed.group(2).lower(),
                "search_title": search_title,
                "search_year": suggestion.get("search_year"),
                "candidate_id": candidate_id,
                "candidate_title": suggestion.get("title") or suggestion.get("tmdb_title"),
                "program_id": row.get("program_id"),
            })

        source_year_match = PLAIN_YEAR_PREFIX.match(source)
        source_year = str(suggestion.get("search_year") or (source_year_match.group(1) if source_year_match else "")) or None
        candidate_year = _candidate_year(suggestion)
        if candidate_id and source_year and candidate_year and source_year != candidate_year:
            year_discrepancies.append({
                "source_filename": source,
                "source_year": source_year,
                "candidate_year": candidate_year,
                "candidate_id": candidate_id,
                "candidate_title": suggestion.get("title") or suggestion.get("tmdb_title"),
                "release_date": suggestion.get("release_date") or suggestion.get("candidate_release_date"),
                "program_id": row.get("program_id"),
            })

    shared_candidates = [
        {"candidate_id": candidate_id, "source_count": len(sources), "sources": sources}
        for candidate_id, sources in sorted(candidate_sources.items())
        if len(sources) > 1
    ]

    return {
        "summary": {
            "total_program_entries": len(rows),
            "matched_suggestions": len(matched),
            "godzilla_related_entries": len(godzilla),
            "statuses": counts,
            "malformed_year_prefix_count": len(malformed_year_prefixes),
            "source_tmdb_year_discrepancy_count": len(year_discrepancies),
            "shared_tmdb_candidate_count": len(shared_candidates),
            "human_approval_required": True,
        },
        "matched_suggestions": matched,
        "godzilla_related_entries": godzilla,
        "audit_flags": {
            "malformed_year_prefixes": malformed_year_prefixes,
            "source_tmdb_year_discrepancies": year_discrepancies,
            "shared_tmdb_candidates": shared_candidates,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    audit = build_audit(read_jsonl(args.input))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit["summary"], indent=2))


if __name__ == "__main__":
    main()
