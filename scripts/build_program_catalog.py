#!/usr/bin/env python3
"""Build a complete reviewable program catalog from enriched inventory JSONL.

Every unique source video becomes an independently playable one-segment entry.
Known Archive derivatives are omitted when their referenced original is present.
Conservative sequential assemblies are added as additional suggested entries.
No source records are changed and no media payloads are downloaded.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

try:
    from .build_program_candidates import build_candidates
except ImportError:  # Script execution from the repository root
    from build_program_candidates import build_candidates


def stable_id(value: str) -> str:
    return "vhsf-program-" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def video_files(item: dict[str, Any]) -> list[dict[str, Any]]:
    inventory = item.get("vhs_fever_file_inventory") or {}
    files = [
        f for f in (inventory.get("video_files") or [])
        if str(f.get("name") or "").strip()
    ]
    # Internet Archive often lists both an uploaded original and an .ia.mp4
    # streaming derivative. Keep the original as the canonical standalone
    # source when the derivative explicitly references that original.
    # If the original is absent, retain the derivative as a usable fallback.
    available_names = {str(f.get("name") or "").strip() for f in files}
    return [
        file for file in files
        if not (
            file.get("origin_hint") == "derivative"
            and str(file.get("original_file_reference") or "").strip()
            in available_names
        )
    ]


def build_catalog(records: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    catalog: list[dict[str, Any]] = []
    review: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()

    for item in records:
        identifier = str(item.get("identifier") or "").strip()
        title = str(item.get("title") or identifier or "Untitled archive item").strip()
        inventory = item.get("vhs_fever_file_inventory") or {}
        status = inventory.get("inventory_status", "missing")
        files = video_files(item) if status == "ok" else []
        classification = item.get("vhs_fever_classification") or {}
        analysis = item.get("vhs_fever_analysis") or {}
        restricted = bool(inventory.get("access_restricted"))
        if status != "ok" or not files or restricted or any(f.get("private") for f in files):
            reasons = []
            if status != "ok":
                reasons.append("inventory_not_successful")
            if status == "ok" and not files:
                reasons.append("no_video_file_candidates")
            if restricted:
                reasons.append("archive_item_access_restricted")
            if any(f.get("private") for f in files):
                reasons.append("one_or_more_video_files_marked_private")
            review.append({
                "archive_identifier": identifier or None,
                "title": title,
                "inventory_status": status,
                "video_file_count": len(files),
                "reasons": reasons,
                "review_status": "needs_review",
            })

        for file in files:
            filename = str(file.get("name") or "").strip()
            key = (identifier, filename)
            if not identifier or key in seen:
                continue
            seen.add(key)
            duration = file.get("duration_seconds")
            is_private = bool(file.get("private"))
            entry = {
                "schema_version": 1,
                "program_id": stable_id(identifier + "\n" + filename),
                "title": title if len(files) == 1 else Path(filename).stem.replace("_", " ").strip() or title,
                "display_title": title,
                "entry_type": "standalone",
                "assembly_status": "confirmed_single_segment",
                "match_confidence": "source_file",
                "playback_mode": "single",
                "segments": [{
                    "segment_id": "segment-001",
                    "order": 1,
                    "role": "main_program",
                    "archive_identifier": identifier,
                    "file_name": filename,
                    "duration_seconds": duration,
                }],
                "total_known_duration_seconds": duration,
                "duration_complete": duration is not None,
                "source_classification": classification.get("label"),
                "source_content_type": analysis.get("content_type"),
                "source_collection_format": analysis.get("collection_format"),
                "source_access_restricted": restricted,
                "source_file_private": is_private,
                "review_required": restricted or is_private or duration is None,
                "review_notes": [
                    note for note, condition in [
                        ("Archive access restriction must be checked at playback time.", restricted),
                        ("File is marked private in Archive metadata.", is_private),
                        ("File duration is unknown.", duration is None),
                    ] if condition
                ],
            }
            catalog.append(entry)

    # Multi-segment suggestions are additional editorial entries. They never
    # replace or suppress the underlying single-file entries.
    for candidate in build_candidates(records):
        candidate = dict(candidate)
        candidate["entry_type"] = "assembled_candidate"
        candidate["review_required"] = True
        candidate["review_status"] = "suggested"
        catalog.append(candidate)

    catalog.sort(key=lambda x: (x.get("title", "").casefold(), x["entry_type"], x["program_id"]))
    review.sort(key=lambda x: ((x.get("title") or "").casefold(), x.get("archive_identifier") or ""))
    return catalog, review


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if line.strip():
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError(f"Expected JSON object on line {number}")
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
    parser.add_argument("--review-output", type=Path, required=True)
    args = parser.parse_args()
    records = read_jsonl(args.input)
    catalog, review = build_catalog(records)
    write_jsonl(args.output, catalog)
    write_jsonl(args.review_output, review)
    raw_video_file_count = sum(
        len((item.get("vhs_fever_file_inventory") or {}).get("video_files") or [])
        for item in records
        if (item.get("vhs_fever_file_inventory") or {}).get("inventory_status") == "ok"
    )
    standalone_count = sum(x["entry_type"] == "standalone" for x in catalog)
    summary = {
        "source_records": len(records),
        "catalog_entries": len(catalog),
        "standalone_entries": standalone_count,
        "assembled_suggestions": sum(x["entry_type"] == "assembled_candidate" for x in catalog),
        "review_queue_entries": len(review),
        "redundant_derivative_files_suppressed": raw_video_file_count - standalone_count,
        "all_unique_video_sources_preserved_as_standalone_entries": True,
        "source_records_modified": False,
        "media_payloads_downloaded": False,
        "rights_determined": False,
        "note": "Standalone entries represent unique source files; known derivatives are suppressed only when their referenced original is present. Assemblies remain review-only suggestions. Playback access and rights must be evaluated separately.",
    }
    args.output.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
