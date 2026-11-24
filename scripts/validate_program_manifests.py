#!/usr/bin/env python3
"""Validate VHS FEVER program manifests and catalog JSONL exports."""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

ALLOWED_ASSEMBLY_STATUSES = {"suggested", "confirmed", "rejected", "confirmed_single_segment"}
ALLOWED_PLAYBACK_MODES = {"single", "sequential"}
ALLOWED_ENTRY_TYPES = {"standalone", "assembled_candidate"}


def _valid_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate_program(program: Any) -> list[str]:
    """Return actionable validation errors for one program manifest."""
    errors: list[str] = []
    if not isinstance(program, dict):
        return ["program must be a JSON object"]

    if program.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if not _valid_text(program.get("program_id")):
        errors.append("program_id must be a non-empty string")
    if not _valid_text(program.get("title")):
        errors.append("title must be a non-empty string")

    status = program.get("assembly_status")
    if status not in ALLOWED_ASSEMBLY_STATUSES:
        errors.append(f"assembly_status must be one of {sorted(ALLOWED_ASSEMBLY_STATUSES)}")
    playback_mode = program.get("playback_mode")
    if playback_mode not in ALLOWED_PLAYBACK_MODES:
        errors.append(f"playback_mode must be one of {sorted(ALLOWED_PLAYBACK_MODES)}")

    entry_type = program.get("entry_type")
    if entry_type is not None and entry_type not in ALLOWED_ENTRY_TYPES:
        errors.append(f"entry_type must be one of {sorted(ALLOWED_ENTRY_TYPES)}")
    if entry_type == "assembled_candidate":
        if status != "suggested":
            errors.append("assembled_candidate must have assembly_status suggested")
        if program.get("review_required") is not True:
            errors.append("assembled_candidate must require review")

    segments = program.get("segments")
    if not isinstance(segments, list) or not segments:
        errors.append("segments must be a non-empty array")
        segments = []

    if playback_mode == "single" and len(segments) != 1:
        errors.append("single playback_mode requires exactly one segment")
    if playback_mode == "sequential" and len(segments) < 2:
        errors.append("sequential playback_mode requires at least two segments")

    segment_ids: set[str] = set()
    orders: list[int] = []
    source_refs: set[tuple[str, str]] = set()
    known_duration_total = 0.0
    all_durations_known = True

    for index, segment in enumerate(segments, 1):
        prefix = f"segments[{index}]"
        if not isinstance(segment, dict):
            errors.append(f"{prefix} must be an object")
            all_durations_known = False
            continue

        segment_id = segment.get("segment_id")
        if not _valid_text(segment_id):
            errors.append(f"{prefix}.segment_id must be a non-empty string")
        elif segment_id in segment_ids:
            errors.append(f"{prefix}.segment_id is duplicated: {segment_id}")
        else:
            segment_ids.add(segment_id)

        order = segment.get("order")
        if isinstance(order, bool) or not isinstance(order, int) or order < 1:
            errors.append(f"{prefix}.order must be a positive integer")
        else:
            if order in orders:
                errors.append(f"{prefix}.order is duplicated: {order}")
            orders.append(order)

        archive_id = segment.get("archive_identifier")
        file_name = segment.get("file_name")
        if not _valid_text(archive_id):
            errors.append(f"{prefix}.archive_identifier must be a non-empty string")
        if not _valid_text(file_name):
            errors.append(f"{prefix}.file_name must be a non-empty string")
        if _valid_text(archive_id) and _valid_text(file_name):
            source_ref = (archive_id.strip(), file_name.strip())
            if source_ref in source_refs:
                errors.append(f"{prefix} duplicate source segment: {archive_id}/{file_name}")
            source_refs.add(source_ref)

        duration = segment.get("duration_seconds")
        if duration is None:
            all_durations_known = False
        elif (isinstance(duration, bool) or not isinstance(duration, (int, float))
              or not math.isfinite(duration) or duration < 0):
            errors.append(f"{prefix}.duration_seconds must be null or a finite non-negative number")
            all_durations_known = False
        else:
            known_duration_total += float(duration)

    if orders and sorted(orders) != list(range(1, len(segments) + 1)):
        errors.append("segment order values must be contiguous from 1 through the segment count")

    duration_complete = program.get("duration_complete")
    if not isinstance(duration_complete, bool):
        errors.append("duration_complete must be a boolean")
    elif duration_complete != all_durations_known:
        errors.append("duration_complete must be false when any segment duration is unknown, and true otherwise")

    total = program.get("total_known_duration_seconds")
    if all_durations_known:
        if isinstance(total, bool) or not isinstance(total, (int, float)) or not math.isfinite(total):
            errors.append("duration total must be a finite number when all segment durations are known")
        elif not math.isclose(float(total), known_duration_total, rel_tol=1e-9, abs_tol=0.01):
            errors.append(f"duration total {total} does not equal segment duration sum {known_duration_total:g}")
    elif total is not None:
        errors.append("duration total must be null when any segment duration is unknown")

    return errors


def validate_programs(programs: list[Any]) -> list[str]:
    """Validate a catalog and enforce unique program IDs across its entries."""
    errors: list[str] = []
    seen_ids: set[str] = set()
    for index, program in enumerate(programs, 1):
        for error in validate_program(program):
            errors.append(f"program[{index}]: {error}")
        if isinstance(program, dict):
            program_id = program.get("program_id")
            if _valid_text(program_id):
                if program_id in seen_ids:
                    errors.append(f"program[{index}]: duplicate program_id: {program_id}")
                seen_ids.add(program_id)
    return errors


def read_jsonl(path: Path) -> list[Any]:
    programs = []
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                programs.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON on line {line_number}: {exc.msg}") from exc
    return programs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Program catalog JSONL to validate")
    parser.add_argument("--report", type=Path, help="Optional JSON validation report path")
    args = parser.parse_args()

    programs = read_jsonl(args.input)
    errors = validate_programs(programs)
    summary = {
        "programs_checked": len(programs),
        "valid": not errors,
        "error_count": len(errors),
        "errors": errors,
    }
    rendered = json.dumps(summary, indent=2)
    print(rendered)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    return 0 if not errors else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
