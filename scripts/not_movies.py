#!/usr/bin/env python3
"""Long videos on the Archive that are clearly not movies on tape.

DJ sets, conference talks, news streams and the like run over 40 minutes, so
length alone lets them in. Their names give them away. Used when pages are
picked and again when the movie list is made. Standard library only.
"""
from __future__ import annotations

import re

NOT_MOVIE = re.compile(
    r"\bboiler\s*room\b|\bdj[\s-]*(?:set|mix)\b|\blive[\s-]*(?:set|stream(?:ing)?|update|coverage)\b|"
    r"\bdef\s*con\b|\bblack\s*hat\b|\bcppcon\b|\bpycon\b|\bted(?:x|talks?)\b|\bkeynote\b|\bwebinar\b|"
    r"\bpanel\s+discussion\b|\bpress\s+(?:briefing|conference)\b|\bpodcast\b|\blivestream\b|"
    r"\bconference\s+talk\b|\bhackers?\s+conference\b|\bcoronavirus\b|\bcovid\b|\bpandemic\b|"
    r"\bearthquake\s+swarms?\b|\bgameplay\b|\blet'?s\s+play\b|\bspeedrun\b|\bwalkthrough\b|"
    r"\b(?:33|45|78)\s*(?:1/3|⅓)?\s*rpm\b|\bvinyl\b|\bfull\s+album\b|\bgloomtube\b",
    re.I)


def not_a_movie(*texts: object) -> str | None:
    """The words that show this isn't a movie, or None."""
    for t in texts:
        if isinstance(t, (list, tuple)):
            t = " ".join(str(x) for x in t)
        m = NOT_MOVIE.search(str(t or "").replace("_", " "))
        if m:
            return m.group(0)
    return None
