#!/usr/bin/env python3
"""Smarter title searching for TMDB: many tries, then careful ranking.

The old matcher searched TMDB once with the cleaned file name. A typo
("101 Dalmatisns"), extra words ("Ariel's Undersea Adventure Volume 1 - Whale
of a Tale", "Opening to Toy Story") or a number written differently ("101"
vs "One Hundred and One") meant no match at all. This module:

* builds a list of search tries for a title, most exact first;
* fixes typos using the words that other titles in the collection spell
  correctly;
* compares names with numbers, "&"/"and", punctuation and a leading "The"
  evened out;
* ranks candidates by name, year, running time and popularity, where the
  tape's year is a soft limit (a wrong year in one file name no longer sinks
  an exact match).

Nothing here confirms a match. It only puts the best guess first and says how
sure it is. Standard library only.
"""
from __future__ import annotations

import difflib
import re
import unicodedata
from collections import Counter
from typing import Any, Callable, Iterable

# --------------------------------------------------------------------------
# Comparing names
# --------------------------------------------------------------------------
ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
ROMAN = {"ii": "2", "iii": "3", "iv": "4", "v": "5", "vi": "6", "vii": "7", "viii": "8", "ix": "9", "x": "10"}


def number_words(n: int) -> str:
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    if n < 1000:
        rest = n % 100
        return ONES[n // 100] + " hundred" + ("" if rest == 0 else " and " + number_words(rest))
    return str(n)


_SMALL = {w: i for i, w in enumerate(ONES) if i}
_TENS = {w: i * 10 for i, w in enumerate(TENS) if w}


def words_to_numbers(tokens: list[str]) -> list[str]:
    """['one', 'hundred', 'and', 'one', 'dalmatians'] -> ['101', 'dalmatians']."""
    out: list[str] = []
    i = 0
    while i < len(tokens):
        j, total, cur, used = i, 0, 0, False
        while j < len(tokens):
            w = tokens[j]
            if w in _SMALL:
                cur += _SMALL[w]
            elif w in _TENS:
                cur += _TENS[w]
            elif w == "hundred" and used:
                cur *= 100
            elif w == "and" and used and j + 1 < len(tokens) and (tokens[j + 1] in _SMALL or tokens[j + 1] in _TENS):
                pass
            else:
                break
            used = True
            j += 1
        if used:
            total += cur
            out.append(str(total))
            i = j
        else:
            out.append(tokens[i])
            i += 1
    return out


def plain(text: str) -> str:
    t = unicodedata.normalize("NFKD", text or "").encode("ascii", "ignore").decode()
    t = t.casefold().replace("&", " and ").replace("³", " 3")
    t = re.sub(r"['’`]", "", t)
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return " ".join(t.split())


def compare_key(text: str) -> str:
    """Name with numbers, roman numerals and a leading article evened out."""
    toks = words_to_numbers(plain(text).split())
    t = " ".join(ROMAN.get(w, w) if i > 0 else w for i, w in enumerate(toks))
    t = re.sub(r"^(?:the|a|an) ", "", t)
    t = re.sub(r"\b(?:the|and|of|a)\b", " ", t)
    return " ".join(t.split())


def name_similarity(a: str, b: str) -> float:
    ka, kb = compare_key(a), compare_key(b)
    if not ka or not kb:
        return 0.0
    if ka == kb:
        return 1.0
    # "Nick Picks Vol 3" is not "Nick Picks Vol 4": different numbers, different thing.
    na = {w for w in ka.split() if w.isdigit() and len(w) <= 3}
    nb = {w for w in kb.split() if w.isdigit() and len(w) <= 3}
    if na and nb and na != nb:
        return round(min(0.6, difflib.SequenceMatcher(None, ka, kb).ratio()), 3)
    ratio = difflib.SequenceMatcher(None, ka, kb).ratio()
    # "Winnie the Pooh Boo to You Too" vs "Boo to You Too! Winnie the Pooh":
    # the same words in a different order.
    if len(ka.split()) >= 3 and sorted(ka.split()) == sorted(kb.split()):
        ratio = max(ratio, 0.95)
    # "Pooh's Grand Adventure" vs "Pooh's Grand Adventure: The Search for
    # Christopher Robin": every word of the short name starts the long one.
    sa, sb = ka.split(), kb.split()
    short, long_ = (sa, sb) if len(sa) <= len(sb) else (sb, sa)
    if len(short) >= 2 and long_[:len(short)] == short:
        ratio = max(ratio, 0.9)
    return round(ratio, 3)


# --------------------------------------------------------------------------
# Typo fixing
# --------------------------------------------------------------------------
def build_vocabulary(names: Iterable[str]) -> Counter[str]:
    """How often each word is spelled a given way across the collection."""
    vocab: Counter[str] = Counter()
    for n in names:
        ws = plain(n).split()
        for w in set(ws):
            if len(w) >= 3 and not w.isdigit():
                vocab[w] += 1
        for pair in set(zip(ws, ws[1:])):
            vocab[" ".join(pair)] += 1   # word pairs, to pick "snow white" over "show white"
    return vocab


def edit_distance(a: str, b: str) -> int:
    """Typing mistakes between two words (swapped neighbours count as one)."""
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[len(a)][len(b)]


_DICT: set[str] | None = None


def english_words() -> set[str]:
    """Real English words (from the system word list, if installed)."""
    global _DICT
    if _DICT is None:
        _DICT = set()
        for path in ("/usr/share/dict/american-english", "/usr/share/dict/words"):
            try:
                with open(path, encoding="utf-8", errors="ignore") as fh:
                    _DICT = {plain(w) for w in fh if w.strip()}
                break
            except OSError:
                continue
    return _DICT


def fix_spelling(title: str, vocab: Counter[str], *, min_count: int = 3) -> str | None:
    """'101 Dalmatisns' -> '101 Dalmatians' when other titles spell it right.

    A word that is a real English word is never changed ("Mary" stays "Mary").
    """
    words = plain(title).split()
    real = english_words()
    changed = False
    out = []
    common = [w for w, c in vocab.items() if c >= min_count and " " not in w]
    for idx, w in enumerate(words):
        if (len(w) < 4 or any(ch.isdigit() for ch in w) or vocab.get(w, 0) >= 2
                or (w.endswith("s") and vocab.get(w[:-1], 0)) or vocab.get(w + "s", 0)
                or w in real or (w.endswith("s") and w[:-1] in real) or re.match(r"^s\d+e\d+$", w)):
            out.append(w)
            continue
        close = difflib.get_close_matches(w, common, n=5, cutoff=0.7)
        limit = 1 if len(w) < 8 else 2
        close = [c for c in close if c[0] == w[0] and edit_distance(w, c) <= limit]
        if close:
            prev = words[idx - 1] if idx else ""
            nxt = words[idx + 1] if idx + 1 < len(words) else ""
            best = max(close, key=lambda c: (vocab.get(f"{prev} {c}", 0) + vocab.get(f"{c} {nxt}", 0),
                                             difflib.SequenceMatcher(None, w, c).ratio(), vocab[c]))
            out.append(best)
            changed = True
        else:
            out.append(w)
    return " ".join(out) if changed else None


# --------------------------------------------------------------------------
# Cleaning file names before searching
# --------------------------------------------------------------------------
# Learned from the names that found nothing in the first 2,400 titles: most
# carried a track number ("02 ", "1. ", "8e. "), a download-site or encoder
# tag, a "1987 VHS •" style label, or tape/edition words TMDB never uses.

# "02 Thomas", "1. Opening to", "8e. Thomas", "19b. Thomas", "#205. Little
# Ones", "003 Pingu". A plain "10 Years of Thomas" / "2 Girls in Love" /
# "60 Minutes" is left alone: no dot, letter, '#' or leading zero.
TRACK_NUMBER = re.compile(r"^\s*(?:#\s*\d{1,4}[a-z]?\.?|\d{1,3}[a-z][.)]?|\d{1,3}[.)]|0\d{1,3}\.?)\s*[-–]?\s+(?=\S)", re.I)
DOWNLOAD_SITE = re.compile(r"^\s*(?:y2mate|ssyoutube|savefrom|ytmp3|yt1s|keepvid|clipconverter|snapsave|"
                           r"onlinevideoconverter|loader)\.?\w*\s*-\s*", re.I)
# "-BESskV0WmT0-480pp-1692124241" / ".avi-i0Uq5oQ613k-nomusic" / " F4ps6zFf"
YOUTUBE_TAIL = re.compile(r"(?:\.\w{2,4})?-[A-Za-z0-9_-]{11}(?:-\d{3,4}p+)?(?:-\d{6,})?(?:-\w+)?(?:\s+\d+)?\s*$")
YEAR_LABEL = re.compile(r"^\s*(?:(?:19|20)\d\d\s+)?(?:found\s+)?(?:vhs|dvd|tape)\s*[•|·:-]\s*", re.I)
NOISE = re.compile(
    r"\b(?:\d{2,3}\s?fps|\d{3,4}p+|\d{3,4}i|prores(?:\s+\d{3,4}[ip])?|hevc|nvenc|x26[45]|h\.?26[45]|xvid|divx|"
    r"deinterlaced|lagarith|"
    r"(?:full\s+)?(?:hd\s+|hq\s+|lq\s+)?(?:vhs|dvd|tv)\s*(?:rip|capture|transfer|recording)(?:\s+version)?|"
    r"full\s+vhs(?:\s+tape)?|vhs\s+tape|full\s+tape|"
    r"screener(?:\s+vhs)?|fan\s?made|home\s?made|custom|bootleg|"
    r"(?:better|alternate|alt|original)\s+version|"
    r"(?:us|uk|usa|aus|australian|canadian|nz|pal|ntsc)\s+(?:vhs|dvd|release|version)|"
    r"(?:roadshow|abc\s+video)?\s*re[\s-]?release|(?:\d{4}\s+)?reprint|"
    r"lyrick\s+studios|anchor\s+bay|strand\s+home\s+video|golden\s+books(?:\s+version)?|cbs\s+fox|"
    r"video\s+release(?:\s+\d+)?|vhs\s+release|short\s+film|music\s+video|full\s+movie|full\s+episodes?|"
    r"ext[a-z]*(?:ly|ley)|ultra\s+rare|very\s+rare|rare)\b",
    re.I)
# Australian / UK TV-tape notes: "partial, NWS-9, 18-08-97", "(KCB Tape 7)",
# "(HB14)", "(Tape 95)", "WOC", "Ch4 14th May 2002".
TV_TAPE_NOTES = re.compile(
    r"\b(?:partial|incomplete|missing\s+start|woc)\b|\b[A-Z]{3}-\d{1,2}\b|\b[A-Z]{3}-[A-Z]{3}\b|"
    r"\b\d{1,2}[-./]\d{1,2}[-./](?:19|20)?\d\d\b|\(\s*(?:kcb\s+)?(?:tape|hb)\s*\d+\s*\)", re.I)
LEAD_LABELS = re.compile(r"^\s*(?:(?:walt\s+)?disney\s+presents|pbs(?:\s+kids)?|nick\s+jr\.?|pixar\s*-)\s+(?=\S)", re.I)
# TV airings of films: "VH1 movie 8 mile", "ABC 13 Saturday Night Movie The Big
# Country", "NBC Network Movie Premiere The Natural", "CBS TV Movie
# Presentation of Northern Exposure".
AIRING_LEAD = re.compile(
    r"^\s*(?:(?!TV\b)[A-Z][A-Z0-9]{1,4}\s+(?:\d{1,2}\s+)?)(?i:(?:the\s+)?(?:\w+\s+){0,2}"
    r"(?:movie|film|cinema)s?(?:\s+(?:premiere|presentation|special|night))?(?:\s+of)?)\s+(?=\S)")
MONTH_TAIL = re.compile(r"\s+(?:\d{1,2}(?:st|nd|rd|th)?\s+)?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)"
                        r"[a-z]*\.?(?:\s+\d{1,2}(?:st|nd|rd|th)?)?\s*$", re.I)
PART_TAIL = re.compile(r"\s+(?:part|pt\.?)\s*\d{1,3}\b.*$", re.I)
OTHER_STORIES = re.compile(r"\s*(?:&|and)?\s+other\s+(?:stories|adventures)\b", re.I)


def clean_name(title: str) -> str:
    """A file name with the clutter TMDB never has taken out.

    The original name is never changed; this is only what gets searched.
    """
    t = (title or "").replace("_", " ").replace("⁄", "/").replace("¦", " ").replace("’", "'")
    if " " not in t.strip() and t.count("-") >= 2:
        t = t.replace("-", " ")                    # "Opening-to-The-Mexican"
    t = DOWNLOAD_SITE.sub("", t)
    t = YOUTUBE_TAIL.sub("", t)
    t = re.sub(r"\.(?:avi|mp4|mkv|mov|mpg|mpeg|wmv|flv|webm|m4v|ts|vob)\b", " ", t, flags=re.I)
    t = re.sub(r"\s*•\s*vhs\b.*$", "", t, flags=re.I)  # "... on ABC • VHS 60 FPS"
    t = YEAR_LABEL.sub("", t)
    for _ in range(3):                             # "([2000])": brackets inside brackets
        t = re.sub(r"\s*[(\[][^()\[\]]*[)\]]", " ", t)
    t = re.sub(r"[()\[\]]", " ", t)
    t = re.sub(r"\b\d+(?:[.:]\d+){2,}\b", " ", t)  # tape counters "01.52.31.561"
    for _ in range(2):
        t = NOISE.sub(" ", t)
        t = TV_TAPE_NOTES.sub(" ", t)
        t = re.sub(r"\b(?:vhs\s*/?\s*dvd|vhs|dvd|blu[\s-]?ray)\b", " ", t, flags=re.I)
        t = re.sub(r"\b(?:version|video\s+release)\s+\d+\b|\bfull\s*$|\b\w+ly\s+rare\b", " ", t, flags=re.I)
        t = " ".join(t.split())
        t = TRACK_NUMBER.sub("", t)
        t = re.sub(r"^\s*(?:opening\s*(?:&|and)?\s*closing|openingclosing)\s+(?:to|of)\s+", "", t, flags=re.I)
        t = LEAD_LABELS.sub("", t)
        if AIRING_LEAD.match(t):
            rest = AIRING_LEAD.sub("", t)
            if len(plain(rest)) >= 3:
                t = rest
        t = MONTH_TAIL.sub("", t)
        t = re.sub(r"\b((?:19|20)\d\d)(?:\s+(?:19|20)\d\d)+\b", r"\1", t)   # "1995 1996"
        t = re.sub(r"(?:\s+(?:19|20)\d\d)+\s*$", "", t)                     # trailing years
    t = OTHER_STORIES.sub(" ", t)
    t = re.sub(r"\s+(?:tv\s+series|the\s+series)\s*$", "", t, flags=re.I)
    t = re.sub(r"[\s,;:•|&+-]+$", "", " ".join(t.split()))
    t = re.sub(r"^[\s,;:•|&+-]+", "", t)
    return t.strip() or " ".join((title or "").split())


# Names a camera, phone, capture card or download tool gave the file. There is
# nothing to search for in these; the Archive page title is used instead.
NO_NAME_PREFIX = re.compile(
    r"^(?:vhs\d*\s+)?(?:vid|img|mov|mvi|dsc\w?|pxl|gopr\w*|capture\w?|vts|untitled|vhs\s+[sle]p|bandicam|"
    r"videoplayback|clideo(?:\s+editor)?|pastedownload|screen\s*recording|new\s+video)\b", re.I)


def _codey(tok: str) -> bool:
    """A date, counter or random code rather than a word."""
    return (any(ch.isdigit() for ch in tok) or len(tok) <= 2 or re.fullmatch(r"[0-9a-f]{8,}", tok, re.I) is not None
            or re.search(r"[a-z][A-Z]|[A-Z]{2}[a-z]", tok) is not None)


def looks_like_no_name(title: str) -> bool:
    """'20240102 222243', 'VHS1984 VID 20261016 200948', 'videoplayback (3)', 'CaptureA 11173'."""
    t = " ".join(re.sub(r"[_()\[\]]", " ", title or "").split())
    if not re.search(r"[A-Za-z]", t):
        return True
    m = NO_NAME_PREFIX.match(t)
    if not m:
        return False
    rest = re.split(r"[\s.-]+", t[m.end():].strip())
    return all(_codey(tok) for tok in rest if tok)


# --------------------------------------------------------------------------
# Search tries
# --------------------------------------------------------------------------
STRIP_PATTERNS = [
    # (label, pattern removed)
    ("without 'Opening to'", re.compile(r"^\s*(?:opening|closing)\s+(?:to|of)\s+", re.I)),
    ("without collection label", re.compile(
        r"\b(?:walt disney )?(?:signature|platinum|diamond|gold(?:en)?|masterpiece|classics?|special|"
        r"collector'?s?|limited|anniversary|deluxe|ultimate|extended|director'?s?|uncut|remastered|"
        r"fullscreen|full screen|widescreen|wide screen|family|holiday)\s+(?:classics?\s+)?"
        r"(?:collection|edition|series|version|cut)\b", re.I)),
    ("without volume", re.compile(r"\s*\b(?:volume|vol\.?)\s*\d+\b.*$", re.I)),
    ("without 'Live Action'", re.compile(r"\(?\blive[\s-]*action\b\)?", re.I)),
    ("without brackets", re.compile(r"\s*[(\[][^)\]]*[)\]]")),
    ("without year and format", re.compile(
        r"\s+(?:(?:19|20)\d\d\s*)?(?:vhs|dvd|blu[\s-]?ray|laser\s?disc|betamax|tape)\b.*$|\s+(?:19|20)\d\d\s*$", re.I)),
]
SEQUEL_SUBTITLE = re.compile(r"^(?P<main>.+?)\s+(?P<n>[2-9]|ii|iii|iv)\s*[:-]?\s+(?P<sub>\S.{3,})$", re.I)
GENERIC = re.compile(r"^(?:the )?(?:movie|film|special|series|show|episode|part|volume|vol|chapter|pilot|"
                     r"christmas special|holiday special|trailer|opening|intro)(?: \d+)?$")
LABEL_ONLY = re.compile(r"^(?:[\d ]+|(?:tape|disc|disk|part|vol|volume|chapter|no) ?\d+|"
                        r"(?:19|20)\d\d(?: \d\d? \d\d?)?|.*\b(?:vhs|dvd|tape|recording|capture)s?)$")
LEADING_YEAR = re.compile(r"^\s*(?:19|20)\d\d\s*[-–—:.,)\]]?\s+(?P<rest>\S+(?:\s+\S+)+)$")
SPLIT_DASH = re.compile(r"\s+[-–—:]\s+|\s*:\s+")


def without_show(title: str, show: str | None) -> str | None:
    """'Barney Our Earth, Our Home' with show 'Barney' -> 'Our Earth, Our Home'."""
    if not show:
        return None
    ws, ss = plain(title).split(), plain(show).split()
    if ws[:1] == ["the"] and ss[:1] != ["the"]:
        ws = ws[1:]
    for n in (len(ss), len(ss) + 1):            # "Barneys" / "Barney's" for "Barney"
        head = ws[:len(ss)]
        if head and (head == ss or (len(head) == len(ss) and head[:-1] == ss[:-1]
                                    and head[-1] in (ss[-1] + "s", ss[-1]))):
            words = (title or "").split()
            # Drop the same number of words from the original (keeps punctuation).
            skip = len(ss) + (1 if plain(words[0]) == "the" and ss[:1] != ["the"] else 0)
            rest = " ".join(words[skip:]).strip(" -:,")
            return rest if len(plain(rest)) >= 4 else None
        break
    # "Alphabet Soup With Barney", "Shapes - Barney": the show named at the
    # end. Needs a joining word or a dash so a title that only happens to end
    # in the show's name ("Just Friends" for "Friends") isn't split.
    words = (title or "").split()
    for n in (len(ss) + 3, len(ss) + 2, len(ss) + 1):   # "with Barney & Friends" / "with Barney"
        if len(words) <= n:
            continue
        tail = plain(" ".join(words[-n:])).split()
        if not tail or tail[0] not in {"with", "starring", "featuring", "feat"}:
            continue
        name = tail[1:]
        if name[:len(ss)] == ss and name[len(ss):] in ([], ["friends"], ["and", "friends"]):
            rest = " ".join(words[:-n]).strip(" -:,")
            return rest if len(plain(rest)) >= 4 else None
    m = re.match(r"^(?P<rest>.+?)\s+[-–—:]\s+(?P<show>.+)$", title or "")
    if m and plain(m["show"]) in (" ".join(ss), " ".join(ss) + " and friends"):
        rest = m["rest"].strip(" -:,")
        return rest if len(plain(rest)) >= 4 else None
    return None


def mentions_show(cand: dict[str, Any], show: str) -> bool:
    """Whether a TMDB result's name or summary names the show."""
    key = plain(show)
    if not key:
        return True
    text = " ".join(str(x) for x in [cand.get("title"), cand.get("original_title"), cand.get("overview"),
                                     *(cand.get("alt_titles") or [])] if x)
    return f" {key} " in f" {plain(text)} "


def search_tries(title: str, vocab: Counter[str] | None = None, *, extra: list[tuple[str, str]] | None = None,
                 show: str | None = None) -> list[tuple[str, str]]:
    """Ordered (query, how it was made) list, most exact first, no repeats.

    ``extra`` adds searches from outside the file name (the Archive page
    title); ``show`` is a show the file belongs to, so 'Show Special Name'
    is also searched as 'Special Name'.
    """
    tries: list[tuple[str, str]] = []
    seen: set[str] = set()

    def add(q: str | None, how: str) -> None:
        if not q:
            return
        q = " ".join(q.replace("_", " ").split()).strip(" -.,:")
        k = plain(q)
        if GENERIC.match(k) or (len(k.split()) < 2 and len(k) < 6 and tries and "spelling" not in how):
            return   # "The Movie", "Part 2": would match anything
        if len(k) >= 2 and k not in seen and not k.isdigit():
            seen.add(k)
            tries.append((q, how))

    add(title, "name as written")
    before_year = re.match(r"^\s*(.{3,}?)\s*[(\[]\s*(?:19|20)\d\d\s*[)\]]", title or "")
    if before_year and len(plain(before_year[1]).split()) >= 1:
        add(clean_name(before_year[1]), "name before the year")
    cleaned = clean_name(title)
    add(cleaned, "name cleaned up")
    # "1975 It's a Bird, It's a Plane, It's Superman": a year written in front
    # of the name. The name with the year is still tried first, so films
    # named after a year ("2001 A Space Odyssey") still match.
    front = LEADING_YEAR.match(cleaned)
    if front:
        add(front["rest"], "without the year in front")
        cleaned = front["rest"]
    current = cleaned
    for how, rx in STRIP_PATTERNS:
        stripped = rx.sub(" ", current)
        if plain(stripped) != plain(current):
            add(stripped, how)
            current = stripped
    if vocab:
        for q, how in list(tries)[:3]:
            add(fix_spelling(q, vocab, min_count=2), how + ", spelling fixed")
    for q, how in list(tries)[:4]:
        if re.search(r",| and ", q, re.I) and len(q.split()) >= 4:
            add(re.sub(r",|\band\b", " ", q, flags=re.I), how + ", without 'and' and commas")
    parts = SPLIT_DASH.split(current)
    if len(parts) > 1:
        if not LABEL_ONLY.match(plain(parts[0])):
            add(parts[0], "part before the dash")
        # "Bear in the Big Blue House - Safe and Sound": the part after the
        # dash is an episode, not a film. Only search it on its own when the
        # part before is just a number, date or label ("07 - Title").
        if LABEL_ONLY.match(plain(parts[0])):
            add(parts[-1], "part after the dash")
    m = SEQUEL_SUBTITLE.match(current)
    if m:
        add(m["sub"], "sequel subtitle on its own")
        add(f"{m['main']} {m['n']}", "sequel number without subtitle")
    for q, how in list(tries):
        words = q.split()
        if re.match(r"^[1-9]\d*$", words[0] if words else "") and len(words) > 1:
            n = int(words[0])
            if 1 <= n < 1000:
                add(number_words(n) + " " + " ".join(words[1:]), how + ", number as words")
    # Glued or dotted names: "AllDogsGoToHeaven" -> "All Dogs Go To Heaven"
    if re.search(r"[a-z][A-Z]", title) and " " not in title.strip():
        add(re.sub(r"(?<=[a-z])(?=[A-Z])", " ", title), "words split apart")
    tries = tries[:7]
    # "The Adventures of Elmo in Grouchland Disney", "Charlie St. Cloud WMax":
    # a studio, channel or note left at the end. Judged against the full name.
    ws = current.split()
    for drop in (1, 2):
        kept = plain(" ".join(ws[:-drop])).split()
        if (len(ws) - drop >= 2 and len(" ".join(kept)) >= 8
                and kept[-1] not in {"the", "a", "an", "and", "of", "in", "to", "my", "on", "at", "for", "with"}):
            add(" ".join(ws[:-drop]), f"last {drop} word{'s' if drop > 1 else ''} dropped")
    rest = without_show(current, show)
    if rest:
        add(rest, "without the show name")
    for q, how in extra or []:
        add(q, how)
        by = re.match(r"^\s*(.{3,}?)\s*[(\[]\s*(?:19|20)\d\d\s*[)\]]", q)
        if by:
            add(clean_name(by[1]), how + ", name before the year")
        c = clean_name(q)
        add(c, how + ", cleaned up")
        front = LEADING_YEAR.match(c)
        if front:
            c = front["rest"]
            add(c, how + ", without the year in front")
        for _how, rx in STRIP_PATTERNS:
            c2 = rx.sub(" ", c)
            if plain(c2) != plain(c):
                c = c2
        add(c, how + ", cleaned up")
    return tries[:11]


# --------------------------------------------------------------------------
# Ranking
# --------------------------------------------------------------------------
def year_of(date: str | None) -> int | None:
    return int(date[:4]) if date and date[:4].isdigit() else None


def rank(query: str, cands: list[dict[str, Any]], *, ceiling: int | None, hint_year: int | None,
         minutes: float | None, kids_hint: bool = False) -> list[dict[str, Any]]:
    """Score every candidate. Adds 'score', 'year_consistent', 'runtime_match' and 'why'."""
    for c in cands:
        why = []
        names = [c.get("title") or "", c.get("original_title") or ""] + list(c.get("alt_titles") or [])
        sim = max(name_similarity(query, n) for n in names if n) if any(names) else 0.0
        c["similarity"] = sim
        score = sim * 60
        why.append(f"name {round(sim * 100)}% alike")
        y = year_of(c.get("release_date"))
        ok = None
        if hint_year and y:
            if abs(y - hint_year) <= 1:
                ok = True
                score += 20
                why.append("same year as the file name")
            elif y < hint_year:
                # "Star Wars CBS FOX 1984": a year after the film is usually
                # when the tape came out, not a different film.
                score -= 3
                why.append(f"film {y}, file name says {hint_year} (likely the tape's year)")
            else:
                ok = False
                score -= 25
                why.append(f"year {y} vs {hint_year} in the file name")
        if ceiling and y:
            if y <= ceiling:
                ok = True if ok is None else ok
                score += 4
            elif y <= ceiling + 2:
                # A tape can't predate the film, but release years written in
                # file names are often off by a year or two.
                score -= 6
                why.append(f"came out {y}, a little after the {ceiling} tape year")
                ok = ok if ok is not None else None
            else:
                ok = False
                score -= 30
                why.append(f"came out {y}, after the {ceiling} tape")
        c["year_consistent"] = ok
        rt = c.get("runtime")
        c["runtime_match"] = None
        if rt and minutes:
            diff = abs(rt - minutes) / max(rt, 1)
            c["runtime_match"] = diff <= 0.12
            if diff <= 0.06:
                score += 15
                why.append(f"runs {rt} min, file runs {round(minutes)}")
            elif diff <= 0.12:
                score += 8
                why.append(f"runs {rt} min, file runs {round(minutes)}")
            elif diff > 0.35:
                score -= 12
                why.append(f"runs {rt} min but the file runs {round(minutes)}")
        if kids_hint and c.get("animated"):
            score += 4
        pop = c.get("popularity") or 0
        score += min(pop, 50) / 10
        c["score"] = round(score, 1)
        c["why"] = why
    cands.sort(key=lambda c: c["score"], reverse=True)
    return cands


def confidence(cands: list[dict[str, Any]]) -> str:
    """sure / likely / several / weak / none, for the best candidate."""
    if not cands:
        return "none"
    best = cands[0]
    if best.get("not_the_show"):
        return "weak"
    lead = best["score"] - (cands[1]["score"] if len(cands) > 1 else 0)
    if best["similarity"] >= 0.95 and best.get("year_consistent") is not False:
        if best.get("runtime_match") and lead >= 10:
            return "sure"
        if lead >= 10 or len(cands) == 1:
            return "likely"
        return "several"
    if best["similarity"] >= 0.85 and best.get("year_consistent") is not False and lead >= 10:
        return "likely"
    return "weak"


STATUS = {"sure": "one_likely_candidate", "likely": "one_likely_candidate",
          "several": "several_candidates", "weak": "weak_candidates_only", "none": "no_candidates"}


def search(title: str, *, find: Callable[[str, int | None], list[dict[str, Any]]],
           details: Callable[[dict[str, Any]], dict[str, Any]] | None = None,
           vocab: Counter[str] | None = None, ceiling: int | None = None, hint_year: int | None = None,
           minutes: float | None = None, kids_hint: bool = False, max_tries: int = 11,
           extra: list[tuple[str, str]] | None = None, show: str | None = None) -> dict[str, Any]:
    """Try each search until a clear match turns up; return ranked candidates.

    ``find(query, year)`` returns raw candidate dicts (tmdb_id, title,
    original_title, release_date, popularity, ...). ``details(c)`` may add
    runtime / alt_titles / animated for the top few.
    """
    pool: dict[Any, dict[str, Any]] = {}
    tried: list[dict[str, Any]] = []
    found_with = None
    full = clean_name(title)

    def judge(q: str, how: str) -> str:
        # A shortened search is judged against the whole cleaned name, so
        # "Jealous" found from "Absolutely Fabulous Jealous" isn't "sure".
        return full if ("dropped" in how or "without the show name" in how) else q

    for q, how in search_tries(title, vocab, extra=extra, show=show)[:max_tries]:
        results = find(q, hint_year) if hint_year else []
        if not results:
            results = find(q, None)
        tried.append({"query": q, "how": how, "results": len(results)})
        for r in results:
            key = (r.get("media_type"), r.get("tmdb_id"))
            if key not in pool:
                r = dict(r, found_with=how, found_query=q)
                pool[key] = r
        if pool:
            ranked = rank(judge(q, how), list(pool.values()), ceiling=ceiling, hint_year=hint_year,
                          minutes=minutes, kids_hint=kids_hint)
            # Stop on a clear name match. A match found only after a spelling
            # fix must still look like the name as written.
            if ranked[0]["similarity"] >= 0.95 and (
                    "spelling" not in how or name_similarity(title, ranked[0]["title"] or "") >= 0.8):
                found_with = how
                break
    cands = list(pool.values())
    if not cands:
        return {"confidence": "none", "status": "no_candidates", "candidates": [], "tried": tried,
                "found_with": None}
    best_query = (found_with and judge(next(t["query"] for t in tried if t["how"] == found_with), found_with)
                  or full or title)
    ranked = rank(best_query, cands, ceiling=ceiling, hint_year=hint_year, minutes=minutes, kids_hint=kids_hint)
    if details:
        for c in ranked[:4]:
            c.update({k: v for k, v in (details(c) or {}).items() if v is not None})
        ranked = rank(best_query, ranked, ceiling=ceiling, hint_year=hint_year, minutes=minutes,
                      kids_hint=kids_hint)
    if show:
        # "Alphabet Soup With Barney": a film called "Alphabet Soup" that has
        # nothing to do with Barney is a different video, however close the name.
        for c in ranked:
            searched_with_show = f" {plain(show)} " in f" {plain(c.get('found_query') or '')} "
            if not searched_with_show and not mentions_show(c, show):
                c["score"] = round(c["score"] - 30, 1)
                c["not_the_show"] = True
                c.setdefault("why", []).append(f"nothing about {show} in it")
        ranked.sort(key=lambda c: c["score"], reverse=True)
    conf = confidence(ranked)
    return {"confidence": conf, "status": STATUS[conf], "candidates": ranked[:5], "tried": tried,
            "found_with": ranked[0].get("found_with")}
