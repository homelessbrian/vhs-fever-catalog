#!/usr/bin/env python3
"""Inspect Internet Archive item file inventories without downloading media."""
from __future__ import annotations
import argparse, json, time, urllib.error, urllib.request
from collections import Counter
from pathlib import Path

VIDEO_EXTS = {".mp4", ".m4v", ".mpeg", ".mpg", ".mkv", ".avi", ".mov", ".ogv", ".webm", ".wmv", ".flv"}
VIDEO_FORMATS = {"mpeg4", "h.264", "h.264 ia", "mpeg2", "mpeg2 iplayer", "matroska", "quicktime", "webm", "ogg video", "windows media"}
LABELS = ["movie_or_film", "tv_episode_or_recording", "cartoon_or_animation", "trailer_or_clip", "vhs_or_dvd_extra", "promo_or_commercial", "other_or_review", "artwork_or_packaging", "audio_only"]

def choose_sample(path: Path, limit: int) -> list[dict]:
    groups = {label: [] for label in LABELS}
    with path.open(encoding="utf-8") as f:
        for line in f:
            item = json.loads(line)
            label = item.get("vhs_fever_classification", {}).get("label", "other_or_review")
            if label in groups:
                groups[label].append(item)
    # Deterministic round-robin keeps small categories represented.
    chosen, indexes = [], {label: 0 for label in LABELS}
    while len(chosen) < limit:
        added = False
        for label in LABELS:
            i = indexes[label]
            if i < len(groups[label]) and len(chosen) < limit:
                chosen.append(groups[label][i]); indexes[label] += 1; added = True
        if not added: break
    return chosen

def inspect(identifier: str, retries: int = 3) -> dict:
    url = "https://archive.org/metadata/" + urllib.parse.quote(identifier, safe="")
    req = urllib.request.Request(url, headers={"User-Agent": "VHS-FEVER-Catalog/0.1 (metadata inventory check)"})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                data = json.load(response)
            files = data.get("files", [])
            video, audio, image, total_bytes = [], [], [], 0
            for entry in files:
                name = entry.get("name", "")
                ext = Path(name).suffix.lower()
                fmt = str(entry.get("format", "")).lower()
                try: size = int(entry.get("size", 0) or 0)
                except (TypeError, ValueError): size = 0
                total_bytes += size
                if ext in VIDEO_EXTS or fmt in VIDEO_FORMATS:
                    video.append({"name": name, "size": size, "format": entry.get("format")})
                elif ext in {".mp3", ".flac", ".ogg", ".wav", ".m4a", ".aac"}:
                    audio.append(name)
                elif ext in {".jpg", ".jpeg", ".png", ".gif", ".tif", ".tiff"}:
                    image.append(name)
            return {"inventory_status": "ok", "file_count": len(files), "video_files": video,
                    "audio_file_count": len(audio), "image_file_count": len(image),
                    "total_listed_bytes": total_bytes,
                    "has_video_candidate": bool(video),
                    "access_restricted": bool(data.get("metadata", {}).get("access-restricted-item"))}
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            if attempt + 1 == retries:
                return {"inventory_status": "error", "error": str(exc)[:300]}
            time.sleep(2 ** attempt)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--limit", type=int, default=120)
    p.add_argument("--delay", type=float, default=0.5)
    a = p.parse_args()
    if not 1 <= a.limit <= 250: p.error("--limit must be between 1 and 250 for this pilot")
    sample = choose_sample(a.input, a.limit)
    counts = Counter()
    a.output.parent.mkdir(parents=True, exist_ok=True)
    with a.output.open("w", encoding="utf-8") as out:
        for index, item in enumerate(sample):
            result = dict(item)
            result["vhs_fever_file_inventory"] = inspect(str(item.get("identifier", "")))
            result["vhs_fever_file_inventory"]["sample_index"] = index + 1
            result["vhs_fever_file_inventory"]["classification_label"] = item.get("vhs_fever_classification", {}).get("label")
            counts[result["vhs_fever_file_inventory"]["inventory_status"]] += 1
            out.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
            if index + 1 < len(sample): time.sleep(max(0, a.delay))
    summary = {"sample_size": len(sample), "requested_limit": a.limit,
               "sampling": "deterministic round-robin across classification labels",
               "inventory_status_counts": dict(counts),
               "items_with_video_file_candidates": sum(1 for x in read_results(a.output) if x["vhs_fever_file_inventory"].get("has_video_candidate")),
               "media_payloads_downloaded": False, "rights_determined": False}
    a.output.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))

def read_results(path):
    with path.open(encoding="utf-8") as f:
        for line in f: yield json.loads(line)

if __name__ == "__main__": main()
