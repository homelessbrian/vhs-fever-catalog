#!/usr/bin/env python3
"""Build conservative, review-only sequential program candidates from inventory JSONL."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any

VIDEO_EXTS = {".mp4", ".m4v", ".mpeg", ".mpg", ".mkv", ".avi", ".mov", ".ogv", ".webm", ".wmv", ".flv"}
ROLE_PATTERNS = [
    ("opening", r"\b(opening|intro|introduction)\b"),
    ("closing", r"\b(closing|outro|end credits|ending)\b"),
    ("bonus_cartoon", r"\b(bonus cartoon|cartoon bonus|bonus animation)\b"),
    ("bonus", r"\b(bonus|extra|special feature|behind the scenes)\b"),
    ("trailer", r"\b(trailer|teaser|preview)\b"),
    ("intermission", r"\b(intermission|intermission reel)\b"),
    ("credits", r"\b(credits)\b"),
]
TITLE_ROLE_PATTERNS = [
    (re.compile(r"^(?:opening|intro(?:duction)?)\s+(?:to\s+)?(.+)$", re.I), "opening"),
    (re.compile(r"^(.+?)\s+(?:opening|intro)$", re.I), "opening"),
    (re.compile(r"^(?:closing|outro)\s+(?:to\s+)?(.+)$", re.I), "closing"),
    (re.compile(r"^(.+?)\s+(?:closing|outro)$", re.I), "closing"),
    (re.compile(r"^(?:bonus\s+)?cartoon\s+(?:from|for|on)\s+(.+)$", re.I), "bonus_cartoon"),
    (re.compile(r"^(.+?)\s+(?:bonus\s+cartoon|bonus|extra)$", re.I), "bonus"),
    (re.compile(r"^(.+?)\s+(?:part|episode)\s*\d+\b", re.I), "main_program"),
]
STOP_TOKENS = {"vhs", "fanmade", "fan", "made", "custom", "rip", "recording", "archive", "internet", "special", "the"}

def norm(value: str) -> str:
    value = value.lower()
    value = re.sub(r"\[[^]]*\]|\([^)]*\)", " ", value)
    value = re.sub(r"\b(19|20)\d{2}\b", " ", value)
    value = re.sub(r"[^a-z0-9]+", " ", value)
    tokens = [t for t in value.split() if t not in STOP_TOKENS]
    return " ".join(tokens).strip()

def title_role(title: str) -> tuple[str, str] | None:
    for pattern, role in TITLE_ROLE_PATTERNS:
        match = pattern.match(title.strip())
        if match:
            base = norm(match.group(1))
            if len(base) >= 4:
                return base, role
    return None

def filename_role(filename: str) -> str | None:
    # Only leading role labels count; "Opening Credits" inside an episode title
    # is not sufficient evidence that the file is a separate opening segment.
    stem = Path(filename).stem.replace("_", " ").replace("-", " ").strip()
    stem = re.sub(r"^\d+[ ._-]*", "", stem)
    anchored = [
        ("opening", r"^(?:opening|intro(?:duction)?)(?:\b|\s)"),
        ("closing", r"^(?:closing|outro|end credits)(?:\b|\s)"),
        ("bonus_cartoon", r"^(?:bonus cartoon|cartoon bonus)(?:\b|\s)"),
        ("bonus", r"^(?:bonus|extra|special feature)(?:\b|\s)"),
        ("trailer", r"^(?:trailer|teaser|preview)(?:\b|\s)"),
        ("intermission", r"^intermission(?:\b|\s)"),
        ("credits", r"^credits(?:\b|\s)"),
    ]
    for role, pattern in anchored:
        if re.search(pattern, stem, re.I):
            return role
    return None

def stable_id(key: str) -> str:
    return "vhsf-program-" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]

def candidate_from_group(key: str, members: list[dict[str, Any]]) -> dict[str, Any] | None:
    # Deduplicate exact source references and require multiple distinct role clues.
    unique: dict[tuple[str, str], dict[str, Any]] = {}
    for member in members:
        unique[(member["archive_identifier"], member["file_name"])] = member
    members = list(unique.values())
    roles = {m["role"] for m in members if m["role"] != "unknown"}
    if len(members) < 2 or len(roles) < 2:
        return None
    if not ({"opening", "closing", "bonus", "bonus_cartoon", "trailer", "intermission", "credits"} & roles):
        return None
    # Promote the longest unlabelled feature-length file to main_program only as a
    # suggested role, never as proof that the files belong together.
    unlabelled = [m for m in members if m["role"] == "unknown" and (m.get("duration_seconds") or 0) >= 300]
    if unlabelled:
        max(unlabelled, key=lambda m: m.get("duration_seconds") or 0)["role"] = "main_program"
        roles.add("main_program")
    role_order = {"opening": 10, "main_program": 20, "intermission": 30, "bonus_cartoon": 40,
                  "bonus": 45, "trailer": 50, "credits": 60, "closing": 70, "unknown": 90}
    members.sort(key=lambda m: (role_order.get(m["role"], 90), m["archive_identifier"], m["file_name"]))
    segments = []
    total = 0.0
    all_durations_known = True
    evidence = []
    for index, member in enumerate(members, 1):
        duration = member.get("duration_seconds")
        if duration is None:
            all_durations_known = False
        else:
            total += duration
        segments.append({
            "segment_id": f"segment-{index:03d}",
            "order": index,
            "role": member["role"],
            "archive_identifier": member["archive_identifier"],
            "file_name": member["file_name"],
            "duration_seconds": duration,
        })
        evidence.extend(member["evidence"])
    confidence = "medium" if len(roles) >= 3 else "low"
    title = next((m.get("program_title") for m in members if m.get("program_title")), None) or key.replace("-", " ").title()
    return {
        "schema_version": 1,
        "program_id": stable_id(key),
        "title": title,
        "assembly_status": "suggested",
        "match_confidence": confidence,
        "playback_mode": "sequential",
        "segments": segments,
        "total_known_duration_seconds": round(total, 2) if all_durations_known else None,
        "duration_complete": all_durations_known,
        "evidence": sorted(set(evidence)),
        "review_notes": ["Heuristic candidate only; verify every segment and its order before confirming."],
    }

def build_candidates(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    known_title_keys = set()
    for item in records:
        title = str(item.get("title") or item.get("identifier") or "")
        parsed = title_role(title)
        if parsed:
            known_title_keys.add(parsed[0])
        inv = item.get("vhs_fever_file_inventory") or {}
        detected_roles = {
            filename_role(str(file.get("name") or ""))
            for file in inv.get("video_files", [])
        } - {None}
        if len(detected_roles) >= 2:
            known_title_keys.add(norm(title))
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in records:
        identifier = str(item.get("identifier") or "").strip()
        if not identifier:
            continue
        title = str(item.get("title") or identifier)
        inv = item.get("vhs_fever_file_inventory") or {}
        if inv.get("inventory_status") != "ok":
            continue
        item_videos = [f for f in inv.get("video_files", []) if Path(str(f.get("name", ""))).suffix.lower() in VIDEO_EXTS or f.get("format")]
        # Drop a streaming derivative when its referenced original is present.
        originals = {str(f.get("name") or "") for f in item_videos if f.get("origin_hint") == "original"}
        item_videos = [
            f for f in item_videos
            if not (f.get("origin_hint") == "derivative"
                    and str(f.get("original_file_reference") or "") in originals)
        ]
        t_role = title_role(title)
        title_key = t_role[0] if t_role else norm(title)
        for file in item_videos:
            filename = str(file.get("name") or "")
            f_role = filename_role(filename)
            role = f_role or (t_role[1] if t_role else None)
            evidence = []
            if f_role:
                evidence.append("segment_role_in_filename:" + f_role)
            if t_role and not f_role:
                evidence.append("segment_role_in_item_title:" + t_role[1])
            if role is None and title_key in known_title_keys and (file.get("duration_seconds") or 0) >= 300:
                role = "main_program"
                evidence.append("exact_normalized_title_match_to_labeled_segments")
            if not role or len(title_key) < 4:
                continue
            groups["title:" + title_key].append({
                "archive_identifier": identifier,
                "file_name": filename,
                "duration_seconds": file.get("duration_seconds"),
                "role": role,
                "program_title": title_key.title(),
                "evidence": evidence,
            })
    candidates = []
    for key, members in sorted(groups.items()):
        candidate = candidate_from_group(key, members)
        if candidate:
            candidates.append(candidate)
    return candidates

def read_jsonl(path: Path) -> list[dict[str, Any]]:
    records = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if line.strip():
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError(f"Expected JSON object on line {line_number}")
                records.append(value)
    return records

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    candidates = build_candidates(read_jsonl(args.input))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as stream:
        for candidate in candidates:
            stream.write(json.dumps(candidate, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {
        "candidate_programs": len(candidates),
        "suggested_only": True,
        "source_records_modified": False,
        "media_payloads_downloaded": False,
        "note": "Candidates require review; heuristics do not establish identity, playback availability, or rights.",
    }
    args.output.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))

if __name__ == "__main__":
    main()
