#!/usr/bin/env python3
"""VHS FEVER auto-sort: a best guess for every video file, with its reasons.

For each file in the catalog model it works out:

* kind   - movie, tv_episode, commercial, commercial_compilation, tv_recording
           (taped off live TV), promo_or_bump, trailer, tape_opening, music,
           extra, clip (a piece of a split video), or unknown;
* format - VHS, BETAMAX, LASERDISC, DVD, BLU-RAY, WEB, TV or unknown, from the
           file name, the Archive collection and the picture size;
* builtin_commercials - whether a TV recording already has its ads in it
           (true / likely / false / unknown), so the TV guide never adds more;
* verdict - good / bad / review, with a reason in the same words the review
           app uses ("Clip", "Won't play", "No audio", ...);
* shelf  - the review app shelf (vhs_release, other_media_release, not_catalog);
* artwork - real box scans from the Archive item first, TMDB poster as backup
           (kept apart, never mixed).

Nothing here changes a person's decision. Human decisions always win, and a
file is only marked good when every clue agrees. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import re
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any

VERSION = 1

# --------------------------------------------------------------------------
# Words in names
# --------------------------------------------------------------------------
I = re.IGNORECASE
RIP_TAGS = [  # (pattern, format, strength)
    (re.compile(r"\bvhs[\s._-]*rip\b|\bvhsrip\b", I), "VHS", 3),
    (re.compile(r"\b(?:ld|laser[\s._-]*disc)[\s._-]*rip\b", I), "LASERDISC", 3),
    (re.compile(r"\bdvd[\s._-]*(?:rip|scr|5|9|r)\b|\bdvdrip\b", I), "DVD", 3),
    (re.compile(r"\b(?:bd|br)[\s._-]*rip\b|\bblu[\s._-]*ray\b|\bremux\b|\b2160p\b|\buhd\b", I), "BLU-RAY", 3),
    (re.compile(r"\bweb[\s._-]*(?:rip|dl)\b|\bamzn\b|\bnf\b|\bhmax\b|\bdsnp\b", I), "WEB", 3),
    (re.compile(r"\bhdtv\b|\bpdtv\b|\bdsr\b|\bsatrip\b|\btvrip\b", I), "TV", 3),
    (re.compile(r"\bbetamax\b|\bbeta[\s._-]*rip\b", I), "BETAMAX", 3),
    (re.compile(r"\blaser[\s._-]*disc\b", I), "LASERDISC", 2),
    (re.compile(r"\bvhs(?![a-z])", I), "VHS", 2),   # also "VHS1984_VID_..."
    (re.compile(r"\bdvd\b|\bvideo_ts\b", I), "DVD", 2),
    (re.compile(r"\bvcd\b", I), "VCD", 2),
    (re.compile(r"\bupscaled?\b|\bai[\s._-]*enhanced\b", I), "UPSCALED", 1),
]
MEDIA_FROM_MODEL = {"VHS": "VHS", "DVD": "DVD", "BLU-RAY": "BLU-RAY", "LASERDISC": "LASERDISC",
                    "BETAMAX": "BETAMAX", "VCD": "VCD", "BROADCAST": "TV", "TAPE_UNSPECIFIED": "VHS"}
TAPE_FORMATS = {"VHS", "BETAMAX"}

COMMERCIAL = re.compile(r"\b(?:commercials?|tv[\s._-]*ads?|adverts?|advertisements?|infomercials?|psas?|"
                        r"public service announcements?|(?:ad|tv|radio|commercial) spots?)\b", I)
WITH_ADS = re.compile(r"\bwith (?:the )?(?:original )?(?:commercials|ads)\b|\b(?:w/|incl\.?|including) ?(?:commercials|ads)\b|"
                      r"\bcommercials? (?:intact|included)\b|\bas[\s._-]*aired\b|\boriginal (?:tv )?broadcast\b|"
                      r"\bfull broadcast\b|\bwith breaks\b", I)
NO_ADS = re.compile(r"\b(?:no|without|w/o|commercial[\s._-]*free|ads? removed|commercials? removed|"
                    r"commercials? cut)\b", I)
OFF_AIR = re.compile(r"\b(?:taped|recorded|tape[sd]?) (?:off|from) (?:of )?(?:tv|television|cable|the air|air|hbo|"
                     r"showtime|nick(?:elodeon)?|cartoon network|disney channel|fox|abc|nbc|cbs|pbs)\b|"
                     r"\boff[\s._-]*air\b|\btv[\s._-]*recording\b|\bbroadcast recording\b|\bas[\s._-]*aired\b|"
                     r"\boriginal (?:tv )?broadcast\b|\blive (?:tv )?feed\b|\bairing\b", I)
MONTH = r"(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?"
# "FRI 2001 MAR 09", "March 9, 2001", "2001-03-09 broadcast": an air date.
AIR_DATE_WORDS = re.compile(
    rf"\b(?:19|20)\d\d\s+{MONTH}\s+\d{{1,2}}\b|\b{MONTH}\s+\d{{1,2}},?\s+(?:19|20)\d\d\b|"
    rf"\b(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b[^a-z]+(?:19|20)\d\d\b|"
    r"\b(?:newscast|news broadcast|evening news|nightly news|nightline|late show|tonight show|"
    r"saturday morning cartoons|telethon|marathon)\b", re.I)
CHANNEL = re.compile(r"\b(?:[KW][A-Z]{2,3}(?:[- ]?TV)?|ABC|NBC|CBS|FOX|PBS|UPN|WB|HBO|MTV|VH1|TBS|TNT|USA Network|"
                     r"Nickelodeon|Nick Jr\.?|Nick at Nite|Cartoon Network|Disney Channel|Toon Disney|ABC Family|"
                     r"Fox Kids|Kids' WB|Showtime|Cinemax|Sci[- ]?Fi Channel|Comedy Central|Channel \d{1,2})\b")
PROMO = re.compile(r"\b(?:bumpers?|bumps?|idents?|station id|promos?|promotional|interstitials?|"
                   r"sign[\s._-]*(?:on|off)|test pattern|coming up next|feature presentation)\b", I)
SCREENER = re.compile(r"\b(?:promo(?:tional)? (?:copy|tape|screener)|screener)\b", I)
TRAILER = re.compile(r"\b(?:trailers?|teasers?|previews?|sneak peeks?|tv spots?)\b", I)
OPENING = re.compile(r"\b(?:opening|closing) (?:to|of)\b|\bopening and closing\b", I)
MUSIC = re.compile(r"\b(?:concert|live at|live in|in concert|music videos?|tour|unplugged|hyde park|"
                   r"greatest hits|sing[\s._-]*along|karaoke)\b", I)
EXTRA = re.compile(r"\b(?:behind the scenes|making of|featurette|deleted scenes?|bloopers?|outtakes?|"
                   r"animatics?|work[\s._-]*in[\s._-]*progress|workprint|storyboards?|bonus|extras?|"
                   r"interview|commentary|screen ?test|rough cut|pilot presentation)\b", I)
NOT_WHOLE = re.compile(r"\b(?:clip|clips|excerpts?|scenes?|segment|snippet|sample|test)\b", I)

# A short version of the review app's language check (Language.kt).
LANGS = (r"spanish|espa[nñ]ol|castellano|latino|french|fran[cç]ais|qu[eé]b[eé]cois|german|deutsch|italian|italiano|"
         r"portuguese|portugu[eê]s|brazilian|polish|polski|russian|japanese|chinese|mandarin|cantonese|korean|dutch|"
         r"flemish|swedish|norwegian|danish|finnish|turkish|hindi|tamil|telugu|arabic|hebrew|greek|hungarian|"
         r"czech|slovak|romanian|bulgarian|serbian|croatian|ukrainian|thai|vietnamese|tagalog|indonesian|persian")
FOREIGN = [
    re.compile(rf"\b(?:{LANGS})\s+(?:canadian\s+)?(?:version|copy|dub|dubbed|voice ?over|audio|lektor|language|"
               rf"track|edition|release|subs?|subtitles?|tape|vhs|dvd)\b", I),
    re.compile(rf"[(\[]\s*(?:{LANGS})\s*[)\]]", I),
    re.compile(rf"\b(?:in|en)\s+(?:{LANGS})\b", I),
    re.compile(r"\b(?:doblad[oa]|doblaje|dublado|dublagem|legendado|subtitulad[oa]|doublage|vostfr|vost|lektor|"
               r"napisy|audio latino)\b", I),
]
ENGLISH = re.compile(r"\benglish\s+(?:dub|dubbed|version|audio|language|track|voice ?over)\b|[(\[]\s*english\s*[)\]]|"
                     r"\bin english\b|\beng ?dub\b", I)

GOOD_VIDEO = {"h264"}
GOOD_AUDIO = {"aac"}


def foreign_reason(name: str) -> str | None:
    text = re.sub(r"\.(?:ia\.)?[a-z0-9]{2,4}$", "", name, flags=I).replace("_", " ").replace(".", " ")
    for ch in text:
        if ch.isalpha() and not unicodedata.name(ch, "").startswith(("LATIN", "DIGIT")):
            return "name is written in another alphabet"
    if ENGLISH.search(text):
        return None
    for rx in FOREIGN:
        m = rx.search(text)
        if m:
            return f"name says “{m.group(0).strip('()[] ')}”"
    return None


def item_describes_video(item: dict[str, Any]) -> bool:
    # (Every page carries the same "Container only" note, so the count decides.)
    return 0 < (item.get("video_file_count") or 0) <= 2


def norm_words(text: str) -> str:
    return re.sub(r"[_.]+", " ", text or "")


# --------------------------------------------------------------------------
# Format
# --------------------------------------------------------------------------
def detect_format(rec: dict[str, Any], src: dict[str, Any], probe: dict[str, Any], item: dict[str, Any]) -> dict[str, Any]:
    """Score each format from every clue. Returns format, confidence and clues."""
    score: Counter[str] = Counter()
    clues: list[str] = []
    upscaled = False
    model_media = MEDIA_FROM_MODEL.get(rec["interpretation"].get("media_format") or "")
    if model_media:
        raw = str(rec["interpretation"].get("media_label_raw") or "")
        if raw.startswith("page:"):
            score[model_media] += 3
            clues.append(f"Archive page title says {model_media}")
        elif raw.startswith("other files on page"):
            score[model_media] += 2.5
            clues.append(f"the other files on its page are {model_media}")
        else:
            score[model_media] += 4
            clues.append(f"file name says {model_media}")
    name = norm_words(src.get("original_name") or "")
    for rx, fmt, strength in RIP_TAGS:
        if rx.search(name):
            if fmt == "UPSCALED":
                upscaled = True
                clues.append("file name says it was upscaled")
                continue
            if not (model_media == fmt and strength <= 2):
                score[fmt] += strength
                clues.append(f"file name has “{rx.search(name).group(0)}”")
    flags = set(rec.get("flags") or [])
    if "dvd_video_ts_file" in flags:
        score["DVD"] += 4
        clues.append("raw DVD folder file")
    if "web_download_not_tape" in flags:
        score["WEB"] += 3
        clues.append("looks like a web download")
    # The Archive page title only describes the video when the page holds one
    # or two videos; big pages ("All Animated VHS and DVD Capture") say nothing.
    title = norm_words(item.get("archive_title") or "") if item_describes_video(item) else ""
    for rx, fmt, strength in RIP_TAGS[:-1]:
        if rx.search(title):
            score[fmt] += 1
            clues.append(f"Archive page title mentions {fmt}")
            break
    cols = set(item.get("collection") or [])
    if cols & {"vhsvault", "vhsmovies", "vhskids", "vhstvshows", "vhsmisc", "vhscommercials", "vhstrailers"}:
        score["VHS"] += 0.5   # a weak hint only: VHS Vault also holds DVDs and downloads

    w, h = probe.get("width"), probe.get("height")
    if w and h:
        aspect = w / h
        if h >= 700 and not upscaled:
            # Only a light hint: tapes are often captured at HD size too.
            for fmt in ("BLU-RAY", "WEB"):
                score[fmt] += 0.75
            clues.append(f"HD-size picture ({w}x{h}), not proof of Blu-ray")
            if score["VHS"] >= 4:
                clues.append("HD picture but named VHS: an upscaled tape or a wrong label")
        elif h <= 576:
            score["VHS"] += 0.5
            score["DVD"] += 0.5
            if aspect >= 1.6 and w >= 700:
                score["DVD"] += 1
                clues.append(f"widescreen standard picture ({w}x{h}), more like DVD")
            elif aspect <= 1.4:
                clues.append(f"square-ish standard picture ({w}x{h})")

    if not score:
        return {"format": "unknown", "confidence": "low", "clues": clues or ["no clues"], "scores": {}}
    ranked = score.most_common()
    best, top = ranked[0]
    second = ranked[1][1] if len(ranked) > 1 else 0
    margin = top - second
    if top >= 4 and margin >= 3:
        conf = "high"
    elif top >= 2.5 and margin >= 1.5:
        conf = "medium"
    else:
        conf = "low"
    if upscaled and best in TAPE_FORMATS:
        clues.append("tape capture that was upscaled")
    return {"format": best, "confidence": conf, "clues": clues,
            "scores": {k: round(v, 1) for k, v in ranked}}


# --------------------------------------------------------------------------
# Kind (what sort of video it is)
# --------------------------------------------------------------------------
def detect_kind(rec: dict[str, Any], src: dict[str, Any], item: dict[str, Any], dur: float | None) -> dict[str, Any]:
    it = rec["interpretation"]
    name = norm_words(src.get("original_name") or "")
    text = name + " | " + (norm_words(item.get("archive_title") or "") if item_describes_video(item) else "")
    cols = set(item.get("collection") or [])
    why: list[str] = []
    kind = None
    commercials = "unknown"

    if (it.get("part_total") or 0) > 1 or rec.get("part_count", 1) > 1:
        kind = "clip"
        why.append(f"piece {it.get('part_index') or '?'} of {it.get('part_total') or rec.get('part_count')} of a split video")
    elif it.get("content_hint") == "tape_opening_or_previews" or OPENING.search(name):
        kind = "tape_opening"
        why.append("opening or closing of a tape")
    elif (OFF_AIR.search(text) or WITH_ADS.search(name) or AIR_DATE_WORDS.search(name)
          or (it.get("air_date") and not it.get("episode"))) and not (
            COMMERCIAL.search(name) and not WITH_ADS.search(name) and (dur or 0) <= 240):
        kind = "tv_recording"
        why.append("taped off live TV")
    elif COMMERCIAL.search(name) or "vhscommercials" in cols:
        if dur is not None and dur <= 240:
            kind = "commercial"
            why.append("commercial, short enough to be one ad")
        else:
            kind = "commercial_compilation"
            why.append("several commercials in one file" if dur else "commercials")
        commercials = "true"
    elif PROMO.search(name) and (dur is None or dur <= 600) and not SCREENER.search(name):
        kind = "promo_or_bump"
        why.append("station ID, bumper or promo")
    elif TRAILER.search(name) or ("vhstrailers" in cols and (dur or 0) < 600):
        kind = "trailer"
        why.append("trailer or preview")
    elif it.get("episode") is not None or it.get("content_hint") == "tv_episode" or it.get("series_title"):
        kind = "tv_episode"
        why.append("TV episode number or show name in file name")
    elif EXTRA.search(name):
        kind = "extra"
        why.append("bonus material (behind the scenes, animatics, ...)")
    elif MUSIC.search(text) or "vhsconcerts_music" in cols:
        kind = "music"
        why.append("concert or music video")
    elif dur is not None and dur >= 40 * 60:
        kind = "movie"
        why.append("feature length")
    elif dur is not None and dur >= 15 * 60:
        kind = "movie" if not NOT_WHOLE.search(name) else "clip"
        why.append("half-hour length: a short feature or a special" if kind == "movie" else "name says clip")
    elif dur is not None and dur >= 3 * 60 and not NOT_WHOLE.search(name):
        kind = "short"
        why.append(f"{round(dur / 60)}-minute short (cartoon or short subject)")
    elif dur is not None:
        kind = "clip" if NOT_WHOLE.search(name) or dur < 3 * 60 else "unknown"
        why.append(f"only {round(dur / 60, 1)} minutes long")
    else:
        kind = "unknown"
        why.append("length unknown")

    # A TV recording or episode may still have its original ads in it.
    if kind in {"tv_recording", "tv_episode"}:
        if NO_ADS.search(name) and re.search(r"commercial|ads?\b", name, I):
            commercials = "false"
            why.append("name says the ads were taken out")
        elif WITH_ADS.search(name) or COMMERCIAL.search(name):
            commercials = "true"
            why.append("name says the original ads are in it")
            kind = "tv_recording"
        elif kind == "tv_recording":
            commercials = "likely"
            why.append("taped off TV, so ads are probably in it")
        elif dur is not None:
            # A half-hour show runs ~22 min without ads, an hour show ~44.
            slot = 30 * 60 if dur < 40 * 60 else 60 * 60
            if dur >= slot * 0.93 and dur <= slot * 1.1 and kind == "tv_episode":
                commercials = "likely"
                why.append(f"runs a full {slot // 60} minutes, so ads are probably still in it")
            else:
                commercials = "false"
        if CHANNEL.search(norm_words(src.get("original_name") or "")):
            why.append("channel name in file name")
    return {"kind": kind, "builtin_commercials": commercials, "why": why}


# --------------------------------------------------------------------------
# Good / bad / review
# --------------------------------------------------------------------------
MIN_LEN = {"movie": 15 * 60, "short": 3 * 60, "tv_episode": 4 * 60, "tv_recording": 10 * 60, "music": 2 * 60,
           "commercial": 10, "commercial_compilation": 60}
GOOD_KINDS = set(MIN_LEN)
TV_SOURCED = {"tv_recording", "commercial", "commercial_compilation", "promo_or_bump"}


def decide(kind: dict[str, Any], src: dict[str, Any], probe: dict[str, Any], dur: float | None,
           name: str) -> dict[str, Any]:
    v = probe.get("verdict")
    k = kind["kind"]
    # Hard "bad": the file itself is broken, or it is a piece of a split video.
    if v == "could_not_open":
        return {"verdict": "bad", "reason": "Won’t play", "confidence": "high",
                "why": ["the file could not be opened"]}
    if probe and probe.get("has_video_track") is False:
        return {"verdict": "bad", "reason": "No video", "confidence": "high", "why": ["no picture in the file"]}
    if v == "no_audio_track":
        return {"verdict": "bad", "reason": "No audio", "confidence": "high", "why": ["no sound track"]}
    if k == "clip" and (src.get("_part_total") or 0) > 1:
        return {"verdict": "bad", "reason": "Clip", "confidence": "high",
                "why": ["piece of a video split into parts"]}

    worries: list[str] = []
    if not probe:
        worries.append("sound and picture not checked yet")
    elif v == "silent_audio":
        # Two spot checks can both land on a quiet stretch, so a person decides.
        worries.append("sound was silent in the spots checked")
    elif v == "audio_not_measured":
        worries.append("sound level could not be measured")
    vc, ac = probe.get("video_codec"), probe.get("audio_codec")
    if probe and vc not in GOOD_VIDEO:
        worries.append(f"picture is {vc or 'an unknown kind'}, which may not play on TV")
    if probe and ac not in GOOD_AUDIO:
        worries.append(f"sound is {ac or 'an unknown kind'}, which may not play on TV")
    peaks = [p for p in (probe.get("sample_max_db") or []) if p is not None]
    if peaks and all(p >= -0.05 for p in peaks):
        worries.append("sound is maxed out, often distorted")
    if src.get("browser_playable") is False:
        worries.append("the Archive can't stream this file type")
    reason = foreign_reason(name)
    if reason:
        worries.append("may not be in English: " + reason)
    if k not in GOOD_KINDS:
        worries.append(f"{k.replace('_', ' ')}, not a full program")
    elif dur is None or dur < MIN_LEN[k]:
        worries.append("shorter than expected")
    if k == "clip":
        return {"verdict": "review", "reason": "Clip", "confidence": "medium", "why": kind["why"] + worries}
    if worries:
        return {"verdict": "review", "reason": None, "confidence": "low", "why": worries}
    return {"verdict": "good", "reason": None, "confidence": "high",
            "why": ["plays, has sound and picture, full length, English name"]}


def shelf_for(fmt: dict[str, Any], kind: str) -> tuple[str | None, str]:
    if kind in {"commercial", "commercial_compilation", "promo_or_bump", "trailer", "clip", "extra"}:
        return "not_catalog", "not a full program"
    if fmt["format"] in TAPE_FORMATS:
        return "vhs_release", "tape"
    if fmt["format"] == "TV":
        return None, "recorded from TV"
    if fmt["format"] in {"DVD", "BLU-RAY", "LASERDISC", "VCD", "WEB"}:
        return "other_media_release", fmt["format"]
    return None, "format unknown"


# --------------------------------------------------------------------------
# Artwork
# --------------------------------------------------------------------------
ART_ORDER = {"vhs_front": 0, "vhs_wrap": 1, "vhs_label": 3, "vhs_spine": 4, "vhs_back": 5, "unknown": 6}
BOX_NAME = re.compile(r"front|cover|box|sleeve|case|clamshell", I)


def pick_art(rec: dict[str, Any], art_by_rec: dict[str, list[dict[str, Any]]],
             title: dict[str, Any] | None) -> dict[str, Any]:
    box = None
    found = art_by_rec.get(rec["recording_id"], [])
    if found:
        ranked = sorted(found, key=lambda a: (ART_ORDER.get(a.get("role_guess") or "unknown", 6),
                                              0 if BOX_NAME.search(a.get("original_name") or "") else 1))
        a = ranked[0]
        box = {"url": a["image_url"], "part": a.get("role_guess") or "unknown",
               "source": "Archive item scan", "artwork_id": a.get("artwork_id"),
               "others": [x["image_url"] for x in ranked[1:5]]}
    poster = None
    tm = (title or {}).get("tmdb") or {}
    cands = [c for c in tm.get("candidates") or [] if c.get("poster")]
    if cands:
        c = cands[0]
        poster = {"url": c["poster"], "tmdb_id": c.get("tmdb_id"), "title": c.get("title"),
                  "match_status": tm.get("status"), "confirmed": bool(tm.get("confirmed_tmdb_id")),
                  "source": "TMDB poster (backup, not the real box)"}
    return {"box": box, "tmdb_poster": poster,
            "use": "box" if box else ("tmdb_poster" if poster else None)}


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    return [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]


def sort_all(recs, items, art, titles, probes) -> list[dict[str, Any]]:
    item_by_id = {i["archive_identifier"]: i for i in items}
    art_by_rec: dict[str, list[dict[str, Any]]] = {}
    for a in art:
        if a.get("recording_id"):
            art_by_rec.setdefault(a["recording_id"], []).append(a)
    title_by_id = {t["title_suggestion_id"]: t for t in titles}
    out = []
    for rec in recs:
        it = rec["interpretation"]
        title = title_by_id.get(rec.get("title_suggestion_id"))
        art_pick = pick_art(rec, art_by_rec, title)
        for seg in rec["segments"]:
            for src in seg["sources"]:
                fid = src["source_id"]
                probe = probes.get(fid, {})
                item = item_by_id.get(src.get("archive_identifier"), {})
                dur = src.get("duration_seconds") or seg.get("duration_seconds")
                src = dict(src, _part_total=max(it.get("part_total") or 0, rec.get("part_count") or 1))
                kind = detect_kind(rec, src, item, dur)
                fmt = detect_format(rec, src, probe, item)
                if kind["kind"] in TV_SOURCED and (fmt["scores"].get(fmt["format"], 0) < 4):
                    fmt = dict(fmt, format="TV", confidence="medium",
                               clues=fmt["clues"] + ["recorded from TV, no disc or tape named"])
                d = decide(kind, src, probe, dur, src.get("original_name") or "")
                shelf, shelf_why = shelf_for(fmt, kind["kind"])
                out.append({
                    "file_id": fid, "recording_id": rec["recording_id"],
                    "name": src.get("original_name"), "archive_identifier": src.get("archive_identifier"),
                    "playback_url": src.get("playback_url"), "role": src.get("role"),
                    "air_date": it.get("air_date"), "years_in_name": years_in(src.get("original_name") or ""),
                    "duration_seconds": dur, "kind": kind["kind"], "kind_why": kind["why"],
                    "builtin_commercials": kind["builtin_commercials"],
                    "format": fmt["format"], "format_confidence": fmt["confidence"],
                    "format_clues": fmt["clues"], "format_scores": fmt["scores"],
                    "verdict": d["verdict"], "reason": d["reason"], "confidence": d["confidence"],
                    "verdict_why": d["why"], "shelf": shelf, "shelf_why": shelf_why,
                    "title": (title or {}).get("display_title") or it.get("title"),
                    "title_suggestion_id": rec.get("title_suggestion_id"),
                    "artwork": art_pick, "version": VERSION,
                })
    return out


# --------------------------------------------------------------------------
# Ad list (for the retro TV guide)
# --------------------------------------------------------------------------
AD_KINDS = {"commercial": "single ad", "commercial_compilation": "ad compilation",
            "promo_or_bump": "promo or bump", "trailer": "trailer"}
HOLIDAYS = {
    "christmas": r"christmas|xmas|holiday|santa|noel|winter",
    "halloween": r"halloween|spooky|trick or treat",
    "thanksgiving": r"thanksgiving|turkey day",
    "easter": r"easter",
    "valentines": r"valentine",
    "summer": r"summer",
    "back_to_school": r"back to school|first day of school",
    "new_year": r"new year",
}
HOLIDAY_RX = {k: re.compile(rf"\b(?:{v})", I) for k, v in HOLIDAYS.items()}
YEAR_RX = re.compile(r"(?<!\d)((?:19[4-9]|20[0-2])\d)(?!\d)")
BREAK_RX = re.compile(r"\bcommercial breaks?\b|\bad breaks?\b", I)


def years_in(name: str) -> list[int]:
    return sorted({int(y) for y in YEAR_RX.findall(name)})


def build_ad_list(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """One entry per ad, promo, bump or trailer recording, plus TV recordings
    that still have their ads in them (listed whole until the breaks inside
    can be found). Archive-made copies are folded into the original."""
    by_rec: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        ad_kind = AD_KINDS.get(r["kind"])
        if ad_kind is None and not (r["kind"] in {"tv_recording", "tv_episode"}
                                    and r["builtin_commercials"] in {"true", "likely"}):
            continue
        by_rec.setdefault(r["recording_id"], []).append(r)
    out = []
    for rid, group in by_rec.items():
        group.sort(key=lambda r: (r.get("role") != "original", r["verdict"] != "good"))
        r = group[0]
        name = r["name"] or ""
        text = norm_words(name)
        years = r["years_in_name"]
        year = int(r["air_date"][:4]) if r.get("air_date") else (years[0] if len(years) == 1 else None)
        kind = AD_KINDS.get(r["kind"])
        if kind is None:
            kind = "TV recording with ads in it"
        elif kind == "ad compilation" and BREAK_RX.search(text):
            kind = "commercial break"
        out.append({
            "ad_id": rid, "kind": kind, "name": name, "title": r.get("title"),
            "duration_seconds": r["duration_seconds"],
            "year": year, "decade": f"{year // 10 * 10}s" if year else None, "air_date": r.get("air_date"),
            "holidays": [k for k, rx in HOLIDAY_RX.items() if rx.search(text)],
            "channel": (CHANNEL.search(text).group(0) if CHANNEL.search(text) else None),
            "ads_inside": r["builtin_commercials"] if r["kind"] in {"tv_recording", "tv_episode"} else None,
            "play_url": next((g["playback_url"] for g in group if g.get("playback_url") and g["verdict"] != "bad"),
                             r.get("playback_url")),
            "archive_page": f"https://archive.org/details/{r['archive_identifier']}",
            "verdict": r["verdict"], "verdict_why": r["verdict_why"],
            "copies": len(group), "file_ids": [g["file_id"] for g in group],
        })
    out.sort(key=lambda a: (a["kind"], a["year"] or 9999, a["name"].casefold()))
    return out


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    C = Counter
    return {
        "version": VERSION, "files": len(rows),
        "verdicts": dict(C(r["verdict"] for r in rows)),
        "bad_reasons": dict(C(r["reason"] for r in rows if r["verdict"] == "bad")),
        "kinds": dict(C(r["kind"] for r in rows).most_common()),
        "formats": dict(C(r["format"] for r in rows).most_common()),
        "format_confidence": dict(C(r["format_confidence"] for r in rows)),
        "shelves": dict(C(str(r["shelf"]) for r in rows)),
        "builtin_commercials": dict(C(r["builtin_commercials"] for r in rows
                                      if r["kind"] in {"tv_recording", "tv_episode"})),
        "with_box_art": sum(1 for r in rows if r["artwork"]["box"]),
        "with_tmdb_poster_only": sum(1 for r in rows if r["artwork"]["use"] == "tmdb_poster"),
        "review_worries": dict(C(re.sub(r"\(.*|: .*| is [a-z0-9]+,", "", w).strip()
                                 for r in rows if r["verdict"] == "review" for w in r["verdict_why"]).most_common(15)),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--model-dir", required=True, help="folder with recordings/archive-items/artwork/titles .jsonl")
    ap.add_argument("--audio", help="audio-checks.jsonl from the sound & picture check")
    ap.add_argument("--output-dir", required=True)
    a = ap.parse_args()
    d = Path(a.model_dir)
    probes = {}
    if a.audio and Path(a.audio).is_file():
        for row in read_jsonl(Path(a.audio)):
            probes[row["file_id"]] = row
    rows = sort_all(read_jsonl(d / "recordings.jsonl"), read_jsonl(d / "archive-items.jsonl"),
                    read_jsonl(d / "artwork.jsonl"), read_jsonl(d / "titles.jsonl"), probes)
    out = Path(a.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    with (out / "auto-sort.jsonl").open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    ads = build_ad_list(rows)
    with (out / "ads.jsonl").open("w", encoding="utf-8") as fh:
        for a_ in ads:
            fh.write(json.dumps(a_, ensure_ascii=False, sort_keys=True) + "\n")
    s = summarize(rows)
    s["ad_list"] = {"entries": len(ads), "by_kind": dict(Counter(x["kind"] for x in ads)),
                    "playable": sum(1 for x in ads if x["verdict"] != "bad"),
                    "by_decade": dict(sorted(Counter(x["decade"] or "unknown" for x in ads).items())),
                    "holiday": dict(Counter(h for x in ads for h in x["holidays"]))}
    (out / "auto-sort.summary.json").write_text(json.dumps(s, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(s, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
