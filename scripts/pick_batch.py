#!/usr/bin/env python3
"""Pick the next batch of Archive pages for the movie bot, never repeating a page.

Reads the VHS Vault list (auto-sort-data/new-items.jsonl) and, with
--more-items, the list of finds from outside the Vault (more-items.jsonl,
found through Vault uploaders, tape keywords and TV/commercial collections).
--from chooses which of the two to pick from. Skips every
page that is already in the catalog or in any earlier batch list
(data/batches/*.txt). Writes the next numbered batch file.

  --kind vhs_movies (default): pages that could hold a full-length VHS movie:
                  English, not ads/TV/openings/how-to, not DVD or Blu-ray,
                  0.15-20 GB; outside the Vault only tapes or Vault uploaders
  --kind movies : pages that look like full movies or kids' tapes, English,
                  VHS/DVD in the name, 0.25-15 GB
  --kind ads    : pages that look like commercials, breaks, promos or
                  TV recordings with their ads in them, English
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import adult  # noqa: E402
import not_movies  # noqa: E402

NOT_A_MOVIE = re.compile(r"\b(?:opening|closing|logos?|promo|intro|introduction|outros?|segments?|trailers?|preview|"
                         r"bumpers?|ident|announcements?|credits|clip|clips|commercials?|sampler|teaser|tv spot|"
                         r"part \d+|\d+ of \d+)\b", re.I)


def already_picked(batches_dir: Path) -> set[str]:
    seen: set[str] = set()
    for f in sorted(batches_dir.glob("*.txt")):
        for line in f.read_text(encoding="utf-8").split("\n"):
            line = line.strip()
            if line and not line.startswith("#"):
                seen.add(line)
    return seen


NOT_MOVIE_COLLECTIONS = {"vhsopenings", "vhsinstructionals", "vhscommercials", "vhsnews", "vhssports",
                         "vhsconcerts_music"}


def wanted(r: dict, kind: str) -> bool:
    if r.get("in_catalog") or r.get("not_english"):
        return False
    cols = set(r.get("collections") or [])
    title = r.get("title") or ""
    if isinstance(title, list):        # a few Archive pages have more than one title
        title = " ".join(str(x) for x in title)
    gb = int(r.get("size_bytes") or 0) / 1e9
    if adult.blocked(cols, title):
        return False          # never read: see adult.py
    if kind == "vhs_movies":
        if r.get("kind_guess") != "movie_or_unknown" or cols & NOT_MOVIE_COLLECTIONS:
            return False
        if NOT_A_MOVIE.search(title) or not_movies.not_a_movie(title) or not 0.15 <= gb <= 20:
            return False
        if r.get("format_guess") in {"DVD", "BLU-RAY", "TV", "WEB"}:
            return False
        if "found_by" in r:
            return bool(r.get("found_by_uploader") or r.get("format_guess") in {"VHS", "BETAMAX"}
                        or cols & adult.ADULT_COLLECTIONS)
        return True
    if kind == "movies":
        if r.get("kind_guess") != "movie_or_unknown" or cols & {"vhsopenings", "vhsinstructionals"}:
            return False
        if NOT_A_MOVIE.search(title) or not 0.25 <= gb <= 15:
            return False
        return r.get("format_guess") in {"VHS", "DVD", "LASERDISC", "BETAMAX"} or "vhsmovies" in cols
    if kind == "ads":
        return r.get("kind_guess") in {"commercials", "tv_recording"} and gb <= 20
    if kind == "all":
        # Everything English; the robot sorts it into movies, ads, TV and so on.
        # Very large pages (over 20 GB) wait for a batch of their own.
        if gb > 20:
            return False
        if "found_by" in r:
            # Outside the Vault only what looks like a tape, TV or ads, or
            # comes from someone who uploads tapes to the Vault.
            return bool(r.get("found_by_uploader")
                        or r.get("format_guess") in {"VHS", "BETAMAX", "LASERDISC"}
                        or r.get("kind_guess") in {"commercials", "tv_recording"})
        return True
    raise ValueError(kind)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--new-items", help="VHS Vault list (new-items.jsonl); needed unless --from more")
    ap.add_argument("--batches-dir", default="data/batches")
    ap.add_argument("--kind", choices=["vhs_movies", "all", "movies", "ads"], default="vhs_movies")
    ap.add_argument("--size", type=int, default=500)
    ap.add_argument("--github-output", help="file to write batch=<name> to (GitHub Actions)")
    ap.add_argument("--more-items", help="finds from outside the VHS Vault (more-items.jsonl)")
    ap.add_argument("--from", dest="source", choices=["vault", "more", "both"], default="vault",
                    help="vault = VHS Vault only (default), more = outside finds only, both = everything")
    a = ap.parse_args()
    bdir = Path(a.batches_dir)
    bdir.mkdir(parents=True, exist_ok=True)
    seen = already_picked(bdir)
    def load(path: str | None) -> list[dict]:
        if not path or not Path(path).is_file():
            return []
        return [json.loads(x) for x in Path(path).read_text(encoding="utf-8").split("\n") if x.strip()]
    if a.source in {"vault", "both"} and not a.new_items:
        ap.error("--new-items is needed unless --from more")
    rows = load(a.new_items) if a.source in {"vault", "both"} else []
    if a.source in {"more", "both"}:
        if not a.more_items:
            ap.error("--from more/both needs --more-items")
        vault_ids = {r["identifier"] for r in rows}
        rows += [r for r in load(a.more_items) if r["identifier"] not in vault_ids]
    pool = [r for r in rows if r["identifier"] not in seen and wanted(r, a.kind)]
    pool.sort(key=lambda r: r.get("added_to_archive") or "", reverse=True)
    # Outside finds from Vault uploaders are the cleanest, so they go before
    # keyword and collection finds. Newest first within each.
    # The Archive's adult VHS collection is just as clean (all tapes), so it goes with them.
    pool.sort(key=lambda r: "found_by" in r and not r.get("found_by_uploader")
              and not set(r.get("collections") or []) & adult.ADULT_COLLECTIONS)
    if a.source == "both":
        # Half Vault, half outside in every batch, so both keep coming in.
        inside = [r for r in pool if "found_by" not in r]
        outside = [r for r in pool if "found_by" in r]
        pool = [r for pair in zip(inside, outside) for r in pair]
        n = min(len(inside), len(outside))
        pool += inside[n:] + outside[n:]
    pick = pool[: a.size]
    if not pick:
        print("Nothing left to pick.")
        return
    n = 1 + max([int(m.group(1)) for f in bdir.glob("batch-*.txt") if (m := re.match(r"batch-(\d+)", f.stem))] or [0])
    out = bdir / f"batch-{n:03d}.txt"
    label = {"vhs_movies": "pages that could hold a full-length VHS movie (English, 0.15-20 GB), newest first",
             "movies": "pages that look like full movies or kids' tapes, English names, VHS/DVD in the name, 0.25-15 GB",
             "ads": "pages that look like commercials, ad breaks, promos or TV recordings with their ads in, English",
             "all": "pages not read yet (English, up to 20 GB each), newest first"}[a.kind]
    where = {"vault": "VHS Vault", "more": "outside-the-Vault", "both": "VHS Vault and outside-the-Vault"}[a.source]
    out.write_text(f"# Batch {n}: {len(pick)} {where} {label}.\n"
                   f"# Skips pages already in the catalog or in an earlier batch. One Archive identifier per line.\n"
                   + "".join(r["identifier"] + "\n" for r in pick), encoding="utf-8")
    print(f"{out}: {len(pick)} pages ({len(pool) - len(pick)} more still waiting)")
    if a.github_output:
        with open(a.github_output, "a", encoding="utf-8") as fh:
            fh.write(f"batch={out.stem}\nwaiting={len(pool) - len(pick)}\n")


if __name__ == "__main__":
    main()
