#!/usr/bin/env python3
"""Create TMDB enrichment sidecars for eligible standalone VHS FEVER programs."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

try:
    from .tmdb_enrichment import TMDBClient, build_identity_match, normalize_title
except ImportError:  # Script execution from the repository root
    from tmdb_enrichment import TMDBClient, build_identity_match, normalize_title

ELIGIBLE_CLASSIFICATIONS = {"movie_or_film"}


def _source_year(source: dict[str, Any]) -> int | None:
    value = source.get("year")
    if isinstance(value, int) and not isinstance(value, bool) and 1800 <= value <= 2200:
        return value
    date_value = source.get("date")
    if isinstance(date_value, str) and len(date_value) >= 4:
        try:
            year = int(date_value[:4])
        except ValueError:
            return None
        return year if 1800 <= year <= 2200 else None
    return None


def build_enrichment_records(
    programs: list[dict[str, Any]],
    inventory_records: list[dict[str, Any]],
    client: Any,
    *,
    limit: int | None = None,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Join programs to Archive source metadata and search each distinct title once."""
    if limit is not None and (not isinstance(limit, int) or isinstance(limit, bool) or limit < 1):
        raise ValueError("limit must be a positive integer")

    source_by_id = {
        item.get("identifier"): item
        for item in inventory_records
        if isinstance(item, dict) and isinstance(item.get("identifier"), str)
    }
    summary = {
        "catalog_programs": len(programs),
        "eligible_programs": 0,
        "processed_programs": 0,
        "skipped_non_standalone": 0,
        "skipped_classification": 0,
        "skipped_missing_source": 0,
        "skipped_missing_title": 0,
        "unique_tmdb_searches": 0,
        "programs_with_candidates": 0,
    }

    eligible: list[tuple[dict[str, Any], dict[str, Any], str, int | None]] = []
    for program in programs:
        if not isinstance(program, dict) or program.get("entry_type") != "standalone":
            summary["skipped_non_standalone"] += 1
            continue
        classification = program.get("source_classification")
        if classification not in ELIGIBLE_CLASSIFICATIONS:
            summary["skipped_classification"] += 1
            continue
        segments = program.get("segments")
        if not isinstance(segments, list) or len(segments) != 1 or not isinstance(segments[0], dict):
            summary["skipped_non_standalone"] += 1
            continue
        archive_id = segments[0].get("archive_identifier")
        source = source_by_id.get(archive_id)
        if not isinstance(source, dict):
            summary["skipped_missing_source"] += 1
            continue
        source_title = source.get("title")
        if not isinstance(source_title, str) or not source_title.strip():
            source_title = program.get("display_title") or program.get("title")
        if not isinstance(source_title, str) or not source_title.strip():
            summary["skipped_missing_title"] += 1
            continue
        eligible.append((program, source, source_title.strip(), _source_year(source)))

    summary["eligible_programs"] = len(eligible)
    if limit is not None:
        eligible = eligible[:limit]

    search_cache: dict[tuple[str, int | None], list[dict[str, Any]]] = {}
    records: list[dict[str, Any]] = []
    for program, source, title, year in eligible:
        cache_key = (normalize_title(title), year)
        if cache_key not in search_cache:
            search_cache[cache_key] = client.search_candidates(title, year=year, media_type=None)
            summary["unique_tmdb_searches"] += 1
        candidates = search_cache[cache_key]
        identity = build_identity_match(
            {"title": title, "year": year, "media_type": None},
            candidates,
        )
        if identity["candidates"]:
            summary["programs_with_candidates"] += 1
        records.append({
            "schema_version": 1,
            "program_id": program.get("program_id"),
            "identity_match": identity,
            "vhs_sleeve_lookup": {
                "status": "not_checked",
                "provider": "videotapes_io",
                "checked_at": None,
                "editions": [],
            },
            "artwork": {
                "signature_asset_id": None,
                "assets": [],
            },
        })
        summary["processed_programs"] += 1

    return records, summary


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON on line {line_number} in {path}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"Expected a JSON object on line {line_number} in {path}")
            rows.append(value)
    return rows


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in records),
        encoding="utf-8",
    )
    os.replace(temporary, path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True, help="Program catalog JSONL")
    parser.add_argument("--inventory", type=Path, required=True, help="Archive inventory JSONL with source titles and years")
    parser.add_argument("--output", type=Path, required=True, help="Enrichment sidecar JSONL output")
    parser.add_argument("--summary", type=Path, help="Optional JSON summary output")
    parser.add_argument("--limit", type=int, help="Maximum eligible programs to process")
    args = parser.parse_args()

    programs = read_jsonl(args.catalog)
    inventory = read_jsonl(args.inventory)
    records, summary = build_enrichment_records(programs, inventory, TMDBClient(), limit=args.limit)
    write_jsonl(args.output, records)
    rendered = json.dumps(summary, indent=2)
    print(rendered)
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.summary.with_name(args.summary.name + ".tmp")
        temporary.write_text(rendered + "\n", encoding="utf-8")
        os.replace(temporary, args.summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
