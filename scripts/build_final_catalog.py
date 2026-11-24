#!/usr/bin/env python3
"""Promote only explicitly human-approved program identities into final JSONL."""
from __future__ import annotations
import argparse, json
from pathlib import Path
from typing import Any


def build_final_catalog(candidates: list[dict[str, Any]], decisions: dict[str, Any]) -> list[dict[str, Any]]:
    approved = []
    for source in candidates:
        pid = source.get("program_id")
        decision = decisions.get(pid) if isinstance(decisions, dict) else None
        if not isinstance(decision, dict):
            continue
        match = decision.get("title_match") or {}
        classification = decision.get("classification") or {}
        if match.get("status") != "confirmed" or not match.get("provider_id"):
            continue
        if classification.get("status") != "confirmed" or not classification.get("value"):
            continue
        row = dict(source)
        row["matched_title_id"] = str(match["provider_id"])
        row["matched_title"] = {
            "provider": match.get("provider", "tmdb"),
            "provider_id": str(match["provider_id"]),
            "title": match.get("title"),
            "release_date": match.get("release_date"),
        }
        row["confirmed_classification"] = classification["value"]
        row["human_approval"] = {
            "status": "approved",
            "confirmed_at": decision.get("updated_at") or match.get("confirmed_at"),
            "reviewer_note": decision.get("notes", ""),
        }
        row.pop("title_match_suggestion", None)
        approved.append(row)
    return approved


def read_jsonl(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--candidates", type=Path, required=True)
    p.add_argument("--decisions", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    candidates = read_jsonl(a.candidates)
    decisions = json.loads(a.decisions.read_text(encoding="utf-8"))
    if not isinstance(decisions, dict):
        raise SystemExit("Review decisions must be a JSON object keyed by program_id")
    final = build_final_catalog(candidates, decisions)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text("".join(json.dumps(x, ensure_ascii=False, sort_keys=True) + "\\n" for x in final), encoding="utf-8")
    print(json.dumps({"candidate_entries": len(candidates), "human_approved_final_entries": len(final),
                      "unapproved_excluded": len(candidates) - len(final)}, indent=2))


if __name__ == "__main__":
    main()
