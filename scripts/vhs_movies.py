#!/usr/bin/env python3
"""Keeps only full-length VHS movies, and builds the one movie list.

Two steps, run by the "Find VHS movies" workflow:

  keep-long   After the Archive pages are read: throws away every recording
              shorter than 40 minutes, split into pieces, taped off TV, a tape
              opening, or a DVD/Blu-ray. Only what's left is matched on TMDB
              and checked for sound, so the bot doesn't waste time on clips.

  finish      After the auto-sort: keeps only files it calls a movie on VHS
              that play, adds a clean name, year and TMDB genres, and writes
              the list both apps read (reviewer-feed.json), plus the details
              the review app shows (auto-sort.jsonl, audio-checks.jsonl).

A movie goes straight onto the shelves ("bot sure") only when TMDB found one
clear match whose running time fits the file, the file is clearly VHS, and it
plays with sound and picture. Everything else waits in the review app.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import not_movies  # noqa: E402

MIN_SECONDS = 40 * 60
DROP_CATEGORIES = {"tv_recording", "vhs_opening_or_previews", "other_media_release"}
MODEL_FILES = ("archive-items", "recordings", "editions", "titles", "artwork")

TMDB = "https://api.themoviedb.org/3"
IMG = "https://image.tmdb.org/t/p/"
GENRE_NAMES = {"Science Fiction": "Sci-Fi", "TV Movie": "TV Movie"}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    with path.open(encoding="utf-8") as fh:
        return [json.loads(x) for x in fh if x.strip()]


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")


# --------------------------------------------------------------------------
# Trimming the catalog model down to some recordings
# --------------------------------------------------------------------------
def is_long_candidate(rec: dict[str, Any]) -> bool:
    return ((rec.get("duration_seconds") or 0) >= MIN_SECONDS
            and (rec.get("part_count") or 1) == 1
            and rec.get("catalog_category") not in DROP_CATEGORIES)


def trim_model(model: dict[str, list[dict[str, Any]]], keep_recs: set[str]) -> dict[str, list[dict[str, Any]]]:
    """Keeps only the given recordings, and the editions, titles, pages and
    artwork that go with them."""
    recs = [r for r in model["recordings"] if r["recording_id"] in keep_recs]
    editions = []
    for e in model["editions"]:
        ids = [x for x in e["recording_ids"] if x in keep_recs]
        if ids:
            editions.append(dict(e, recording_ids=ids))
    kept_eds = {e["edition_suggestion_id"] for e in editions}
    titles = []
    for t in model["titles"]:
        eds = [x for x in t.get("edition_suggestion_ids") or [] if x in kept_eds]
        un = [x for x in t.get("unassigned_recording_ids") or [] if x in keep_recs]
        if eds or un:
            titles.append(dict(t, edition_suggestion_ids=eds, unassigned_recording_ids=un))
    pages = {a for r in recs for a in r.get("archive_identifiers") or []}
    items = [i for i in model["archive-items"] if i["archive_identifier"] in pages]
    art = [a for a in model["artwork"] if a.get("recording_id") in keep_recs]
    return {"archive-items": items, "recordings": recs, "editions": editions, "titles": titles, "artwork": art}


def load_model(d: Path) -> dict[str, list[dict[str, Any]]]:
    return {n: read_jsonl(d / f"{n}.jsonl") for n in MODEL_FILES}


def save_model(d: Path, model: dict[str, list[dict[str, Any]]], extra_summary: dict[str, Any]) -> None:
    d.mkdir(parents=True, exist_ok=True)
    for n in MODEL_FILES:
        write_jsonl(d / f"{n}.jsonl", model[n])
    # Files the movie list doesn't use are removed so nothing stale is left around.
    for n in ("archive-files.jsonl", "legacy-program-map.jsonl"):
        (d / n).unlink(missing_ok=True)
    summary = {"archive_pages": len(model["archive-items"]), "recordings": len(model["recordings"]),
               "titles": len(model["titles"]), **extra_summary}
    (d / "catalog-model.summary.json").write_text(json.dumps(summary, indent=2) + "\n")


def keep_long(model_dir: Path) -> dict[str, Any]:
    model = load_model(model_dir)
    total_pages = len(model["archive-items"])
    total = len(model["recordings"])
    blocked = {i["archive_identifier"] for i in model["archive-items"] if i.get("blocked")}
    keep = {r["recording_id"] for r in model["recordings"] if is_long_candidate(r)
            and not blocked & set(r.get("archive_identifiers") or [])}
    trimmed = trim_model(model, keep)
    summary = {"pages_read": total_pages, "recordings_read": total, "long_enough": len(keep),
               "blocked_adult_pages": len(blocked)}
    save_model(model_dir, trimmed, summary)
    return summary


# --------------------------------------------------------------------------
# TMDB details (genres, running time, art) for matched movies
# --------------------------------------------------------------------------
def tmdb_details(ids: list[int], cache: dict[str, Any], token: str | None, delay: float = 0.05) -> int:
    """Fills cache[str(id)] for every id not looked up yet. Returns lookups made."""
    calls = 0
    for i in ids:
        if str(i) in cache or not token:
            continue
        url = f"{TMDB}/movie/{i}?" + urllib.parse.urlencode({"language": "en-US"})
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
        d = None
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    d = json.load(r)
                break
            except Exception:  # noqa: BLE001 - retry, then leave it for the next run
                time.sleep(2 * (attempt + 1))
        calls += 1
        time.sleep(delay)
        if d is None:
            continue
        cache[str(i)] = {
            "tmdb_id": i, "title": d.get("title"), "year": (d.get("release_date") or "")[:4] or None,
            "genres": [GENRE_NAMES.get(g["name"], g["name"]) for g in d.get("genres") or [] if g.get("name")],
            "runtime": d.get("runtime") or None,
            "poster": IMG + "w342" + d["poster_path"] if d.get("poster_path") else None,
            "backdrop": IMG + "w780" + d["backdrop_path"] if d.get("backdrop_path") else None,
            "overview": (d.get("overview") or "")[:600] or None,
        }
    return calls


# --------------------------------------------------------------------------
# Picking the movies
# --------------------------------------------------------------------------
def best_candidate(title: dict[str, Any] | None) -> tuple[dict[str, Any], str | None]:
    tm = (title or {}).get("tmdb") or {}
    cands = tm.get("candidates") or []
    return (cands[0] if cands else {}), tm.get("confidence")


TAPED_OFF_TV = re.compile(
    r"\bS\d{1,2}\s?E\d{1,3}\b|\bep(?:isode)?s?\.?\s*\d|\bfull episodes?\b|\bepisodes\b|\btv series\b|"
    r"\(\s*(?:19|20)\d\d\s*,\s*tv\s*\)|\bprogrammes?\b|\brecordings?\b|\bshows\b|\btaped\b|\boff[- ]air\b|"
    r"\b(?:bbc ?(?:one|two|1|2|3|4)?|itv|cbeebies|cbbc|citv|channel ?[45]|nickelodeon|nick jr|syfy|sci-?fi channel|"
    r"hbo|showtime|cinemax|tnt|tbs|usa network|cartoon network|disney channel|abc family|fox kids|kids ?wb|"
    r"pbs|cbs|nbc|wgn|mtv|vh1|comedy central|amc|tcm)\b|\b(?:abc|ch\.?|channel) ?\d{1,2}\b|\bnight movie\b", re.I)


# Home-video labels named after a TV company ("CBS/FOX Video", "HBO Home Video"):
# the tape was sold in stores, not taped off TV.
VIDEO_LABEL = re.compile(r"\bcbs\s*[/_-]?\s*fox\b(?:\s+video)?|\b(?:hbo|fox|cbs|abc|nbc|pbs|bbc)\s+(?:home\s+)?video\b", re.I)


def taped_off_tv(text: str) -> bool:
    return bool(TAPED_OFF_TV.search(VIDEO_LABEL.sub(" ", text or "")))


# Tape collections on the Archive. VHS Vault also holds some DVDs and downloads,
# but a page there is still a reasonable sign of a tape.
TAPE_COLLECTIONS = {"vhsvault", "vhsmovies", "vhskids", "vhstvshows", "vhsmisc", "adultvhs"}
TAPE_WORDS_ON_PAGE = re.compile(r"\b(?:vhs|betamax|beta\s*max|video\s*tape|videocassette|vcr|laserdisc)\b", re.I)
LAST_TAPE_YEAR = 2006   # films after this were hardly ever sold on VHS


def tape_sign(row: dict[str, Any], title: dict[str, Any] | None, page: dict[str, Any] | None) -> str | None:
    """Why the bot thinks this came off a tape, or None when nothing says so.

    "Long and plays" is not enough: DJ sets, talks and news streams are that
    too. Something has to point at a tape.
    """
    if row.get("format_confidence") in {"high", "medium"}:
        return "file looks like a tape"
    page = page or {}
    cols = page.get("collection") or []
    cols = {cols} if isinstance(cols, str) else set(cols)
    if cols & TAPE_COLLECTIONS:
        return "page is in a tape collection"
    if TAPE_WORDS_ON_PAGE.search(str(row.get("name") or "").replace("_", " ")):
        return "file name says it's a tape"
    if (page.get("page_context") or {}).get("format") in {"VHS", "BETAMAX", "LASERDISC"} \
            or TAPE_WORDS_ON_PAGE.search(str(page.get("archive_title") or "")):
        return "page says it's a tape"
    cand, conf = best_candidate(title)
    year = str(cand.get("release_date") or "")[:4]
    if conf in {"sure", "likely", "several"} and cand.get("media_type") == "movie" and year.isdigit() \
            and int(year) <= LAST_TAPE_YEAR:
        return "a movie from the VHS years"
    return None


def why_not_kept(row: dict[str, Any], title: dict[str, Any] | None,
                 page: dict[str, Any] | None = None, check_tape: bool = False) -> str | None:
    if ((title or {}).get("tmdb") or {}).get("status") == "skipped_not_english":
        return "not in English"
    words = not_movies.not_a_movie(row.get("name"), (title or {}).get("display_title"),
                                   (page or {}).get("archive_title"))
    if words:
        return f"not a movie ({words})"
    if taped_off_tv(row.get("name") or "") or taped_off_tv((title or {}).get("display_title") or ""):
        return "taped off TV or a TV episode"
    if row["kind"] != "movie":
        return "not a movie (" + row["kind"] + ")"
    if row["format"] != "VHS":
        return "not VHS (" + row["format"] + ")"
    if (row.get("duration_seconds") or 0) < MIN_SECONDS:
        return "shorter than 40 minutes"
    if row["verdict"] == "bad":
        return "doesn't play (" + (row.get("reason") or "bad") + ")"
    cand, conf = best_candidate(title)
    if conf in {"sure", "likely"} and cand.get("media_type") == "tv":
        return "TMDB says it's a TV show"
    if check_tape and not tape_sign(row, title, page):
        return "not VHS (nothing says it's a tape)"
    return None


def bot_sure(row: dict[str, Any], title: dict[str, Any] | None, is_adult: bool = False) -> list[str]:
    """Why the bot can't put this movie on the shelf by itself (empty = sure)."""
    if is_adult:
        return ["adult tape: always checked by hand"]
    cand, conf = best_candidate(title)
    worries = []
    if conf != "sure" or cand.get("media_type") != "movie":
        worries.append({"likely": "TMDB match is likely but not certain",
                        "several": "several possible TMDB matches",
                        "weak": "only weak TMDB matches",
                        None: "not looked up on TMDB"}.get(conf, "no TMDB match"))
    if row["format_confidence"] != "high":
        worries.append("not clear it's a VHS tape")
    if row["verdict"] != "good":
        worries.append("; ".join(row.get("verdict_why") or []) or "sound or picture not checked")
    return worries


TAPE_WORDS = re.compile(
    r"\b(?:vhs(?:rip)?|dvd|betamax|ntsc|pal|secam|full(?: movie| vhs| tape)?|video release|fan ?made|screener|"
    r"uk|us|usa|aus?|nz|canadian|australian|american|british|rip|tape|capture|upscaled|hq)\b", re.I)
# A year after the name ("Snoopy Come Home 1996"), never one that starts it ("2001 A Space Odyssey").
LATE_YEAR = re.compile(r"(?<=\S)\s+(?:19|20)\d\d(?:\s*-\s*(?:19|20)\d\d)?\b")


def clean_title(text: str) -> str:
    """'19b. Thomas and His Friends Get Along (US VHS) ([2000]).mp4' -> 'Thomas and His Friends Get Along'."""
    raw = (text or "").strip()
    t = re.sub(r"\.(mp4|m4v|mkv|avi|mpe?g|mov|ogv|webm|ts|wmv)$", "", raw, flags=re.I)
    if t.count(".") >= 2 and " " not in t:
        t = t.replace(".", " ")
    t = t.replace("_", " ")
    t = re.sub(r"\([^)]*\)|\[[^\]]*\]|\{[^}]*\}", " ", t)
    t = re.sub(r"^\s*(?:\d{1,3}[a-z]?\.?|S\d{1,2})\s+-?\s*", "", t, flags=re.I)   # "02 ", "19b. ", "S5 - "
    t = TAPE_WORDS.sub(" ", t)
    t = LATE_YEAR.sub(" ", t)
    pieces = [x.strip() for x in t.split(" - ")]
    if len(pieces) >= 3 and all(len(x.split()) <= 2 for x in pieces):
        t = " ".join(pieces)                     # "Inspector - Gadget - Beats - The - Trap"
    t = re.sub(r"\s+", " ", t).strip(" -_.,:;&")
    m = re.match(r"^(.*), (The|A|An)$", t)
    if m:
        t = f"{m.group(2)} {m.group(1)}"
    return t or raw


# What the TV app reads from the movie list; everything else is only for the review app.
_TV_TITLE = ("id", "t", "adult", "genres")
_TV_MOVIE = ("tmdb_id", "title", "year", "poster", "overview", "backdrop")
_TV_GUESS = ("tmdb_id", "title", "release_date", "poster", "overview", "media_type")
_TV_SRC = ("id", "ok", "role", "n", "item", "dur", "url", "fmt")


def _pick(o: dict[str, Any] | None, keys: tuple[str, ...]) -> dict[str, Any]:
    return {k: o[k] for k in keys if o and o.get(k) is not None}


def archive_url(item: str, name: str) -> str:
    """The Archive's download link for a file; the TV app works out the same link."""
    return f"https://archive.org/download/{item}/{urllib.parse.quote(name)}"


def _tv_src(src: dict[str, Any]) -> dict[str, Any]:
    out = _pick(src, _TV_SRC)
    # the usual download link is left out (the TV builds it from the item and file name)
    if out.get("url") == archive_url(out.get("item", ""), out.get("n", "")):
        del out["url"]
    return out


def _tv_rec(rec: dict[str, Any]) -> dict[str, Any]:
    out = _pick(rec, ("ver", "notes"))
    out["segs"] = [{"src": [_tv_src(s) for s in seg.get("src") or []]} for seg in rec.get("segs") or []]
    return out


def tv_feed(feed: dict[str, Any]) -> dict[str, Any]:
    """A much smaller copy of the movie list with only what the TV app uses, so TVs load it quickly."""
    titles = []
    for t in feed["titles"]:
        o = _pick(t, _TV_TITLE)
        if t.get("movie"):
            o["movie"] = _pick(t["movie"], _TV_MOVIE)
        first = ((t.get("tmdb") or {}).get("c") or [None])[0]
        if first:
            o["tmdb"] = {"c": [_pick(first, _TV_GUESS)]}
        if t.get("un"):
            o["un"] = [_tv_rec(r) for r in t["un"]]
        if t.get("eds"):
            o["eds"] = [{**_pick(e, ("media", "year")), "recs": [_tv_rec(r) for r in e.get("recs") or []]} for e in t["eds"]]
        titles.append(o)
    return {"generated_at": feed.get("generated_at"), "titles": titles}


def finish(model_dir: Path, sort_path: Path, audio_path: Path, out: Path, cache_path: Path | None,
           pages_read: int | None) -> dict[str, Any]:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import catalog_model  # noqa: E402

    model = load_model(model_dir)
    titles = {t["title_suggestion_id"]: t for t in model["titles"]}
    # Adult tapes (kept behind the apps' red curtain) and blocked ones (never kept).
    adult_pages = {i["archive_identifier"] for i in model["archive-items"] if i.get("adult")}
    blocked_pages = {i["archive_identifier"] for i in model["archive-items"] if i.get("blocked")}
    pages = {i["archive_identifier"]: i for i in model["archive-items"]}
    rec_pages = {r["recording_id"]: set(r.get("archive_identifiers") or []) for r in model["recordings"]}
    adult_recs = {rid for rid, pages in rec_pages.items() if pages & adult_pages}
    rows = read_jsonl(sort_path)
    rows_by_rec: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        rows_by_rec.setdefault(r["recording_id"], []).append(r)

    # A recording is kept when its original file (or, without one, any file) passes.
    keep_recs: set[str] = set()
    dropped: Counter[str] = Counter()
    for rid, group in rows_by_rec.items():
        group.sort(key=lambda r: (r.get("role") != "original", r["verdict"] == "bad"))
        title = titles.get(group[0].get("title_suggestion_id"))
        # The same tape can be on several pages: one tape-looking page is enough.
        on_pages = [pages[p] for p in sorted(rec_pages.get(rid, set()) | {group[0].get("archive_identifier")})
                    if p in pages] or [None]
        on_pages.sort(key=lambda pg: tape_sign(group[0], title, pg) is None)
        why = why_not_kept(group[0], title, on_pages[0], check_tape=True)
        if rec_pages.get(rid, set()) & blocked_pages:
            why = "blocked adult tape"
        if why is None:
            keep_recs.add(rid)
        else:
            dropped[re.sub(r" \(.*", "", why)] += 1

    # Genres and art for every movie TMDB matched with some confidence.
    cache: dict[str, Any] = {}
    if cache_path and cache_path.is_file():
        cache = json.loads(cache_path.read_text(encoding="utf-8"))
    want = sorted({c["tmdb_id"] for rid in keep_recs - adult_recs for r in rows_by_rec[rid][:1]
                   for c, conf in [best_candidate(titles.get(r.get("title_suggestion_id")))]
                   if c.get("media_type") == "movie" and c.get("tmdb_id") and conf in {"sure", "likely", "several"}})
    calls = tmdb_details(want, cache, os.environ.get("TMDB_API_READ_ACCESS_TOKEN"))

    # The bot's call per file: "good" only when it's sure, otherwise "review".
    sure_files: set[str] = set()
    out_rows = []
    for rid in keep_recs:
        for r in rows_by_rec[rid]:
            if r["verdict"] == "bad":
                out_rows.append(r)
                continue
            worries = bot_sure(r, titles.get(r.get("title_suggestion_id")), rid in adult_recs)
            if worries:
                r = dict(r, verdict="review", confidence="low", verdict_why=worries)
            else:
                sure_files.add(r["file_id"])
            out_rows.append(r)
    out_rows.sort(key=lambda r: (r.get("title") or "").casefold())

    trimmed = trim_model(model, keep_recs)
    out.mkdir(parents=True, exist_ok=True)
    save_model(out, trimmed, {"vhs_movies": len(keep_recs)})
    feed = catalog_model.build_reviewer_feed(str(out))

    # Clean names, genres, and which files the bot is sure about.
    genre_count: Counter[str] = Counter()
    sure_titles = 0
    curtain = 0
    for t in feed["titles"]:
        recs_out = t["un"] + [r for e in t["eds"] for r in e["recs"]]
        if any(r["id"] in adult_recs for r in recs_out):
            # Behind the curtain: title and Archive links only. No pictures,
            # no TMDB guesses (TMDB isn't searched for adult tapes).
            t["adult"] = True
            for r in recs_out:
                r["art"] = []
            t["tmdb"] = {"status": "adult_not_looked_up", "c": []}
            t["raw"] = t["t"]
            t["t"] = clean_title(t["t"])
            t["genres"] = []
            curtain += 1
            continue
        src_title = titles.get(t["id"]) or {}
        cand, conf = best_candidate(src_title)
        info = cache.get(str(cand.get("tmdb_id"))) if cand.get("media_type") == "movie" else None
        files = [x for r in t["un"] + [r for e in t["eds"] for r in e["recs"]] for s in r["segs"] for x in s["src"]]
        any_sure = False
        for x in files:
            if x["id"] in sure_files:
                x["ok"] = True
                any_sure = True
        t["raw"] = t["t"]
        if any_sure and info:
            t["t"] = info["title"] or cand.get("title") or clean_title(t["t"])
            t["movie"] = info
            t["genres"] = info["genres"]
            sure_titles += 1
            genre_count.update(info["genres"] or ["No genre on TMDB"])
        else:
            t["t"] = clean_title(t["t"])
            if info:
                t["guess"] = info    # TMDB's best guess, for the review screen; not confirmed
            t["genres"] = []
    feed["titles"].sort(key=lambda t: t["t"].casefold())
    feed["summary"] = {
        "pages_read": pages_read, "vhs_movies": len(feed["titles"]), "bot_sure": sure_titles,
        "waiting_for_review": len(feed["titles"]) - sure_titles - curtain,
        "behind_the_curtain": curtain,
        "files": len(out_rows), "genres": dict(genre_count.most_common()),
        "left_out": dict(dropped.most_common()), "tmdb_detail_lookups": calls,
    }
    (out / "reviewer-feed.json").write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")),
                                            encoding="utf-8")
    (out / "tv-feed.json").write_text(json.dumps(tv_feed(feed), ensure_ascii=False, separators=(",", ":")),
                                      encoding="utf-8")
    write_jsonl(out / "auto-sort.jsonl", out_rows)
    keep_files = {r["file_id"] for r in out_rows}
    write_jsonl(out / "audio-checks.jsonl", [a for a in read_jsonl(audio_path) if a.get("file_id") in keep_files])
    if cache_path:
        (out / "tmdb-movies.json").write_text(json.dumps(cache, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    (out / "summary.json").write_text(json.dumps(feed["summary"], indent=2, ensure_ascii=False) + "\n")
    return feed["summary"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="step", required=True)
    k = sub.add_parser("keep-long", help="drop everything under 40 minutes, split, TV, openings, DVD/Blu-ray")
    k.add_argument("--model-dir", required=True)
    f = sub.add_parser("finish", help="keep only VHS movies and write the movie list")
    f.add_argument("--model-dir", required=True)
    f.add_argument("--auto-sort", required=True)
    f.add_argument("--audio", required=True)
    f.add_argument("--output-dir", required=True)
    f.add_argument("--tmdb-cache", help="earlier tmdb-movies.json (genres already looked up)")
    f.add_argument("--pages-read", type=int)
    a = ap.parse_args()
    if a.step == "keep-long":
        s = keep_long(Path(a.model_dir))
    else:
        s = finish(Path(a.model_dir), Path(a.auto_sort), Path(a.audio), Path(a.output_dir),
                   Path(a.tmdb_cache) if a.tmdb_cache else None, a.pages_read)
    s["built_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    print(json.dumps(s, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
