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


STAGE_A_LABELS = [
    ("vhs_packaging", "a physical VHS videocassette package or cassette label, including a cardboard sleeve, plastic clamshell case, or tape label"),
    ("other_physical_media_packaging", "physical DVD, Blu-ray, audio cassette, or other non-VHS media packaging"),
    ("poster_or_promotional_art", "a standalone theatrical movie poster, promotional one-sheet, advertisement, or publicity graphic, not a physical package"),
    ("video_frame_or_screenshot", "a frame grab, still, or screenshot taken from a movie or television video"),
    ("photograph_or_other", "an ordinary photograph, unrelated scan, document, or other non-packaging image"),
    ("uncertain", "an ambiguous, blurry, cropped, or unidentifiable image"),
]
STAGE_B_LABELS = [
    ("front", "the front panel of a VHS sleeve or case, with cover artwork and title"),
    ("back", "the back panel of a VHS sleeve or case, often with synopsis, credits, and small stills"),
    ("spine", "the narrow spine edge of a VHS sleeve or case, usually with a vertical title"),
    ("full_wrap", "a complete unfolded VHS sleeve or wrap showing front, spine, and back together"),
    ("cassette_label", "the printed label on the VHS cassette tape itself"),
    ("insert_or_other", "another physical VHS package component, insert, flap, or unclear package panel"),
]
PACKAGING_THRESHOLD = 0.30
PACKAGING_MARGIN = 0.06
COMPONENT_THRESHOLD = 0.28
COMPONENT_MARGIN = 0.045
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


def _rank_scores(scores: list[float], labels: list[tuple[str, str]]) -> list[dict[str, Any]]:
    return sorted([{"label": labels[i][0], "score": float(scores[i])} for i in range(len(labels))], key=lambda x: x["score"], reverse=True)

def _decision(ranked: list[dict[str, Any]], threshold: float, margin: float) -> dict[str, Any]:
    if not ranked: return {"label": "uncertain", "status": "needs_review", "reason": "no_model_scores"}
    ordered = sorted(ranked, key=lambda x: float(x.get("score", 0)), reverse=True)
    top = ordered[0]; score = float(top.get("score", 0)); gap = score - (float(ordered[1].get("score", 0)) if len(ordered) > 1 else 0.0)
    if score < threshold or gap < margin:
        return {"label": "uncertain", "status": "needs_review", "reason": "low_similarity_or_ambiguous", "top_candidate": top["label"], "top_score": round(score, 4), "score_margin": round(gap, 4)}
    return {"label": top["label"], "status": "needs_review", "reason": "contextual_zero_shot_similarity", "top_score": round(score, 4), "score_margin": round(gap, 4)}

def _contextual_prompt(description: str, title: str, filename: str) -> str:
    clues = []
    if title.strip(): clues.append(f'Archive item title: "{title.strip()[:180]}"')
    if filename.strip(): clues.append(f'original image filename: "{filename.strip()[:180]}"')
    prefix = ("Context clues (not proof): " + "; ".join(clues) + ". ") if clues else ""
    return prefix + description

def _score_stage(image, processor, model, torch, labels, title="", filename=""):
    prompts = [_contextual_prompt(description, title, filename) for _, description in labels]
    inputs = processor(text=prompts, images=image, return_tensors="pt", padding=True)
    with torch.inference_mode():
        logits = model(**inputs).logits_per_image[0]; scores = logits.softmax(dim=0).tolist()
    return _rank_scores(scores, labels)

def decide_two_stage(stage_a: list[dict[str, Any]], stage_b: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    first = _decision(stage_a, PACKAGING_THRESHOLD, PACKAGING_MARGIN)
    result = {"stage_a": first, "stage_a_candidates": stage_a[:6], "review_status": "needs_review", "auto_approved": False}
    if first["label"] != "vhs_packaging":
        result.update({"stage_b": None, "suggested_role": None, "is_vhs_packaging_candidate": False, "final_decision": "uncertain" if first["label"] == "uncertain" else "not_vhs_packaging"})
        return result
    second = _decision(stage_b or [], COMPONENT_THRESHOLD, COMPONENT_MARGIN)
    result.update({"stage_b": second, "stage_b_candidates": (stage_b or [])[:6], "suggested_role": second["label"], "is_vhs_packaging_candidate": True, "final_decision": "vhs_packaging_component_uncertain" if second["label"] == "uncertain" else "vhs_packaging_candidate"})
    return result

def classify_image(payload: bytes, classifier, title: str = "", filename: str = "") -> dict[str, Any]:
    torch, Image, processor, model = classifier
    from io import BytesIO
    image = Image.open(BytesIO(payload)).convert("RGB")
    # Preserve image-only and contextual scores separately for later comparison.
    a_image = _score_stage(image, processor, model, torch, STAGE_A_LABELS)
    a_context = _score_stage(image, processor, model, torch, STAGE_A_LABELS, title, filename)
    first = _decision(a_context, PACKAGING_THRESHOLD, PACKAGING_MARGIN)
    if first["label"] != "vhs_packaging":
        result = decide_two_stage(a_context, None); result["stage_a_image_only_candidates"] = a_image
        return result
    b_image = _score_stage(image, processor, model, torch, STAGE_B_LABELS)
    b_context = _score_stage(image, processor, model, torch, STAGE_B_LABELS, title, filename)
    result = decide_two_stage(a_context, b_context)
    result["stage_a_image_only_candidates"] = a_image; result["stage_b_image_only_candidates"] = b_image
    return result

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--manifest", type=Path, required=True); parser.add_argument("--output", type=Path, required=True); parser.add_argument("--model", default=MODEL_ID); parser.add_argument("--delay", type=float, default=0.1); parser.add_argument("--limit", type=int, default=40)
    args = parser.parse_args(); manifest = json.loads(args.manifest.read_text(encoding="utf-8")); entries = manifest.get("items") or []; classifier = load_classifier(args.model); enriched = []
    for entry in entries[:max(0, args.limit)]:
        item = dict(entry)
        try:
            payload = fetch_preview(str(item.get("asset_url") or ""))
            result = classify_image(payload, classifier, str(item.get("item_title") or ""), str(item.get("source_file_name") or item.get("file_name") or ""))
            item["ai_visual_classification"] = result
            item["ai_context"] = {"item_title": item.get("item_title"), "original_filename": item.get("source_file_name") or item.get("file_name"), "context_used": True}
            item["ai_model"] = args.model; item["ai_method"] = "local_two_stage_contextual_clip"
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            item["ai_visual_classification"] = {"stage_a": {"label": "uncertain", "status": "needs_review", "reason": "preview_or_inference_error"}, "stage_b": None, "suggested_role": None, "is_vhs_packaging_candidate": False, "final_decision": "uncertain", "review_status": "needs_review", "auto_approved": False, "error": str(exc)[:200]}
        enriched.append(item); time.sleep(max(0, args.delay))
    manifest["schema_version"] = max(3, int(manifest.get("schema_version", 1))); decisions = [x.get("ai_visual_classification", {}) for x in enriched]
    manifest["ai_review"] = {"enabled": True, "model": args.model, "method": "local_two_stage_contextual_clip", "items_classified": sum(bool(x.get("ai_context", {}).get("context_used")) for x in enriched), "stage_a_vhs_packaging_candidates": sum(x.get("stage_a", {}).get("label") == "vhs_packaging" for x in decisions), "stage_a_video_frame_predictions": sum(x.get("stage_a", {}).get("label") == "video_frame_or_screenshot" for x in decisions), "auto_approved": 0, "scores_are_calibrated_probabilities": False, "context_fields": ["item_title", "original_filename"], "note": "Two-stage contextual CLIP suggestions only. Scores are relative within each stage and are not calibrated probabilities. All predictions require manual review."}
    manifest["items"] = enriched; args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\\n", encoding="utf-8"); print(json.dumps(manifest["ai_review"], indent=2))



if __name__ == "__main__":
    main()
