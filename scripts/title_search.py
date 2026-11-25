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
    ratio = difflib.SequenceMatcher(None, ka, kb).ratio()
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
SPLIT_DASH = re.compile(r"\s+[-–—:]\s+|\s*:\s+")


def search_tries(title: str, vocab: Counter[str] | None = None) -> list[tuple[str, str]]:
    """Ordered (query, how it was made) list, most exact first, no repeats."""
    tries: list[tuple[str, str]] = []
    seen: set[str] = set()

    def add(q: str | None, how: str) -> None:
        if not q:
            return
        q = " ".join(q.replace("_", " ").split()).strip(" -.,:")
        k = plain(q)
        if GENERIC.match(k) or (len(k.split()) < 2 and len(k) < 6 and tries):
            return   # "The Movie", "Part 2": would match anything
        if len(k) >= 2 and k not in seen and not k.isdigit():
            seen.add(k)
            tries.append((q, how))

    add(title, "name as written")
    current = title
    for how, rx in STRIP_PATTERNS:
        stripped = rx.sub(" ", current)
        if plain(stripped) != plain(current):
            add(stripped, how)
            current = stripped
    if vocab:
        for q, how in list(tries)[:3]:
            add(fix_spelling(q, vocab), how + ", spelling fixed")
    parts = SPLIT_DASH.split(current)
    if len(parts) > 1:
        add(parts[0], "part before the dash")
        add(parts[-1], "part after the dash")
    m = SEQUEL_SUBTITLE.match(current)
    if m:
        add(m["sub"], "sequel subtitle on its own")
        add(f"{m['main']} {m['n']}", "sequel number without subtitle")
    for q, how in list(tries):
        words = q.split()
        if re.match(r"^\d+$", words[0] if words else "") and len(words) > 1:
            n = int(words[0])
            if 1 <= n < 1000:
                add(number_words(n) + " " + " ".join(words[1:]), how + ", number as words")
    # Glued or dotted names: "AllDogsGoToHeaven" -> "All Dogs Go To Heaven"
    if re.search(r"[a-z][A-Z]", title) and " " not in title.strip():
        add(re.sub(r"(?<=[a-z])(?=[A-Z])", " ", title), "words split apart")
    return tries[:8]


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
            ok = abs(y - hint_year) <= 1
            score += 20 if ok else -25
            why.append("same year as the file name" if ok else f"year {y} vs {hint_year} in the file name")
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
           minutes: float | None = None, kids_hint: bool = False, max_tries: int = 8) -> dict[str, Any]:
    """Try each search until a clear match turns up; return ranked candidates.

    ``find(query, year)`` returns raw candidate dicts (tmdb_id, title,
    original_title, release_date, popularity, ...). ``details(c)`` may add
    runtime / alt_titles / animated for the top few.
    """
    pool: dict[Any, dict[str, Any]] = {}
    tried: list[dict[str, Any]] = []
    found_with = None
    for q, how in search_tries(title, vocab)[:max_tries]:
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
            ranked = rank(q, list(pool.values()), ceiling=ceiling, hint_year=hint_year,
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
    best_query = found_with and next(t["query"] for t in tried if t["how"] == found_with) or title
    ranked = rank(best_query, cands, ceiling=ceiling, hint_year=hint_year, minutes=minutes, kids_hint=kids_hint)
    if details:
        for c in ranked[:4]:
            c.update({k: v for k, v in (details(c) or {}).items() if v is not None})
        ranked = rank(best_query, ranked, ceiling=ceiling, hint_year=hint_year, minutes=minutes,
                      kids_hint=kids_hint)
    conf = confidence(ranked)
    return {"confidence": conf, "status": STATUS[conf], "candidates": ranked[:5], "tried": tried,
            "found_with": ranked[0].get("found_with")}
