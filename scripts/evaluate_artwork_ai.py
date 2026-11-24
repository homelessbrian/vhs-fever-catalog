#!/usr/bin/env python3
"""Evaluate artwork suggestions against a manually reviewed JSONL label set."""
from __future__ import annotations
import argparse, json
from pathlib import Path
from typing import Any

def prediction(item: dict[str, Any]) -> str:
    c = item.get("ai_visual_classification") or {}
    if c.get("stage_a"):
        label = (c.get("stage_a") or {}).get("label")
        return "positive" if label == "vhs_packaging" else ("uncertain" if label == "uncertain" else "negative")
    if c.get("is_vhs_packaging_candidate") is True: return "positive"
    role = c.get("suggested_role")
    if role == "unknown": return "uncertain"
    return "negative" if role else "uncertain"

def evaluate(manifest: dict[str, Any], labels: list[dict[str, Any]]) -> dict[str, Any]:
    by_key = {(x.get("archive_identifier"), x.get("source_file_name") or x.get("file_name")): x for x in manifest.get("items", [])}
    excluded = set()
    for group in (manifest.get("frame_filter") or {}).get("excluded_items", []):
        for f in group.get("files", []): excluded.add((group.get("archive_identifier"), f.get("file_name")))
    counts = {"tp":0,"fp":0,"tn":0,"fn":0,"abstain_positive":0,"abstain_negative":0,"skipped_low_resolution":0,"missing_prediction":0}
    rows=[]; component_correct=0; component_total=0
    for label in labels:
        key=(label.get("archive_identifier"),label.get("source_file_name")); item=by_key.get(key); truth=label["ground_truth"]
        if item is None and key in excluded: item={"frame_filtered":True}; pred="negative"
        elif item is None:
            counts["missing_prediction"]+=1; rows.append({"key":key,"truth":truth,"prediction":"missing"}); continue
        else:
            classification=item.get("ai_visual_classification") or {}
            if classification.get("final_decision") == "skipped_low_resolution":
                counts["skipped_low_resolution"]+=1
                rows.append({"archive_identifier":key[0],"source_file_name":key[1],"truth":truth,"prediction":"skipped_low_resolution","correct":None,"skip_reason":"below_minimum_artwork_resolution","image_dimensions":classification.get("image_dimensions"),"excluded_by_frame_filter":False})
                continue
            pred=prediction(item)
        if pred=="uncertain": counts["abstain_positive" if truth=="positive" else "abstain_negative"]+=1
        elif truth=="positive" and pred=="positive": counts["tp"]+=1
        elif truth=="negative" and pred=="positive": counts["fp"]+=1
        elif truth=="negative" and pred=="negative": counts["tn"]+=1
        elif truth=="positive" and pred=="negative": counts["fn"]+=1
        c=item.get("ai_visual_classification") or {}; component=((c.get("stage_b") or {}).get("label")); match=None
        if label.get("component") and component and component!="uncertain":
            component_total+=1; component_correct+=int(component==label["component"]); match=component==label["component"]
        rows.append({"archive_identifier":key[0],"source_file_name":key[1],"truth":truth,"prediction":pred,"correct":pred==truth if pred!="uncertain" else None,"ground_truth_role":label.get("component"),"predicted_component":component,"component_correct":match,"excluded_by_frame_filter":bool(item.get("frame_filtered"))})
    decided=counts["tp"]+counts["fp"]+counts["tn"]+counts["fn"]
    precision=counts["tp"]/(counts["tp"]+counts["fp"]) if counts["tp"]+counts["fp"] else None
    recall=counts["tp"]/(counts["tp"]+counts["fn"]) if counts["tp"]+counts["fn"] else None
    eligible=len(labels)-counts["missing_prediction"]-counts["skipped_low_resolution"]
    return {"sample_size":len(labels),"matched_predictions":len(labels)-counts["missing_prediction"],"eligible_after_resolution_filter":eligible,"decided_count":decided,"counts":counts,"precision":precision,"recall":recall,"false_positive_rate":counts["fp"]/(counts["fp"]+counts["tn"]) if counts["fp"]+counts["tn"] else None,"coverage":decided/len(labels) if labels else 0,"coverage_after_resolution_filter":decided/eligible if eligible else 0,"component_accuracy":component_correct/component_total if component_total else None,"component_evaluated_count":component_total,"rows":rows,"warning":"Small manually reviewed pilot sample only. Low-resolution skips are excluded from classifier abstentions and metrics; uncertain predictions are abstentions. Do not scale or auto-approve from these metrics."}

def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument("--manifest",type=Path,required=True); parser.add_argument("--labels",type=Path,required=True); parser.add_argument("--output",type=Path); args=parser.parse_args()
    manifest=json.loads(args.manifest.read_text(encoding="utf-8")); labels=[json.loads(x) for x in args.labels.read_text(encoding="utf-8").splitlines() if x.strip()]
    rendered=json.dumps(evaluate(manifest,labels),ensure_ascii=False,indent=2)+"\n"
    if args.output: args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(rendered,encoding="utf-8")
    print(rendered,end="")

if __name__=="__main__": main()
