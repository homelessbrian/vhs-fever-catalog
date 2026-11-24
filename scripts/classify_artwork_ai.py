#!/usr/bin/env python3
"""Experimental, review-only zero-shot AI classification of VHS artwork previews.

Uses a local CLIP model; no hosted inference API is called. Scores are relative
similarities, not calibrated probabilities and never auto-approve artwork.
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

MODEL_ID = "openai/clip-vit-base-patch32"
LABELS = [
    ("vhs_front_cover", "the front cover panel of a physical VHS videocassette rental box, showing its movie artwork and title"),
    ("vhs_back_cover", "the back cover panel of a physical VHS videocassette rental box, usually with synopsis and small screenshots"),
    ("vhs_spine", "the narrow spine edge of a physical VHS videocassette box, with a vertical title"),
    ("vhs_full_wrap", "a complete unfolded VHS videocassette sleeve or box wrap showing front, spine and back together"),
    ("vhs_cassette_label", "the paper label attached to a VHS videocassette itself"),
    ("dvd_packaging", "a DVD keep case or DVD disc packaging, not VHS packaging"),
    ("movie_poster", "a standalone theatrical movie poster or one-sheet, not physical VHS packaging"),
    ("video_screenshot", "a frame grab or screenshot from a movie or television video"),
    ("photograph", "an ordinary photograph unrelated to physical video packaging"),
    ("book_or_magazine", "a book cover or magazine cover, not VHS packaging"),
    ("video_game_packaging", "video game box art or game packaging, not VHS packaging"),
    ("other_or_unclear", "an unrelated image, graphic, scan, or unclear object"),
]
VHS_LABELS = {"vhs_front_cover", "vhs_back_cover", "vhs_spine", "vhs_full_wrap", "vhs_cassette_label"}


def decide_review_label(ranked: list[dict[str, Any]], threshold: float = 0.24,
                        margin: float = 0.035) -> dict[str, Any]:
    """Convert relative CLIP scores into conservative suggestions."""
    if not ranked:
        return {"suggested_role": "unknown", "review_status": "needs_review",
                "reason": "no_model_scores", "confidence_band": "none"}
    ordered = sorted(ranked, key=lambda x: float(x.get("score", 0)), reverse=True)
    top = ordered[0]
    top_score = float(top.get("score", 0))
    second_score = float(ordered[1].get("score", 0)) if len(ordered) > 1 else 0.0
    gap = top_score - second_score
    role = str(top.get("label", "other_or_unclear"))
    if top_score < threshold or gap < margin:
        return {"suggested_role": "unknown", "review_status": "needs_review",
                "reason": "low_similarity_or_ambiguous", "confidence_band": "low",
                "top_candidate": role, "top_score": round(top_score, 4),
                "score_margin": round(gap, 4)}
    return {"suggested_role": role, "review_status": "needs_review",
            "reason": "zero_shot_visual_similarity", "confidence_band": "medium" if top_score < 0.34 else "higher",
            "top_score": round(top_score, 4), "score_margin": round(gap, 4),
            "is_vhs_packaging_candidate": role in VHS_LABELS}


def fetch_preview(url: str, max_bytes: int = 2_000_000) -> bytes:
    if not url.startswith("https://archive.org/"):
        raise ValueError("Only archive.org preview URLs are allowed")
    request = urllib.request.Request(url, headers={"User-Agent": "VHS-FEVER-Artwork-Audit/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = response.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise ValueError("preview exceeds 2 MB limit")
    return payload


def load_classifier(model_id: str):
    try:
        import torch
        from PIL import Image
        from transformers import CLIPModel, CLIPProcessor
    except ImportError as exc:
        raise SystemExit("Install dependencies: pip install Pillow torch transformers safetensors") from exc
    torch.set_num_threads(2)
    processor = CLIPProcessor.from_pretrained(model_id)
    model = CLIPModel.from_pretrained(model_id, use_safetensors=True)
    model.eval()
    return torch, Image, processor, model


def classify_image(payload: bytes, classifier) -> list[dict[str, Any]]:
    torch, Image, processor, model = classifier
    from io import BytesIO
    image = Image.open(BytesIO(payload)).convert("RGB")
    prompts = [f"a photograph or scan of {description}" for _, description in LABELS]
    inputs = processor(text=prompts, images=image, return_tensors="pt", padding=True)
    with torch.inference_mode():
        logits = model(**inputs).logits_per_image[0]
        scores = logits.softmax(dim=0).tolist()
    return [{"label": LABELS[i][0], "score": float(scores[i])} for i in range(len(LABELS))]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default=MODEL_ID)
    parser.add_argument("--delay", type=float, default=0.1)
    parser.add_argument("--limit", type=int, default=40)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    entries = manifest.get("items") or []
    classifier = load_classifier(args.model)
    enriched = []
    for entry in entries[:max(0, args.limit)]:
        item = dict(entry)
        try:
            payload = fetch_preview(str(item.get("asset_url") or ""))
            ranked = classify_image(payload, classifier)
            item["ai_visual_candidates"] = sorted(ranked, key=lambda x: x["score"], reverse=True)[:6]
            item["ai_visual_classification"] = decide_review_label(ranked)
            item["ai_model"] = args.model
            item["ai_method"] = "local_zero_shot_clip"
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            item["ai_visual_classification"] = {
                "suggested_role": "unknown", "review_status": "needs_review",
                "reason": "preview_or_inference_error", "error": str(exc)[:200]}
        enriched.append(item)
        time.sleep(max(0, args.delay))

    manifest["schema_version"] = max(2, int(manifest.get("schema_version", 1)))
    manifest["ai_review"] = {
        "enabled": True, "model": args.model, "method": "local_zero_shot_clip",
        "items_classified": sum("ai_visual_candidates" in x for x in enriched),
        "vhs_packaging_candidates": sum(
            bool((x.get("ai_visual_classification") or {}).get("is_vhs_packaging_candidate"))
            for x in enriched),
        "auto_approved": 0, "scores_are_calibrated_probabilities": False,
        "note": "Experimental CLIP similarity suggestions only. All results require human review; model scores are relative within this label set and must not be interpreted as calibrated confidence."
    }
    manifest["items"] = enriched
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest["ai_review"], indent=2))


if __name__ == "__main__":
    main()
