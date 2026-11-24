#!/usr/bin/env python3
"""Find which TV episode a file is: TMDB first, TheTVDB as a backup.

The filename gives some of: show name, season, episode number, episode title,
air date (see ``catalog_model.interpret_filename``). This module finds the
show, pulls its full episode list, and lines the file up against it by number,
by title (forgiving: case, punctuation, "The", small typos, two-story
episodes) and by air date. Results are suggestions only; nothing is confirmed.

TheTVDB is used only when the TVDB_API_KEY secret is set (plus TVDB_PIN for a
subscriber key), and only when TMDB could not find the show or the episode.
"""
from __future__ import annotations

import difflib
import json
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable

TMDB_API = "https://api.themoviedb.org/3"
TMDB_IMG = "https://image.tmdb.org/t/p/"
TVDB_API = "https://api4.thetvdb.com/v4"

SHOW_MIN_SIMILARITY = 0.8
TITLE_MIN_SIMILARITY = 0.84


# ---------------------------------------------------------------------------
# Text matching
# ---------------------------------------------------------------------------

def norm(text: str | None) -> str:
    t = unicodedata.normalize("NFKD", text or "").encode("ascii", "ignore").decode()
    t = t.casefold().replace("&", " and ")
    t = re.sub(r"[^a-z0-9]+", " ", t)
    t = re.sub(r"^(?:the|a|an) ", "", t.strip())
    return " ".join(t.split())


def similarity(a: str | None, b: str | None) -> float:
    x, y = norm(a), norm(b)
    if not x or not y:
        return 0.0
    if x == y:
        return 1.0
    r = difflib.SequenceMatcher(None, x, y).ratio()
    # "And Too Much Birthday" vs "The Berenstain Bears and Too Much Birthday",
    # "Life Goes On" vs "Life Goes On (and On and On)": one fully inside the other.
    short, long_ = sorted((x, y), key=len)
    if len(short) >= 6 and re.search(r"\b" + re.escape(short) + r"\b", long_):
        r = max(r, 0.86 + 0.14 * len(short) / len(long_))
    return round(r, 3)


def search_text(show: str) -> str:
    """'Gullah.Gullah.Island' -> 'Gullah Gullah Island' for the search box."""
    return re.sub(r"\s+", " ", re.sub(r"(?<=\w)[._](?=\w)", " ", show)).strip()


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

def _request(url: str, headers: dict[str, str], body: dict | None = None) -> dict[str, Any]:
    data = json.dumps(body).encode() if body is not None else None
    h = {"Accept": "application/json", "User-Agent": "vhs-fever-catalog", **headers}
    if data is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h)
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
    raise RuntimeError("request failed: " + url.split("?")[0])


# ---------------------------------------------------------------------------
# Sources. Each returns shows and episodes in one shared shape.
#   show:    {source, id, name, year, first_air_date, poster, overview, url}
#   episode: {season, episode, absolute, name, air_date, still, overview, url}
# ---------------------------------------------------------------------------

class Tmdb:
    name = "tmdb"

    def __init__(self, token: str, delay: float = 0.03):
        self.h = {"Authorization": "Bearer " + token}
        self.delay = delay
        self._eps: dict[int, list[dict[str, Any]]] = {}
        self.calls = 0

    def _get(self, path: str, **params: Any) -> dict[str, Any]:
        self.calls += 1
        time.sleep(self.delay)
        q = ("?" + urllib.parse.urlencode(params)) if params else ""
        return _request(TMDB_API + path + q, self.h)

    def search_shows(self, query: str) -> list[dict[str, Any]]:
        res = self._get("/search/tv", query=search_text(query), include_adult="false").get("results") or []
        out = []
        for r in res[:10]:
            date = r.get("first_air_date") or ""
            out.append({
                "source": "tmdb", "id": r.get("id"), "name": r.get("name") or "",
                "original_name": r.get("original_name"),
                "year": int(date[:4]) if date[:4].isdigit() else None, "first_air_date": date or None,
                "poster": (TMDB_IMG + "w342" + r["poster_path"]) if r.get("poster_path") else None,
                "overview": (r.get("overview") or "")[:400] or None,
                "popularity": r.get("popularity"),
                "url": f"https://www.themoviedb.org/tv/{r.get('id')}",
            })
        return out

    def episodes(self, show_id: int) -> list[dict[str, Any]]:
        if show_id in self._eps:
            return self._eps[show_id]
        show = self._get(f"/tv/{show_id}")
        out: list[dict[str, Any]] = []
        for s in sorted(show.get("seasons") or [], key=lambda s: s.get("season_number") or 0):
            n = s.get("season_number")
            if n is None or not s.get("episode_count"):
                continue
            season = self._get(f"/tv/{show_id}/season/{n}")
            for e in season.get("episodes") or []:
                out.append({
                    "season": e.get("season_number"), "episode": e.get("episode_number"),
                    "name": e.get("name") or "", "air_date": e.get("air_date") or None,
                    "still": (TMDB_IMG + "w300" + e["still_path"]) if e.get("still_path") else None,
                    "overview": (e.get("overview") or "")[:400] or None,
                    "url": f"https://www.themoviedb.org/tv/{show_id}/season/{e.get('season_number')}/episode/{e.get('episode_number')}",
                })
        _number_absolute(out)
        self._eps[show_id] = out
        return out


class Tvdb:
    name = "tvdb"

    def __init__(self, api_key: str, pin: str | None = None, delay: float = 0.05):
        body = {"apikey": api_key}
        if pin:
            body["pin"] = pin
        tok = _request(TVDB_API + "/login", {}, body)["data"]["token"]
        self.h = {"Authorization": "Bearer " + tok}
        self.delay = delay
        self._eps: dict[int, list[dict[str, Any]]] = {}
        self.calls = 1

    def _get(self, path: str, **params: Any) -> dict[str, Any]:
        self.calls += 1
        time.sleep(self.delay)
        q = ("?" + urllib.parse.urlencode(params)) if params else ""
        return _request(TVDB_API + path + q, self.h)

    @staticmethod
    def _show(r: dict[str, Any]) -> dict[str, Any]:
        sid = r.get("tvdb_id") or r.get("id")
        sid = int(re.sub(r"\D", "", str(sid))) if sid and re.search(r"\d", str(sid)) else None
        year = r.get("year") or (r.get("first_air_time") or r.get("firstAired") or "")[:4]
        name = r.get("name") or ""
        trans = r.get("translations") or {}
        if isinstance(trans, dict) and trans.get("eng"):
            name = trans["eng"]
        ov = r.get("overview") or ""
        ovs = r.get("overviews") or {}
        if isinstance(ovs, dict) and ovs.get("eng"):
            ov = ovs["eng"]
        return {
            "source": "tvdb", "id": sid, "name": name,
            "year": int(year) if str(year).isdigit() else None,
            "first_air_date": r.get("first_air_time") or r.get("firstAired") or None,
            "poster": r.get("image_url") or r.get("image") or None,
            "overview": (ov or "")[:400] or None, "popularity": None,
            "url": f"https://thetvdb.com/dereferrer/series/{sid}" if sid else None,
        }

    def search_shows(self, query: str) -> list[dict[str, Any]]:
        res = self._get("/search", query=search_text(query), type="series", limit=10).get("data") or []
        return [s for s in (self._show(r) for r in res) if s["id"]]

    def find_by_episode_title(self, title: str) -> list[tuple[dict[str, Any], dict[str, Any]]]:
        """When the filename has no show name: search episodes by title."""
        res = self._get("/search", query=title, type="episode", limit=10).get("data") or []
        out = []
        for r in res[:5]:
            eid = r.get("tvdb_id") or r.get("id")
            eid = re.sub(r"\D", "", str(eid or ""))
            if not eid:
                continue
            e = self._get(f"/episodes/{eid}").get("data") or {}
            if not e.get("seriesId"):
                continue
            show = self._show(self._get(f"/series/{e['seriesId']}").get("data") or {})
            if show["id"]:
                ep = self._episode(e, show["id"])
                out.append((show, ep))
        return out

    @staticmethod
    def _episode(e: dict[str, Any], sid: int) -> dict[str, Any]:
        img = e.get("image")
        if img and img.startswith("/"):
            img = "https://artworks.thetvdb.com" + img
        return {
            "season": e.get("seasonNumber"), "episode": e.get("number"), "name": e.get("name") or "",
            "air_date": e.get("aired") or None, "still": img or None,
            "overview": (e.get("overview") or "")[:400] or None,
            "absolute_from_source": e.get("absoluteNumber") or None,
            "url": f"https://thetvdb.com/series/{sid}/episodes/{e.get('id')}" if e.get("id") else None,
        }

    def episodes(self, show_id: int) -> list[dict[str, Any]]:
        if show_id in self._eps:
            return self._eps[show_id]
        out: list[dict[str, Any]] = []
        page = 0
        while page < 20:
            try:
                d = self._get(f"/series/{show_id}/episodes/default/eng", page=page)
            except urllib.error.HTTPError:
                d = self._get(f"/series/{show_id}/episodes/default", page=page)
            eps = (d.get("data") or {}).get("episodes") or []
            out += [self._episode(e, show_id) for e in eps]
            if not (d.get("links") or {}).get("next") or not eps:
                break
            page += 1
        out.sort(key=lambda e: ((e["season"] if e["season"] is not None else 999), e["episode"] or 0))
        _number_absolute(out)
        self._eps[show_id] = out
        return out


def _number_absolute(eps: list[dict[str, Any]]) -> None:
    """Running count over the regular seasons (specials, season 0, excluded)."""
    n = 0
    for e in eps:
        if (e.get("season") or 0) >= 1:
            n += 1
            e["absolute"] = n
        else:
            e["absolute"] = None


# ---------------------------------------------------------------------------
# Lining a file up against an episode list
# ---------------------------------------------------------------------------

def match_episodes(eps: list[dict[str, Any]], *, season: int | None, episode: int | None,
                   titles: list[str], air_date: str | None) -> list[dict[str, Any]]:
    """Episodes that fit what the filename says, best first. Each result is the
    episode plus ``basis`` (what matched) and ``title_similarity``."""
    scored: dict[int, dict[str, Any]] = {}

    def add(i: int, why: str, points: float, title_sim: float | None = None) -> None:
        cur = scored.setdefault(i, {"basis": [], "points": 0.0, "title_similarity": None})
        if why not in cur["basis"]:
            cur["basis"].append(why)
            cur["points"] += points
        if title_sim is not None:
            cur["title_similarity"] = max(cur["title_similarity"] or 0, title_sim)

    if episode is not None:
        exact = [i for i, e in enumerate(eps) if e["season"] == season and e["episode"] == episode] if season is not None else []
        for i in exact:
            add(i, "season_and_episode_number", 2.0)
        if not exact:
            # "Blue's Clues S06E136": the number counts across the whole show.
            for i, e in enumerate(eps):
                if episode in (e.get("absolute"), e.get("absolute_from_source")):
                    add(i, "episode_number_counted_across_seasons" if season is not None else "episode_number", 1.5 if season is None else 1.2)
    if titles:
        for i, e in enumerate(eps):
            best = max(similarity(t, e["name"]) for t in titles)
            if best >= TITLE_MIN_SIMILARITY:
                add(i, "episode_title", 2.5 * best, best)
            elif i in scored:
                scored[i]["title_similarity"] = best
    if air_date:
        for i, e in enumerate(eps):
            if e.get("air_date") == air_date:
                add(i, "air_date", 2.0)

    out = []
    for i, s in scored.items():
        e = dict(eps[i])
        e["basis"] = s["basis"]
        e["title_similarity"] = s["title_similarity"]
        # A number match whose title clearly disagrees is probably the wrong
        # episode (numbering differs between sources): rank it lower.
        points = s["points"]
        if titles and "episode_title" not in s["basis"] and (s["title_similarity"] or 0) < 0.5:
            points -= 1.0
            e["basis"] = e["basis"] + ["title_does_not_match"]
        e["_points"] = points
        out.append(e)
    out.sort(key=lambda e: e["_points"], reverse=True)
    for e in out:
        e.pop("_points")
    return out[:3]


def strong(ep: dict[str, Any]) -> bool:
    """Title matched, or numbers and air date agree. Numbers alone are not enough."""
    b = set(ep["basis"])
    return "title_does_not_match" not in b and ("episode_title" in b or ("air_date" in b and len(b) > 1))


# ---------------------------------------------------------------------------
# The full lookup for one title
# ---------------------------------------------------------------------------

def _candidate(show: dict[str, Any], ep: dict[str, Any] | None, show_sim: float,
               ceiling: int | None) -> dict[str, Any]:
    year_ok = None
    when = (ep or {}).get("air_date") or show.get("first_air_date") or ""
    if ceiling and when[:4].isdigit():
        year_ok = int(when[:4]) <= ceiling
    c = {
        "source": show["source"], "media_type": "tv",
        "tmdb_id": show["id"] if show["source"] == "tmdb" else None,
        "tvdb_id": show["id"] if show["source"] == "tvdb" else None,
        "title": show["name"], "release_date": show.get("first_air_date"),
        "similarity": show_sim, "year_consistent": year_ok, "popularity": show.get("popularity"),
        "poster": show.get("poster"), "overview": show.get("overview"), "tmdb_url": show.get("url"),
    }
    if ep:
        c["episode"] = {k: ep.get(k) for k in ("season", "episode", "name", "air_date", "still", "overview",
                                               "url", "basis", "title_similarity")}
        if ep.get("overview"):
            c["overview"] = ep["overview"]
    return c


def find_episode(sources: list[Any], *, show: str | None, season: int | None, episode: int | None,
                 episode_title: str | None, air_date: str | None, ceiling: int | None = None,
                 title_pieces: Callable[[str | None], list[str]] | None = None,
                 require_title_match: bool = False) -> dict[str, Any]:
    """Try each source in turn (TMDB, then TVDB). Stops at the first source that
    finds the episode convincingly. Returns {status, source, candidates, tried}."""
    titles = (title_pieces or (lambda t: [t] if t else []))(episode_title)
    tried: list[str] = []
    best_show_only: list[dict[str, Any]] = []
    weak: list[dict[str, Any]] = []
    for src in sources:
        tried.append(src.name)
        try:
            if show:
                shows = src.search_shows(show)
                scored = sorted(((similarity(show, s["name"]), s) for s in shows), key=lambda x: (x[0], x[1].get("popularity") or 0), reverse=True)
                scored = [(sim, s) for sim, s in scored if sim >= SHOW_MIN_SIMILARITY][:3]
                found: list[dict[str, Any]] = []
                for sim, s in scored:
                    eps = src.episodes(s["id"])
                    for ep in match_episodes(eps, season=season, episode=episode, titles=titles, air_date=air_date):
                        found.append(_candidate(s, ep, sim, ceiling))
                    if not found and not best_show_only:
                        best_show_only.append(_candidate(s, None, sim, ceiling))
                good = [c for c in found if strong(c["episode"])]
                if good:
                    return {"status": "one_likely_episode" if len(good) == 1 else "several_episode_candidates",
                            "source": src.name, "candidates": (good + [c for c in found if c not in good])[:5],
                            "tried": tried}
                if found and not require_title_match and not titles:
                    # Only numbers to go on, and no title to contradict them.
                    return {"status": "episode_by_number_only", "source": src.name,
                            "candidates": found[:5], "tried": tried}
                weak += found
            elif titles and hasattr(src, "find_by_episode_title"):
                for s, ep in src.find_by_episode_title(titles[0]):
                    sim = max(similarity(t, ep["name"]) for t in titles)
                    if sim < TITLE_MIN_SIMILARITY:
                        continue
                    if season is not None and ep["season"] not in (season, None):
                        continue
                    ep = {**ep, "basis": ["episode_title_search"] + (["season_and_episode_number"] if (ep["season"], ep["episode"]) == (season, episode) else []),
                          "title_similarity": sim}
                    weak.append(_candidate(s, ep, 0.0, ceiling))
                if weak:
                    return {"status": "show_found_from_episode_title", "source": src.name,
                            "candidates": weak[:5], "tried": tried}
        except Exception as exc:  # noqa: BLE001 - one source failing must not stop the other
            tried[-1] = f"{src.name} (error: {str(exc)[:80]})"
    if weak and not require_title_match:
        return {"status": "weak_episode_candidates", "source": weak[0]["source"], "candidates": weak[:5], "tried": tried}
    if best_show_only and not require_title_match:
        return {"status": "show_found_episode_not_matched", "source": best_show_only[0]["source"],
                "candidates": best_show_only, "tried": tried}
    return {"status": "no_episode_found", "source": None, "candidates": [], "tried": tried}
