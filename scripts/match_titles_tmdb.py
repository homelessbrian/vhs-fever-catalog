#!/usr/bin/env python3
"""Attach TMDB (and TheTVDB) candidates to catalog-model title suggestions. Never confirms.

One search per distinct title (not per file). The year in "(1992 VHS)" is the
tape's year, so it is used only as a ceiling: a film released after the tape
came out cannot be what is on the tape. Film years written in the filename
("(1954) Godzilla") are used as a direct hint. Up to five candidates are kept
for a person to pick from.

TV episodes ("S02E05", "2x05", "Season 2 Episode 5", "Episode 5", an air date,
or "Show Name - Episode Name") are looked up as episodes: the show is found,
its episode list is pulled, and the file is lined up against it by number,
title and air date (see tv_episodes.py). TheTVDB is the backup when TMDB can't
find the show or the episode, if the TVDB_API_KEY secret is set.
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
from catalog_model import episode_title_pieces, title_key  # noqa: E402
import tv_episodes  # noqa: E402

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
    ap.add_argument("--max-lookups", type=int, default=4000)
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

    tmdb_tv = tv_episodes.Tmdb(token, delay=args.delay) if token else None
    tvdb_key = os.environ.get("TVDB_API_KEY")
    tvdb_state: dict[str, Any] = {}

    def tv_sources() -> list[Any]:
        out = [tmdb_tv] if tmdb_tv else []
        if tvdb_key and "client" not in tvdb_state:
            try:
                tvdb_state["client"] = tv_episodes.Tvdb(tvdb_key, os.environ.get("TVDB_PIN") or None)
            except Exception as exc:  # noqa: BLE001
                tvdb_state["client"] = None
                tvdb_state["error"] = str(exc)[:200]
        if tvdb_state.get("client"):
            out.append(tvdb_state["client"])
        return out

    def movie_search(query: str, tid: str, hint_year: int | None, kind: str = "movie") -> list[dict[str, Any]]:
        nonlocal lookups
        key = (kind, title_key(query), hint_year, first_release.get(tid))
        if key not in cache:
            try:
                res = _get(f"/search/{kind}", {"query": query, "include_adult": "false"}, token)
                cache[key] = score_candidates(query, res.get("results") or [], kind=kind,
                                              latest_allowed_year=first_release.get(tid),
                                              hint_year=hint_year)
            except Exception as exc:  # noqa: BLE001
                cache[key] = [{"error": str(exc)[:200]}]
            lookups += 1
            time.sleep(args.delay)
        return cache[key]

    # Files with no show name ("Season 2 Episode 15 - Strange Vet Fellows"):
    # another file with the same episode title often names the show.
    show_by_ep_title: dict[str, set[str]] = {}
    for t in titles:
        se = t.get("series") or {}
        if se.get("series_title") and se.get("episode_title"):
            for piece in episode_title_pieces(se["episode_title"])[:1]:
                show_by_ep_title.setdefault(tv_episodes.norm(piece), set()).add(se["series_title"])
    for t in titles:
        se = t.get("series") or {}
        if se and not se.get("series_title") and se.get("episode_title"):
            shows = show_by_ep_title.get(tv_episodes.norm(se["episode_title"]), set())
            if len({title_key(x) for x in shows}) == 1:
                se["series_title"] = sorted(shows)[0]
                se["series_title_from"] = "another file with the same episode title"

    cache: dict[tuple, list[dict[str, Any]]] = {}
    ep_cache: dict[tuple, dict[str, Any]] = {}
    lookups = 0
    counts: dict[str, int] = {}
    for t in titles:
        tid = t["title_suggestion_id"]
        c = cats.get(tid, set())
        series = t.get("series")
        dash = t.get("show_dash_hint")
        if c == {"vhs_opening_or_previews"}:
            t["tmdb"] = {"status": "skipped_tape_opening"}
        elif not token:
            t["tmdb"] = {"status": "skipped_no_api_token"}
        elif lookups >= args.max_lookups:
            t["tmdb"] = {"status": "deferred_lookup_limit", "search_title": t["tmdb"].get("search_title")}
        elif series:
            key = ("ep", title_key(series.get("series_title") or ""), series.get("season"), series.get("episode"),
                   title_key(series.get("episode_title") or ""), series.get("air_date"), first_release.get(tid))
            if key not in ep_cache:
                ep_cache[key] = tv_episodes.find_episode(
                    tv_sources(), show=series.get("series_title"), season=series.get("season"),
                    episode=series.get("episode"), episode_title=series.get("episode_title"),
                    air_date=series.get("air_date"), ceiling=first_release.get(tid),
                    title_pieces=episode_title_pieces)
                lookups += 1
            r = ep_cache[key]
            t["tmdb"] = {
                "status": r["status"], "search_title": series.get("series_title") or series.get("episode_title"),
                "search_kind": "tv_episode", "source": r["source"], "sources_tried": r["tried"],
                "latest_allowed_year": first_release.get(tid), "candidates": r["candidates"],
                "confirmed_tmdb_id": None, "note": "Suggestions only. A person must confirm the match.",
            }
        else:
            hint = sorted(hints.get(tid, []))
            hint_year = int(hint[0]) if len(hint) == 1 else None
            query = t["tmdb"]["search_title"]
            ep_result = None
            if dash:
                # "Show Name - Episode Name": an episode only if TMDB/TVDB has a
                # show by that name AND an episode by that name. Otherwise it
                # is treated as a movie title like any other.
                key = ("dash", title_key(dash["show"]), title_key(dash["episode_title"]), first_release.get(tid), hint_year)
                if key not in ep_cache:
                    ep_cache[key] = tv_episodes.find_episode(
                        tv_sources(), show=dash["show"], season=None, episode=None,
                        episode_title=dash["episode_title"], air_date=None, ceiling=first_release.get(tid),
                        title_pieces=episode_title_pieces, require_title_match=True, hint_year=hint_year)
                    lookups += 1
                r = ep_cache[key]
                if r["status"] in {"one_likely_episode", "several_episode_candidates"}:
                    ep_result = r
            cands = movie_search(query, tid, hint_year)
            if cands and "error" in cands[0] and not ep_result:
                t["tmdb"] = {"status": "lookup_error", "error": cands[0]["error"], "search_title": query}
            else:
                movies = [x for x in cands if "error" not in x]
                if ep_result:
                    t["tmdb"] = {
                        "status": ep_result["status"], "search_title": dash["show"], "search_kind": "tv_episode",
                        "source": ep_result["source"], "sources_tried": ep_result["tried"],
                        "latest_allowed_year": first_release.get(tid), "filename_film_year": hint_year,
                        "candidates": (ep_result["candidates"] + movies)[:6], "confirmed_tmdb_id": None,
                        "note": "Filename looks like 'Show - Episode'. Suggestions only.",
                    }
                else:
                    t["tmdb"] = {
                        "status": verdict(movies), "search_title": query, "search_kind": "movie",
                        "latest_allowed_year": first_release.get(tid), "filename_film_year": hint_year,
                        "candidates": movies, "confirmed_tmdb_id": None,
                        "note": "Suggestions only. A person must confirm the match.",
                    }
        counts[t["tmdb"]["status"]] = counts.get(t["tmdb"]["status"], 0) + 1

    with (d / "titles.jsonl").open("w", encoding="utf-8") as fh:
        for t in titles:
            fh.write(json.dumps(t, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {"tmdb_lookups": lookups, "tmdb_tv_calls": tmdb_tv.calls if tmdb_tv else 0,
               "tvdb": ("on, " + str(tvdb_state["client"].calls) + " calls") if tvdb_state.get("client")
               else ("error: " + tvdb_state["error"]) if tvdb_state.get("error")
               else "off (no TVDB_API_KEY)" if not tvdb_key else "on, not needed",
               "title_status_counts": dict(sorted(counts.items()))}
    (d / "tmdb.summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
