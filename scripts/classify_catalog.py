#!/usr/bin/env python3
"""Non-destructive, rules-based classification of VHS Vault metadata.

This labels metadata only. It does not remove records, inspect media payloads,
or determine whether an item is legally playable or redistributable.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

RULES = [
    ("artwork_or_packaging", re.compile(
        r"\b(cover|sleeve|box ?art|poster|jacket|disc label|dvd menu background|"
        r"vhs label|case art|scan|artwork|packaging)\\b", re.I)),
    ("promo_or_commercial", re.compile(
        r"\b(promo|promotional|commercial|advert|advertisement|ad break|station ident|"
        r"continuity|bumper|tv break|idents?)\\b", re.I)),
    ("vhs_or_dvd_extra", re.compile(
        r"\b(dvd opening|dvd closing|opening and closing|opening to|closing to|"
        r"menu|bonus feature|special feature|behind the scenes|making of|easter egg|"
        r"vhs opening|vhs closing)\\b", re.I)),
    ("trailer_or_clip", re.compile(
        r"\b(trailer|teaser|preview|clip|excerpt|snippet|scene from)\\b", re.I)),
    ("cartoon_or_animation", re.compile(
        r"\b(cartoon|animated|animation|anime|toon|looney tunes|merrie melodies)\\b", re.I)),
    ("tv_episode_or_recording", re.compile(
        r"\b(episode|tv recording|television recording|broadcast|aircheck|recorded off|"
        r"taped off|full episode|sitcom|news broadcast|newscast|telecast)\\b", re.I)),
]
MOVIE_TITLE = re.compile(r"\b(movie|film|feature film|motion picture)\\b", re.I)


def as_text(value: object) -> str:
    if isinstance(value, list):
        return " ".join(str(part) for part in value)
    return str(value or "")


def classify(item: dict) -> tuple[str, str, str]:
    media_type = item.get("mediatype")
    if media_type == "audio":
        return "audio_only", "high", "mediatype_audio"
    if media_type == "image":
        return "artwork_or_packaging", "high", "mediatype_image"
    if media_type != "movies":
        return "other_or_review", "high", f"mediatype_{media_type or 'missing'}"

    searchable = f"{as_text(item.get('title'))} {as_text(item.get('description'))}"
    for label, pattern in RULES:
        match = pattern.search(searchable)
        if match:
            return label, "medium", match.group(0).lower()
    if MOVIE_TITLE.search(as_text(item.get("title"))):
        return "movie_or_film", "low", "movie_or_film_title_term"
    return "other_or_review", "low", "no_rule_matched"


def main() -> int:
    parser = argparse.ArgumentParser(description="Add preliminary labels to VHS Vault metadata.")
    parser.add_argument("--input", type=Path, default=Path("data/vhsvault-discovery.jsonl"))
    parser.add_argument("--output", type=Path, default=Path("data/vhsvault-classified.jsonl"))
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error("Input and output must be different; classification is non-destructive.")
    if not args.input.is_file():
        parser.error(f"Input file does not exist: {args.input}")

    counts: Counter[str] = Counter()
    confidence: Counter[str] = Counter()
    total = 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp_output = args.output.with_name(args.output.name + ".tmp")
    try:
        with args.input.open(encoding="utf-8") as source, temp_output.open("w", encoding="utf-8") as dest:
            for line_number, line in enumerate(source, 1):
                try:
                    item = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise ValueError(f"Invalid JSON on input line {line_number}") from exc
                if not isinstance(item, dict):
                    raise ValueError(f"Expected a JSON object on input line {line_number}")
                label, level, reason = classify(item)
                result = dict(item)
                result["vhs_fever_classification"] = {
                    "label": label,
                    "confidence": level,
                    "reason": reason,
                    "needs_review": level == "low",
                }
                dest.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
                counts[label] += 1
                confidence[level] += 1
                total += 1
        temp_output.replace(args.output)
    except Exception:
        temp_output.unlink(missing_ok=True)
        raise

    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "input": str(args.input),
        "output": str(args.output),
        "records_classified": total,
        "classification_counts": dict(sorted(counts.items())),
        "confidence_counts": dict(sorted(confidence.items())),
        "low_confidence_records_need_review": confidence["low"],
        "source_preserved": True,
        "media_inspected": False,
        "rights_determined": False,
    }
    summary_path = args.output.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
