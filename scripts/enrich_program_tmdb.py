#!/usr/bin/env python3
"""Match each individual Archive video filename to TMDB, never the parent item title."""
from __future__ import annotations
import argparse, json, os, re, time
from pathlib import Path
from typing import Any
try:\n    from .enrich_metadata_artwork import find_tmdb_match\nexcept ImportError:\n    from enrich_metadata_artwork import find_tmdb_match

YEAR_PREFIX = re.compile(r"^\s*[([]?((?:18|19|20)\d{2})[)\]. _-]+")
SUFFIX = re.compile(r"\s*(?:\[[^]]*(?:vhs|dvd|variant|capture|part\s*\d+)[^]]*\]|\([^()]*(?:vhs|dvd|variant|capture|part\s*\d+)[^()]*\))\s*$", re.I)

def filename_title(program: dict[str, Any]) -> tuple[str | None, str | None]:
    segments = program.get("segments") or []
    raw = Path(str((segments[0] if segments else {}).get("file_name") or program.get("title") or "")).stem
    raw = raw.replace("_", " ").strip()
    year = None
    m = YEAR_PREFIX.match(raw)
    if m:
        year = m.group(1)
        raw = raw[m.end():]
    raw = SUFFIX.sub("", raw).strip(" ._-()[]")
    raw = re.sub(r"\s+", " ", raw)
    if len(re.sub(r"[^a-z0-9]", "", raw.casefold())) < 3:
        return None, year
    return raw, year

def enrich_programs(rows: list[dict[str, Any]], token: str | None, delay: float = 0.2) -> tuple[list[dict[str, Any]], dict[str, int]]:
    cache: dict[tuple[str, str | None], dict[str, Any]] = {}
    counts: dict[str, int] = {}
    output = []
    for original in rows:
        row = dict(original)
        if row.get("entry_type") != "standalone":
            output.append(row)
            continue
        title, year = filename_title(row)
        if not title:
            suggestion = {"status":"unusable_source_filename","review_status":"needs_review","source_filename":(row.get("segments") or [{}])[0].get("file_name")}
        elif not token:
            suggestion = {"status":"skipped_no_api_token","search_title":title,"search_year":year,"review_status":"needs_review"}
        else:
            key=(title.casefold(),year)
            if key not in cache:
                try:
                    cache[key]=find_tmdb_match({"title":title,"year":year},token)
                except Exception as exc:
                    cache[key]={"status":"lookup_error","error":str(exc)[:240],"review_status":"needs_review","search_title":title,"search_year":year}
                time.sleep(max(0,delay))
            suggestion=dict(cache[key])
            suggestion["source"]="individual_video_filename"
            suggestion["search_title"]=title
            suggestion["search_year"]=year
            suggestion["review_status"]="needs_review"
        if suggestion.get("tmdb_id"):
            suggestion.update({"status":"candidate","provider":"tmdb","provider_id":str(suggestion["tmdb_id"]),"confidence":suggestion.get("match_confidence","medium"),"poster":suggestion.get("tmdb_poster"),"backdrop":suggestion.get("tmdb_backdrop")})
        row["title_match_suggestion"]=suggestion
        row["matched_title_id"]=None
        row["review_required"]=True
        row.setdefault("review_notes",[])
        row["review_notes"]=list(row["review_notes"])+["TMDB title suggestion is based on this individual video filename; human confirmation required."]
        status="matched" if suggestion.get("tmdb_id") else suggestion.get("status","unknown")
        counts[status]=counts.get(status,0)+1
        output.append(row)
    return output,counts

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input",type=Path,required=True);p.add_argument("--output",type=Path,required=True);p.add_argument("--delay",type=float,default=0.2)
    a=p.parse_args(); rows=[json.loads(x) for x in a.input.read_text(encoding="utf-8").splitlines() if x.strip()]
    result,counts=enrich_programs(rows,os.environ.get("TMDB_API_READ_ACCESS_TOKEN"),a.delay)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text("".join(json.dumps(x,ensure_ascii=False,sort_keys=True)+"\n" for x in result),encoding="utf-8")
    summary={"program_entries":len(result),"tmdb_lookup_counts":counts,"matching_level":"individual_video_filename","human_approval_required":True}
    a.output.with_suffix(".summary.json").write_text(json.dumps(summary,indent=2)+"\n",encoding="utf-8");print(json.dumps(summary,indent=2))
if __name__=="__main__": main()
