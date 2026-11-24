#!/usr/bin/env python3
"""Attach TMDB candidates to catalog-model title suggestions. Never confirms.

One search per distinct title (not per file). The year in "(1992 VHS)" is the
tape's year, so it is used only as a ceiling: a film released after the tape
came out cannot be what is on the tape. Film years written in the filename
("(1954) Godzilla") are used as a direct hint. TV episodes search TMDB TV
by series name. Up to five candidates are kept for a person to pick from.
"""
from __future__ import annotations

import argparse
import difflib
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from catalog_model import title_key  # noqa: E402

API = "https://api.themoviedb.org/3"
IMG = "https://image.tmdb.org/t/p/"


def _get(path: str, params: dict[str, Any], token: str) -> dict[str, Any]:
    url = API + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token,
                                               "Accept": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as exc:
            if exc.code == 429 or exc.code >= 500:
                time.sleep(2 * (attempt + 1))
                continue
            raise
        except urllib.error.URLError:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("TMDB request failed: " + path)


def similarity(a: str, b: str) -> float:
    return round(difflib.SequenceMatcher(None, title_key(a), title_key(b)).ratio(), 3)


def score_candidates(query: str, results: list[dict[str, Any]], *, kind: str,
                     latest_allowed_year: int | None, hint_year: int | None) -> list[dict[str, Any]]:
    out = []
    for r in results[:20]:
        name = r.get("title") or r.get("name") or ""
        orig = r.get("original_title") or r.get("original_name") or ""
        date = r.get("release_date") or r.get("first_air_date") or ""
        year = int(date[:4]) if date[:4].isdigit() else None
        sim = max(similarity(query, name), similarity(query, orig) if orig else 0)
        year_ok = None
        if latest_allowed_year and year:
            year_ok = year <= latest_allowed_year
        if hint_year and year:
            year_ok = abs(year - hint_year) <= 1 and (year_ok is not False)
        out.append({
            "tmdb_id": r.get("id"), "media_type": kind, "title": name,
            "original_title": orig or None, "release_date": date or None,
            "similarity": sim, "year_consistent": year_ok,
            "popularity": r.get("popularity"),
            "poster": (IMG + "w342" + r["poster_path"]) if r.get("poster_path") else None,
            "backdrop": (IMG + "w780" + r["backdrop_path"]) if r.get("backdrop_path") else None,
            "overview": (r.get("overview") or "")[:400] or None,
            "tmdb_url": f"https://www.themoviedb.org/{kind}/{r.get('id')}",
        })
    out.sort(key=lambda c: (c["year_consistent"] is not False, c["similarity"], c["popularity"] or 0),
             reverse=True)
    return out[:5]


def verdict(cands: list[dict[str, Any]]) -> str:
    good = [c for c in cands if c["similarity"] >= 0.9 and c["year_consistent"] is not False]
    if not cands:
        return "no_candidates"
    if len(good) == 1:
        return "one_likely_candidate"
    if len(good) > 1:
        return "several_candidates"
    return "weak_candidates_only"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--max-lookups", type=int, default=2500)
    ap.add_argument("--delay", type=float, default=0.05)
    args = ap.parse_args()
    token = os.environ.get("TMDB_API_READ_ACCESS_TOKEN")
    d = Path(args.model_dir)
    read = lambda n: [json.loads(x) for x in (d / f"{n}.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]
    titles, editions, recs = read("titles"), read("editions"), read("recordings")

    cats: dict[str, set[str]] = {}
    hints: dict[str, set[str]] = {}
    for r in recs:
        tid = r.get("title_suggestion_id")
        if tid:
            cats.setdefault(tid, set()).add(r["catalog_category"])
            if r["interpretation"].get("work_year"):
                hints.setdefault(tid, set()).add(r["interpretation"]["work_year"])
    first_release: dict[str, int] = {}
    for e in editions:
        y = int(e["release_year"])
        first_release[e["title_suggestion_id"]] = min(y, first_release.get(e["title_suggestion_id"], y))

    cache: dict[tuple, list[dict[str, Any]]] = {}
    lookups = 0
    counts: dict[str, int] = {}
    for t in titles:
        tid = t["title_suggestion_id"]
        c = cats.get(tid, set())
        if c == {"vhs_opening_or_previews"}:
            t["tmdb"] = {"status": "skipped_tape_opening"}
        elif not token:
            t["tmdb"] = {"status": "skipped_no_api_token"}
        else:
            series = t.get("series")
            kind = "tv" if series else "movie"
            query = series["series_title"] if series else t["tmdb"]["search_title"]
            hint = sorted(hints.get(tid, []))
            hint_year = int(hint[0]) if len(hint) == 1 else None
            key = (kind, title_key(query), hint_year, first_release.get(tid))
            if key not in cache:
                if lookups >= args.max_lookups:
                    t["tmdb"] = {"status": "deferred_lookup_limit", "search_title": query}
                    counts["deferred_lookup_limit"] = counts.get("deferred_lookup_limit", 0) + 1
                    continue
                try:
                    res = _get(f"/search/{kind}", {"query": query, "include_adult": "false"}, token)
                    cache[key] = score_candidates(query, res.get("results") or [], kind=kind,
                                                  latest_allowed_year=first_release.get(tid),
                                                  hint_year=hint_year)
                except Exception as exc:  # noqa: BLE001
                    cache[key] = [{"error": str(exc)[:200]}]
                lookups += 1
                time.sleep(args.delay)
            cands = cache[key]
            if cands and "error" in cands[0]:
                t["tmdb"] = {"status": "lookup_error", "error": cands[0]["error"], "search_title": query}
            else:
                t["tmdb"] = {
                    "status": verdict(cands), "search_title": query, "search_kind": kind,
                    "latest_allowed_year": first_release.get(tid), "filename_film_year": hint_year,
                    "candidates": cands, "confirmed_tmdb_id": None,
                    "note": "Suggestions only. A person must confirm the match.",
                }
        counts[t["tmdb"]["status"]] = counts.get(t["tmdb"]["status"], 0) + 1

    with (d / "titles.jsonl").open("w", encoding="utf-8") as fh:
        for t in titles:
            fh.write(json.dumps(t, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {"tmdb_lookups": lookups, "title_status_counts": dict(sorted(counts.items()))}
    (d / "tmdb.summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
