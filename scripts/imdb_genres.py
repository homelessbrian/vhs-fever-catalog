"""Genres from IMDb, which are usually closer to how people file a movie than TMDB's.

TMDB tags Donnie Darko "Fantasy, Drama, Mystery"; IMDb says "Drama, Mystery, Sci-Fi".
The bot looks the IMDb genres up for every matched movie (IMDb's free data file, the
same movie's IMDb id that TMDB gives us), then:

  - keeps the genres IMDb names, in TMDB's order when both name them (TMDB's order says
    which one is the main genre), then the ones only IMDb names;
  - falls back to TMDB's own genres for a movie IMDb has nothing on;
  - lets a hand-written override win over both (data/genre-overrides.json).

The data file is big (about 200 MB packed), so it is only downloaded when there is a movie
whose genres haven't been looked up yet. Looked-up genres are kept in a small cache file that
is published with the rest of what the bot remembers between runs.
"""
from __future__ import annotations

import gzip
import io
import json
import re
import time
import urllib.request
from pathlib import Path
from typing import Any, Iterable

BASICS_URL = "https://datasets.imdbws.com/title.basics.tsv.gz"
IMDB_ID = re.compile(r"^tt\d{6,10}$")

# IMDb's names -> the names the TMDB lookup (and so both apps) already use.
# None = not a movie genre the shelves know; dropped.
NAMES: dict[str, str | None] = {
    "Action": "Action", "Adventure": "Adventure", "Animation": "Animation", "Comedy": "Comedy",
    "Crime": "Crime", "Documentary": "Documentary", "Drama": "Drama", "Family": "Family",
    "Fantasy": "Fantasy", "History": "History", "Horror": "Horror", "Music": "Music",
    "Musical": "Music", "Mystery": "Mystery", "Romance": "Romance", "Sci-Fi": "Sci-Fi",
    "Thriller": "Thriller", "War": "War", "Western": "Western",
    "Biography": "History", "Film-Noir": "Crime",
    "Sport": None, "Short": None, "Adult": None, "News": None, "Reality-TV": None,
    "Talk-Show": None, "Game-Show": None,
}


def map_genres(imdb: Iterable[str]) -> list[str]:
    """IMDb's genre names as ours, no repeats, dropping the ones no shelf uses."""
    out: list[str] = []
    for g in imdb:
        name = NAMES.get(g.strip())
        if name and name not in out:
            out.append(name)
    return out


def merge(tmdb: list[str], imdb: list[str] | None) -> list[str]:
    """The movie's genres. IMDb decides which genres count; TMDB's order decides which comes first."""
    if not imdb:
        return list(tmdb)
    imdb = map_genres(imdb)
    if not imdb:
        return list(tmdb)
    first = [g for g in tmdb if g in imdb]
    return first + [g for g in imdb if g not in first]


def load_overrides(path: Path) -> dict[str, list[str]]:
    """data/genre-overrides.json: {"tmdb:141": ["Science Fiction", ...], "tt0246578": [...]}. Keys starting "_" are notes."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {k: [GENRE_ALIASES.get(g, g) for g in v] for k, v in raw.items()
            if not k.startswith("_") and isinstance(v, list)}


# Overrides can be written the way the shelves are named.
GENRE_ALIASES = {"Science Fiction": "Sci-Fi"}


def override_for(overrides: dict[str, list[str]], tmdb_id: Any, imdb_id: str | None) -> list[str] | None:
    for key in (f"tmdb:{tmdb_id}", imdb_id or ""):
        if key and key in overrides:
            return overrides[key]
    return None


def fetch_missing(ids: Iterable[str], cache: dict[str, list[str]], *, url: str = BASICS_URL,
                  opener: Any = None, tries: int = 3) -> int:
    """Looks up IMDb genres for every id not in cache (cache[id] = list, [] when IMDb has none).

    Streams the data file once and stops early when everything wanted is found.
    Returns how many were looked up; leaves the cache alone if the download fails
    (the movie keeps its TMDB genres and is tried again next run).
    """
    want = {i for i in ids if i and IMDB_ID.match(i) and i not in cache}
    if not want:
        return 0
    opener = opener or urllib.request.urlopen
    found: dict[str, list[str]] = {}
    for attempt in range(tries):
        found = {}
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "vhs-fever-catalog"})
            with opener(req, timeout=120) as resp, gzip.open(resp, "rb") as gz:
                for raw in io.TextIOWrapper(gz, encoding="utf-8", errors="replace", newline="\n"):
                    tconst, _, rest = raw.partition("\t")
                    if tconst not in want:
                        continue
                    cols = rest.rstrip("\n").split("\t")
                    # tconst, titleType, primaryTitle, originalTitle, isAdult, startYear, endYear, runtime, genres
                    genres = cols[7] if len(cols) > 7 else "\\N"
                    found[tconst] = [] if genres == "\\N" else genres.split(",")
                    if len(found) == len(want):
                        break
            break
        except Exception:  # noqa: BLE001 - retry, then leave it for the next run
            if attempt == tries - 1:
                return 0
            time.sleep(5 * (attempt + 1))
    # Ids IMDb's file doesn't have are remembered as empty so the big file isn't read for them every run.
    for i in want:
        cache[i] = found.get(i, [])
    return len(want)
