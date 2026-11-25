#!/usr/bin/env python3
"""Find VHS-era uploads on the Internet Archive outside the VHS Vault.

Three ways in, each read a few pages a night and picked up where the last
run stopped (oldest upload first, so new uploads simply land at the end):

  uploaders   people who upload tapes to the VHS Vault; their other uploads
              (outside the Vault) are read. Uploaders are learned a few
              hundred Vault pages a night, starting with pages already in
              the catalog, then pages that look like VHS movies or TV.
  keywords    phrases that tape uploaders use ("VHS rip", "taped off",
              "with commercials", ...) in titles, descriptions or tags.
  collections Archive collections full of off-air tapes and old commercials.

Every item gets the same quick first guess the VHS Vault list uses and a
note saying which way it was found. Nothing is downloaded and nothing is
added to the catalog; the list is kept on its own (more-items.jsonl) so a
batch is only brought in when picked.

Uploader e-mail addresses are only used to ask the Archive for their
uploads. They stay in the run's private cache and never go into the
published list, which names each uploader by a short code instead.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import discover_new_items as vault  # noqa: E402

SEARCH = "https://archive.org/advancedsearch.php"
METADATA = "https://archive.org/metadata/{}/metadata/uploader"
FIELDS = vault.FIELDS + ["access-restricted-item"]

# Never wanted: adult tapes, video-game footage, DVD disc images, a high school TV station
# called "VHS", pages that need an Archive login to play, the TV News
# Archive (not playable) and the Vault itself (already covered).
SKIP_COLLECTIONS = ["vhsvault", "adultvhs", "gamevideos", "game_replays", "speed_runs", "vhstvnj",
                    "community_media", "loggedin", "tvnews", "tvarchive", "dvdtray", "dvdtray_inbox"]
ADULT_WORDS = re.compile(r"\b(?:xxx|porn\w*|playboy|penthouse|erotic\w*|nudes?|nudity|softcore|hardcore|hentai|"
                         r"adult (?:film|movie|video)s?|sexy|stripper\w*|lingerie)\b", re.I)
BASE = ("mediatype:movies AND NOT access-restricted-item:true AND "
        + " AND ".join(f"NOT collection:{c}" for c in SKIP_COLLECTIONS))

KEYWORDS = ['"vhs rip"', "vhsrip", '"vhs capture"', '"vhs transfer"', '"from vhs"', '"from a vhs"',
            '"taped off"', '"recorded off"', '"off air"', '"as aired"', '"with commercials"',
            '"original commercials"', '"vhs tape"', '"betamax"', '"laserdisc rip"']
COLLECTIONS = ["classic_tv_commercials", "classic_tv_commercials_emperor", "localnewsarchive", "classic_tv"]
COMMERCIAL_COLLECTIONS = {"classic_tv_commercials", "classic_tv_commercials_emperor", "vhscommercials"}

UPLOADER_MIN_SEEDS = 3      # Vault pages an uploader needs before their other uploads are read
TAPE_GUESS = {"VHS", "BETAMAX", "LASERDISC"}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def uploader_code(email: str) -> str:
    return "u-" + hashlib.sha256(email.strip().lower().encode()).hexdigest()[:10]


def get_json(url: str) -> Any:
    req = urllib.request.Request(url, headers={"User-Agent": vault.UA})
    last: Exception | None = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as exc:  # noqa: BLE001
            last = exc
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"Archive request failed: {last}")


def search(query: str, page: int, rows: int) -> list[dict[str, Any]]:
    params = [("q", query), ("rows", rows), ("page", page), ("output", "json"), ("sort[]", "addeddate asc")]
    params += [("fl[]", f) for f in FIELDS]
    return get_json(SEARCH + "?" + urllib.parse.urlencode(params))["response"]["docs"]


def lookup_uploader(identifier: str) -> str | None:
    result = get_json(METADATA.format(urllib.parse.quote(identifier, safe=""))).get("result")
    return result.strip() if isinstance(result, str) and "@" in result else None


def is_unwanted(doc: dict[str, Any]) -> bool:
    cols = vault.as_list(doc.get("collection"))
    text = " ".join([str(doc.get("title") or "")] + vault.as_list(doc.get("subject")))
    return (str(doc.get("access-restricted-item")).lower() == "true"
            or bool(ADULT_WORDS.search(text))
            or any(c in SKIP_COLLECTIONS or "adult" in c.lower() or "xxx" in c.lower() for c in cols))


def guess(doc: dict[str, Any], source_kind: str) -> dict[str, Any]:
    """The Vault list's first guess, plus two extra clues that matter outside the Vault."""
    g = vault.first_guess(doc)
    cols = set(vault.as_list(doc.get("collection")))
    if g["kind_guess"] in {"movie_or_unknown", "tv_show"} and cols & COMMERCIAL_COLLECTIONS:
        g["kind_guess"] = "commercials"
    if g["format_guess"] == "unknown" and source_kind == "keyword":
        # The phrase that found it is often only in the description.
        text = vault.auto_sort.norm_words(str(doc.get("description") or ""))[:3000]
        for rx, fmt, _strength in vault.auto_sort.RIP_TAGS:
            if fmt in TAPE_GUESS | {"DVD"} and rx.search(text):
                g["format_guess"] = fmt
                g["format_from"] = "description"
                break
    return g


class Source:
    """One saved place to read from. `marker` is the upload date reached so far."""

    def __init__(self, key: str, kind: str, label: str, query: str, state: dict[str, Any]):
        self.key, self.kind, self.label, self.query = key, kind, label, query
        self.state = state.setdefault(key, {"kind": kind, "label": label, "marker": None, "skip_pages": 0,
                                            "found": 0, "done": False})
        self.state["label"] = label

    def next_query(self) -> tuple[str, int]:
        marker = self.state.get("marker")
        q = f"({BASE}) AND ({self.query})"
        if marker:
            q += f" AND addeddate:[{marker} TO *]"
        return q, 1 + int(self.state.get("skip_pages") or 0)

    def advance(self, docs: list[dict[str, Any]], rows: int) -> None:
        dates = [str(d["addeddate"]) for d in docs if d.get("addeddate")]
        last = max(dates) if dates else None
        if last and last != self.state.get("marker"):
            self.state["marker"], self.state["skip_pages"] = last, 0
        else:  # a full page of uploads all from the same second: step past it
            self.state["skip_pages"] = int(self.state.get("skip_pages") or 0) + 1
        self.state["done"] = len(docs) < rows
        self.state["last_read"] = now_iso()


def learn_uploaders(seeds: list[str], cache: dict[str, Any], limit: int, delay: float,
                    lookup: Callable[[str], str | None] = lookup_uploader) -> int:
    """Look up who uploaded up to `limit` Vault pages not looked up before."""
    done = cache.setdefault("by_item", {})
    n = 0
    for ident in seeds:
        if n >= limit:
            break
        if ident in done:
            continue
        try:
            done[ident] = lookup(ident)
        except RuntimeError:
            continue
        n += 1
        time.sleep(delay)
    return n


def uploader_sources(cache: dict[str, Any], state: dict[str, Any]) -> list[tuple[Source, int]]:
    counts = Counter(e for e in cache.get("by_item", {}).values() if e)
    out = []
    for email, n in counts.most_common():
        if n < UPLOADER_MIN_SEEDS:
            break
        code = uploader_code(email)
        esc = email.replace('"', "")
        out.append((Source(f"uploader:{code}", "uploader", f"uploader {code} ({n} Vault pages)",
                           f'uploader:"{esc}"', state), n))
    return out


def seed_order(vault_rows: list[dict[str, Any]], in_catalog: set[str]) -> list[str]:
    """Pages in the catalog first, then Vault pages that look like tapes, then the rest."""
    def rank(r: dict[str, Any]) -> int:
        if r["identifier"] in in_catalog or r.get("in_catalog"):
            return 0
        if r.get("format_guess") in TAPE_GUESS and r.get("kind_guess") in {"movie_or_unknown", "tv_recording", "tv_show"}:
            return 1
        if r.get("format_guess") in TAPE_GUESS:
            return 2
        return 3
    return [r["identifier"] for r in sorted(vault_rows, key=lambda r: (rank(r), str(r.get("added_to_archive") or "")),
                                            reverse=False) if rank(r) < 3]


def read_jsonl(path: str | None) -> list[dict[str, Any]]:
    if not path or not Path(path).is_file():
        return []
    return [json.loads(x) for x in Path(path).read_text(encoding="utf-8").splitlines() if x.strip()]


def read_json(path: str | None, default: Any) -> Any:
    if path and Path(path).is_file():
        try:
            return json.loads(Path(path).read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            pass
    return default


def run(a: argparse.Namespace, search_fn: Callable[[str, int, int], list[dict[str, Any]]] = search,
        lookup: Callable[[str], str | None] = lookup_uploader) -> dict[str, Any]:
    known = {r["identifier"]: r for r in read_jsonl(a.known)}
    vault_rows = read_jsonl(a.vault_items)
    vault_ids = {r["identifier"] for r in vault_rows}
    in_catalog = {r["archive_identifier"] for r in read_jsonl(a.catalog_items) if r.get("archive_identifier")}
    state = read_json(a.state, {}).get("sources", {})
    cache = read_json(a.uploader_cache, {})
    started = now_iso()

    looked_up = learn_uploaders(seed_order(vault_rows, in_catalog), cache, a.uploader_lookups, a.lookup_delay, lookup)

    sources: list[Source] = []
    if "uploaders" in a.ways:
        sources += [s for s, _n in uploader_sources(cache, state)]
    if "keywords" in a.ways:
        sources += [Source(f"keyword:{k.strip(chr(34))}", "keyword", f"keyword {k}",
                           f"title:({k}) OR description:({k}) OR subject:({k})", state) for k in KEYWORDS]
    if "collections" in a.ways:
        sources += [Source(f"collection:{c}", "collection", f"collection {c}", f"collection:{c}", state)
                    for c in COLLECTIONS]

    added: Counter[str] = Counter()
    pages = 0
    budget = a.max_pages
    # Fair share: every source gets a turn before any gets a second one.
    for round_no in range(a.pages_per_source):
        for src in sources:
            if budget <= 0:
                break
            if src.state.get("done") and round_no > 0:
                continue
            query, page = src.next_query()
            try:
                docs = search_fn(query, page, a.rows)
            except RuntimeError as exc:
                src.state["last_error"] = str(exc)[:200]
                continue
            pages += 1
            budget -= 1
            for d in docs:
                ident = d.get("identifier")
                if not ident or ident in vault_ids or is_unwanted(d):
                    continue
                row = known.get(ident)
                if row is None:
                    row = known[ident] = {
                        "identifier": ident, "title": d.get("title"), "added_to_archive": d.get("addeddate"),
                        "first_seen": started, "collections": vault.as_list(d.get("collection")),
                        "size_bytes": d.get("item_size"), "files": d.get("num_files"),
                        "url": f"https://archive.org/details/{ident}", "found_by": [], **guess(d, src.kind),
                    }
                    src.state["found"] = int(src.state.get("found") or 0) + 1
                    added[src.kind] += 1
                if src.label.split(" (")[0] not in row["found_by"]:
                    row["found_by"].append(src.label.split(" (")[0])
            src.advance(docs, a.rows)
            time.sleep(a.delay)
        if budget <= 0:
            break

    for ident, row in known.items():
        row["in_catalog"] = ident in in_catalog
        row["found_by_uploader"] = any(f.startswith("uploader ") for f in row.get("found_by", []))
    rows = sorted(known.values(), key=lambda r: str(r.get("added_to_archive") or ""), reverse=True)
    out = Path(a.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    with (out / "more-items.jsonl").open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    (out / "more-sources-state.json").write_text(
        json.dumps({"updated_at": now_iso(), "sources": state}, indent=2, ensure_ascii=False) + "\n")
    if a.uploader_cache:
        Path(a.uploader_cache).parent.mkdir(parents=True, exist_ok=True)
        Path(a.uploader_cache).write_text(json.dumps(cache) + "\n")

    waiting = [r for r in rows if not r["in_catalog"]]
    by_way: Counter[str] = Counter()
    for r in waiting:
        for f in r.get("found_by", []):
            by_way[f.split(" ")[0]] += 1
    uploaders = uploader_sources(cache, {})
    summary = {
        "checked_at": started, "pages_read": pages, "vault_pages_looked_up_for_uploaders": looked_up,
        "vault_pages_looked_up_total": len(cache.get("by_item", {})),
        "uploaders_followed": len(uploaders),
        "new_this_run": dict(added), "items_known": len(rows), "not_in_catalog_yet": len(waiting),
        "waiting_by_way_found": dict(by_way.most_common()),
        "waiting_by_format_guess": dict(Counter(r["format_guess"] for r in waiting).most_common()),
        "waiting_by_kind_guess": dict(Counter(r["kind_guess"] for r in waiting).most_common()),
        "waiting_not_english": sum(1 for r in waiting if r.get("not_english")),
        "sources_finished": sum(1 for s in state.values() if s.get("done")),
        "sources_total": len(state),
        "best_sources": sorted(({"source": s["label"], "found": s.get("found", 0)} for s in state.values()),
                               key=lambda x: -x["found"])[:15],
    }
    (out / "more-items.summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
    return summary


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--known", help="earlier more-items.jsonl (kept and extended)")
    ap.add_argument("--state", help="earlier more-sources-state.json (where each source stopped)")
    ap.add_argument("--uploader-cache", help="private file of looked-up uploaders (never published)")
    ap.add_argument("--vault-items", help="VHS Vault new-items.jsonl (used to learn uploaders and to skip Vault pages)")
    ap.add_argument("--catalog-items", help="archive-items.jsonl of items already in the catalog")
    ap.add_argument("--output-dir", required=True)
    ap.add_argument("--ways", nargs="+", default=["uploaders", "keywords", "collections"],
                    choices=["uploaders", "keywords", "collections"])
    ap.add_argument("--rows", type=int, default=200)
    ap.add_argument("--max-pages", type=int, default=60, help="search pages per run, all sources together")
    ap.add_argument("--pages-per-source", type=int, default=3, help="most pages any one source gets per run")
    ap.add_argument("--uploader-lookups", type=int, default=400, help="Vault pages to look up uploaders for per run")
    ap.add_argument("--delay", type=float, default=1.0)
    ap.add_argument("--lookup-delay", type=float, default=0.2)
    summary = run(ap.parse_args())
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
