#!/usr/bin/env python3
"""Build conservative, review-only alternate-source title groups.

A group is only suggested when separate Archive items have the same unconfirmed
TMDB candidate, the same normalized Archive display title, and compatible known
runtimes. No source entry is removed or rewritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from pathlib import Path
from typing import Any
import re


def normalize_title(value: Any) -> str:
    text = str(value or "").casefold()
    text = re.sub(r"\([^)]*\)|\[[^]]*\]", " ", text)
    text = re.sub(r"\b(19|20)\d{2}\b", " ", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return " ".join(text.split())


def runtime_compatible(entries: list[dict[str, Any]]) -> bool:
    durations = [e.get("total_known_duration_seconds") for e in entries]
    if any(isinstance(x, bool) or not isinstance(x, (int, float)) or x <= 0 for x in durations):
        return False
    median = statistics.median(durations)
    tolerance = max(15 * 60, median * 0.20)
    return all(abs(float(x) - median) <= tolerance for x in durations)


def stable_group_id(provider_id: str, title_key: str, entries: list[dict[str, Any]]) -> str:
    source_keys = sorted(
        f"{(e.get('segments') or [{}])[0].get('archive_identifier','')}/"
        f"{(e.get('segments') or [{}])[0].get('file_name','')}"
        for e in entries
    )
    raw = "\n".join([provider_id, title_key, *source_keys])
    return "vhsf-title-group-" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def build_title_groups(programs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    buckets: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for program in programs:
        if program.get("entry_type") != "standalone":
            continue
        suggestion = program.get("title_match_suggestion") or {}
        provider_id = suggestion.get("provider_id")
        if suggestion.get("provider") != "tmdb" or not provider_id:
            continue
        title_key = normalize_title(program.get("display_title") or "")
        if len(title_key) < 3:
            continue
        buckets.setdefault((str(provider_id), title_key), []).append(program)

    groups = []
    for (provider_id, title_key), entries in sorted(buckets.items()):
        # Require at least two distinct Archive items. Files from one item may
        # be different programs and are deliberately not grouped by this pass.
        by_archive: dict[str, list[dict[str, Any]]] = {}
        for entry in entries:
            segments = entry.get("segments") or []
            if len(segments) != 1:
                continue
            archive_id = str(segments[0].get("archive_identifier") or "")
            if archive_id:
                by_archive.setdefault(archive_id, []).append(entry)
        if len(by_archive) < 2:
            continue

        # Only choose one representative source per Archive item in this
        # conservative first pass; multiple same-item files need manual review.
        representatives = []
        for archive_id, candidates in sorted(by_archive.items()):
            if len(candidates) != 1:
                representatives = []
                break
            representatives.append(candidates[0])
        if len(representatives) < 2 or not runtime_compatible(representatives):
            continue

        sample = representatives[0].get("title_match_suggestion") or {}
        groups.append({
            "schema_version": 1,
            "title_group_id": stable_group_id(provider_id, title_key, representatives),
            "status": "suggested",
            "review_required": True,
            "group_type": "alternate_sources",
            "provider": "tmdb",
            "provider_id": provider_id,
            "suggested_title": sample.get("title"),
            "normalized_archive_title": title_key,
            "source_count": len(representatives),
            "archive_item_count": len(representatives),
            "runtime_seconds": [x["total_known_duration_seconds"] for x in representatives],
            "runtime_rule": "all known runtimes within max(15 minutes, 20% of median)",
            "evidence": [
                "same unconfirmed TMDB candidate ID",
                "exact normalized Archive display-title match",
                "distinct Archive item identifiers",
                "compatible known runtimes",
            ],
            "sources": [
                {
                    "source_id": entry.get("source_id"),
                    "program_id": entry.get("program_id"),
                    "archive_identifier": entry["segments"][0]["archive_identifier"],
                    "file_name": entry["segments"][0]["file_name"],
                    "duration_seconds": entry["total_known_duration_seconds"],
                }
                for entry in representatives
            ],
            "review_notes": [
                "Suggestion only: verify that each source contains the same title.",
                "This does not identify a VHS edition or prove the sources are identical encodes.",
                "Confirming this group must not delete or hide any source.",
            ],
        })
    return groups


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    groups = build_title_groups(read_jsonl(args.input))
    write_jsonl(args.output, groups)
    print(json.dumps({"suggested_title_groups": len(groups), "sources_grouped": sum(g["source_count"] for g in groups)}, indent=2))


if __name__ == "__main__":
    main()
