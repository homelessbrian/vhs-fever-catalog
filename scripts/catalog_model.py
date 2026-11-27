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

import tv_tapes

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
# "(2002 Australian VHS)", "(1999 UK VHS)": a country between year and format.
REGION_WORDS = re.compile(r"\b(?:US|USA|U\.S\.A?\.?|UK|U\.K\.|British|English|American|Australian|Australia|AUS|"
                          r"Canadian|Canada|CAN|NZ|New Zealand|Irish|PAL|NTSC|Original|Rental|Retail)\b", re.I)
# A numbered file at least this long is a whole movie, not a piece of one.
FULL_LENGTH_SECONDS = 40 * 60
SPLIT_PART = re.compile(r"^(?P<base>.+?)\s+(?:part|pt\.?|clip|segment)\s*#?(?P<i>\d{1,3})(?:\s*[-:]\s+.*)?$", re.I)
CAPTURE_NUMBER = re.compile(r"\s+-\s+0\d{2,3}\s*$")
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
# Every way an episode number shows up in this collection's filenames, most
# specific first: "S03E69", "S1-08", "1x09", "Season 2 Episode 9", "Episode 4".
EPISODE_MARKERS = [
    EPISODE,
    re.compile(r"\b[Ss](?P<s>\d{1,2})-(?P<e>\d{1,3})\b"),
    re.compile(r"(?<![\dx])(?P<s>\d{1,2})x(?P<e>\d{2,3})\b", re.I),
    re.compile(r"\bSeason\s*(?P<s>\d{1,2})[\s,._-]*(?:Episode|Ep\.?)\s*#?(?P<e>\d{1,3})\b", re.I),
    re.compile(r"\b(?:Episode|Ep\.?)\s*#?(?P<e>\d{1,3})\b", re.I),
]
# "Supaidaman/03 - Title": a numbered file inside a folder named for the show.
FOLDER_NUMBER = re.compile(r"^\s*(?P<e>\d{1,3})\s*[-.]\s+(?P<rest>\S.*)$")
AIR_DATES = [
    re.compile(r"\b(?P<y>(?:19|20)\d\d)[-.](?P<m>0?[1-9]|1[0-2])[-.](?P<d>0?[1-9]|[12]\d|3[01])\b"),
    re.compile(r"\b(?P<m>0?[1-9]|1[0-2])[-.](?P<d>0?[1-9]|[12]\d|3[01])[-.](?P<y>(?:19|20)?\d\d)\b"),
]
FOLDER_NOT_SHOW = re.compile(r"^(?:season|series|disc|disk|dvd|volume|vol\.?|tape|part|video_ts|s)\s*\d*$", re.I)
SPACED_DASH = re.compile(r"\s+[-\u2013\u2014]\s+")


def find_episode_marker(text: str) -> tuple[re.Match[str] | None, int | None, int | None]:
    """First episode marker in ``text``: (match, season, episode)."""
    for rx in EPISODE_MARKERS:
        m = rx.search(text)
        if m:
            s = m.groupdict().get("s")
            return m, (int(s) if s else None), int(m["e"])
    return None, None, None


def find_air_date(text: str) -> tuple[re.Match[str] | None, str | None]:
    for rx in AIR_DATES:
        m = rx.search(text)
        if m:
            y = m["y"]
            if len(y) == 2:
                y = ("19" if int(y) > 30 else "20") + y
            return m, f"{y}-{int(m['m']):02d}-{int(m['d']):02d}"
    return None, None


def show_from_folder(folder: str | None) -> str | None:
    """Innermost folder name that names a show ("The Maxx/Season 1" -> "The Maxx")."""
    if not folder:
        return None
    for part in reversed(folder.split("/")):
        clean = re.sub(r"\s+", " ", part.replace("_", " ")).strip(" -.")
        if clean and not FOLDER_NOT_SHOW.match(clean) and len(re.sub(r"[^a-z0-9]", "", clean.casefold())) >= 2:
            return clean
    return None


def episode_title_pieces(text: str | None) -> list[str]:
    """Ways an episode title may be written: whole, and each story of a
    two-story episode ("The Snack Helper   Buds", "Racing Time / All Tied Up")."""
    if not text or re.fullmatch(r"\s*(?:chapter|episode|ep\.?|part|pt\.?)\s*#?\d+\s*", text, re.I):
        return []   # "Chapter 8" says nothing the episode number doesn't
    out = [text.strip()]
    no_tag = re.sub(r"\s+[A-Z]{2,5}$", "", out[0]).strip()
    if no_tag and no_tag != out[0]:
        out.append(no_tag)   # "Binyah Goes to School CBS"
    for piece in re.split(r"\s{2,}|\s*/\s*|\s+[-\u2013]\s+|\s*;\s*", text):
        piece = piece.strip(" -.,!")
        if piece and piece not in out and len(piece) >= 3 and not re.match(r"^(?:volume|vol\.?|part)\s*\d+$", piece, re.I):
            out.append(piece)
    return out
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
    em, season, episode = find_episode_marker(text)
    numbered_in_show_folder = False
    if em is None and folder:
        fm = FOLDER_NUMBER.match(text)
        if fm and show_from_folder(folder) and not PART_SUFFIX.search(text):
            episode = int(fm["e"])
            numbered_in_show_folder = True
    if episode is not None:
        content_hint = "tv_episode"
    _dm, air_date = find_air_date(text)
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

    cm = CAPTURE_NUMBER.search(text)
    if cm:   # "Once Upon a Time 1999 VHS - 0002": the uploader's capture counter
        text = text[: cm.start()]
        flags.append("capture_number_removed")
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
        if not rm and REGION_WORDS.search(g):
            region = " ".join(REGION_WORDS.findall(g))
            rm = RELEASE_GROUP.match(REGION_WORDS.sub(" ", g)) or RELEASE_GROUP_REV.match(REGION_WORDS.sub(" ", g))
            if rm:
                edition_notes.append(region)
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
    if episode is not None:
        # Two-story episodes are often written "Story One   Story Two".
        title = re.sub(r"(?<=\S)\s{2,}(?=\S)", " / ", title)
    title = re.sub(r"\s+", " ", title).strip(" -_.,")
    episode_title = None
    series_title = None
    show_hint = None
    if episode is not None:
        em, _s, _e = find_episode_marker(title)
        if numbered_in_show_folder:
            fm = FOLDER_NUMBER.match(title)
            episode_title = fm["rest"].strip(" -_.,") if fm else None
            title = ""
        elif em:
            episode_title = title[em.end():].strip(" -_.,") or None
            title = title[: em.start()].strip(" -_.,")
        if episode_title:
            dm, _d = find_air_date(episode_title)
            if dm and dm.start() <= 1:
                episode_title = episode_title[dm.end():].strip(" -_.,") or None
        dm, _d = find_air_date(title)
        if dm:
            title = (title[: dm.start()] + " " + title[dm.end():]).strip(" -_.,")
            title = re.sub(r"\s+", " ", title)
        if not title:
            folder_show = show_from_folder(folder)
            if folder_show:
                title = folder_show
                flags.append("show_name_from_folder")
        if not title and episode_title and SPACED_DASH.search(episode_title):
            # "S1-08 - 05-23-01 - That's My Bush! - Fare Thee Welfare"
            left, right = SPACED_DASH.split(episode_title, maxsplit=1)
            title, episode_title = left.strip(" -_.,"), right.strip(" -_.,") or None
        series_title = title or None
    elif content_hint is None and SPACED_DASH.search(title):
        # "Show Name - Episode Name". Only a hint: TMDB decides whether the
        # left side really is a TV show. The title itself is left as is.
        left, right = SPACED_DASH.split(title, maxsplit=1)
        left, right = left.strip(" -_.,"), right.strip(" -_.,")
        if len(re.sub(r"[^a-z0-9]", "", left.casefold())) >= 2 and len(re.sub(r"[^a-z0-9]", "", right.casefold())) >= 2:
            if not re.fullmatch(r"[\d\s.#]+", left):
                show_hint = {"show": left, "episode_title": right}
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
        "series_title": series_title,
        "air_date": air_date,
        "show_dash_hint": show_hint,
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

# ---------------------------------------------------------------------------
# Context from the Archive page a file sits on
# ---------------------------------------------------------------------------
PAGE_FORMAT = [
    (re.compile(r"\bvhs\b|\bvideo ?cassettes?\b|\bvideotapes?\b", re.I), "VHS"),
    (re.compile(r"\btv\s*(?:\+|and|&|/)|\btv (?:rips?|recordings?|captures?)\b|\boff[- ]air\b|\bbroadcasts?\b|\bweb[- ]?(?:dl|rips?)\b", re.I),
     "BROADCAST"),
    (re.compile(r"\bbeta(?:max)?\b", re.I), "BETAMAX"),
    (re.compile(r"\blaser ?discs?\b", re.I), "LASERDISC"),
    (re.compile(r"\bdvds?\b", re.I), "DVD"),
    (re.compile(r"\bblu[- ]?rays?\b", re.I), "BLU-RAY"),
]
# Page titles that say nothing about which show is inside.
GENERIC_PAGE = re.compile(r"\b(?:capture|captures|rips?|misc|miscellaneous|various|random|assorted|mixed|"
                          r"all animated|my|old|home videos?|tapes? \d+|lot|archive|uploads?)\b", re.I)
PAGE_NOISE = re.compile(r"\b(?:vhs|dvd|blu[- ]?ray|laser ?disc|betamax|tapes?|video ?cassettes?|videos?|"
                        r"collection|complete|full|set|series|compilation|releases?|rips?|captures?|"
                        r"(?:19|20)\d\d|uk|us|usa|canadian|australian)\b", re.I)
REGION_TAIL = re.compile(r"(?:\s|^)(CANADA|CANADIAN|UK|USA|US|AUS|AUSTRALIA|AUSTRALIAN|NZ|IRELAND|IRISH|"
                         r"FRENCH|SPANISH|PAL|NTSC)\s*$")


def _as_list(v: Any) -> list[str]:
    if v is None:
        return []
    if isinstance(v, list):
        out = []
        for x in v:
            out += [p.strip() for p in str(x).split(";") if p.strip()]
        return out
    return [p.strip() for p in str(v).split(";") if p.strip()]


def page_context(item: dict[str, Any]) -> dict[str, Any] | None:
    """What the page itself says: the format of everything on it, and which
    show it is about (from its title and tags). "Blue's Clues VHS Collection"
    tagged "blue's clues" -> format VHS, show "Blue's Clues"."""
    title = str(item.get("title") or "")
    if not title:
        return None
    ctx: dict[str, Any] = {}
    found = [fmt for rx, fmt in PAGE_FORMAT if rx.search(title)]
    if len(found) == 1 and found[0] != "BROADCAST":   # "VHS and DVD Capture" names two formats, so it settles nothing
        ctx["format"] = found[0]
        ctx["format_from"] = "page title"
    show = None
    if not GENERIC_PAGE.search(title):
        tkey = title_key(title)
        # A tag that is also in the page title names the show ("blue's clues").
        tags = sorted((t for t in _as_list(item.get("subject")) if len(title_key(t)) >= 3 and "/" not in t
                       and title_key(t) in tkey and title_key(t) not in {"vhs", "dvd", "nick jr", "nickelodeon"}),
                      key=lambda t: -len(t))
        if tags:
            # Use the page title's own spelling of the tag.
            m = re.search(re.escape(tags[0]).replace("\\ ", r"[\s_]+"), title, re.I)
            show = m.group(0) if m else tags[0]
        elif sum(1 for f in item.get("files") or [] if _is_video(f)
                 and str(f.get("source") or "").lower() != "derivative") >= 3:
            # Only a page holding several videos is a show collection; a
            # single-video page's title is the video's own title.
            rest = PAGE_NOISE.sub(" ", re.sub(r"[(\[].*?[)\]]", " ", title))
            rest = re.sub(r"\s+", " ", rest).strip(" -:,&")
            if rest and len(rest.split()) <= 5 and len(rest) >= 4:
                show = rest
    if show:
        show = re.sub(r"\s+", " ", show).strip(" -:")
        words = [w for w in re.findall(r"[A-Za-z0-9']+", show) if w.lower() not in {"the", "of", "and", "a"}]
        ctx["show"] = show
        ctx["show_initials"] = "".join(w[0] for w in words).upper() if len(words) >= 2 else None
    return ctx or None


def _split_glued(text: str) -> str:
    """'StoryTime' -> 'Story Time', 'Arts&Crafts' -> 'Arts & Crafts', 'Abc&123' -> 'Abc & 123'."""
    t = re.sub(r"\s*&\s*", " & ", text)
    t = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", t)
    t = re.sub(r"(?<=[A-Za-z])(?=\d)|(?<=\d)(?=[A-Za-z])", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def _episode_code(title: str, show: str) -> tuple[int, int, str, str | None] | None:
    """"#205. Little Ones [2000]" on a page about one show -> season 2, episode 5,
    "Little Ones", year 2000. The show name may come first ("Ricky's Room #205 -
    Little Ones"). Three digits are season + episode; four digits need a "#"
    and can't look like a year."""
    t = title.strip()
    words = [re.escape(w) for w in re.findall(r"[A-Za-z0-9']+", show)]
    if words:
        m = re.match(r"^\W*" + r"\W+".join(words) + r"\W*", t, re.I)
        if m and m.end() < len(t):
            t = t[m.end():]
    m = re.match(r"^#\s*(?P<code>\d{3,4})\b[\s.):_-]*(?P<rest>\S.*)$", t) \
        or re.match(r"^(?P<code>\d{3})\s*(?:[.)]|\s-)\s*(?P<rest>\S.*)$", t)
    if not m:
        return None
    code = m["code"]
    if len(code) == 4 and re.fullmatch(r"(?:18|19|20)\d\d", code):
        return None
    season, episode = int(code[:-2]), int(code[-2:])
    if season < 1 or episode < 1:
        return None
    rest, year = m["rest"], None
    ym = re.search(r"\s*[\[(]\s*((?:19|20)\d\d)\s*[\])]\s*$", rest)
    if ym:
        year, rest = ym.group(1), rest[: ym.start()]
    rest = re.sub(r"\s+", " ", rest).strip(" -_.,:\"'")
    if len(re.sub(r"[^a-z0-9]", "", rest.casefold())) < 2:
        return None
    return season, episode, rest, year


def _apply_page_context(it: dict[str, Any], ctx: dict[str, Any]) -> None:
    """Fill in what the file name leaves out, from the page. Never overwrites."""
    flags = list(it.get("flags") or [])
    title = it.get("title") or ""
    show = ctx.get("show")
    initials = ctx.get("show_initials")
    if show and title and it.get("episode") is None and it.get("content_hint") is None:
        code = _episode_code(title, show)
        if code:
            # The page is about one show, so a numbered file on it is an episode.
            season, episode, ep_title, year = code
            it.update({"title": show, "series_title": show, "season": season, "episode": episode,
                       "episode_title": ep_title, "content_hint": "tv_episode", "show_dash_hint": None})
            if year and not it.get("work_year"):
                it["work_year"] = year
            flags.append("episode_code_read_with_show_from_page")
            title = show
    # "BCStoryTimeCANADA" -> "Blue's Clues Story Time", note "Canada"
    if initials and title and "episode_code_read_with_show_from_page" not in flags:
        m = re.match(rf"^(?:\d+\s+)?{re.escape(initials)}(?=[A-Z][a-z]|[\s_-])", title)
        if m:
            rest = _split_glued(title[m.end():])
            region = REGION_TAIL.search(rest)
            if region:
                rest = rest[: region.start()]
                it["edition_notes"] = list(dict.fromkeys((it.get("edition_notes") or []) + [region.group(1).title()]))
            rest = rest.strip(" -_")
            if rest:
                it["title"] = f"{show} {rest}"
                flags.append("show_short_form_expanded_from_page")
                title = it["title"]
    elif title and " " not in title.strip() and re.search(r"[a-z][A-Z]", title):
        it["title"] = _split_glued(title)
        title = it["title"]
        flags.append("glued_words_split")
    digital = {"web_download_not_tape", "dvd_video_ts_file", "scene_style_encoding_tags_removed"} & set(flags)
    if not it.get("media_format") and ctx.get("format") and not digital:
        it["media_format"] = ctx["format"]
        it["media_label_raw"] = it.get("media_label_raw") or f"page: {ctx['format']}"
        flags = [f for f in flags if f != "no_media_format_in_filename"]
        flags.append("format_from_page_title")
    it["flags"] = flags


def _fill_from_page_title(it: dict[str, Any], page: dict[str, Any]) -> None:
    """A page holding a single video: its title often says what the file name
    doesn't ("Barney & Friends: Carnival of Numbers (1992 VHS)" for
    "Carnival of Numbers - 0001.mp4"). Fill gaps only; never overwrite."""
    used = False
    if not it.get("media_format") and page.get("media_format"):
        it["media_format"] = page["media_format"]
        it["media_label_raw"] = page.get("media_label_raw")
        it["release_year"] = it.get("release_year") or page.get("release_year")
        it["edition_notes"] = list(dict.fromkeys((it.get("edition_notes") or []) + (page.get("edition_notes") or [])))
        used = True
    ft, pt = title_key(it.get("title") or ""), title_key(page.get("title") or "")
    if pt and ft and ft != pt and ft in pt and it.get("episode") is None:
        it["title"] = page["title"]   # adds the show or series name the file left out
        used = True
    if not it.get("work_year") and page.get("work_year"):
        it["work_year"] = page["work_year"]
        used = True
    if used:
        flags = [f for f in it.get("flags") or [] if f != "no_media_format_in_filename" or not it.get("media_format")]
        it["flags"] = flags + ["filled_from_archive_page_title"]


def _apply_tv_tape(it: dict[str, Any], tv: dict[str, Any], name: str, pieces: list[str]) -> None:
    """A capture file of an off-air TV tape: the page names the shows and air
    dates; the file name ("0002_0102_045252.mp4") says nothing."""
    if name not in pieces:
        it["flags"] = list(it.get("flags") or []) + ["on_tv_tape_page_but_not_a_tape_capture_file"]
        return
    n = pieces.index(name) + 1
    it["title"] = tv_tapes.display_title(tv)
    it["title_qualifiers"] = []
    it["content_hint"] = "off_air_tv_tape"
    it["media_format"] = "BROADCAST"
    it["media_label_raw"] = "page: off-air TV tape"
    it["release_year"] = None
    it["work_year"] = None
    it["air_date"] = tv.get("air_date")
    it["episode"] = it["season"] = it["part_index"] = it["part_total"] = None
    it["tv_tape"] = {
        "tape_number": tv.get("tape_number"), "tape_type": tv.get("tape_type"),
        "shows": tv["shows"], "channels": tv["channels"],
        "air_date": tv.get("air_date"), "air_date_end": tv.get("air_date_end"), "air_dates": tv["air_dates"],
        "air_month": tv.get("air_month"), "lineup": tv["lineup"],
        "piece": n, "pieces": len(pieces), "commercials": tv["commercials"],
        "transcript": next((t for t in tv["transcripts"]
                            if PurePosixPath(t).name.startswith(PurePosixPath(name).stem + ".")), None),
    }
    drop = {"no_media_format_in_filename", "no_usable_title", "tape_format_not_stated", "release_year_malformed"}
    it["flags"] = [f for f in it.get("flags") or [] if f not in drop] + ["off_air_tv_tape_from_archive_page"]


def _adult_marks(item: dict[str, Any], files: list[dict[str, Any]]) -> dict[str, Any]:
    import adult
    cols = _as_list(item.get("collection"))
    text = [item.get("title"), item.get("subject"), item.get("description"), item.get("creator"),
            [f.get("name") for f in files if _is_video(f)]]
    out: dict[str, Any] = {}
    if adult.is_adult(cols, item.get("title"), item.get("subject")):
        out["adult"] = True
        why = adult.blocked(cols, *text)
        if why:
            out["blocked"] = why
    return out


def build(items: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    archive_items: list[dict[str, Any]] = []
    archive_files: list[dict[str, Any]] = []
    artwork: list[dict[str, Any]] = []
    legacy_map: list[dict[str, Any]] = []

    # piece_key -> piece dict (one original file's bytes, plus its encodes)
    pieces: dict[str, dict[str, Any]] = {}
    page_by_item: dict[str, dict[str, Any]] = {}

    ctx_by_item: dict[str, dict[str, Any]] = {}
    # Off-air TV tapes (Oakley Tapes): what the page says about the tape, and
    # its capture files in order (one tape is cut into a few long files).
    tv_by_item: dict[str, dict[str, Any]] = {}
    tv_pieces: dict[str, list[str]] = {}
    for item in items:
        ident = item["identifier"]
        files = [f for f in item.get("files") or [] if str(f.get("name") or "")]
        tv = tv_tapes.read_page(item)
        if tv:
            tv_by_item[ident] = tv
            tv_pieces[ident] = tv_tapes.tape_pieces(
                [f["name"] for f in files if _is_video(f) and str(f.get("source") or "").lower() != "derivative"], tv)
        ctx = None if tv else page_context(item)
        if ctx:
            ctx_by_item[ident] = ctx
        by_name = {f["name"]: f for f in files}
        archive_items.append({
            "archive_identifier": ident,
            "archive_title": item.get("title"),
            "collection": item.get("collection"),
            "page_context": ctx_by_item.get(ident),
            "tv_tape": tv_by_item.get(ident),
            "access_restricted": bool(item.get("access_restricted")),
            # Adult tapes are kept for the apps' red curtain; blocked ones never are (adult.py).
            **_adult_marks(item, files),
            "archive_url": f"https://archive.org/details/{ident}",
            "video_file_count": sum(_is_video(f) for f in files),
            "uploaded_image_count": sum(_is_image(f) and f.get("source") == "original"
                                        and not f["name"].startswith("__ia_thumb") for f in files),
            "note": "Container only. Its title does not identify the videos inside it.",
        })
        video_stems: dict[str, str] = {}
        originals = [f for f in files if _is_video(f) and str(f.get("source") or "").lower() != "derivative"]
        page = interpret_filename(str(item.get("title") or "") + ".mp4") \
            if len(originals) == 1 and item.get("title") and ident not in tv_by_item else None
        if page:
            page_by_item[ident] = page
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
                if page:
                    _fill_from_page_title(record["interpretation"], page)
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
            basis = "none_unassigned"
            for vstem, pk in sorted(video_stems.items(), key=lambda kv: -len(kv[0])):
                if stem.startswith(vstem):
                    match = pk
                    basis = "filename_prefix_matches_video"
                    break
            if match is None and len(set(video_stems.values())) == 1:
                # One tape per page: the scans on the page belong to it.
                match = next(iter(video_stems.values()))
                basis = "only_video_on_its_page"
            artwork.append({
                "artwork_id": archive_file_id(ident, f["name"]),
                "archive_identifier": ident, "original_name": f["name"],
                "category": "authentic_packaging_candidate",
                "role_guess": _artwork_role(stem) or "unknown",
                "linked_piece_key": match,
                "link_basis": basis,
                "image_url": "https://archive.org/download/" + ident + "/" + "/".join(urllib.parse.quote(x) for x in f["name"].split("/")),
                "review_status": "needs_review",
            })

    # ---- Recordings: single pieces, or ordered NNofNN parts -----------------
    for p in pieces.values():
        first = p["original_files"][0] if p["original_files"] else p["sources"][0]
        p["interpretation"] = interpret_filename(first["original_name"])
        page = page_by_item.get(first.get("archive_identifier"))
        if page:
            _fill_from_page_title(p["interpretation"], page)
        ctx = ctx_by_item.get(first.get("archive_identifier"))
        if ctx:
            _apply_page_context(p["interpretation"], ctx)
        tv_ident = first.get("archive_identifier")
        if tv_ident in tv_by_item:
            _apply_tv_tape(p["interpretation"], tv_by_item[tv_ident], first["original_name"], tv_pieces[tv_ident])
        p["also_named"] = sorted({o["original_name"] for o in p["original_files"]})
        p["archive_identifiers"] = sorted({s["archive_identifier"] for s in p["sources"]})
        durs = [s["duration_seconds"] for s in p["sources"] if s["duration_seconds"]]
        p["duration_seconds"] = max(durs) if durs else None
        if len(p["also_named"]) > 1:
            p.setdefault("flags", []).append("identical_bytes_under_different_names")

    # A page about one show that holds numbered episodes of it: its other
    # files are probably episodes too. Only a hint; TMDB/TVDB decides (it
    # must find an episode of that show by the file's name).
    show_pages: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for p in pieces.values():
        first = p["original_files"][0] if p["original_files"] else p["sources"][0]
        show_pages[first.get("archive_identifier")].append(p["interpretation"])
    for ident, its in show_pages.items():
        show = (ctx_by_item.get(ident) or {}).get("show")
        if not show or not any(it.get("episode") is not None and title_key(it.get("series_title") or "") == title_key(show)
                               for it in its):
            continue
        for it in its:
            title = it.get("title") or ""
            if it.get("episode") is not None or it.get("content_hint") or it.get("show_dash_hint") \
                    or it.get("part_index") is not None or title_key(show) in title_key(title):
                continue
            rest = re.sub(r"\s*[\[(]\s*(?:19|20)\d\d\s*[\])]\s*$", "", title).strip(" -_.,")
            if len(re.sub(r"[^a-z0-9]", "", rest.casefold())) >= 2:
                it["show_dash_hint"] = {"show": show, "episode_title": rest, "from": "Archive page show"}

    # "Pocahontas part 01" ... "part 17": pieces of one split video. Without
    # "of N" this looks like a sequel ("Bambi Part 2"), so it only counts when
    # the same page holds at least three numbered parts of the same name.
    split_groups: dict[tuple, list[tuple[dict[str, Any], int, str]]] = defaultdict(list)
    for p in pieces.values():
        it = p["interpretation"]
        if it["part_index"] is not None or not it.get("title"):
            continue
        m = SPLIT_PART.match(it["title"])
        if m:
            first = p["original_files"][0] if p["original_files"] else p["sources"][0]
            split_groups[(first.get("archive_identifier"), title_key(m["base"]))].append((p, int(m["i"]), m["base"]))
    for (_ident, _base), members in split_groups.items():
        if len({i for _p, i, _b in members}) < 3:
            continue
        # "Back to the Future Part 1/2/3": when every numbered file is already
        # movie length, they're sequels on one page, not pieces of one video.
        if all((p.get("duration_seconds") or 0) >= FULL_LENGTH_SECONDS for p, _i, _b in members):
            continue
        for p, i, base in members:
            it = p["interpretation"]
            it["part_index"], it["part_total"] = i, None
            it["split_base"] = base.strip()
            it["title"] = base.strip()
            it["flags"] = list(it["flags"]) + ["split_part_from_numbered_siblings"]

    # Other files on the same page: when nearly every named file on a page is
    # the same format, the few unnamed ones almost certainly are too.
    by_page: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for p in pieces.values():
        first = p["original_files"][0] if p["original_files"] else p["sources"][0]
        by_page[first.get("archive_identifier")].append(p)
    for ident, group in by_page.items():
        named = Counter(p["interpretation"]["media_format"] for p in group if p["interpretation"]["media_format"])
        if len(group) < 5 or not named:
            continue
        fmt, n = named.most_common(1)[0]
        page_fmt = (ctx_by_item.get(ident) or {}).get("format")
        if n < 4 or n / sum(named.values()) < 0.9 or (page_fmt and page_fmt != fmt):
            continue
        if len(group) > 40 and page_fmt != fmt:
            continue   # a big mixed page ("Nick Jr. Tapes/Nick Jr. Shows") proves little
        for p in group:
            it = p["interpretation"]
            digital = {"web_download_not_tape", "dvd_video_ts_file", "scene_style_encoding_tags_removed"} & set(it["flags"])
            late = fmt == "VHS" and any(int(y) >= 2007 for y in re.findall(r"(?<!\d)(20[0-2]\d)(?!\d)", it["original_name"]))
            if not it["media_format"] and not digital and not late and it.get("content_hint") != "tv_episode":
                it["media_format"] = fmt
                it["media_label_raw"] = f"other files on page: {fmt}"
                it["flags"] = [f for f in it["flags"] if f != "no_media_format_in_filename"] + [
                    "format_from_other_files_on_page"]

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
            if it.get("split_base"):
                whole = it["split_base"]
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
        is_episode = it.get("episode") is not None
        if not it["title"] and not (is_episode and it.get("episode_title")):
            rec["edition_suggestion_id"] = None
            rec["unassigned_reason"] = "no_usable_title"
            continue
        series = None
        if is_episode:
            # Each episode is its own program; the series is shared context.
            # The show name may be missing ("Season 2 Episode 15 - Strange Vet
            # Fellows"): the episode title is then all there is to search by.
            series = {"series_title": it["title"], "season": it["season"], "episode": it["episode"],
                      "episode_title": it.get("episode_title"), "air_date": it.get("air_date")}
            code = (f"S{it['season']:02d}" if it["season"] is not None else "") + f"E{it['episode']:02d}"
            show = it["title"] or ""
            tkey = (title_key(" ".join([show, *it["title_qualifiers"]])) if show else "no show "
                    + title_key(it.get("episode_title") or "")) + f"|{code}"
            display = " ".join(x for x in [show, code, it.get("episode_title") or ""] if x)
        else:
            tkey = title_key(" ".join([it["title"], *it["title_qualifiers"]]))
            display = " ".join([it["title"], *(f"({q})" for q in it["title_qualifiers"])])
        title = titles.setdefault(tkey, {
            "title_suggestion_id": _hash("vhsf-title-sugg-", tkey),
            "display_title": display,
            "content_hint": it.get("content_hint"),
            "series": series,
            "show_dash_hint": it.get("show_dash_hint"),
            "match_key": tkey, "work_years_seen": [], "edition_suggestion_ids": [],
            "unassigned_recording_ids": [], "tmdb": {"status": "not_searched",
                                                    "search_title": it["title"] or it.get("episode_title")},
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
    "on_tv_tape_page_but_not_a_tape_capture_file": "on a TV tape page, but not one of the tape's own capture files",
}


def categorize(rec: dict[str, Any]) -> tuple[str, list[str]]:
    """Which shelf a recording belongs on. Nothing is removed; anything that is
    not clearly a VHS (or other physical-media) release goes to manual review."""
    it = rec["interpretation"]
    if it.get("content_hint") == "off_air_tv_tape":
        tv = it["tv_tape"]
        return "tv_recording", tv_tapes.reasons(tv, tv["piece"], tv["pieces"])
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
                delay: float = 0.2, max_age_days: float = 14, workers: int = 6,
                time_budget_minutes: float | None = None) -> list[dict[str, Any]]:
    """Read-only Archive /metadata fetch (keeps md5 checksums). Cached on disk.

    A saved copy is reused until it is ``max_age_days`` old, so a nightly
    rebuild doesn't download every page again; pages rarely change. Pages that
    need downloading are read a few at a time (``workers``), politely spaced.

    ``time_budget_minutes``: stop downloading after this long. Pages not yet
    downloaded are left out of this run (they are not lost: the caller lists them
    again next run, and by then the saved copies of this run's downloads are kept).
    This stops a run with no saved copies from running out of time and starting over."""
    import sys
    import threading
    import time
    import urllib.request
    from concurrent.futures import ThreadPoolExecutor
    from pathlib import Path
    idents = list(identifiers)
    cache = Path(cache_dir) if cache_dir else None
    if cache:
        cache.mkdir(parents=True, exist_ok=True)

    def cache_path(ident: str):
        return cache / (ident.replace("/", "_") + ".json") if cache else None

    def saved(ident: str):
        path = cache_path(ident)
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            fetched = data.get("_vhsfever_fetched_at") or path.stat().st_mtime
            if time.time() - float(fetched) <= max_age_days * 86400:
                return data
        return None

    lock = threading.Lock()
    last_start = [0.0]
    deadline = time.time() + time_budget_minutes * 60 if time_budget_minutes else None

    def download(ident: str):
        if deadline and time.time() > deadline:
            return None
        # Space out the starts a little so the Archive isn't hit all at once.
        with lock:
            wait = last_start[0] + delay - time.time()
            if wait > 0:
                time.sleep(wait)
            last_start[0] = time.time()
        url = "https://archive.org/metadata/" + urllib.parse.quote(ident, safe="")
        req = urllib.request.Request(url, headers={"User-Agent": "VHS-FEVER-Catalog/0.3 (metadata only)"})
        for attempt in range(4):
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    data = json.load(resp)
                break
            except Exception:  # noqa: BLE001 - retry transient network errors
                if attempt == 3:
                    raise
                time.sleep(3 * (attempt + 1))
        data["_vhsfever_fetched_at"] = time.time()
        path = cache_path(ident)
        if path:
            path.write_text(json.dumps(data), encoding="utf-8")
        return data

    pages = {i: saved(i) for i in idents}
    todo = [i for i, d in pages.items() if d is None]
    if todo:
        with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
            for ident, data in zip(todo, pool.map(download, todo)):
                pages[ident] = data
    items = []
    skipped = 0
    for ident in idents:
        data = pages[ident]
        if data is None:
            skipped += 1
            continue
        md = data.get("metadata") or {}
        col = md.get("collection")
        items.append({
            "identifier": ident, "title": md.get("title"),
            # One collection comes back as a plain string, several as a list.
            "collection": [col] if isinstance(col, str) else col,
            "subject": md.get("subject"), "description": md.get("description"), "date": md.get("date"),
            "creator": md.get("creator"),
            "access_restricted": bool(data.get("is_dark")) or str(md.get("access-restricted-item", "")).lower() == "true",
            "files": data.get("files") or [],
        })
    downloaded = len(todo) - skipped
    print(f"Archive pages: {downloaded} downloaded, {len(items) - downloaded} reused from the saved copy",
          file=sys.stderr)
    if skipped:
        print(f"Archive pages: {skipped} left for the next run (ran out of the {time_budget_minutes} minute "
              f"download budget)", file=sys.stderr)
    return items


def build_reviewer_feed(model_dir: str) -> dict[str, Any]:
    """Nested, compact Title -> Edition -> Recording -> Source feed for the reviewer."""
    from datetime import datetime, timezone
    from pathlib import Path
    d = Path(model_dir)
    read = lambda n: [json.loads(x) for x in (d / f"{n}.jsonl").read_text(encoding="utf-8").split("\n") if x.strip()]
    titles, editions, recs, art = read("titles"), read("editions"), read("recordings"), read("artwork")
    rec_by = {r["recording_id"]: r for r in recs}
    art_by = {a["artwork_id"]: a for a in art}

    def rec_out(r: dict[str, Any]) -> dict[str, Any]:
        it = r["interpretation"]
        return {
            "id": r["recording_id"], "name": r["display_name"], "cat": r["catalog_category"],
            "why": r["category_reasons"], "flags": r["flags"], "dur": r["duration_seconds"],
            "parts": r["part_count"], "items": r["archive_identifiers"], "also": r["also_named"],
            "ver": it.get("version_label"), "notes": it.get("edition_notes"),
            **({"tv": it["tv_tape"]} if it.get("tv_tape") else {}),
            "ident": r["identity_basis"],
            "art": [{"url": art_by[a]["image_url"], "role": art_by[a]["role_guess"],
                     "name": art_by[a]["original_name"]} for a in r["artwork_ids"] if a in art_by],
            "segs": [{"o": s["order"], "dur": s["duration_seconds"], "src": [
                {"id": x["source_id"], "n": x["original_name"], "item": x["archive_identifier"], "role": x["role"],
                 "fmt": x["format"], "url": x["playback_url"], "play": x["browser_playable"],
                 "dur": x["duration_seconds"]} for x in s["sources"]]} for s in r["segments"]],
        }

    ed_by = {e["edition_suggestion_id"]: e for e in editions}
    out = []
    for t in titles:
        eds = []
        for eid in t["edition_suggestion_ids"]:
            e = ed_by[eid]
            eds.append({"id": eid, "media": e["media_format"], "year": e["release_year"],
                        "notes": e["edition_notes_seen"], "flags": e.get("flags", []),
                        "recs": [rec_out(rec_by[x]) for x in e["recording_ids"]]})
        eds.sort(key=lambda e: (e["media"] != "VHS", e["year"]))
        un = [rec_out(rec_by[x]) for x in t["unassigned_recording_ids"]]
        allrecs = [r for e in eds for r in e["recs"]] + un
        tm = t.get("tmdb") or {}
        out.append({
            "id": t["title_suggestion_id"], "t": t["display_title"], "series": t.get("series"),
            "dash": t.get("show_dash_hint"),
            "hint": t.get("content_hint"), "similar": t.get("possibly_same_as", []),
            "years": t.get("work_years_seen", []),
            "cats": sorted({r["cat"] for r in allrecs}),
            "nrec": len(allrecs), "nsrc": sum(len(s["src"]) for r in allrecs for s in r["segs"]),
            "tmdb": {"status": tm.get("status"), "q": tm.get("search_title"),
                     "kind": tm.get("search_kind"), "src": tm.get("source"),
                     "ceiling": tm.get("latest_allowed_year"),
                     "conf": tm.get("confidence"), "found_with": tm.get("found_with"),
                     "c": [{k: c.get(k) for k in ("tmdb_id", "tvdb_id", "source", "media_type", "title",
                                                  "release_date", "similarity", "year_consistent", "poster",
                                                  "overview", "tmdb_url", "episode", "runtime", "why")
                            if c.get(k) is not None or k in ("tmdb_id", "title")}
                           for c in tm.get("candidates") or []]},
            "eds": eds, "un": un,
        })
    out.sort(key=lambda t: t["t"].casefold())
    summary = json.loads((d / "catalog-model.summary.json").read_text())
    return {"generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "summary": summary, "titles": out}


def _read_jsonl(path: str) -> list[dict[str, Any]]:
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--inventory", help="legacy latest.jsonl with file inventories")
    src.add_argument("--archive-json", help="JSON file: {items:[{identifier,title,files:[...]}]}")
    src.add_argument("--feed-from", help="existing model dir: write reviewer-feed.json there")
    src.add_argument("--fetch-from-inventory", help="latest.jsonl; re-read those items live from the Archive (with checksums)")
    src.add_argument("--identifiers", help="text file, one Archive identifier per line (# for notes); read live")
    ap.add_argument("--cache-dir", help="where to cache Archive metadata responses")
    ap.add_argument("--max-age-days", type=float, default=14, help="re-download a saved page once it is this old")
    ap.add_argument("--time-budget-minutes", type=float, default=None,
                    help="stop downloading pages after this long; the rest are left for the next run")
    ap.add_argument("--output-dir")
    args = ap.parse_args()
    if args.feed_from:
        from pathlib import Path
        feed = build_reviewer_feed(args.feed_from)
        (Path(args.feed_from) / "reviewer-feed.json").write_text(
            json.dumps(feed, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print(f"reviewer feed: {len(feed['titles'])} titles")
        return
    if not args.output_dir:
        ap.error("--output-dir is required")
    if args.inventory:
        items = items_from_inventory(_read_jsonl(args.inventory))
    elif args.identifiers:
        with open(args.identifiers, encoding="utf-8") as fh:
            idents = [x.strip() for x in fh if x.strip() and not x.lstrip().startswith("#")]
        items = fetch_items(idents, args.cache_dir, max_age_days=args.max_age_days,
                            time_budget_minutes=args.time_budget_minutes)
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
