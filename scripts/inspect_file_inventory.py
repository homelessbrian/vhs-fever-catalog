#!/usr/bin/env python3
"""Inspect Internet Archive file inventories without downloading media payloads."""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

VIDEO_EXTS = {".mp4", ".m4v", ".mpeg", ".mpg", ".mkv", ".avi", ".mov", ".ogv", ".webm", ".wmv", ".flv"}
VIDEO_FORMATS = {"mpeg4", "h.264", "h.264 ia", "mpeg2", "mpeg2 iplayer", "matroska", "quicktime", "webm", "ogg video", "windows media"}
AUDIO_EXTS = {".mp3", ".flac", ".ogg", ".wav", ".m4a", ".aac"}
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".tif", ".tiff"}
LABELS = [
    "movie_or_film", "tv_episode_or_recording", "cartoon_or_animation", "fan_made_vhs_compilation", "holiday_special",
    "trailer_or_clip", "vhs_or_dvd_extra", "promo_or_commercial",
    "other_or_review", "artwork_or_packaging", "audio_only",
]


def choose_sample(path: Path, limit: int) -> list[dict]:
    groups = {label: [] for label in LABELS}
    with path.open(encoding="utf-8") as source:
        for line in source:
            item = json.loads(line)
            label = item.get("vhs_fever_classification", {}).get("label", "other_or_review")
            if label in groups:
                groups[label].append(item)
    chosen, indexes = [], {label: 0 for label in LABELS}
    while len(chosen) < limit:
        added = False
        for label in LABELS:
            i = indexes[label]
            if i < len(groups[label]) and len(chosen) < limit:
                chosen.append(groups[label][i])
                indexes[label] += 1
                added = True
        if not added:
            break
    return chosen


def number(value: object) -> float | None:
    try:
        parsed = float(value)
        return parsed if parsed >= 0 else None
    except (TypeError, ValueError):
        return None


def inspect(identifier: str, retries: int = 3) -> dict:
    url = "https://archive.org/metadata/" + urllib.parse.quote(identifier, safe="")
    request = urllib.request.Request(
        url, headers={"User-Agent": "VHS-FEVER-Catalog/0.2 (metadata inventory check)"}
    )
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = json.load(response)
            files = data.get("files", [])
            video, audio, image = [], [], []
            total_bytes = 0
            for entry in files:
                name = str(entry.get("name", ""))
                ext = Path(name).suffix.lower()
                fmt = str(entry.get("format", "")).lower()
                size = number(entry.get("size")) or 0
                total_bytes += int(size)
                if ext in VIDEO_EXTS or fmt in VIDEO_FORMATS:
                    source = str(entry.get("source", "")).lower() or None
                    original = entry.get("original")
                    if source == "original":
                        origin_hint = "original"
                    elif source == "derivative" or original:
                        origin_hint = "derivative"
                    else:
                        origin_hint = "unknown"
                    video.append({
                        "name": name,
                        "size_bytes": int(size),
                        "format": entry.get("format"),
                        "duration_seconds": number(entry.get("length")),
                        "source_hint": source,
                        "original_file_reference": original,
                        "origin_hint": origin_hint,
                        "private": str(entry.get("private", "")).lower() in {"true", "1"},
                    })
                elif ext in AUDIO_EXTS:
                    audio.append(name)
                elif ext in IMAGE_EXTS:
                    image.append(name)

            durations = [f["duration_seconds"] for f in video if f["duration_seconds"] is not None]
            sizes = [f["size_bytes"] for f in video if f["size_bytes"] > 0]
            return {
                "inventory_status": "ok",
                "file_count": len(files),
                "video_files": video,
                "video_file_count": len(video),
                "audio_file_count": len(audio),
                "image_file_count": len(image),
                "total_listed_bytes": total_bytes,
                "video_total_bytes": sum(sizes),
                "video_duration_min_seconds": min(durations) if durations else None,
                "video_duration_max_seconds": max(durations) if durations else None,
                "video_files_with_duration": len(durations),
                "video_origin_hints": dict(Counter(f["origin_hint"] for f in video)),
                "has_video_candidate": bool(video),
                "access_restricted": bool(data.get("metadata", {}).get("access-restricted-item")),
                "item_runtime_metadata": data.get("metadata", {}).get("runtime"),
            }
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            if attempt + 1 == retries:
                return {"inventory_status": "error", "error": str(exc)[:300]}
            time.sleep(2 ** attempt)



def analyze_item(item: dict, inventory: dict) -> dict:
    """Infer content and collection format using title, filenames and file lengths."""
    title = str(item.get("title", ""))
    low = title.lower()
    files = inventory.get("video_files", [])
    durations = [f["duration_seconds"] for f in files if f.get("duration_seconds") is not None]
    total = sum(durations)
    names = " ".join(str(f.get("name", "")) for f in files).lower()
    fanmade = bool(__import__("re").search(r"\\b(fan[ -]?made|custom)\\b", low) and __import__("re").search(r"\\b(vhs|tape|special|compilation)\\b", low))
    segment_words = sum(word in names for word in ("opening", "closing", "episode", "part", "bonus", "cartoon", "feature"))
    promo_words = sum(word in names for word in ("trailer", "commercial", "promo", "advert"))
    # Runtime bands are clues, not hard category gates. Multi-file sets are treated as
    # compilations when filenames suggest separately authored segments.
    if fanmade or (len(files) >= 3 and segment_words >= 2):
        fmt = "fan_made_vhs" if fanmade else "multi_segment_compilation"
    elif "dvd opening" in low or "opening to" in low or "dvd closing" in low:
        fmt = "dvd_extra"
    elif len(files) > 1:
        fmt = "multi_file_item"
    else:
        fmt = "standalone_or_unknown"
    if fanmade and any(x in low for x in ("halloween", "christmas", "holiday")):
        content = "holiday_special"
    elif fanmade and "ice age" in low:
        content = "movie_or_film"
    elif "trailer" in low or ("trailer" in names and len(files) == 1 and total and total < 600):
        content = "trailer_or_clip"
    elif "commercial" in low or ("commercial" in names and total and total < 180):
        content = "commercial_or_promo"
    elif "episode" in low or "episode" in names:
        content = "tv_episode_or_recording"
    elif "cartoon" in low or "animation" in low:
        content = "cartoon_or_animation"
    elif total >= 3600:
        content = "movie_or_film_or_long_form"
    elif total >= 1200:
        content = "special_or_long_program"
    elif total and total < 120:
        content = "short_clip_or_bumper"
    else:
        content = "unknown_needs_review"
    if promo_words and (fanmade or segment_words >= 2):
        conflict = "promo_words_in_file_names_but_multi_segment_context"
    elif promo_words:
        conflict = "promo_filename_clues"
    else:
        conflict = None
    return {
        "content_type": content,
        "collection_format": fmt,
        "video_file_count": len(files),
        "total_known_video_duration_seconds": round(total, 2),
        "longest_video_seconds": max(durations) if durations else None,
        "shortest_video_seconds": min(durations) if durations else None,
        "filename_segment_clues": segment_words,
        "filename_promo_clues": promo_words,
        "conflicting_evidence": conflict,
        "needs_review": content == "unknown_needs_review" or conflict is not None or not durations,
        "logic_version": 2,
    }

def read_results(path: Path):
    with path.open(encoding="utf-8") as source:
        for line in source:
            yield json.loads(line)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=120)
    parser.add_argument("--delay", type=float, default=0.5)
    args = parser.parse_args()
    if not 1 <= args.limit <= 250:
        parser.error("--limit must be between 1 and 250 for this pilot")

    sample = choose_sample(args.input, args.limit)
    status_counts = Counter()
    label_stats = {}
    all_video_files = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as output:
        for index, item in enumerate(sample):
            inventory = inspect(str(item.get("identifier", "")))
            label = item.get("vhs_fever_classification", {}).get("label", "other_or_review")
            inventory["sample_index"] = index + 1
            inventory["classification_label"] = label
            result = dict(item)
            result["vhs_fever_file_inventory"] = inventory
            status_counts[inventory["inventory_status"]] += 1
            stats = label_stats.setdefault(label, {"sampled": 0, "with_video_candidate": 0})
            stats["sampled"] += 1
            stats["with_video_candidate"] += int(inventory.get("has_video_candidate", False))
            all_video_files.extend(inventory.get("video_files", []))
            output.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
            if index + 1 < len(sample):
                time.sleep(max(0, args.delay))

    origin_counts = Counter(file["origin_hint"] for file in all_video_files)
    summary = {
        "sample_size": len(sample),
        "requested_limit": args.limit,
        "sampling": "deterministic round-robin across classification labels",
        "inventory_status_counts": dict(status_counts),
        "items_with_video_file_candidates": sum(v["with_video_candidate"] for v in label_stats.values()),
        "items_by_classification": label_stats,\n        "duration_logic_enabled": True,\n        "analysis_logic_version": 2,
        "video_file_count": len(all_video_files),
        "video_files_with_duration": sum(f["duration_seconds"] is not None for f in all_video_files),
        "video_origin_hints": dict(origin_counts),
        "video_file_size_bytes_total": sum(f["size_bytes"] for f in all_video_files),
        "media_payloads_downloaded": False,
        "rights_determined": False,
        "note": "Original/derivative is a metadata hint only, not a verified playback or rights determination.",
    }
    args.output.with_suffix(".summary.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
