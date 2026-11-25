#!/usr/bin/env python3
"""Pick the next batch of Archive pages to run, never repeating a page.

Reads the VHS Vault list (auto-sort-data/new-items.jsonl) and, with
--more-items, the list of finds from outside the Vault (more-items.jsonl,
found through Vault uploaders, tape keywords and TV/commercial collections).
--from chooses which of the two to pick from. Skips every
page that is already in the catalog or in any earlier batch list
(data/batches/*.txt). Writes the next numbered batch file.

  --kind movies : pages that look like full movies or kids' tapes, English,
                  VHS/DVD in the name, 0.25-15 GB
  --kind ads    : pages that look like commercials, breaks, promos or
                  TV recordings with their ads in them, English
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

NOT_A_MOVIE = re.compile(r"\b(?:opening|closing|logos?|promo|intro|introduction|outros?|segments?|trailers?|preview|"
                         r"bumpers?|ident|announcements?|credits|clip|clips|commercials?|sampler|teaser|tv spot|"
                         r"part \d+|\d+ of \d+)\b", re.I)


def already_picked(batches_dir: Path) -> set[str]:
    seen: set[str] = set()
    for f in sorted(batches_dir.glob("*.txt")):
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                seen.add(line)
    return seen


def wanted(r: dict, kind: str) -> bool:
    if r.get("in_catalog") or r.get("not_english"):
        return False
    cols = set(r.get("collections") or [])
    title = r.get("title") or ""
    gb = int(r.get("size_bytes") or 0) / 1e9
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
    ap.add_argument("--kind", choices=["all", "movies", "ads"], default="all")
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
        return [json.loads(x) for x in Path(path).read_text(encoding="utf-8").splitlines() if x.strip()]
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
    # Vault pages and outside finds from Vault uploaders are the cleanest, so
    # they go first; keyword and collection finds after. Newest first within each.
    pool.sort(key=lambda r: not ("found_by" not in r or r.get("found_by_uploader")))
    pick = pool[: a.size]
    if not pick:
        print("Nothing left to pick.")
        return
    n = 1 + max([int(m.group(1)) for f in bdir.glob("batch-*.txt") if (m := re.match(r"batch-(\d+)", f.stem))] or [0])
    out = bdir / f"batch-{n:03d}.txt"
    label = {"movies": "pages that look like full movies or kids' tapes, English names, VHS/DVD in the name, 0.25-15 GB",
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
