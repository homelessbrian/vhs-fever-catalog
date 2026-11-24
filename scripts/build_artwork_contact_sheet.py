#!/usr/bin/env python3
"""Build a review contact sheet from small Internet Archive artwork previews.

Only public image preview files are fetched. Video, PDF, and full-resolution
image payloads are never downloaded. Every tile is linked to its exact Archive
identifier and filename in the accompanying JSON manifest.
"""
from __future__ import annotations

import argparse
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}
FRAME_TITLE_RE = re.compile(r"\b(?:frames?|screenshots?|stills?|fotogramas?|fotograma|imagen\s+fija)\b", re.I)
PACKAGING_TITLE_RE = re.compile(r"\b(?:vhs|videocassette|video\s*tape|sleeve|cover|box|cassette|packaging)\b", re.I)
FRAME_NAME_RE = re.compile(r"(?:^|[_ .-])(?:frame|still|screenshot)[_ .-]*\d+", re.I)
SEQUENTIAL_INDEX_RE = re.compile(r"(?:\s*\((\d{1,5})\)|[_ .-](\d{4,}))$", re.I)

def is_thumbnail_derivative(filename: str, candidate: dict[str, Any]) -> bool:
    path = Path(filename)
    parts = [p.casefold() for p in path.parts]
    return (any(p.endswith(".thumbs") for p in parts)
            or str(candidate.get("source_hint", "")).casefold() == "derivative"
            or str(candidate.get("format", "")).casefold() == "thumbnail"
            or path.stem.casefold().endswith("_thumb"))

def frame_filter_decision(item: dict[str, Any], candidates: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    inv = item.get("vhs_fever_file_inventory") or {}
    has_video = bool(inv.get("has_video_candidate"))
    title = str(item.get("title") or "")
    description = str(item.get("description") or "")
    explicit_frames = bool(FRAME_TITLE_RE.search(title) or FRAME_TITLE_RE.search(description[:1200]))
    packaging_context = bool(PACKAGING_TITLE_RE.search(title))
    safe, filtered = [], []
    for candidate in candidates:
        name = str(candidate.get("file_name") or candidate.get("name") or "")
        reason = None
        if is_thumbnail_derivative(name, candidate):
            reason = "archive_thumbnail_derivative"
        elif has_video and explicit_frames:
            reason = "item_metadata_identifies_video_frames"
        elif has_video and not packaging_context and FRAME_FILE_RE.search(Path(name).stem):
            reason = "numbered_video_frame_pattern"
        if reason:
            filtered.append({"file_name": name, "reason": reason})
        else:
            kept = dict(candidate)
            if has_video and SEQUENTIAL_INDEX_RE.search(Path(name).stem) and packaging_context:
                kept["frame_filter_status"] = "ambiguous_numbered_image_kept_for_review"
            safe.append(kept)
    return safe, filtered


def preview_name(filename: str) -> str:
    path = Path(filename)
    if path.stem.casefold().endswith("_thumb"):
        return filename
    return str(path.with_name(path.stem + "_thumb" + path.suffix))


def infer_geometry_role(width: int, height: int, filename_role: str | None = None) -> tuple[str, str, str]:
    """Suggest a packaging role from scan geometry; never claim visual verification."""
    if filename_role and filename_role != "generic_packaging_art":
        return filename_role, "filename", "high"
    if width <= 0 or height <= 0:
        return "generic_packaging_art", "unknown_geometry", "low"
    ratio = width / height
    if ratio <= 0.28:
        return "vhs_spine_candidate", "very_narrow_portrait_scan", "medium"
    if ratio >= 1.45:
        return "vhs_full_wrap_candidate", "wide_landscape_scan", "medium"
    if 0.52 <= ratio <= 0.86:
        return "vhs_cover_panel_candidate", "portrait_cover_scan", "medium"
    return "generic_packaging_art", "unclassified_geometry", "low"


def collect_previews(input_path: Path, limit: int, include_filter_summary: bool = False):
    selected: dict[tuple[str, str], dict[str, Any]] = {}
    filtered_summary: list[dict[str, Any]] = []
    with input_path.open(encoding="utf-8") as stream:
        for line in stream:
            if not line.strip(): continue
            item = json.loads(line)
            enrichment = item.get("vhs_fever_metadata_artwork") or {}
            eligible = []
            for candidate in enrichment.get("artwork_candidates") or []:
                name = str(candidate.get("file_name") or "")
                if Path(name).suffix.casefold() not in IMAGE_EXTS or candidate.get("private"): continue
                eligible.append(candidate)
            safe, rejected = frame_filter_decision(item, eligible)
            if rejected:
                filtered_summary.append({"archive_identifier": item.get("identifier"), "item_title": item.get("title"), "excluded_count": len(rejected), "reasons": sorted({x["reason"] for x in rejected}), "files": rejected[:30]})
            for candidate in safe:
                name = str(candidate.get("file_name") or "")
                if str(candidate.get("source_hint", "")).casefold() == "derivative" or Path(name).stem.casefold().endswith("_thumb"): continue
                key = (str(item.get("identifier") or ""), name.casefold())
                if not key[0]: continue
                preview = preview_name(name)
                url = candidate.get("asset_url")
                if preview != name and url:
                    url = str(url).replace(urllib.parse.quote(name, safe="/"), urllib.parse.quote(preview, safe="/"))
                selected[key] = {"archive_identifier": key[0], "item_title": item.get("title"), "item_description": str(item.get("description") or "")[:1000], "item_has_video": bool((item.get("vhs_fever_file_inventory") or {}).get("has_video_candidate")), "file_name": preview, "source_file_name": name, "candidate_role": candidate.get("candidate_role", "generic_packaging_art"), "asset_url": url, "licenseurl": item.get("licenseurl"), "review_status": "visual_review_required", "frame_filter_status": candidate.get("frame_filter_status", "passed")}
                if len(selected) >= limit: break
            if len(selected) >= limit: break
    result = list(selected.values())
    return (result, filtered_summary) if include_filter_summary else result
def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=40)
    parser.add_argument("--delay", type=float, default=0.15)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    entries, filtered_items = collect_previews(args.input, max(1, args.limit), include_filter_summary=True)

    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as exc:
        raise SystemExit("Pillow is required: pip install Pillow") from exc

    tile_w, tile_h, image_h = 360, 270, 195
    cols = 3
    rows = max(1, (len(entries) + cols - 1) // cols)
    sheet = Image.new("RGB", (cols * tile_w, rows * tile_h), "#171923")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    downloaded = []
    for index, entry in enumerate(entries):
        url = entry.get("asset_url")
        x, y = (index % cols) * tile_w, (index // cols) * tile_h
        try:
            if not url or not str(url).startswith("https://archive.org/"):
                raise ValueError("untrusted or missing Archive URL")
            request = urllib.request.Request(str(url), headers={"User-Agent": "VHS-FEVER-Artwork-Audit/1.0"})
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = response.read(2_000_001)
            if len(payload) > 2_000_000:
                raise ValueError("preview exceeds 2 MB limit")
            from io import BytesIO
            image = Image.open(BytesIO(payload)).convert("RGB")
            source_width, source_height = image.size
            suggested_role, role_basis, role_confidence = infer_geometry_role(
                source_width, source_height, entry.get("candidate_role")
            )
            entry["source_dimensions"] = [source_width, source_height]
            entry["source_aspect_ratio"] = round(source_width / source_height, 3) if source_height else None
            entry["filename_role"] = entry.get("candidate_role")
            entry["suggested_role"] = suggested_role
            entry["role_basis"] = role_basis
            entry["role_confidence"] = role_confidence
            image.thumbnail((tile_w - 20, image_h - 12))
            px = x + (tile_w - image.width) // 2
            py = y + 8 + (image_h - image.height) // 2
            sheet.paste(image, (px, py))
            entry["preview_status"] = "downloaded"
            entry["preview_dimensions"] = list(image.size)
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            entry["preview_status"] = "unavailable"
            entry["preview_error"] = str(exc)[:180]
            draw.rectangle((x + 10, y + 10, x + tile_w - 10, y + image_h), fill="#343849")
            draw.text((x + 22, y + 80), "Preview unavailable", fill="white", font=font)
        label = f'{index + 1}. {str(entry.get("item_title") or "")[:42]}'
        draw.text((x + 10, y + image_h + 8), label, fill="white", font=font)
        role_label = entry.get("suggested_role", entry.get("candidate_role", "generic_packaging_art"))
        draw.text((x + 10, y + image_h + 25), f'{role_label} | {entry["file_name"][:43]}', fill="#f5d76e", font=font)
        draw.text((x + 10, y + image_h + 42), f'IA: {entry["archive_identifier"][:48]}', fill="#b9c0d4", font=font)
        downloaded.append(entry)
        time.sleep(max(0, args.delay))

    sheet.save(args.output_dir / "vhs-artwork-contact-sheet.jpg", quality=88, optimize=True)
    manifest = {
        "schema_version": 3,
        "role_inference": "Filename labels take precedence; otherwise scan aspect ratio suggests spine, cover-panel, or full-wrap candidates. Geometry is a review hint, not visual confirmation.",
        "preview_count": len(entries),
        "frame_filter": {"version": 1, "excluded_candidate_count": sum(x["excluded_count"] for x in filtered_items), "affected_item_count": len(filtered_items), "excluded_items": filtered_items},
        "downloaded_preview_count": sum(e.get("preview_status") == "downloaded" for e in entries),
        "full_resolution_images_downloaded": False,
        "video_payloads_downloaded": False,
        "rights_determined": False,
        "items": downloaded,
        "note": "Original image candidates are selected; Archive thumbnail derivatives and likely video frames are excluded with reasons recorded. Ambiguous numbered assets remain for review. Contact sheet previews are for human review only. Filename roles are not visually verified; Archive access and rights must be checked before display or playback.",
    }
    (args.output_dir / "vhs-artwork-review-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({k: v for k, v in manifest.items() if k != "items"}, indent=2))


if __name__ == "__main__":
    main()
