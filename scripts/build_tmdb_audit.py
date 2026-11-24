#!/usr/bin/env python3
"""Build a compact, human-auditable report from the individual-program TMDB output."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


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


def build_audit(rows: list[dict[str, Any]]) -> dict[str, Any]:
    matched = []
    godzilla = []
    counts: dict[str, int] = {}
    for row in rows:
        suggestion = row.get("title_match_suggestion") or {}
        status = str(suggestion.get("status") or "missing")
        counts[status] = counts.get(status, 0) + 1
        if suggestion.get("tmdb_id") or suggestion.get("provider_id"):
            matched.append(row)
        source = str(suggestion.get("source_filename") or "")
        search_title = str(suggestion.get("search_title") or "")
        if "godzilla" in (source + " " + search_title + " " + str(row.get("title") or "")).casefold():
            godzilla.append(row)
    return {
        "summary": {
            "total_program_entries": len(rows),
            "matched_suggestions": len(matched),
            "godzilla_related_entries": len(godzilla),
            "statuses": counts,
            "human_approval_required": True,
        },
        "matched_suggestions": matched,
        "godzilla_related_entries": godzilla,
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
