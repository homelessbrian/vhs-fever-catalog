#!/usr/bin/env python3
"""Conservative metadata classifier for VHS FEVER. Never downloads media."""
from __future__ import annotations
import argparse, json, re, sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

PATTERNS = [
 ("artwork_or_packaging", r"\b(cover|sleeve|box art|poster|jacket|disc label|vhs label|case art|artwork|packaging)\b"),
 ("promo_or_commercial", r"\b(promo|promotional|commercial|advertisement|ad break|station ident|continuity|bumper|tv break|ident)\b"),
 ("vhs_or_dvd_extra", r"\b(dvd opening|dvd closing|opening and closing|opening to|closing to|menu|bonus feature|behind the scenes|making of|easter egg)\b"),
 ("trailer_or_clip", r"\b(trailer|teaser|preview|clip|excerpt|snippet|scene from)\b"),
 ("cartoon_or_animation", r"\b(cartoon|animated|animation|anime|toon|looney tunes|merrie melodies)\b"),
 ("tv_episode_or_recording", r"\b(episode|tv recording|television recording|broadcast|aircheck|recorded off|taped off|sitcom|news broadcast|newscast|telecast)\b"),
]
def txt(v):
    return " ".join(map(str,v)) if isinstance(v,list) else str(v or "")
def classify(item):
    mt=item.get("mediatype")
    if mt=="audio": return "audio_only","high","mediatype_audio","audio","standalone"
    if mt=="image": return "artwork_or_packaging","high","mediatype_image","artwork","standalone"
    if mt!="movies": return "other_or_review","high",f"mediatype_{mt or 'missing'}","unknown","unknown"
    title=txt(item.get("title")); lowtitle=title.lower(); desc=txt(item.get("description"))
    # Strong title-level VHS/compilation signals outrank incidental description keywords.
    fanmade=bool(re.search(r"\b(fan[ -]?made|custom)\b",lowtitle) and re.search(r"\b(vhs|tape|special|compilation)\b",lowtitle))
    holiday=bool(re.search(r"\b(halloween|christmas|holiday|easter|thanksgiving)\b",lowtitle) and re.search(r"\b(special|vhs|tape|compilation)\b",lowtitle))
    if fanmade:
        content="holiday_special" if holiday else ("movie_or_film" if re.search(r"\b(movie|film)\b",lowtitle) else "special_or_compilation")
        return "fan_made_vhs_compilation","high","title_fanmade_vhs",content,"fan_made_vhs"
    if holiday and not re.search(r"\b(trailer|teaser|promo|commercial)\b",lowtitle):
        return "holiday_special","medium","title_holiday_special","holiday_special","standalone_or_compilation"
    # Strong title evidence only. Description keywords are supporting evidence and cannot
    # turn a complete item into a promo/trailer by themselves.
    for label,pat in PATTERNS:
        m=re.search(pat,title,re.I)
        if m:
            content={"promo_or_commercial":"commercial_or_promo","trailer_or_clip":"trailer_or_clip","vhs_or_dvd_extra":"dvd_or_vhs_extra","cartoon_or_animation":"cartoon_or_animation","tv_episode_or_recording":"tv_episode_or_recording","artwork_or_packaging":"artwork"}[label]
            fmt="dvd_extra" if label=="vhs_or_dvd_extra" else "standalone"
            return label,"high","title_keyword:"+m.group(0).lower(),content,fmt
    # Description is deliberately low-weight: only use it when title itself is inconclusive,
    # and never allow generic promo/trailer words in the description to override title format.
    if re.search(r"\b(episode|full episode|broadcast|aircheck)\b",desc,re.I):
        return "tv_episode_or_recording","low","description_episode_hint","tv_episode_or_recording","standalone_or_compilation"
    if re.search(r"\b(cartoon|animated|anime)\b",title,re.I):
        return "cartoon_or_animation","high","title_animation_hint","cartoon_or_animation","standalone"
    if re.search(r"\b(movie|film|feature film)\b",title,re.I):
        return "movie_or_film","medium","title_movie_term","movie_or_film","standalone"
    return "other_or_review","low","insufficient_title_evidence","unknown","unknown"

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--input",type=Path,default=Path("data/vhsvault-discovery.jsonl"))
    p.add_argument("--output",type=Path,default=Path("data/vhsvault-classified.jsonl"))
    a=p.parse_args()
    if a.input.resolve()==a.output.resolve(): p.error("Input and output must differ")
    counts=Counter(); conf=Counter(); total=0
    a.output.parent.mkdir(parents=True,exist_ok=True); tmp=a.output.with_name(a.output.name+".tmp")
    try:
        with a.input.open(encoding="utf-8") as src,tmp.open("w",encoding="utf-8") as dst:
            for n,line in enumerate(src,1):
                item=json.loads(line)
                if not isinstance(item,dict): raise ValueError(f"Expected object on line {n}")
                label,level,reason,content,fmt=classify(item)
                out=dict(item); out["vhs_fever_classification"]={"label":label,"confidence":level,"reason":reason,"needs_review":level=="low","content_type":content,"collection_format":fmt}
                dst.write(json.dumps(out,ensure_ascii=False,sort_keys=True)+"\n")
                counts[label]+=1; conf[level]+=1; total+=1
        tmp.replace(a.output)
    except Exception:
        tmp.unlink(missing_ok=True); raise
    summary={"generated_at":datetime.now(timezone.utc).isoformat(),"records_classified":total,"classification_counts":dict(sorted(counts.items())),"confidence_counts":dict(sorted(conf.items())),"low_confidence_records_need_review":conf["low"],"source_preserved":True,"media_inspected":False,"rights_determined":False}
    a.output.with_suffix(".summary.json").write_text(json.dumps(summary,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(summary,indent=2))
if __name__=="__main__":
    try: main()
    except (OSError,ValueError,json.JSONDecodeError) as e: print(f"ERROR: {e}",file=sys.stderr); sys.exit(1)
