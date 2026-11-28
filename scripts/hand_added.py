#!/usr/bin/env python3
"""Archive files added by hand (data/hand-added.json).

The movie bot normally decides what is a VHS movie by itself. Files listed here
were picked by a person, so the bot keeps them even when they look like a
DVD/Blu-ray rip instead of a tape. From the page a listed file is on, the bot
keeps only the listed files (a page can hold a whole collection). Listed files
never go straight onto the TV shelves: they always wait in the review app.

  python scripts/hand_added.py pages    prints the Archive pages to read, one per line
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

DEFAULT = Path(__file__).resolve().parent.parent / "data" / "hand-added.json"
EXT = re.compile(r"\.(?:mp4|m4v|mkv|avi|mpe?g|mpeg4|mov|ogv|webm|ts|wmv|flv|divx)$", re.I)
COPY_MARK = re.compile(r"(?:\.ia|_512kb|\.512kb)$", re.I)


def stem(name: str | None) -> str:
    """'Dir/Behind Enemy Lines.ia.mp4' and 'dir/behind enemy lines.mkv' give the same stem."""
    s = EXT.sub("", str(name or "").strip())
    return COPY_MARK.sub("", s).casefold()


def load(path: Path | None = DEFAULT) -> list[dict[str, Any]]:
    if not path or not Path(path).is_file():
        return []
    return list(json.loads(Path(path).read_text(encoding="utf-8")).get("files") or [])


def pages(entries: list[dict[str, Any]]) -> set[str]:
    return {e["page"] for e in entries}


def wanted(entries: list[dict[str, Any]]) -> set[tuple[str, str]]:
    return {(e["page"], stem(e["file"])) for e in entries}


def _sources(rec: dict[str, Any]) -> list[tuple[str, str]]:
    return [(x.get("archive_identifier"), stem(x.get("original_name")))
            for seg in rec.get("segments") or [] for x in seg.get("sources") or []]


def on_hand_page(rec: dict[str, Any], entries: list[dict[str, Any]]) -> bool:
    return bool(set(rec.get("archive_identifiers") or []) & pages(entries))


def is_listed(rec: dict[str, Any], entries: list[dict[str, Any]]) -> bool:
    """True when one of the recording's files is a file listed by hand."""
    want = wanted(entries)
    return any(s in want for s in _sources(rec))


def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "pages":
        for p in sorted(pages(load())):
            print(p)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
