#!/usr/bin/env python3
"""Merge sound & picture check files into one (later files win per file id)."""
from __future__ import annotations

import argparse
import json


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--output", required=True)
    ap.add_argument("inputs", nargs="*")
    a = ap.parse_args()
    merged: dict[str, dict] = {}
    for path in a.inputs:
        try:
            with open(path, encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        r = json.loads(line)
                        merged[r["file_id"]] = r
        except FileNotFoundError:
            continue
    with open(a.output, "w", encoding="utf-8") as fh:
        for r in merged.values():
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{len(merged)} sound checks combined from {len(a.inputs)} files")


if __name__ == "__main__":
    main()
