#!/usr/bin/env python3
"""Name each tape of a movie and pick the copy that plays first.

Two steps, run after the movie list is built:

  measure  Looks at a few frames of every file for black bars, to know the real
           shape of the picture. Uses the Archive's own preview frames (small
           JPEGs), or a few seconds of the video when a page has none. Results
           are kept between runs, so each file is only looked at once.

  name     Gives every file a tape name ("1992 VHS · Full screen"), puts copies
           of the same recording together as one tape (the other copies become
           backups the TV tries when one won't play), and ranks the tapes so the
           best one plays first. Writes the names into both movie lists.

Nothing is deleted: every file stays in the lists, only grouped and named.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import statistics
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

CHECK_VERSION = 1
UA = {"User-Agent": "VHS-FEVER-Catalog/0.3 (picture check)"}
I = re.I


# --------------------------------------------------------------------------
# Reading the movie list
# --------------------------------------------------------------------------
def read_jsonl(path: Path | None) -> list[dict[str, Any]]:
    if not path or not path.exists():
        return []
    with path.open(encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")


def feed_files(feed: dict[str, Any]) -> Iterable[tuple[dict, dict | None, dict, list[dict], dict]]:
    """(title, edition or None, recording, the files of its segment, file) for every file."""
    for t in feed["titles"]:
        for r in t.get("un") or []:
            for seg in r.get("segs") or []:
                for x in seg.get("src") or []:
                    yield t, None, r, seg["src"], x
        for e in t.get("eds") or []:
            for r in e.get("recs") or []:
                for seg in r.get("segs") or []:
                    for x in seg.get("src") or []:
                        yield t, e, r, seg["src"], x


def is_copy(x: dict[str, Any]) -> bool:
    return x.get("role") == "archive_derivative"


# --------------------------------------------------------------------------
# measure: where the picture is in the frame
# --------------------------------------------------------------------------
def _get(url: str, timeout: int = 40) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read()


def lit_box(img: Any) -> list[int] | None:
    """The part of a frame that isn't black bars: [left, top, right, bottom, width, height]."""
    g = img.convert("L")
    w, h = g.size
    px = g.load()
    step = max(1, w // 160)
    xs, ys = range(0, w, step), range(0, h, step)
    rows = [y for y in range(h) if sum(px[x, y] for x in xs) / len(xs) > 22]
    cols = [x for x in range(w) if sum(px[x, y] for y in ys) / len(ys) > 22]
    if not rows or not cols:
        return None
    return [cols[0], rows[0], cols[-1] + 1, rows[-1] + 1, w, h]


class Pictures:
    def __init__(self) -> None:
        self.items: dict[str, list[str] | None] = {}
        self.lock = threading.Lock()

    def item_files(self, item: str) -> list[str] | None:
        with self.lock:
            if item in self.items:
                return self.items[item]
        try:
            names = [f["name"] for f in json.loads(_get("https://archive.org/metadata/" + urllib.parse.quote(item)))
                     .get("files") or []]
        except Exception:  # noqa: BLE001
            names = None
        with self.lock:
            self.items[item] = names
        return names

    def from_thumbs(self, item: str, name: str) -> list[list[int]] | None:
        names = self.item_files(item)
        if not names:
            return None
        stem = name.rsplit(".", 1)[0]
        thumbs = sorted(n for n in names if ".thumbs/" in n and n.split("/")[-1].rsplit("_", 1)[0] == stem)
        if len(thumbs) < 3:
            return None
        from PIL import Image
        boxes = []
        for n in [thumbs[int(len(thumbs) * k / 8)] for k in range(2, 7)]:
            try:
                b = lit_box(Image.open(io.BytesIO(_get(f"https://archive.org/download/{item}/{urllib.parse.quote(n)}"))))
            except Exception:  # noqa: BLE001
                b = None
            if b:
                boxes.append(b)
        return boxes or None

    @staticmethod
    def from_video(url: str, dur: float | None, w: int | None, h: int | None) -> list[list[int]] | None:
        if not w or not h:
            return None
        boxes = []
        for frac in (0.4, 0.6):
            try:
                p = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-ss", str(int((dur or 3600) * frac)), "-i", url,
                                    "-t", "6", "-vf", "fps=1/2,cropdetect=limit=24:round=2:reset=0", "-an", "-f", "null", "-"],
                                   capture_output=True, text=True, timeout=120)
            except Exception:  # noqa: BLE001
                continue
            m = re.findall(r"crop=(\d+):(\d+):(\d+):(\d+)", p.stderr)
            if m:
                cw, ch, x, y = (int(v) for v in m[-1])
                boxes.append([x, y, x + cw, y + ch, w, h])
        return boxes or None


def measure(feed_path: Path, audio_path: Path, previous: Path | None, output: Path,
            workers: int, budget_minutes: float) -> dict[str, int]:
    feed = json.loads(feed_path.read_text(encoding="utf-8"))
    audio = {a["file_id"]: a for a in read_jsonl(audio_path)}
    prev = {p["file_id"]: p for p in read_jsonl(previous) if p.get("check_version") == CHECK_VERSION}
    todo, keep, seen = [], {}, set()
    for _t, _e, _r, seg, x in feed_files(feed):
        if x["id"] in seen or (is_copy(x) and any(not is_copy(s) for s in seg)):
            continue   # the Archive's copy has the same picture as the file it was made from
        seen.add(x["id"])
        old = prev.get(x["id"])
        # a file with no preview frames and no readable video gets another try after 30 days
        if old and not (old.get("none") and (time.time() - _ts(old.get("checked_at"))) > 30 * 86400):
            keep[x["id"]] = old
        else:
            todo.append(x)
    pics = Pictures()
    deadline = time.time() + budget_minutes * 60

    def run(x: dict[str, Any]) -> dict[str, Any] | None:
        if time.time() > deadline:
            return None   # left for the next run
        a = audio.get(x["id"]) or {}
        res = {"file_id": x["id"], "check_version": CHECK_VERSION,
               "checked_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
        boxes = pics.from_thumbs(x["item"], x["n"])
        if boxes:
            res.update(boxes=boxes, via="preview frames")
        else:
            url = x.get("url") or f"https://archive.org/download/{x['item']}/{urllib.parse.quote(x['n'])}"
            boxes = pics.from_video(url, x.get("dur"), a.get("width"), a.get("height"))
            res.update(boxes=boxes, via="video") if boxes else res.update(none=True)
        return res

    new = 0
    with ThreadPoolExecutor(workers) as ex:
        for res in ex.map(run, todo):
            if res:
                keep[res["file_id"]] = res
                new += 1
    write_jsonl(output, keep.values())
    return {"files": len(seen), "reused": len(seen) - len(todo), "measured": new, "left_for_next_run": len(todo) - new}


def _ts(s: str | None) -> float:
    try:
        return datetime.fromisoformat(s).timestamp() if s else 0
    except ValueError:
        return 0


# --------------------------------------------------------------------------
# name: tape names, grouping and the default
# --------------------------------------------------------------------------
YEAR = re.compile(r"(?<!\d)((?:19[4-9]|20[0-2])\d)(?!\d)")
# Words on the file or page that are worth showing. One only counts when every
# copy of the recording agrees, because the same names get copied around.
WORDS = [
    (r"\bwide ?screen\b|\bletter ?box(?:ed)?\b", "Widescreen"),
    (r"\bfull ?screen\b|\bpan (?:and|&) scan\b", "Full screen"),
    (r"\bthx\b", "THX"), (r"masterpiece", "Masterpiece Collection"), (r"black diamond", "Black Diamond"),
    (r"\b(?:walt )?disney classics\b", "Walt Disney Classics"), (r"limited (?:edition|issue)", "Limited Edition"),
    (r"special edition", "Special Edition"), (r"collector'?s", "Collector's Edition"), (r"clam ?shell", "Clamshell"),
    (r"director.?s cut", "Director's Cut"), (r"\buncut\b", "Uncut"), (r"\bunrated\b", "Unrated"),
    (r"\bremaster(?:ed)?\b|\brestored\b", "Remastered"), (r"\bcanad(?:a|ian)\b", "Canadian"),
    (r"\b(?:uk|u\.k\.|british|pal)\b", "UK"), (r"\baustralian?\b|\baus\b", "Australian"),
    (r"\bwith (?:previews|trailers)\b|\bw/ ?previews\b", "With previews"), (r"\brental\b", "Rental copy"),
]
MEDIA_WORDS = [(r"betamax|\bbeta\b", "Betamax"), (r"laser ?disc", "LaserDisc"), (r"\bvhs\b", "VHS")]
MEDIA_NAMES = {"BETAMAX": "Betamax", "LASERDISC": "LaserDisc", "VHS": "VHS"}
PAL_SPEED = 25 / 23.976     # UK tapes run about 4% fast


def frame_shape(w: int | None, h: int | None) -> float | None:
    """How wide the whole frame shows on a TV. Analog captures (480/576 lines) show as 4:3."""
    if not w or not h:
        return None
    if h in (240, 288, 480, 486, 576) and 320 <= w <= 768:
        return 4 / 3
    return w / h


def picture_shape(boxes: list[list[int]] | None, w: int | None, h: int | None) -> tuple[float | None, float | None]:
    """(shape of the picture without black bars, shape of the whole frame)."""
    fa = frame_shape(w, h)
    if not boxes or not fa:
        return None, fa
    x1 = min(b[0] for b in boxes); y1 = min(b[1] for b in boxes)
    x2 = max(b[2] for b in boxes); y2 = max(b[3] for b in boxes)
    tw, th = boxes[0][4], boxes[0][5]
    ca = fa * ((x2 - x1) / tw) / ((y2 - y1) / th)
    return (ca if 1.0 <= ca <= 2.6 else None), fa   # outside that the frames were too dark to tell


def picture_word(ca: float | None, fa: float | None, words: list[str]) -> str | None:
    if ca is None:
        if "Widescreen" in words:
            return "Widescreen"
        return "Full screen" if fa and fa < 1.5 else None
    if ca < 1.6:
        return "Full screen"
    if fa and fa < 1.5:
        return "Widescreen"           # bars top and bottom on the tape itself
    if "Widescreen" in words or ca >= 1.84:
        return "Widescreen"           # said so, or a film shape with bars still there
    return "Zoomed"                   # a tape picture filling a widescreen file: zoomed or stretched to fit


def movie_key(t: dict[str, Any]) -> str:
    if t.get("adult"):
        return "adult:" + t["id"]
    for m in (t.get("movie"), t.get("guess")):
        if m and m.get("tmdb_id"):
            return f"tmdb:{m['tmdb_id']}"
    c = ((t.get("tmdb") or {}).get("c") or [None])[0]
    if c and c.get("tmdb_id") and c.get("media_type", "movie") == "movie":
        return f"tmdb:{c['tmdb_id']}"
    return "title:" + t["id"]


def movie_info(t: dict[str, Any]) -> tuple[int | None, str | None]:
    """(TMDB running time in minutes, film year) for a title, when known."""
    for m in (t.get("movie"), t.get("guess")):
        if m:
            return m.get("runtime"), m.get("year")
    c = ((t.get("tmdb") or {}).get("c") or [None])[0] or {}
    return None, (c.get("release_date") or "")[:4] or None


def _tg(file_id: str) -> str:
    return "t" + hashlib.sha1(file_id.encode()).hexdigest()[:10]


def name_movie(files: list[dict[str, Any]], runtime: int | None, film_year: str | None) -> dict[str, Any]:
    """Tape names, groups and ranks for every file of one movie.

    Each entry of [files] has: id, n, item, role, dur, media, tape_year, notes,
    w, h, sound, ch, boxes. Returns {"files": {id: {lab, tg, pk}}, "note": str|None}.
    """
    # 1. Recordings: the same video uploaded to several pages, or the Archive's
    #    copy of a file. Same release, length (within 30 s), size and sound.
    def rec_key(f: dict[str, Any]) -> tuple:
        return (f["tape_year"], f["w"], f["h"], f["sound"], f["ch"])
    recs: list[list[dict[str, Any]]] = []
    by_key: dict[tuple, list[dict[str, Any]]] = {}
    for f in files:
        by_key.setdefault(rec_key(f), []).append(f)
    for group in by_key.values():
        group.sort(key=lambda f: f["dur"] or 0)
        cur: list[dict[str, Any]] = []
        for f in group:
            if cur and (f["dur"] or 0) - (cur[-1]["dur"] or 0) > 30:
                recs.append(cur); cur = []
            cur.append(f)
        recs.append(cur)

    rows = []
    for fs in recs:
        fs.sort(key=lambda f: (is_copy(f), not f["n"].lower().endswith(".mp4"), f["n"]))
        rep = next((f for f in fs if f.get("boxes")), fs[0])
        words = [lab for pat, lab in WORDS
                 if all(re.search(pat, f["n"] + " " + " ".join(f["notes"]), I) for f in fs)]
        media = next((MEDIA_NAMES.get(f["media"]) for f in fs if f.get("media") in MEDIA_NAMES), None) or next(
            (lab for pat, lab in MEDIA_WORDS if re.search(pat, rep["n"], I)), "VHS")
        ca, fa = picture_shape(rep.get("boxes"), rep["w"], rep["h"])
        pal = rep["h"] == 576 or rep["w"] == 768 or "UK" in words
        rows.append({"files": fs, "rep": rep, "words": words, "media": media, "ca": ca, "fa": fa, "pal": pal,
                     "year": rep["tape_year"], "dur": rep["dur"] or 0, "w": rep["w"], "h": rep["h"],
                     "ch": rep["ch"], "silent": rep["sound"] == "silent_audio",
                     "pages": len({f["item"] for f in fs})})

    def norm(r: dict[str, Any]) -> float:
        return r["dur"] * (PAL_SPEED if r["pal"] else 1)

    def wmedian(rs: list[dict[str, Any]]) -> float:
        xs = sorted(x for r in rs for x in [norm(r)] * r["pages"])
        return xs[len(xs) // 2] if xs else 0

    good = [r for r in rows if not r["silent"]]
    typical = wmedian(good)
    for r in rows:
        r["cut"] = (norm(r) < runtime * 60 * 0.9) if runtime else (norm(r) < typical * 0.8)
    for r in rows:
        tags = []
        pic = picture_word(r["ca"], r["fa"], r["words"])
        if pic:
            tags.append(pic)
        if r["h"] and r["h"] >= 700:
            tags.append("Upscaled HD")
        if r["ch"] == 1:
            tags.append("Mono")
        same = [o for o in good if o is not r and o["year"] == r["year"] and not o["cut"]]
        base = wmedian(same + [r]) if same else norm(r)
        if not r["cut"] and same and 120 < norm(r) - base < 20 * 60 and "With previews" not in r["words"]:
            tags.append("With previews")
        if r["cut"]:
            tags.append("Cut short")
        if r["silent"]:
            tags.append("No sound")
        extra = [w for w in r["words"] if w not in tags and w != "UK"
                 and not (w in ("Widescreen", "Full screen") and pic)]
        year = r["year"]
        if year and year == str(film_year or "") and not any(re.search(r"vhs|beta|laser", f["n"], I) for f in r["files"]):
            year = None   # that's the film's year written on the file, not the tape's
        head = " ".join(p for p in [year, ("UK " if r["pal"] else "") + r["media"] + (" (PAL)" if r["pal"] else "")] if p)
        r["label"] = " · ".join([head] + extra + tags)
        r["score"] = (r["silent"], r["cut"], pic == "Zoomed", "Upscaled HD" in tags, r["ch"] == 1, r["pal"],
                      int(year or r["year"] or 9999), "With previews" not in tags, -r["pages"], -(r["w"] or 0),
                      r["rep"]["id"])

    # 2. Tapes: recordings with the same name and about the same length are
    #    one tape on the TV; the rest of them become its backups.
    rows.sort(key=lambda r: r["score"])
    tapes: list[list[dict[str, Any]]] = []
    for r in rows:
        twin = next((tp for tp in tapes if tp[0]["label"] == r["label"] and abs(tp[0]["dur"] - r["dur"]) < 90), None)
        twin.append(r) if twin else tapes.append([r])
    names = [tp[0]["label"] for tp in tapes]
    out: dict[str, dict[str, Any]] = {}
    for rank, tp in enumerate(tapes):
        label = tp[0]["label"]
        if names.count(label) > 1:
            label += f" · {round(tp[0]['dur'] / 60)} min"
        tg = _tg(tp[0]["rep"]["id"])
        order = 0
        for r in tp:
            for f in r["files"]:
                out[f["id"]] = {"lab": label, "tg": tg, "pk": rank * 1000 + order}
                if tp[0]["silent"]:
                    out[f["id"]]["hide"] = True   # no sound: kept, but not listed when there's another tape
                order += 1

    note = None
    if runtime and good:
        off = [r for r in good if not (0.85 * runtime * 60 <= norm(r) <= 1.35 * runtime * 60)]
        med = statistics.median(norm(r) for r in good) / 60
        if off and sum(r["pages"] for r in off) * 4 >= sum(r["pages"] for r in good) and abs(med - runtime) > runtime * 0.03:
            lens = ", ".join(str(m) for m in sorted({round(norm(r) / 60) for r in off})[:4])
            note = (f"Some tapes run {lens} min but this movie is {runtime} min. "
                    "Either it's matched to the wrong movie or the tape has other things on it.")
    return {"files": out, "note": note}


def name_feed(feed: dict[str, Any], audio: dict[str, dict], pictures: dict[str, dict]) -> dict[str, int]:
    """Writes lab / tg / pk into every file of the movie list, and a length note on titles."""
    movies: dict[str, dict[str, Any]] = {}
    for t, e, r, _seg, x in feed_files(feed):
        k = movie_key(t)
        m = movies.setdefault(k, {"titles": {}, "files": [], "seen": set()})
        m["titles"][t["id"]] = t
        if x["id"] in m["seen"]:
            continue
        m["seen"].add(x["id"])
        a = audio.get(x["id"]) or {}
        p = pictures.get(x["id"])
        ty = (e or {}).get("year")
        if not ty:
            ys = YEAR.findall(x["n"])
            ty = ys[-1] if ys else None
        m["files"].append({
            "id": x["id"], "n": x["n"], "item": x["item"], "role": x.get("role"), "dur": x.get("dur"),
            "media": (e or {}).get("media"), "tape_year": str(ty) if ty else None,
            # the recording's own notes; an edition's notes are everything any of its copies said
            "notes": list(r.get("notes") or []),
            "w": a.get("width"), "h": a.get("height"), "sound": a.get("verdict"), "ch": a.get("audio_channels"),
            "boxes": (p or {}).get("boxes"), "_x": x,
        })
    # The Archive's copy of a file has the same picture as the file it was made from.
    by_id = {f["id"]: f for m in movies.values() for f in m["files"]}
    for t, _e, _r, seg, x in feed_files(feed):
        f = by_id.get(x["id"])
        if f and not f["boxes"] and is_copy(x):
            orig = next((by_id.get(s["id"]) for s in seg if not is_copy(s) and by_id.get(s["id"])), None)
            if orig and orig["boxes"]:
                f["boxes"] = orig["boxes"]
                f["w"], f["h"] = f["w"] or orig["w"], f["h"] or orig["h"]
    named = notes = 0
    for m in movies.values():
        first = next(iter(m["titles"].values()))
        runtime, film_year = movie_info(first)
        res = name_movie(m["files"], runtime, film_year)
        for f in m["files"]:
            f["_x"].update(res["files"][f["id"]])
            named += 1
        for t in m["titles"].values():
            t.pop("tape_note", None)
            if res["note"]:
                t["tape_note"] = res["note"]
                notes += 1
    return {"movies": len(movies), "files_named": named, "length_notes": notes}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="step", required=True)
    m = sub.add_parser("measure", help="look at a few frames of each new file for black bars")
    m.add_argument("--feed", required=True, help="reviewer-feed.json")
    m.add_argument("--audio", required=True, help="sound and size checks (jsonl)")
    m.add_argument("--previous", help="earlier picture checks (jsonl) to reuse")
    m.add_argument("--output", required=True)
    m.add_argument("--workers", type=int, default=24)
    m.add_argument("--budget-minutes", type=float, default=40, help="stop and leave the rest for the next run")
    n = sub.add_parser("name", help="name the tapes and pick the default in both movie lists")
    n.add_argument("--site", required=True, help="folder with reviewer-feed.json and tv-feed.json")
    n.add_argument("--audio", required=True)
    n.add_argument("--pictures", required=True)
    a = ap.parse_args()
    if a.step == "measure":
        s = measure(Path(a.feed), Path(a.audio), Path(a.previous) if a.previous else None, Path(a.output),
                    a.workers, a.budget_minutes)
    else:
        import vhs_movies   # same folder
        site = Path(a.site)
        feed = json.loads((site / "reviewer-feed.json").read_text(encoding="utf-8"))
        audio = {x["file_id"]: x for x in read_jsonl(Path(a.audio))}
        pictures = {x["file_id"]: x for x in read_jsonl(Path(a.pictures))}
        s = name_feed(feed, audio, pictures)
        (site / "reviewer-feed.json").write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        tv = vhs_movies.tv_feed(feed)
        for _t, _e, _r, _seg, x in feed_files(tv):
            if x.get("pk", 0) % 1000:
                x.pop("lab", None)   # the TV takes a tape's name from its first file; keeps the list small
        (site / "tv-feed.json").write_text(json.dumps(tv, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(json.dumps(s, indent=2))


if __name__ == "__main__":
    main()
