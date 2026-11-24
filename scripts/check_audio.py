#!/usr/bin/env python3
"""Check every video file for a sound track, silence, and a picture.

Reads recordings.jsonl from the catalog model, probes each playable file over
HTTP with ffprobe/ffmpeg (only small pieces are read, nothing is downloaded
whole), and writes one result per file. Results are reused between runs, so
only new files are checked. Work can be split into shards for GitHub Actions.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SILENT_MAX_DB = -50.0   # loudest moment quieter than this = effectively silent
SAMPLE_SECONDS = 10
CHECK_VERSION = 1


def files_from_recordings(path: Path) -> list[dict[str, Any]]:
    out, seen = [], set()
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            rec = json.loads(line)
            for seg in rec["segments"]:
                for s in seg["sources"]:
                    if s["source_id"] in seen:
                        continue
                    seen.add(s["source_id"])
                    out.append({"file_id": s["source_id"], "url": s["playback_url"],
                                "name": s["original_name"], "duration": s.get("duration_seconds")})
    return out


def shard_of(file_id: str, shards: int) -> int:
    return int(hashlib.sha256(file_id.encode()).hexdigest(), 16) % shards


def probe(url: str, timeout: int = 90) -> dict[str, Any]:
    cmd = ["ffprobe", "-v", "error", "-show_entries",
           "stream=codec_type,codec_name,channels,width,height:format=duration",
           "-of", "json", url]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError((p.stderr or "ffprobe failed").strip()[:200])
    return json.loads(p.stdout or "{}")


def loudest_db(url: str, start: float, timeout: int = 90) -> float | None:
    cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-ss", f"{start:.0f}", "-i", url,
           "-t", str(SAMPLE_SECONDS), "-vn", "-af", "volumedetect", "-f", "null", "-"]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    m = re.search(r"max_volume:\s*(-?[\d.]+|-inf) dB", p.stderr)
    if not m:
        return None
    return float("-inf") if m.group(1) == "-inf" else float(m.group(1))


def check(f: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {"file_id": f["file_id"], "name": f["name"],
                              "check_version": CHECK_VERSION,
                              "checked_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    try:
        info = probe(f["url"])
    except Exception as exc:  # noqa: BLE001
        result.update(verdict="could_not_open", error=str(exc)[:200])
        return result
    streams = info.get("streams") or []
    audio = [s for s in streams if s.get("codec_type") == "audio"]
    video = [s for s in streams if s.get("codec_type") == "video"]
    dur = f.get("duration") or float((info.get("format") or {}).get("duration") or 0) or None
    result.update(
        has_audio_track=bool(audio), has_video_track=bool(video),
        audio_codec=audio[0].get("codec_name") if audio else None,
        audio_channels=audio[0].get("channels") if audio else None,
        video_codec=video[0].get("codec_name") if video else None,
        width=video[0].get("width") if video else None,
        height=video[0].get("height") if video else None,
    )
    if not audio:
        result["verdict"] = "no_audio_track"
        return result
    points = [dur * 0.25, dur * 0.75] if dur and dur > 60 else [0]
    levels = []
    for start in points:
        try:
            levels.append(loudest_db(f["url"], start))
        except Exception:  # noqa: BLE001
            levels.append(None)
    result["sample_max_db"] = [None if v is None else (None if v == float("-inf") else v) for v in levels]
    measured = [v for v in levels if v is not None]
    if not measured:
        result["verdict"] = "audio_not_measured"
    elif all(v < SILENT_MAX_DB for v in measured):
        result["verdict"] = "silent_audio"
    elif not video:
        result["verdict"] = "no_video_track"
    else:
        result["verdict"] = "ok"
    return result


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--recordings", required=True)
    ap.add_argument("--previous", help="earlier results (jsonl) to reuse")
    ap.add_argument("--output", required=True)
    ap.add_argument("--shard", type=int, default=0)
    ap.add_argument("--shards", type=int, default=1)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--limit", type=int, default=0, help="check at most N new files (0 = all)")
    args = ap.parse_args()

    previous: dict[str, dict[str, Any]] = {}
    if args.previous and Path(args.previous).exists():
        for line in Path(args.previous).read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                if r.get("check_version") == CHECK_VERSION and r.get("verdict") != "could_not_open":
                    previous[r["file_id"]] = r
    files = [f for f in files_from_recordings(Path(args.recordings))
             if shard_of(f["file_id"], args.shards) == args.shard]
    todo = [f for f in files if f["file_id"] not in previous]
    if args.limit:
        todo = todo[: args.limit]
    kept = [previous[f["file_id"]] for f in files if f["file_id"] in previous]
    print(f"shard {args.shard}/{args.shards}: {len(files)} files, {len(kept)} already checked, {len(todo)} to check")
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        fresh = list(pool.map(check, todo))
    rows = kept + fresh
    with open(args.output, "w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    counts: dict[str, int] = {}
    for r in fresh:
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
    print(json.dumps({"checked_now": len(fresh), "verdicts": counts}, indent=2))


if __name__ == "__main__":
    main()
