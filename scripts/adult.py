"""Adult tapes: which Archive pages are adult, and which are never allowed.

Adult tapes are kept, but only behind the "red curtain": both apps hide them
until it is unlocked, they never go on the normal shelves, and no pictures of
them are saved in the repository (titles and Archive links only).

Some 1980s adult tapes star performers who were under 18 when they were made.
Those tapes are illegal to share, so any adult page that names one of them, or
uses words that point at minors, is BLOCKED: the bot drops it before reading
it, and it never reaches a list or an app. This list only ever grows.
"""
from __future__ import annotations

import re
from typing import Any, Iterable

# The Archive's own adult tape collection ("Mature VHS Tapes").
ADULT_COLLECTIONS = {"adultvhs"}

# Looked for in a page's title and tags only: a normal movie's description
# can say "rated R for nudity" without it being an adult tape.
ADULT_WORDS = re.compile(
    r"\b(?:xxx|porn\w*|playboy|penthouse|hustler|erotic(?:a|ism)?(?!\s+(?:thriller|drama)s?\b)|nudes?|nudity|nudist|softcore|hentai|"
    r"adult (?:film|movie|video|tape)s?|x-rated|rated x|stripper\w*|striptease)\b", re.I)

# Performers known to have been under 18 in adult tapes they appeared in.
UNDERAGE_PERFORMERS = re.compile(
    r"\b(?:traci\s+lords?|nora\s+kuzma|kristie\s+nussman|alexandria\s+quinn|lolly\s+badcock)\b", re.I)

# Words that point at minors. On an adult page any of these blocks it.
MINOR_WORDS = re.compile(
    r"\b(?:lolitas?|jail\s?bait|under\s?-?\s?age\w*|pre\s?-?\s?teens?|child\w*|kids?|minors?|"
    r"school\s?girls?|school\s?boys?|barely\s+legal|young\s+girls?|teen\w*|virgin\s+teens?|"
    r"junior\s+high|high\s+school|cheerleaders?\s+camp|(?:[1-9]|1[0-7])\s?-?\s?(?:yo|years?[- ]old))\b", re.I)


def _text(parts: Iterable[Any]) -> str:
    out: list[str] = []
    for p in parts:
        if p is None:
            continue
        if isinstance(p, (list, tuple, set)):
            out.append(_text(p))
        else:
            out.append(str(p))
    return " ".join(out)


def is_adult(collections: Iterable[str] | None, title: Any = None, tags: Any = None) -> bool:
    cols = [str(c).lower() for c in collections or []]
    if any(c in ADULT_COLLECTIONS or "adult" in c or "xxx" in c for c in cols):
        return True
    return bool(ADULT_WORDS.search(_text([title, tags])))


def blocked(collections: Iterable[str] | None, title: Any = None, tags: Any = None, *more: Any) -> str | None:
    """Why an adult page must never be used (None = fine, or not adult).
    `more` is everything else known about the page (description, performers,
    file names): all of it is searched."""
    if not is_adult(collections, title, tags):
        return None
    words = _text([title, tags, *more])
    m = UNDERAGE_PERFORMERS.search(words)
    if m:
        return f"names a performer who was under 18 ({m.group(0)})"
    m = MINOR_WORDS.search(words)
    if m:
        return f"adult page with a word that points at minors ({m.group(0)})"
    return None
