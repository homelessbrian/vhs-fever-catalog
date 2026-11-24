#!/usr/bin/env python3
"""VHS FEVER catalog model: Archive item -> file -> recording -> edition -> title.

This replaces the "one video file = one program" model. It:

* keeps every Archive file, with its exact original name, as an ArchiveFile;
* reads each video filename into a structured interpretation (title words,
  tape/disc release year, media format, version label, part number, Archive
  derivative suffix) without ever rewriting the original name;
* groups an Archive-generated derivative (``.ia.mp4`` etc.) with the original
  it was made from, using the Archive's own ``original`` field;
* treats each original video file as a separate Recording (one capture of
  one physical tape or airing); byte-identical uploads in different Archive
  items (same md5) collapse into one Recording, different files never do;
* joins ``NNofNN`` split files into one multi-part Recording, in order;
* suggests Editions (same title words + same media + same release year) and
  Titles (same title words). Every suggestion is ``suggested`` until a person
  confirms it. Nothing is auto-approved, nothing is deleted.

Input is either raw Archive ``/metadata`` file lists or the existing
``latest.jsonl`` inventory records. Standard library only.
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import unicodedata
import urllib.parse
from collections import Counter, defaultdict
from pathlib import PurePosixPath
from typing import Any, Iterable

SCHEMA_VERSION = 2

VIDEO_EXTS = {".mp4", ".m4v", ".mpg", ".mpeg", ".avi", ".mkv", ".mov", ".wmv",
              ".flv", ".webm", ".ogv", ".ts", ".vob", ".divx", ".3gp", ".mpeg4"}
VIDEO_FORMATS = {"mpeg4", "h.264", "h.264 ia", "512kb mpeg4", "cinepack", "mpeg2", "mpeg1",
                 "ogg video", "matroska", "quicktime", "windows media", "flash video",
                 "divx", "avi", "webm"}
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".tif", ".tiff", ".bmp"}

# Archive derivative markers placed before the real extension.
DERIVATIVE_SUFFIXES = (".ia", "_512kb", ".512kb")

MEDIA_WORDS = {
    "vhs": "VHS",
    "dvd": "DVD",
    "blu-ray": "BLU-RAY", "bluray": "BLU-RAY", "blu ray": "BLU-RAY",
    "laserdisc": "LASERDISC", "laser disc": "LASERDISC",
    "betamax": "BETAMAX", "beta": "BETAMAX",
    "vcd": "VCD",
    "tv": "BROADCAST", "broadcast": "BROADCAST",
    "tape": "TAPE_UNSPECIFIED",
}
MEDIA_RE = "|".join(sorted((re.escape(k) for k in MEDIA_WORDS), key=len, reverse=True))

# "(1992 VHS)", "(199 VHS)", "(2000 vHS)"
RELEASE_GROUP = re.compile(rf"^\s*(?P<year>\d{{3,4}})\s*(?P<media>{MEDIA_RE})\s*$", re.I)
# "(VHS 1992)"
RELEASE_GROUP_REV = re.compile(rf"^\s*(?P<media>{MEDIA_RE})\s*(?P<year>\d{{3,4}})\s*$", re.I)
VERSION_GROUP = re.compile(r"^\s*(?:version|ver\.?|v)\s*#?\s*(?P<n>\d+)\s*$", re.I)
YEAR_GROUP = re.compile(r"^\s*(?P<year>(?:18|19|20)\d{2})(?P<letter>[a-z])?\s*$", re.I)
PART_SUFFIX = re.compile(r"\s*[-_]?\s*(?:part\s*)?(?P<i>\d{1,3})\s*of\s*(?P<n>\d{1,3})\s*$", re.I)
PART_GROUP = re.compile(r"^\s*(?:part|pt\.?|disc|disk|tape)\s*(?P<i>\d{1,3})(?:\s*of\s*(?P<n>\d{1,3}))?\s*$", re.I)
LEADING_YEAR = re.compile(r"^\s*\((?P<year>(?:18|19|20)\d{2})(?P<letter>[a-z])?\)\s*")
LEADING_YEAR_DASH = re.compile(r"^\s*(?P<year>(?:18|19|20)\d{2})\s*-\s+")
TRAILING_YEAR = re.compile(r"\s+(?P<year>(?:18|19|20)\d{2})\s*$")
# Mini-Classics style: "... 1983 Tape"
TRAILING_YEAR_MEDIA = re.compile(rf"\s+(?P<year>(?:18|19|20)\d{{2}})\s+(?P<media>{MEDIA_RE})\s*$", re.I)


EPISODE = re.compile(r"\b[Ss](?P<s>\d{1,2})\s*[Ee](?P<e>\d{1,3})\b")
SCENE_TAGS = re.compile(r"\b(?:WEB[-. ]?DL|WEBRip|HDTV|DVDRip|BDRip|BluRay|AAC\d?(?:\.\d)?|AC3|DD\d\.\d|"
                        r"[xh]\.?26[45]|HEVC|XviD|DivX|480p|576p|720p|1080p|2160p)\b.*$", re.I)
TRAILING_PART = re.compile(r"^\s*(?:part|pt\.?)\s*(?P<i>\d{1,3})\s*$", re.I)
OPENING = re.compile(r"^\s*(?:opening|closing)\s+(?:to|of)\s+", re.I)


def _hash(prefix: str, *parts: str) -> str:
    return prefix + hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()[:16]


def archive_file_id(identifier: str, name: str) -> str:
    """Same formula as the legacy ``program_id``, so old rows map 1:1."""
    return _hash("vhsf-program-", identifier + "\n" + name)


def title_key(text: str) -> str:
    """Normalisation used only for *suggesting* groups; never displayed."""
    t = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    t = t.casefold().replace("&", " and ")
    t = re.sub(r"live[\s_-]*action", "live action", t)
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return " ".join(t.split())


# ---------------------------------------------------------------------------
# Filename interpretation
# ---------------------------------------------------------------------------

def _split_groups(text: str) -> tuple[str, list[tuple[str, int]], list[str]]:
    """Split ``Base (a) (b)`` into base text and ordered parenthetical groups.

    Returns (base_text, [(group_text, position_index)], flags). Text that sits
    between or after groups is kept and appended to the group list as a
    ``loose`` entry so nothing is silently discarded.
    """
    flags: list[str] = []
    depth = 0
    base_chars: list[str] = []
    groups: list[tuple[str, int]] = []
    current: list[str] = []
    seen_group = False
    loose: list[str] = []
    for ch in text:
        if ch == "(":
            if depth == 0:
                current = []
            else:
                current.append(ch)
            depth += 1
            seen_group = True
        elif ch == ")":
            if depth == 0:
                flags.append("unbalanced_parentheses")
                continue
            depth -= 1
            if depth == 0:
                groups.append(("".join(current), len(groups)))
            else:
                current.append(ch)
        elif depth > 0:
            current.append(ch)
        elif seen_group:
            loose.append(ch)
        else:
            base_chars.append(ch)
    if depth != 0:
        flags.append("unbalanced_parentheses")
        groups.append(("".join(current), len(groups)))
    loose_text = "".join(loose).strip(" -_")
    if loose_text:
        if not TRAILING_PART.match(loose_text):
            flags.append("text_after_parentheses")
        groups.append((loose_text, len(groups)))
    return "".join(base_chars).strip(), groups, flags


def interpret_filename(name: str) -> dict[str, Any]:
    """Structured reading of one Archive filename. ``original_name`` is exact."""
    path = PurePosixPath(name)
    folder = str(path.parent) if str(path.parent) != "." else None
    stem, ext = path.stem, path.suffix.lower()
    derivative_suffix = None
    for marker in DERIVATIVE_SUFFIXES:
        if stem.lower().endswith(marker):
            derivative_suffix = stem[-len(marker):]
            stem = stem[: -len(marker)]
            break

    flags: list[str] = []
    work_year: str | None = None
    release_year: str | None = None
    media: str | None = None
    media_raw: str | None = None
    version: str | None = None
    part_index: int | None = None
    part_total: int | None = None
    qualifiers: list[str] = []
    edition_notes: list[str] = []
    unclassified: list[str] = []

    text = stem
    content_hint: str | None = None
    season = episode = None
    if " " not in text and text.count(".") >= 2:
        text = text.replace(".", " ")
        flags.append("dotted_release_name")
    if SCENE_TAGS.search(text):
        if re.search(r"WEB[-. ]?DL|WEBRip", text, re.I):
            media_raw_hint = "WEB"
        else:
            media_raw_hint = None
        text = SCENE_TAGS.sub("", text).strip()
        flags.append("scene_style_encoding_tags_removed")
        if media_raw_hint:
            flags.append("web_download_not_tape")
    em = EPISODE.search(text)
    if em:
        season, episode = int(em["s"]), int(em["e"])
        content_hint = "tv_episode"
    if OPENING.match(text):
        content_hint = "tape_opening_or_previews"
    if re.search(r"\bfan[\s-]?made\b", text, re.I):
        flags.append("fan_made_not_official_release")
    if ext == ".vob" or (folder and "VIDEO_TS" in folder.upper()):
        flags.append("dvd_video_ts_file")
    m = PART_SUFFIX.search(text)
    if m and int(m["n"]) > 1:
        part_index, part_total = int(m["i"]), int(m["n"])
        text = text[: m.start()]

    m = LEADING_YEAR.match(text)
    if m:
        work_year = m["year"]
        if m["letter"]:
            flags.append("year_has_letter_suffix")
        text = text[m.end():]
    else:
        m = LEADING_YEAR_DASH.match(text)
        if m:
            work_year = m["year"]
            text = text[m.end():]

    base, groups, gflags = _split_groups(text)
    flags += gflags

    if not groups:
        m = TRAILING_YEAR_MEDIA.search(base)
        if m:
            release_year, media_raw = m["year"], m["media"]
            base = base[: m.start()]
        elif work_year is None:
            m = TRAILING_YEAR.search(base)
            if m:
                work_year = m["year"]
                base = base[: m.start()]

    release_seen = release_year is not None
    for raw, _pos in groups:
        g = raw.strip()
        rm = RELEASE_GROUP.match(g) or RELEASE_GROUP_REV.match(g)
        if rm and not release_seen:
            release_seen = True
            media_raw = rm["media"]
            if len(rm["year"]) == 4 and rm["year"][:2] in {"18", "19", "20"}:
                release_year = rm["year"]
            else:
                flags.append("release_year_malformed")
            continue
        vm = VERSION_GROUP.match(g)
        if vm:
            if version is not None:
                flags.append("multiple_version_labels")
            version = vm["n"]
            continue
        tp = TRAILING_PART.match(g)
        if tp and part_index is None:
            part_index = int(tp["i"])
            continue
        pm = PART_GROUP.match(g)
        if pm and part_index is None:
            part_index = int(pm["i"])
            part_total = int(pm["n"]) if pm["n"] else None
            continue
        ym = YEAR_GROUP.match(g)
        if ym and work_year is None and not release_seen:
            work_year = ym["year"]
            if ym["letter"]:
                flags.append("year_has_letter_suffix")
            continue
        if not g:
            flags.append("empty_parentheses")
            continue
        # Before the release annotation it usually distinguishes the work
        # ("Live Action"); after it, it describes the copy ("Canadian Copy").
        (edition_notes if release_seen else qualifiers).append(g)

    if media_raw is not None:
        media = MEDIA_WORDS[media_raw.casefold()]
        if media_raw not in {media_raw.upper(), media_raw.capitalize()}:
            flags.append("media_label_irregular_case")
        if media == "TAPE_UNSPECIFIED":
            flags.append("tape_format_not_stated")

    title = re.sub(r"[_]+", " ", base)
    title = re.sub(r"\s+", " ", title).strip(" -_.,")
    episode_title = None
    if season is not None:
        em = EPISODE.search(title)
        if em:
            episode_title = title[em.end():].strip(" -_.,") or None
            title = title[: em.start()].strip(" -_.,")
    if len(re.sub(r"[^a-z0-9]", "", title.casefold())) < 2:
        flags.append("no_usable_title")
        title = None
    if media is None:
        flags.append("no_media_format_in_filename")
    if folder:
        flags.append("file_in_subfolder")
    if qualifiers and media is not None:
        # e.g. "Walt Disney Gold Classic Collection" before "(2000 DVD)":
        # could be part of the title or an edition line; a person decides.
        unclassified = list(qualifiers)

    return {
        "original_name": name,
        "folder": folder,
        "extension": ext,
        "archive_derivative_suffix": derivative_suffix,
        "title": title,
        "title_qualifiers": qualifiers,
        "work_year": work_year,
        "release_year": release_year,
        "media_format": media,
        "media_label_raw": media_raw,
        "version_label": version,
        "part_index": part_index,
        "part_total": part_total,
        "edition_notes": edition_notes,
        "needs_person_to_classify": unclassified,
        "content_hint": content_hint,
        "season": season,
        "episode": episode,
        "episode_title": episode_title,
        "flags": sorted(set(flags)),
    }


# ---------------------------------------------------------------------------
# Input normalisation
# ---------------------------------------------------------------------------

def _num(v: Any) -> float | None:
    try:
        return float(v) if v not in (None, "") else None
    except (TypeError, ValueError):
        return None


def items_from_inventory(records: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Convert legacy latest.jsonl records into the raw Archive item shape."""
    items: dict[str, dict[str, Any]] = {}
    for r in records:
        inv = r.get("vhs_fever_file_inventory")
        if not inv or inv.get("inventory_status") != "ok":
            continue
        files = []
        for v in inv.get("video_files") or []:
            files.append({
                "name": v.get("name"), "format": v.get("format"),
                "source": v.get("source_hint") or v.get("origin_hint"),
                "original": v.get("original_file_reference"),
                "length": v.get("duration_seconds"), "size": v.get("size_bytes"),
                "md5": v.get("md5"), "private": v.get("private"),
            })
        for im in inv.get("image_files") or []:
            files.append({"name": im.get("name"), "format": im.get("format"),
                          "source": im.get("source_hint"), "size": im.get("size_bytes"),
                          "md5": im.get("md5")})
        items[r["identifier"]] = {
            "identifier": r["identifier"], "title": r.get("title"),
            "collection": r.get("collection"), "files": files,
            "access_restricted": inv.get("access_restricted"),
        }
    return list(items.values())


def _is_video(f: dict[str, Any]) -> bool:
    if PurePosixPath(str(f.get("name", ""))).suffix.lower() in VIDEO_EXTS:
        return True
    return str(f.get("format") or "").casefold() in VIDEO_FORMATS


def _is_image(f: dict[str, Any]) -> bool:
    return PurePosixPath(str(f.get("name", ""))).suffix.lower() in IMAGE_EXTS


ARTWORK_ROLES = [
    ("vhs_front", re.compile(r"(front\s*cover|cover\s*front|vhsfront|vhscoverfront|[-_ ]front\b)", re.I)),
    ("vhs_back", re.compile(r"(back\s*cover|cover\s*back|vhsback|vhscoverback|[-_ ]back\b)", re.I)),
    ("vhs_spine", re.compile(r"spine", re.I)),
    ("vhs_label", re.compile(r"(label|cassette)", re.I)),
    ("vhs_wrap", re.compile(r"(wrap|full\s*cover|sleeve)", re.I)),
]


def _artwork_role(name: str) -> str | None:
    for role, rx in ARTWORK_ROLES:
        if rx.search(name):
            return role
    return None


# ---------------------------------------------------------------------------
# Model build
# ---------------------------------------------------------------------------

def build(items: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    archive_items: list[dict[str, Any]] = []
    archive_files: list[dict[str, Any]] = []
    artwork: list[dict[str, Any]] = []
    legacy_map: list[dict[str, Any]] = []

    # piece_key -> piece dict (one original file's bytes, plus its encodes)
    pieces: dict[str, dict[str, Any]] = {}

    for item in items:
        ident = item["identifier"]
        files = [f for f in item.get("files") or [] if str(f.get("name") or "")]
        by_name = {f["name"]: f for f in files}
        archive_items.append({
            "archive_identifier": ident,
            "archive_title": item.get("title"),
            "collection": item.get("collection"),
            "access_restricted": bool(item.get("access_restricted")),
            "archive_url": f"https://archive.org/details/{ident}",
            "video_file_count": sum(_is_video(f) for f in files),
            "uploaded_image_count": sum(_is_image(f) and f.get("source") == "original"
                                        and not f["name"].startswith("__ia_thumb") for f in files),
            "note": "Container only. Its title does not identify the videos inside it.",
        })
        video_stems: dict[str, str] = {}
        for f in files:
            name = f["name"]
            fid = archive_file_id(ident, name)
            is_video = _is_video(f)
            source = str(f.get("source") or "").lower() or None
            original = f.get("original")
            record = {
                "file_id": fid,
                "archive_identifier": ident,
                "original_name": name,
                "kind": "video" if is_video else ("image" if _is_image(f) else "other"),
                "format": f.get("format"),
                "archive_source": source,
                "derived_from": original,
                "size_bytes": int(_num(f.get("size")) or 0) or None,
                "duration_seconds": _num(f.get("length")),
                "md5": f.get("md5"),
                "private": str(f.get("private", "")).lower() in {"true", "1"},
                "download_url": "https://archive.org/download/" + ident + "/" + "/".join(
                    urllib.parse.quote(p) for p in name.split("/")),
            }
            if is_video:
                record["interpretation"] = interpret_filename(name)
                legacy_map.append({"legacy_program_id": fid, "file_id": fid})
            archive_files.append(record)

            if not is_video:
                continue
            # Which original file's bytes does this video belong to?
            if source == "derivative" and original and original in by_name:
                root_name = original
                role = "archive_derivative"
            else:
                root_name = name
                role = "original" if source == "original" else "unverified_original"
            root = by_name.get(root_name, f)
            root_md5 = root.get("md5")
            piece_key = ("md5:" + root_md5) if root_md5 else ("file:" + ident + "/" + root_name)
            piece = pieces.setdefault(piece_key, {
                "piece_key": piece_key, "original_files": [], "sources": [],
            })
            if role != "archive_derivative":
                piece["original_files"].append({"file_id": fid, "archive_identifier": ident,
                                                "original_name": name})
                video_stems[PurePosixPath(name).stem] = piece_key
            piece["sources"].append({
                "source_id": fid, "archive_identifier": ident, "original_name": name,
                "role": role, "format": f.get("format"),
                "duration_seconds": _num(f.get("length")),
                "playback_url": record["download_url"],
                "browser_playable": PurePosixPath(name).suffix.lower() in {".mp4", ".m4v", ".webm", ".ogv"},
            })

        # Packaging images uploaded next to a specific video (same stem prefix).
        for f in files:
            if not _is_image(f) or f.get("source") != "original" or f["name"].startswith("__ia_thumb"):
                continue
            stem = PurePosixPath(f["name"]).stem
            match = None
            for vstem, pk in sorted(video_stems.items(), key=lambda kv: -len(kv[0])):
                if stem.startswith(vstem):
                    match = pk
                    break
            artwork.append({
                "artwork_id": archive_file_id(ident, f["name"]),
                "archive_identifier": ident, "original_name": f["name"],
                "category": "authentic_packaging_candidate",
                "role_guess": _artwork_role(stem) or "unknown",
                "linked_piece_key": match,
                "link_basis": "filename_prefix_matches_video" if match else "none_unassigned",
                "image_url": "https://archive.org/download/" + ident + "/" + "/".join(urllib.parse.quote(x) for x in f["name"].split("/")),
                "review_status": "needs_review",
            })

    # ---- Recordings: single pieces, or ordered NNofNN parts -----------------
    for p in pieces.values():
        first = p["original_files"][0] if p["original_files"] else p["sources"][0]
        p["interpretation"] = interpret_filename(first["original_name"])
        p["also_named"] = sorted({o["original_name"] for o in p["original_files"]})
        p["archive_identifiers"] = sorted({s["archive_identifier"] for s in p["sources"]})
        durs = [s["duration_seconds"] for s in p["sources"] if s["duration_seconds"]]
        p["duration_seconds"] = max(durs) if durs else None
        if len(p["also_named"]) > 1:
            p.setdefault("flags", []).append("identical_bytes_under_different_names")

    recordings: list[dict[str, Any]] = []
    multipart: dict[tuple, list[dict[str, Any]]] = defaultdict(list)
    for p in pieces.values():
        it = p["interpretation"]
        if it["part_index"] is not None:
            ident = p["archive_identifiers"][0]
            first_name = (p["original_files"] or p["sources"])[0]["original_name"]
            whole = PurePosixPath(first_name).stem
            for marker in DERIVATIVE_SUFFIXES:
                if whole.lower().endswith(marker):
                    whole = whole[: -len(marker)]
                    break
            whole = PART_SUFFIX.sub("", whole)
            whole = re.sub(r"\s*(?:part|pt\.?)\s*\d{1,3}\s*$", "", whole, flags=re.I)
            multipart[(ident, whole.strip(), it["part_total"])].append(p)
        else:
            recordings.append(_recording([p], it))
    for (ident, whole, total), plist in multipart.items():
        plist.sort(key=lambda x: x["interpretation"]["part_index"])
        rec = _recording(plist, plist[0]["interpretation"])
        found = [x["interpretation"]["part_index"] for x in plist]
        if total is None:
            rec["flags"].append("part_total_unknown")
            if found != list(range(1, len(found) + 1)):
                rec["flags"].append("part_numbers_have_gaps")
        elif found != list(range(1, total + 1)):
            rec["flags"].append(f"parts_incomplete_{len(found)}_of_{total}")
        recordings.append(rec)

    # ---- Edition and title suggestions ---------------------------------------
    editions: dict[str, dict[str, Any]] = {}
    titles: dict[str, dict[str, Any]] = {}
    for rec in recordings:
        it = rec["interpretation"]
        if not it["title"]:
            rec["edition_suggestion_id"] = None
            rec["unassigned_reason"] = "no_usable_title"
            continue
        tkey = title_key(" ".join([it["title"], *it["title_qualifiers"]]))
        display = " ".join([it["title"], *(f"({q})" for q in it["title_qualifiers"])])
        series = None
        if it.get("season") is not None:
            # Each episode is its own program; the series is shared context.
            series = {"series_title": it["title"], "season": it["season"], "episode": it["episode"]}
            code = f"S{it['season']:02d}E{it['episode']:02d}"
            tkey = f"{tkey}|{code}"
            display = f"{it['title']} {code}" + (f" {it['episode_title']}" if it.get("episode_title") else "")
        title = titles.setdefault(tkey, {
            "title_suggestion_id": _hash("vhsf-title-sugg-", tkey),
            "display_title": display,
            "content_hint": it.get("content_hint"),
            "series": series,
            "match_key": tkey, "work_years_seen": [], "edition_suggestion_ids": [],
            "unassigned_recording_ids": [], "tmdb": {"status": "not_searched",
                                                    "search_title": it["title"]},
            "review_status": "suggested",
        })
        if it["work_year"] and it["work_year"] not in title["work_years_seen"]:
            title["work_years_seen"].append(it["work_year"])
        rec["title_suggestion_id"] = title["title_suggestion_id"]
        if not (it["media_format"] and it["release_year"]):
            rec["edition_suggestion_id"] = None
            rec["unassigned_reason"] = "media_or_release_year_missing"
            title["unassigned_recording_ids"].append(rec["recording_id"])
            continue
        ekey = f"{tkey}|{it['media_format']}|{it['release_year']}"
        ed = editions.get(ekey)
        if ed is None:
            ed = editions[ekey] = {
                "edition_suggestion_id": _hash("vhsf-edition-sugg-", ekey),
                "title_suggestion_id": title["title_suggestion_id"],
                "display_title": title["display_title"],
                "media_format": it["media_format"],
                "release_year": it["release_year"],
                "is_vhs": it["media_format"] == "VHS",
                "edition_notes_seen": [], "recording_ids": [], "artwork_ids": [],
                "basis": "same title words + same media format + same release year in filename",
                "review_status": "suggested",
            }
            title["edition_suggestion_ids"].append(ed["edition_suggestion_id"])
        for note in it["edition_notes"]:
            if note not in ed["edition_notes_seen"]:
                ed["edition_notes_seen"].append(note)
        ed["recording_ids"].append(rec["recording_id"])
        rec["edition_suggestion_id"] = ed["edition_suggestion_id"]

    # Edition notes like "Canadian Copy" may be a different release: flag.
    for ed in editions.values():
        if ed["edition_notes_seen"]:
            ed.setdefault("flags", []).append("recordings_carry_edition_notes_check_if_separate_release")

    # Near-identical title keys (typos such as "Dalmatisns"): hint only.
    keys = sorted(titles)
    for i, a in enumerate(keys):
        for b in keys[i + 1:]:
            if a[:1] == b[:1] and difflib.SequenceMatcher(None, a, b).ratio() >= 0.9:
                titles[a].setdefault("possibly_same_as", []).append(titles[b]["title_suggestion_id"])
                titles[b].setdefault("possibly_same_as", []).append(titles[a]["title_suggestion_id"])

    for rec in recordings:
        rec["catalog_category"], rec["category_reasons"] = categorize(rec)

    # Artwork -> edition via recording.
    piece_to_rec = {pk: r for r in recordings for pk in r["piece_keys"]}
    ed_by_id = {e["edition_suggestion_id"]: e for e in editions.values()}
    for a in artwork:
        rec = piece_to_rec.get(a["linked_piece_key"])
        a["recording_id"] = rec["recording_id"] if rec else None
        if rec and rec.get("edition_suggestion_id"):
            ed = ed_by_id[rec["edition_suggestion_id"]]
            ed["artwork_ids"].append(a["artwork_id"])
            if ed["media_format"] == "DVD":
                a["category"] = "dvd_packaging_candidate"
            elif ed["media_format"] not in {"VHS", "TAPE_UNSPECIFIED"}:
                a["category"] = "other_media_packaging_candidate"
        if rec:
            rec["artwork_ids"].append(a["artwork_id"])

    return {
        "archive_items": archive_items,
        "archive_files": archive_files,
        "recordings": recordings,
        "editions": list(editions.values()),
        "titles": list(titles.values()),
        "artwork": artwork,
        "legacy_program_map": legacy_map,
    }


NOT_A_RELEASE_FLAGS = {
    "fan_made_not_official_release": "fan-made, not an official release",
    "web_download_not_tape": "web download, not a tape",
    "dvd_video_ts_file": "raw DVD folder file",
    "tape_format_not_stated": "tape format not stated in filename",
    "release_year_malformed": "release year in filename is malformed",
    "no_usable_title": "filename has no usable title",
}


def categorize(rec: dict[str, Any]) -> tuple[str, list[str]]:
    """Which shelf a recording belongs on. Nothing is removed; anything that is
    not clearly a VHS (or other physical-media) release goes to manual review."""
    it = rec["interpretation"]
    reasons = [label for flag, label in NOT_A_RELEASE_FLAGS.items() if flag in rec["flags"]]
    if it.get("content_hint") == "tv_episode":
        reasons.append("TV episode, not a home-video release")
    if it.get("content_hint") == "tape_opening_or_previews" and not reasons and it["media_format"] == "VHS":
        return "vhs_opening_or_previews", []
    if not it["media_format"]:
        reasons.append("no VHS/DVD format in filename")
    elif not it["release_year"]:
        reasons.append("no release year in filename")
    if reasons:
        return "manual_review", sorted(set(reasons))
    if it["media_format"] == "VHS":
        return "vhs_release", []
    return "other_media_release", [f"{it['media_format']} release, not VHS"]


def _recording(pieces: list[dict[str, Any]], it: dict[str, Any]) -> dict[str, Any]:
    keys = [p["piece_key"] for p in pieces]
    flags = sorted({f for p in pieces for f in p.get("flags", [])} | set(it["flags"]))
    durs = [p["duration_seconds"] for p in pieces]
    segments = [{
        "order": i + 1,
        "kind": "part_file" if len(pieces) > 1 else "whole_recording",
        "piece_key": p["piece_key"],
        "duration_seconds": p["duration_seconds"],
        "sources": p["sources"],
    } for i, p in enumerate(pieces)]
    return {
        "recording_id": _hash("vhsf-rec-", *keys),
        "piece_keys": keys,
        "interpretation": it,
        "display_name": pieces[0]["also_named"][0] if pieces[0]["also_named"] else pieces[0]["sources"][0]["original_name"],
        "archive_identifiers": sorted({a for p in pieces for a in p["archive_identifiers"]}),
        "also_named": sorted({n for p in pieces for n in p["also_named"]}),
        "part_count": len(pieces),
        "duration_seconds": round(sum(durs), 2) if all(durs) else None,
        "segments": segments,
        "source_count": sum(len(p["sources"]) for p in pieces),
        "identity_basis": "md5" if all(k.startswith("md5:") for k in keys) else "archive_file_name",
        "program_segments": [],  # timestamps of programs/commercials, filled by review
        "artwork_ids": [],
        "flags": flags,
        "review_status": "suggested",
    }


def summarize(model: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    recs = model["recordings"]
    return {
        "schema_version": SCHEMA_VERSION,
        "archive_items": len(model["archive_items"]),
        "video_files": sum(f["kind"] == "video" for f in model["archive_files"]),
        "recordings": len(recs),
        "recordings_without_edition": sum(r.get("edition_suggestion_id") is None for r in recs),
        "recordings_by_category": dict(sorted(Counter(r["catalog_category"] for r in recs).items())),
        "multipart_recordings": sum(r["part_count"] > 1 for r in recs),
        "edition_suggestions": len(model["editions"]),
        "vhs_edition_suggestions": sum(e["is_vhs"] for e in model["editions"]),
        "title_suggestions": len(model["titles"]),
        "artwork_candidates": len(model["artwork"]),
        "confirmed_titles": 0,
        "files_deleted": 0,
    }


def fetch_items(identifiers: Iterable[str], cache_dir: str | None = None,
                delay: float = 0.5) -> list[dict[str, Any]]:
    """Read-only Archive /metadata fetch (keeps md5 checksums). Cached on disk."""
    import time
    import urllib.request
    from pathlib import Path
    items = []
    cache = Path(cache_dir) if cache_dir else None
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
    for ident in identifiers:
        path = cache / (ident.replace("/", "_") + ".json") if cache else None
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
        else:
            url = "https://archive.org/metadata/" + urllib.parse.quote(ident, safe="")
            req = urllib.request.Request(url, headers={"User-Agent": "VHS-FEVER-Catalog/0.3 (metadata only)"})
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(req, timeout=60) as resp:
                        data = json.load(resp)
                    break
                except Exception:  # noqa: BLE001 - retry transient network errors
                    if attempt == 2:
                        raise
                    time.sleep(2 * (attempt + 1))
            if path:
                path.write_text(json.dumps(data), encoding="utf-8")
            time.sleep(delay)
        md = data.get("metadata") or {}
        items.append({
            "identifier": ident, "title": md.get("title"),
            "collection": md.get("collection"),
            "access_restricted": bool(data.get("is_dark")) or str(md.get("access-restricted-item", "")).lower() == "true",
            "files": data.get("files") or [],
        })
    return items


def _read_jsonl(path: str) -> list[dict[str, Any]]:
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--inventory", help="legacy latest.jsonl with file inventories")
    src.add_argument("--archive-json", help="JSON file: {items:[{identifier,title,files:[...]}]}")
    src.add_argument("--fetch-from-inventory", help="latest.jsonl; re-read those items live from the Archive (with checksums)")
    ap.add_argument("--cache-dir", help="where to cache Archive metadata responses")
    ap.add_argument("--output-dir", required=True)
    args = ap.parse_args()
    if args.inventory:
        items = items_from_inventory(_read_jsonl(args.inventory))
    elif args.fetch_from_inventory:
        idents = [i["identifier"] for i in items_from_inventory(_read_jsonl(args.fetch_from_inventory))]
        items = fetch_items(idents, args.cache_dir)
    else:
        with open(args.archive_json, encoding="utf-8") as fh:
            items = json.load(fh)["items"]
    model = build(items)
    from pathlib import Path
    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    for name, rows in model.items():
        with (out / f"{name.replace('_', '-')}.jsonl").open("w", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    summary = summarize(model)
    (out / "catalog-model.summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
