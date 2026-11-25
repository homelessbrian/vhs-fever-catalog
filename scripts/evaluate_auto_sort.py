#!/usr/bin/env python3
"""Check the auto-sort against the files a person already reviewed.

Reads auto-sort.jsonl and the review app's decisions.json, and reports in
plain words how often the robot agrees with the person: for its "good" pile,
its "bad" pile, and the shelf (VHS / other media / not catalog). Every
disagreement is listed so the rules can be fixed. Writes a JSON report and a
short Markdown report for the GitHub run page.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

# The bar the "good" pile must clear before it may ever be switched on.
GOOD_PILE_TARGET = 0.98
# The rules were written while looking at reviews made up to this moment, so
# only reviews made after it are a fair, unseen test.
RULES_WRITTEN_AT = "2026-11-26T00:00:00Z"


def evaluate(rows: list[dict[str, Any]], decisions: dict[str, Any], *, since: str | None = None) -> dict[str, Any]:
    if since is None:
        full = evaluate(rows, decisions, since="")
        full["unseen_reviews"] = evaluate(rows, decisions, since=RULES_WRITTEN_AT)
        for k in ("robot_good_person_bad", "robot_bad_person_good", "shelf_disagreements", "kind_disagreements"):
            full["unseen_reviews"].pop(k, None)
        # Only the fair test can say the good pile is ready.
        full["good_pile"]["ready_to_switch_on"] = full["unseen_reviews"]["good_pile"]["ready_to_switch_on"]
        return full
    by_id = {r["file_id"]: r for r in rows}
    files = (decisions or {}).get("files") or {}
    pairs = []
    for fid, d in files.items():
        r = by_id.get(fid)
        if r is None or (since and (d.get("updated_at") or "") < since):
            continue
        if d.get("robot") == "agreed_all":
            continue   # agreed in bulk without looking: says nothing about the robot
        pairs.append((fid, d, r))

    status_pairs = [(fid, d, r) for fid, d, r in pairs if d.get("status") in {"good", "bad"}]
    confusion = Counter((r["verdict"], d["status"]) for _f, d, r in status_pairs)
    good_pile = [(f, d, r) for f, d, r in status_pairs if r["verdict"] == "good"]
    bad_pile = [(f, d, r) for f, d, r in status_pairs if r["verdict"] == "bad"]
    good_right = sum(1 for _f, d, _r in good_pile if d["status"] == "good")
    bad_right = sum(1 for _f, d, _r in bad_pile if d["status"] == "bad")
    person_good = sum(1 for _f, d, _r in status_pairs if d["status"] == "good")
    person_bad = sum(1 for _f, d, _r in status_pairs if d["status"] == "bad")

    def reason_of(d: dict[str, Any]) -> str:
        bits = list(d.get("reasons") or [])
        if d.get("other_reason"):
            bits.append(d["other_reason"])
        return ", ".join(bits) or "-"

    wrong_good = [{"file_id": f, "name": r["name"], "person_said": "bad: " + reason_of(d),
                   "robot_said": "good", "robot_why": r["verdict_why"]}
                  for f, d, r in good_pile if d["status"] != "good"]
    wrong_bad = [{"file_id": f, "name": r["name"], "person_said": "good",
                  "robot_said": "bad: " + (r["reason"] or ""), "robot_why": r["verdict_why"]}
                 for f, d, r in bad_pile if d["status"] != "bad"]
    reason_match = Counter()
    for _f, d, r in bad_pile:
        if d["status"] == "bad":
            person = {x.casefold() for x in (d.get("reasons") or [])} | (
                {"clip"} if "clip" in (d.get("other_reason") or "").casefold() else set())
            reason_match["same reason" if (r["reason"] or "").casefold() in person else "different reason"] += 1

    shelf_pairs = [(f, d, r) for f, d, r in pairs if d.get("shelf")]
    shelf_right = [(f, d, r) for f, d, r in shelf_pairs if d["shelf"] == r["shelf"]]
    shelf_wrong = [{"file_id": f, "name": r["name"], "person_shelf": d["shelf"], "robot_shelf": r["shelf"],
                    "robot_format": r["format"], "clues": r["format_clues"]}
                   for f, d, r in shelf_pairs if d["shelf"] != r["shelf"]]

    # "What is it?" picks. Taps on Agree copy the robot's kind, so they don't count.
    kind_pairs = [(f, d, r) for f, d, r in pairs if d.get("kind") and not d.get("robot") and r.get("kind")]
    kind_right = sum(1 for _f, d, r in kind_pairs if d["kind"] == r["kind"])
    kind_wrong = Counter((r["kind"], d["kind"]) for _f, d, r in kind_pairs if d["kind"] != r["kind"])

    def pct(a: int, b: int) -> float | None:
        return round(a / b, 3) if b else None

    good_precision = pct(good_right, len(good_pile))
    return {
        "reviewed_files_compared": len(status_pairs),
        "person_good": person_good, "person_bad": person_bad,
        "good_pile": {"size": len(good_pile), "person_agreed": good_right, "agreement": good_precision,
                      "share_of_person_good_found": pct(good_right, person_good),
                      "target": GOOD_PILE_TARGET,
                      "ready_to_switch_on": bool(good_precision is not None and good_precision >= GOOD_PILE_TARGET
                                                 and len(good_pile) >= 100)},
        "bad_pile": {"size": len(bad_pile), "person_agreed": bad_right, "agreement": pct(bad_right, len(bad_pile)),
                     "share_of_person_bad_found": pct(bad_right, person_bad), "reasons": dict(reason_match)},
        "sent_to_review": sum(1 for _f, _d, r in status_pairs if r["verdict"] == "review"),
        "confusion": {f"robot {a} / person {b}": n for (a, b), n in sorted(confusion.items())},
        "shelf": {"compared": len(shelf_pairs), "agreed": len(shelf_right),
                  "agreement": pct(len(shelf_right), len(shelf_pairs))},
        "robot_good_person_bad": wrong_good,
        "robot_bad_person_good": wrong_bad,
        "shelf_disagreements": shelf_wrong,
        "kind": {"compared": len(kind_pairs), "agreed": kind_right, "agreement": pct(kind_right, len(kind_pairs))},
        "kind_disagreements": [{"robot": a, "person": b, "files": n} for (a, b), n in kind_wrong.most_common()],
    }


def markdown(rep: dict[str, Any]) -> str:
    g, b, s = rep["good_pile"], rep["bad_pile"], rep["shelf"]

    def p(x: float | None) -> str:
        return "n/a" if x is None else f"{x * 100:.1f}%"

    u = rep.get("unseen_reviews") or {}
    ug = u.get("good_pile") or {}
    lines = [
        "## Auto-sort accuracy (against your reviews)",
        f"Compared with **{rep['reviewed_files_compared']}** files you already marked "
        f"({rep['person_good']} good, {rep['person_bad']} bad).",
        "",
        f"- **Good pile:** {g['size']} files, you agreed on {g['person_agreed']} ({p(g['agreement'])}). "
        f"It needs {p(g['target'])} on at least 100 fresh reviews before it may approve anything on its own: "
        f"**{'ready' if g['ready_to_switch_on'] else 'not yet'}**.",
        f"- **Bad pile:** {b['size']} files, you agreed on {b['person_agreed']} ({p(b['agreement'])}).",
        f"- **Sent to you to check:** {rep['sent_to_review']} files.",
        f"- **Shelf (VHS / other media / not catalog):** agreed on {s['agreed']} of {s['compared']} ({p(s['agreement'])}).",
        f"- **What is it (kinds you picked):** agreed on {rep['kind']['agreed']} of {rep['kind']['compared']} "
        f"({p(rep['kind']['agreement'])}).",
        f"- **Fair test (reviews made after these rules were written):** {u.get('reviewed_files_compared', 0)} files so far; "
        f"good pile {ug.get('person_agreed', 0)} of {ug.get('size', 0)} ({p(ug.get('agreement'))}).",
    ]
    for title, key, a, b2 in (("Robot said good, you said bad", "robot_good_person_bad", "person_said", "robot_why"),
                              ("Robot said bad, you said good", "robot_bad_person_good", "robot_said", "robot_why")):
        rows = rep[key]
        if rows:
            lines += ["", f"### {title} ({len(rows)})"]
            for r in rows[:25]:
                lines.append(f"- {r['name']}: {r[a]}" + (f" (robot: {'; '.join(r[b2])})" if key.startswith("robot_bad") else ""))
    if rep["shelf_disagreements"]:
        lines += ["", f"### Shelf disagreements ({len(rep['shelf_disagreements'])})"]
        for r in rep["shelf_disagreements"][:15]:
            lines.append(f"- {r['name']}: you {r['person_shelf']}, robot {r['robot_shelf']} ({r['robot_format']})")
    if rep.get("kind_disagreements"):
        lines += ["", "### Kinds you changed"]
        for r in rep["kind_disagreements"][:15]:
            lines.append(f"- robot {r['robot']} → you {r['person']}: {r['files']} files")
    return "\n".join(lines) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--auto-sort", required=True)
    ap.add_argument("--decisions", required=True)
    ap.add_argument("--output-dir", required=True)
    a = ap.parse_args()
    rows = [json.loads(x) for x in Path(a.auto_sort).read_text(encoding="utf-8").splitlines() if x.strip()]
    dp = Path(a.decisions)
    decisions = json.loads(dp.read_text(encoding="utf-8")) if dp.is_file() else {}
    rep = evaluate(rows, decisions)
    out = Path(a.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "accuracy.json").write_text(json.dumps(rep, indent=2, ensure_ascii=False) + "\n")
    md = markdown(rep)
    (out / "accuracy.md").write_text(md, encoding="utf-8")
    print(md)


if __name__ == "__main__":
    main()
