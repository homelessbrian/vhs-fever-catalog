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


def preview_name(filename: str) -> str:
    path = Path(filename)
    if path.stem.casefold().endswith("_thumb"):
        return filename
    return str(path.with_name(path.stem + "_thumb" + path.suffix))


def collect_previews(input_path: Path, limit: int) -> list[dict[str, Any]]:
    selected: dict[tuple[str, str], dict[str, Any]] = {}
    with input_path.open(encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            item = json.loads(line)
            enrichment = item.get("vhs_fever_metadata_artwork") or {}
            for candidate in enrichment.get("artwork_candidates") or []:
                name = str(candidate.get("file_name") or "")
                ext = Path(name).suffix.casefold()
                if ext not in IMAGE_EXTS or candidate.get("private"):
                    continue
                if re.search(r"(?:^|[/\\])(?:__ia_thumb|.*\.thumbs)(?:[/\\]|$)", name, re.I):
                    continue
                stem = Path(name).stem
                stem = re.sub(r"_thumb$", "", stem, flags=re.I)
                key = (str(candidate.get("archive_identifier") or ""), stem.casefold())
                if not key[0]:
                    continue
                preview = preview_name(name)
                url = candidate.get("asset_url")
                if preview != name:
                    url = str(url or "").replace(urllib.parse.quote(name, safe="/"), urllib.parse.quote(preview, safe="/"))
                entry = {
                    "archive_identifier": key[0],
                    "item_title": item.get("title"),
                    "file_name": preview,
                    "source_file_name": name,
                    "candidate_role": candidate.get("candidate_role", "generic_packaging_art"),
                    "asset_url": url,
                    "licenseurl": item.get("licenseurl"),
                    "review_status": "visual_review_required",
                }
                # Prefer a thumbnail record when available.
                if key not in selected or stem.casefold().endswith("_thumb"):
                    selected[key] = entry
                if len(selected) >= limit:
                    break
            if len(selected) >= limit:
                break
    return list(selected.values())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=40)
    parser.add_argument("--delay", type=float, default=0.15)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    entries = collect_previews(args.input, max(1, args.limit))

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
        draw.text((x + 10, y + image_h + 25), f'{entry["candidate_role"]} | {entry["file_name"][:43]}', fill="#f5d76e", font=font)
        draw.text((x + 10, y + image_h + 42), f'IA: {entry["archive_identifier"][:48]}', fill="#b9c0d4", font=font)
        downloaded.append(entry)
        time.sleep(max(0, args.delay))

    sheet.save(args.output_dir / "vhs-artwork-contact-sheet.jpg", quality=88, optimize=True)
    manifest = {
        "schema_version": 1,
        "preview_count": len(entries),
        "downloaded_preview_count": sum(e.get("preview_status") == "downloaded" for e in entries),
        "full_resolution_images_downloaded": False,
        "video_payloads_downloaded": False,
        "rights_determined": False,
        "items": downloaded,
        "note": "Contact sheet previews are for human review only. Filename roles are not visually verified; Archive access and rights must be checked before display or playback.",
    }
    (args.output_dir / "vhs-artwork-review-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({k: v for k, v in manifest.items() if k != "items"}, indent=2))


if __name__ == "__main__":
    main()
