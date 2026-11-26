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
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from catalog_model import episode_title_pieces, title_key  # noqa: E402
import auto_sort  # noqa: E402
import title_search  # noqa: E402
import tv_episodes  # noqa: E402

API = "https://api.themoviedb.org/3"
# Bumped whenever the search gets smarter. Titles that found nothing (or only
# weak guesses) with an older version are searched again instead of being
# copied forward; confident matches are kept.
MATCHER_VERSION = 2
KEEP_STATUSES = {"one_likely_candidate", "one_likely_episode", "skipped_tape_opening",
                 "skipped_commercial_or_promo", "skipped_not_english"}
# File names that are a piece of a tape, not the film itself: the Archive
# page title is never used for these.
TAPE_PART = re.compile(r"\b(?:opening|closing|intro|outro|trailers?|teasers?|promos?|previews?|sneak peek|credits|"
                       r"bumpers?|logos?|commercials?|ads?|segments?|poster|menu|warning|fbi)\b", re.I)
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
    ap.add_argument("--max-lookups", type=int, default=15000)
    ap.add_argument("--delay", type=float, default=0.05)
    ap.add_argument("--retry-lookups", type=int, default=9000,
                    help="stop re-searching old unmatched titles after this many lookups (the rest wait for the next run)")
    ap.add_argument("--reuse", nargs="*", default=[],
                    help="earlier titles.jsonl files: titles already looked up there are copied, not searched again")
    args = ap.parse_args()
    token = os.environ.get("TMDB_API_READ_ACCESS_TOKEN")
    d = Path(args.model_dir)
    read = lambda n: [json.loads(x) for x in (d / f"{n}.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]
    titles, editions, recs = read("titles"), read("editions"), read("recordings")
    items = {i["archive_identifier"]: i for i in (read("archive-items") if (d / "archive-items.jsonl").exists() else [])}

    cats: dict[str, set[str]] = {}
    hints: dict[str, set[str]] = {}
    lengths: dict[str, list[float]] = {}
    for r in recs:
        tid = r.get("title_suggestion_id")
        if tid and r.get("duration_seconds"):
            lengths.setdefault(tid, []).append(float(r["duration_seconds"]))
        if tid:
            cats.setdefault(tid, set()).add(r["catalog_category"])
            if r["interpretation"].get("work_year"):
                hints.setdefault(tid, set()).add(r["interpretation"]["work_year"])
    first_release: dict[str, int] = {}
    for e in editions:
        y = int(e["release_year"])
        first_release[e["title_suggestion_id"]] = min(y, first_release.get(e["title_suggestion_id"], y))
    names: dict[str, list[str]] = {}
    for r in recs:
        tid = r.get("title_suggestion_id")
        if not tid:
            continue
        names.setdefault(tid, []).append(r.get("display_name") or "")
        # "Opening to Pinocchio 1993 VHS" has no edition, but its name still
        # says when the tape came out.
        ry = r["interpretation"].get("release_year")
        if ry and str(ry).isdigit() and len(str(ry)) == 4 and tid not in first_release:
            first_release[tid] = int(ry)

    # Which Archive pages each title's files come from.
    pages_of: dict[str, list[dict[str, Any]]] = {}
    for r in recs:
        tid = r.get("title_suggestion_id")
        for a in r.get("archive_identifiers") or []:
            if tid and a in items and items[a] not in pages_of.setdefault(tid, []):
                pages_of[tid].append(items[a])

    # Shows the collection knows about: from page titles ("show" in the page
    # context), "Show - Episode" names and season/episode names. Used to read
    # "Barney Our Earth, Our Home" as show "Barney" + "Our Earth, Our Home".
    show_count: dict[str, int] = {}
    show_name: dict[str, str] = {}
    def note_show(n: str | None) -> None:
        k = title_search.plain(n or "")
        if len(k) >= 4 and not k.isdigit():
            show_count[k] = show_count.get(k, 0) + 1
            show_name.setdefault(k, n.strip())
    for i in items.values():
        note_show((i.get("page_context") or {}).get("show"))
    for t in titles:
        note_show((t.get("series") or {}).get("series_title"))
        note_show((t.get("show_dash_hint") or {}).get("show"))

    def show_for(tid: str, title: str) -> str | None:
        own = {title_search.plain((p.get("page_context") or {}).get("show") or "") for p in pages_of.get(tid, [])} - {""}
        cands = sorted(own) if len(own) == 1 else []
        cands += sorted((k for k, n in show_count.items() if n >= 3), key=len, reverse=True)
        for k in cands:
            if title_search.without_show(title, show_name.get(k, k)):
                return show_name.get(k, k)
        return None

    def page_tries(tid: str, title: str) -> list[tuple[str, str]]:
        """The Archive page title, when the page is about this one video."""
        ps = pages_of.get(tid) or []
        if len(ps) != 1 or TAPE_PART.search(title):
            return []
        p = ps[0]
        pt = p.get("archive_title") or ""
        if (p.get("video_file_count") or 0) > 4 or not pt or TAPE_PART.search(pt):
            return []
        return [(pt, "Archive page title")]

    def not_a_movie(tid: str, title: str) -> str | None:
        """Commercials, bumpers and promos aren't on TMDB, so they aren't searched."""
        ns = names.get(tid) or [title]
        def tv_block(n: str) -> bool:
            n = auto_sort.norm_words(n)
            # Airings of real shows ("Dental Hyjinks (Nick Jr. Airing)") are still looked up.
            return bool((auto_sort.COMMERCIAL.search(n) and not auto_sort.WITH_ADS.search(n))
                        or (auto_sort.PROMO.search(n) and not auto_sort.SCREENER.search(n)))
        if all(tv_block(n) for n in ns):
            return "skipped_commercial_or_promo"
        if all(auto_sort.foreign_reason(n) for n in ns):
            return "skipped_not_english"
        return None

    reused: dict[str, dict[str, Any]] = {}
    for path in args.reuse:
        p_ = Path(path)
        if not p_.is_file():
            continue
        for line in p_.read_text(encoding="utf-8").splitlines():
            if line.strip():
                old = json.loads(line)
                tm = old.get("tmdb") or {}
                if tm.get("status") and tm["status"] not in {"lookup_error", "deferred_lookup_limit",
                                                             "skipped_no_api_token", "not_searched",
                                                             "no_usable_name"}:
                    reused[old["title_suggestion_id"]] = tm
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

    vocab = title_search.build_vocabulary(t["display_title"] for t in titles)
    raw_cache: dict[tuple, list[dict[str, Any]]] = {}
    detail_cache: dict[tuple, dict[str, Any]] = {}

    def typical_minutes(tid: str) -> float | None:
        vals = sorted(lengths.get(tid) or [])
        return round(vals[len(vals) // 2] / 60, 1) if vals else None

    def raw_find(kind: str) -> Any:
        def find(query: str, year: int | None) -> list[dict[str, Any]]:
            nonlocal lookups
            key = (kind, title_search.plain(query), year)
            if key not in raw_cache:
                params = {"query": query, "include_adult": "false"}
                if year:
                    params["year" if kind == "movie" else "first_air_date_year"] = year
                try:
                    res = _get(f"/search/{kind}", params, token).get("results") or []
                except Exception:  # noqa: BLE001
                    res = []
                lookups += 1
                time.sleep(args.delay)
                raw_cache[key] = [{
                    "tmdb_id": r.get("id"), "media_type": kind,
                    "title": r.get("title") or r.get("name") or "",
                    "original_title": r.get("original_title") or r.get("original_name") or None,
                    "release_date": r.get("release_date") or r.get("first_air_date") or None,
                    "popularity": r.get("popularity"),
                    "animated": 16 in (r.get("genre_ids") or []),
                    "poster": (IMG + "w342" + r["poster_path"]) if r.get("poster_path") else None,
                    "backdrop": (IMG + "w780" + r["backdrop_path"]) if r.get("backdrop_path") else None,
                    "overview": (r.get("overview") or "")[:400] or None,
                    "tmdb_url": f"https://www.themoviedb.org/{kind}/{r.get('id')}",
                } for r in res[:20]]
            return [dict(x) for x in raw_cache[key]]
        return find

    def details(c: dict[str, Any]) -> dict[str, Any]:
        nonlocal lookups
        key = (c["media_type"], c["tmdb_id"])
        if key not in detail_cache:
            out: dict[str, Any] = {}
            try:
                if c["media_type"] == "movie":
                    d = _get(f"/movie/{c['tmdb_id']}", {"append_to_response": "alternative_titles"}, token)
                    out["runtime"] = d.get("runtime") or None
                    alts = (d.get("alternative_titles") or {}).get("titles") or []
                else:
                    d = _get(f"/tv/{c['tmdb_id']}", {"append_to_response": "alternative_titles"}, token)
                    rts = d.get("episode_run_time") or []
                    out["runtime"] = rts[0] if rts else None
                    alts = (d.get("alternative_titles") or {}).get("results") or []
                out["alt_titles"] = [a.get("title") for a in alts
                                     if a.get("title") and (a.get("iso_3166_1") in (None, "US", "GB", "CA", "AU"))][:15]
                out["animated"] = any(g.get("id") == 16 for g in d.get("genres") or [])
            except Exception:  # noqa: BLE001
                pass
            lookups += 1
            time.sleep(args.delay)
            detail_cache[key] = out
        return detail_cache[key]

    def smart_search(query: str, tid: str, hint_year: int | None, extra: list[tuple[str, str]] | None = None,
                     show: str | None = None) -> dict[str, Any]:
        common = dict(vocab=vocab, ceiling=first_release.get(tid), hint_year=hint_year,
                      minutes=typical_minutes(tid), details=details, extra=extra, show=show)
        res = title_search.search(query, find=raw_find("movie"), **common)
        if res["confidence"] in {"none", "weak"}:
            # TV specials and video collections of shows live under TV on TMDB.
            tv = title_search.search(query, find=raw_find("tv"), max_tries=3, **common)
            order = ["none", "weak", "several", "likely", "sure"]
            if order.index(tv["confidence"]) > order.index(res["confidence"]):
                tv["tried"] = res["tried"] + tv["tried"]
                tv["kind"] = "tv"
                return tv
        res["kind"] = "movie"
        return res

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
    conf_counts: dict[str, int] = {}
    rescued: dict[str, int] = {}
    for t in titles:
        tid = t["title_suggestion_id"]
        c = cats.get(tid, set())
        series = t.get("series")
        dash = t.get("show_dash_hint")
        skip = None if series else not_a_movie(tid, t["display_title"])
        old = reused.get(tid)
        retry = bool(old) and old.get("status") not in KEEP_STATUSES and (old.get("matcher") or 1) < MATCHER_VERSION
        if old and dash and dash.get("from") == "Archive page show" and not old.get("page_show_tried"):
            # The Archive page names a show this title was never tried
            # against: look again, even over a confident movie match.
            retry = True
        if old and (not retry or lookups >= args.retry_lookups or not token):
            t["tmdb"] = old
            counts["reused_from_earlier_run"] = counts.get("reused_from_earlier_run", 0) + 1
        elif c == {"vhs_opening_or_previews"}:
            t["tmdb"] = {"status": "skipped_tape_opening"}
        elif c == {"tv_recording"}:
            # Whole tapes recorded off TV: the shows and air dates come from
            # the Archive page, not from TMDB.
            t["tmdb"] = {"status": "skipped_tv_recording"}
        elif skip:
            t["tmdb"] = {"status": skip, "search_title": t["tmdb"].get("search_title"),
                         "note": "Not looked up: commercials, promos and non-English titles are skipped."}
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
        elif title_search.looks_like_no_name(t["display_title"]) and not page_tries(tid, "x"):
            # "20240102 222243", "VHS1984 VID 20261016 200948": a camera or
            # capture name and an Archive page with many videos. Nothing to search.
            t["tmdb"] = {"status": "no_usable_name", "search_title": None,
                         "note": "The file name is a camera/capture name, so there is nothing to search for."}
        else:
            hint = sorted(hints.get(tid, []))
            hint_year = int(hint[0]) if len(hint) == 1 else None
            query = t["tmdb"].get("search_title") or t["display_title"]
            extra = page_tries(tid, query)
            if title_search.looks_like_no_name(t["display_title"]):
                query = extra[0][0]
                extra = []
            show = show_for(tid, title_search.clean_name(query))
            ep_result = None
            if not dash and show and not series:
                rest = title_search.without_show(title_search.clean_name(query), show)
                if rest and not TAPE_PART.search(rest):
                    # "Absolutely Fabulous Jealous": maybe an episode of a show
                    # the collection knows. Only kept if both are found.
                    dash = {"show": show, "episode_title": rest, "from": "known show name"}
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
            found = smart_search(query, tid, hint_year, extra, show)
            movies = found["candidates"]
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
                    "status": found["status"], "confidence": found["confidence"],
                    "search_title": query, "search_kind": found["kind"],
                    "found_with": found["found_with"], "searches_tried": found["tried"],
                    "latest_allowed_year": first_release.get(tid), "filename_film_year": hint_year,
                    "file_minutes": typical_minutes(tid),
                    "candidates": movies, "confirmed_tmdb_id": None,
                    "note": "Suggestions only. A person must confirm the match.",
                }
                if retry and old and found["status"] == "no_candidates" and old.get("candidates"):
                    t["tmdb"] = dict(old)    # never lose earlier guesses to a worse search
        if dash and dash.get("from") == "Archive page show" and t["tmdb"] is not old:
            t["tmdb"]["page_show_tried"] = True
        if t["tmdb"] is not old:
            t["tmdb"]["matcher"] = MATCHER_VERSION
        counts[t["tmdb"]["status"]] = counts.get(t["tmdb"]["status"], 0) + 1
        if t["tmdb"].get("confidence"):
            conf_counts[t["tmdb"]["confidence"]] = conf_counts.get(t["tmdb"]["confidence"], 0) + 1
            if t["tmdb"].get("found_with") and t["tmdb"]["found_with"] != "name as written":
                rescued[t["tmdb"]["found_with"]] = rescued.get(t["tmdb"]["found_with"], 0) + 1

    with (d / "titles.jsonl").open("w", encoding="utf-8") as fh:
        for t in titles:
            fh.write(json.dumps(t, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {"tmdb_lookups": lookups, "tmdb_tv_calls": tmdb_tv.calls if tmdb_tv else 0,
               "tvdb": ("on, " + str(tvdb_state["client"].calls) + " calls") if tvdb_state.get("client")
               else ("error: " + tvdb_state["error"]) if tvdb_state.get("error")
               else "off (no TVDB_API_KEY)" if not tvdb_key else "on, not needed",
               "title_status_counts": dict(sorted(counts.items())),
               "movie_match_confidence": dict(sorted(conf_counts.items())),
               "found_only_by_a_later_search": dict(sorted(rescued.items(), key=lambda x: -x[1]))}
    (d / "tmdb.summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
