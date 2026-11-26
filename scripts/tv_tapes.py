#!/usr/bin/env python3
"""Off-air TV tapes: what an Archive page says about a tape recorded off live TV.

The Oakley Tapes uploads (The Don Gorelick Television Collection) are whole
VHS tapes recorded off TV, commercials and all. Their video files are named by
the capture device ("0002_0102_045252.mp4", "20240121_221007.mp4"), so the file
name says nothing. The page does:

* the title: tape number, what was recorded and when it aired,
  "449 NEWS & NIGHTLINE WED 2001 APR 11";
* the Archive "date" field: the first air date;
* sometimes a description with a lineup by date and channel,
  "December 3 1998 / America's Funniest Home Videos (USA)".

``read_page`` turns that into one tidy record (shows, channels, air dates,
lineup). ``tape_pieces`` puts the capture files of one tape in order: a tape is
usually cut into a few long files, and played in order they are the whole tape.
Standard library only.
"""
from __future__ import annotations

import html
import re
from datetime import date
from pathlib import PurePosixPath
from typing import Any, Iterable

I = re.IGNORECASE

MONTHS = {"jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6, "jul": 7, "aug": 8,
          "sep": 9, "oct": 10, "nov": 11, "dec": 12}
MON = r"(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|" \
      r"oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?"
WEEKDAY = r"(?:mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)\.?"
YEAR = r"(?:19|20)\d\d"
YY = r"(?:(?:19|20)\d\d|\d\d)"

# A weekday only counts when a date follows it ("SAT 2001 MAR 03"), so
# "SAT. NITE LIVE" stays part of the show name.
WEEKDAY_BEFORE_DATE = re.compile(rf"\b{WEEKDAY}(?=[\s,.]+(?:{WEEKDAY}[\s,.]+)?(?:{YY}\b|{MON}|\d{{1,2}}-\d{{1,2}}-\d\d\b))", I)
# "2001 APR 11", "94 APR 29", "1988 WED JUN 29"
Y_M_D = re.compile(rf"\b(?P<y>{YY})\s+(?:{WEEKDAY}\s+)?(?P<m>{MON})\s*(?P<d>\d{{1,2}})(?!\d)", I)
# "APR 21 1990", "JANUARY 31 1988", "OCT 31 1997"
M_D_Y = re.compile(rf"\b(?P<m>{MON})\s*(?P<d>\d{{1,2}}),?\s+(?P<y>{YEAR})\b", I)
# "5-23-98"
NUMERIC = re.compile(r"\b(?P<m>0?[1-9]|1[0-2])-(?P<d>0?[1-9]|[12]\d|3[01])-(?P<y>\d\d)\b")
# "19940527", "20010106" at the start; "200605" (a month)
COMPACT = re.compile(rf"^(?P<y>{YEAR})(?P<m>0[1-9]|1[0-2])(?P<d>0[1-9]|[12]\d|3[01])?$")
# "19981202 - 19981206"
COMPACT_IN_TEXT = re.compile(rf"\b(?P<y>{YEAR})(?P<m>0[1-9]|1[0-2])(?P<d>0[1-9]|[12]\d|3[01])\b")
# "1988 AUG", "MAY 2006": a month only
Y_M = re.compile(rf"\b(?P<y>{YEAR})\s+(?P<m>{MON})(?!\s*\d)", I)
M_Y = re.compile(rf"\b(?P<m>{MON})\s+(?P<y>{YEAR})\b", I)
# What may follow a full date: "- AUG 21", ", MAR 09", "& AUG 29", "- 07", "-24", " 30"
MORE = re.compile(rf"^\s*(?:[-,&]|\band\b)?\s*(?:{WEEKDAY}\.?\s*)?(?:(?P<y>{YEAR})\s+)?(?:(?P<m>{MON})\s*)?(?P<d>\d{{1,2}})(?!\d)", I)

# Shows that come up on these tapes, with the channel they aired on.
# (pattern, name, channel or [(last year, channel), ...])
KNOWN_SHOWS: list[tuple[re.Pattern[str], str, Any]] = [
    (re.compile(r"\bent(?:ertain(?:ment)?)?\.?\s*(?:tonight|tonite|2-?nite)\b", I), "Entertainment Tonight", "Syndicated"),
    (re.compile(r"\bd(?:ave|avid)?\.?\s*lett(?:erman)?\b\.?", I), "David Letterman", [(1993, "NBC"), (2015, "CBS")]),
    (re.compile(r"\bj(?:ohnny)?\.?\s*carson\b", I), "The Tonight Show Starring Johnny Carson", "NBC"),
    (re.compile(r"\bj(?:ay)?\.?\s*leno\b", I), "The Tonight Show with Jay Leno", "NBC"),
    (re.compile(r"\bnight-?line\b", I), "Nightline", "ABC"),
    (re.compile(r"\bsis(?:kel)?\.?\s*(?:&|and)?\s*ebert\b", I), "Siskel & Ebert", "Syndicated"),
    (re.compile(r"\blarry king\b", I), "Larry King Live", "CNN"),
    (re.compile(r"\bshowbiz today\b", I), "Showbiz Today", "CNN"),
    (re.compile(r"\brosie\s+o'?\s*donnell\b", I), "The Rosie O'Donnell Show", "Syndicated"),
    (re.compile(r"\bellen\s+degeneres\b", I), "The Ellen DeGeneres Show", "Syndicated"),
    (re.compile(r"\btoday show\b", I), "Today", "NBC"),
    (re.compile(r"\b700 club\b", I), "The 700 Club", None),
    (re.compile(r"\bsat(?:urday)?\.?\s*(?:night|nite)\s*live\b", I), "Saturday Night Live", "NBC"),
    (re.compile(r"\bc-?span\b", I), "C-SPAN", "C-SPAN"),
    (re.compile(r"\bsuper ?bowl\b", I), "Super Bowl", None),
    (re.compile(r"\bnews magazine shows\b", I), "News magazine shows", None),
    (re.compile(r"\btrailer park\b", I), "Trailer Park", None),
]
LETTERMAN = {"NBC": "Late Night with David Letterman", "CBS": "Late Show with David Letterman"}

TAPE_WORDS = re.compile(r"\b(?:ent(?:ertain(?:ment)?)?\.?\s*tape|tape)\b", I)
NEWS = re.compile(r"\bnews\b", I)
SEPARATORS = re.compile(r"\s*(?:,|&|\+|;|\band\b|\s-\s)\s*", I)
CHANNEL_IN_PARENS = re.compile(r"\(([^()]{2,20})\)\s*(?:\(partial\))?\s*$")

# Capture-device file names: "20240121_221007", "0002_0102_045252", "2024-09-22_00-41-11".
CAPTURE_NAME = re.compile(r"^(?:\d{8}_\d{6}|\d{4}_\d{4}_\d{6}|\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d)$")
DERIVATIVE = re.compile(r"(?:\.ia|_512kb|\.512kb)$", I)


def is_tv_tape_page(item: dict[str, Any]) -> bool:
    """An off-air TV tape page: an Oakley Tapes upload or the Gorelick TV collection."""
    ident = str(item.get("identifier") or item.get("archive_identifier") or "")
    cols = item.get("collection") or []
    cols = [cols] if isinstance(cols, str) else cols
    return ident.lower().startswith("oakleytapes") or any(str(c).lower() == "gorelicktv" for c in cols)


def _month(text: str) -> int:
    return MONTHS[text.lower().rstrip(".")[:3]]


def _year(text: str) -> int:
    y = int(text)
    if y < 100:
        y += 1900 if y > 30 else 2000
    return y


def _day(y: int, m: int, d: int) -> date | None:
    try:
        return date(y, m, d)
    except ValueError:
        return None


def _dates(text: str) -> tuple[list[date], str | None, int | None]:
    """Every air date in a tape title, in the order written, and where the date
    part starts. Ranges ("AUG 17 - AUG 21", "JUNE 06 - 07", "Sept 29 30") give
    their last day too. Also returns a month ("1988-08") when only that is known."""
    found: list[tuple[int, date]] = []
    month_only = None
    starts: list[int] = []
    for m in COMPACT_IN_TEXT.finditer(text):
        d = _day(int(m["y"]), int(m["m"]), int(m["d"]))
        if d:
            found.append((m.start(), d))
            starts.append(m.start())
    for rx in (Y_M_D, M_D_Y, NUMERIC):
        for m in rx.finditer(text):
            d = _day(_year(m["y"]), _month(m["m"]) if not m["m"].isdigit() else int(m["m"]), int(m["d"]))
            if not d:
                continue
            found.append((m.start(), d))
            starts.append(m.start())
            # Follow-on days without a year: "- AUG 21", ", MAR 09", " 30"
            rest, last = text[m.end():], d
            while True:
                n = MORE.match(rest)
                if not n:
                    break
                y = int(n["y"]) if n["y"] else last.year
                mo = _month(n["m"]) if n["m"] else last.month
                nd = _day(y, mo, int(n["d"]))
                if not nd:
                    break
                if nd < last and not n["y"]:
                    # "DEC 27 - JAN 3": into the next year; "SEPT 30 - 01": next month
                    nd = _day(y + 1, mo, int(n["d"])) if n["m"] else _day(y + (mo == 12), mo % 12 + 1, int(n["d"]))
                    if not nd:
                        break
                found.append((m.end() + n.start(), nd))
                rest, last = rest[n.end():], nd
    if not found:
        for rx in (Y_M, M_Y):
            m = rx.search(text)
            if m:
                month_only = f"{int(m['y'])}-{_month(m['m']):02d}"
                starts.append(m.start())
                break
    seen: dict[date, int] = {}
    first = None
    for pos, d in sorted(found):
        if first is None:
            first = d
        elif d < first and (first - d).days > 300:
            # "WED 1989 DEC 27 - WED 1989 JAN 3": the year on the label is a slip
            d = _day(d.year + 1, d.month, d.day) or d
        seen.setdefault(d, pos)
    return list(seen), month_only, (min(starts) if starts else None)


def _title_case(text: str) -> str:
    words = []
    for w in text.split():
        paren = re.fullmatch(r"\(([A-Z]{5,})\)", w)
        if paren:   # "(GENERAL)" -> "(General)"; "(NHL)" stays
            words.append("(" + paren.group(1).title() + ")")
            continue
        if re.fullmatch(r"[A-Z0-9.'&-]+", w) and len(w) > 1 and not re.fullmatch(r"(?:[A-Z]\.)+", w):
            w = "-".join(p[:1] + p[1:].lower() for p in w.split("-"))
        words.append(w)
    return " ".join(words)


def _channel(rule: Any, year: int | None) -> str | None:
    if isinstance(rule, list):
        if year is None:
            return None
        for last, ch in rule:
            if year <= last:
                return ch
        return None
    return rule


def read_shows(text: str, year: int | None) -> tuple[list[dict[str, Any]], str | None]:
    """Shows named in the program part of a tape title, and the tape type
    ("News", "Entertainment"). 'NEWS, ENT. TONIGHT, D. LETT' -> News,
    Entertainment Tonight (Syndicated), Late Show with David Letterman (CBS)."""
    kind = None
    if re.search(r"\bent(?:ertain(?:ment)?)?\.?\s*tape\b", text, I):
        kind = "Entertainment"
    text = re.sub(r"\bnews\s*-\s*(?=ent)", "NEWS, ", text, flags=I)   # "NEWS-ENT. 2-NITE"
    shows: list[tuple[int, dict[str, Any]]] = []
    rest = text
    for rx, name, rule in KNOWN_SHOWS:
        m = rx.search(rest)
        if not m:
            continue
        at = rx.search(text)
        ch = _channel(rule, year)
        if name == "David Letterman":
            name = LETTERMAN.get(ch or "", "David Letterman")
        shows.append((at.start() if at else m.start(), {"name": name, "channel": ch, "from": "tape title"}))
        rest = rest[: m.start()] + " | " + rest[m.end():]
    rest = TAPE_WORDS.sub(" | ", rest)
    rest = re.sub(r"[\[\]]", " | ", rest)
    rest = re.sub(r"\(\s*\|\s*\)", " | ", rest)
    pos = 0
    for piece in re.split(r"\|", rest):
        for part in SEPARATORS.split(piece):
            part = part.strip(" .-:,")
            inner = re.fullmatch(r"\((.*)\)", part)
            if inner:
                part = inner.group(1).strip(" .-:,")
            idx = text.find(part) if part else -1
            if not part or not re.search(r"[A-Za-z]", part):
                continue
            if NEWS.fullmatch(part):
                shows.append((idx, {"name": "News", "channel": None, "from": "tape title"}))
                kind = kind or "News"
                continue
            if re.fullmatch(r"unk(?:nown)?\.?", part, I):
                shows.append((idx, {"name": "Unknown program", "channel": None, "from": "tape title"}))
                continue
            name = _title_case(part)
            if name.lower() in {"local hr.", "local hr"}:
                continue
            shows.append((idx if idx >= 0 else 10_000 + pos, {"name": name, "channel": None, "from": "tape title"}))
            pos += 1
    shows.sort(key=lambda s: s[0])
    out, seen = [], set()
    for _p, s in shows:
        if s["name"].lower() not in seen:
            seen.add(s["name"].lower())
            out.append(s)
    if kind is None and out and out[0]["name"] == "News":
        kind = "News"
    return out, kind


def read_lineup(description: Any) -> list[dict[str, Any]]:
    """A lineup in the page description: date headings, then "Show (CHANNEL)" lines.

        December 3 1998
        America's Funniest Home Videos (USA)
        Star Trek: ... "Plato's Stepchildren" (Sci-Fi) (partial)
    """
    if isinstance(description, list):
        description = "\n".join(str(x) for x in description)
    if not description:
        return []
    text = re.sub(r"<\s*(?:br|/div|/p|/li)\s*/?\s*>", "\n", str(description), flags=I)
    text = html.unescape(re.sub(r"<[^>]+>", "", text))
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    out: list[dict[str, Any]] = []
    current: str | None = None
    unknown = False
    heading = re.compile(rf"^(?:{WEEKDAY},?\s+)?(?:(?P<m>{MON})\s+(?P<d>\d{{1,2}}),?\s+(?P<y>{YEAR})|unknown date,?\s+(?P<uy>{YEAR}))\s*$", I)
    for ln in lines:
        h = heading.match(ln)
        if h:
            if h["uy"]:
                current, unknown = h["uy"], True
            else:
                d = _day(int(h["y"]), _month(h["m"]), int(h["d"]))
                current, unknown = (d.isoformat() if d else None), False
            continue
        if current is None:
            continue   # notes before the first date ("This tape will be rerecorded...")
        partial = bool(re.search(r"\(partial\)\s*$", ln, I))
        ln2 = re.sub(r"\s*\(partial\)\s*$", "", ln, flags=I)
        ch = CHANNEL_IN_PARENS.search(ln2)
        show = ln2[: ch.start()].strip() if ch else ln2
        out.append({"date": None if unknown else current, "year": int(current[:4]) if current else None,
                    "show": show, "channel": ch.group(1).strip() if ch else None, "partial": partial})
    return out


def read_page(item: dict[str, Any]) -> dict[str, Any] | None:
    """Everything a TV-tape page says about its tape. None if it isn't one."""
    if not is_tv_tape_page(item):
        return None
    title = str(item.get("title") or "").strip()
    desc = item.get("description")
    desc_text = " ".join(desc) if isinstance(desc, list) else str(desc or "")
    first_desc_line = html.unescape(re.split(r"<\s*(?:br|/div|/p)\s*/?\s*>|\n", re.sub(r"^\s*<div>", "", desc_text), maxsplit=1)[0])
    first_desc_line = re.sub(r"<[^>]+>", "", first_desc_line).strip()
    # "[1739] 19981202 - 19981206 (Entertainment)": the description's first
    # line is the tape's own label ("1739 Entertain Tape Wed 98 Dec 2 6").
    label = title
    bracket = re.match(r"^\[(\d+)\]", title)
    if bracket and first_desc_line.startswith(bracket.group(1)):
        label = first_desc_line
    tape_number = None
    lead = re.match(r"^\s*\[?(\d{1,8})\]?\s*", label)
    lead_date = None
    if lead:
        num = lead.group(1)
        cm = COMPACT.match(num)
        if len(num) >= 6 and cm:
            lead_date = (_day(int(cm["y"]), int(cm["m"]), int(cm["d"])) if cm["d"] else None) or \
                        f"{cm['y']}-{cm['m']}"
        else:
            tape_number = num
        rest = label[lead.end():]
    else:
        rest = label
    dates, month_only, date_at = _dates(rest)
    wd = WEEKDAY_BEFORE_DATE.search(rest)
    cut = min(x for x in (date_at, wd.start() if wd else None) if x is not None) \
        if (date_at is not None or wd) else len(rest)
    program_text = rest[:cut]
    # A program name after the dates ("SAT NIGHT LIVE MAY 2006 - UNK 15") is rare; keep the front.
    if isinstance(lead_date, date):
        dates = [lead_date] + [d for d in dates if d != lead_date]
    elif isinstance(lead_date, str) and not dates:
        month_only = month_only or lead_date
    page_date = None
    if item.get("date"):
        m = re.match(r"^(\d{4})-(\d{2})-(\d{2})", str(item["date"]))
        page_date = _day(int(m[1]), int(m[2]), int(m[3])) if m else None
    start = dates[0] if dates else page_date
    if page_date and dates and page_date in dates:
        start = min(dates)
    year = start.year if start else (int(month_only[:4]) if month_only else None)
    shows, kind = read_shows(program_text, year)
    lineup = read_lineup(item.get("description")) if desc_text.strip() and desc_text.strip() != title else []
    if lineup:
        # The uploader's own lineup is better than the short tape label.
        shows, known = [], set()
        for row in lineup:
            if row["show"].lower() not in known:
                known.add(row["show"].lower())
                shows.append({"name": row["show"], "channel": row["channel"], "from": "page description"})
    elif not shows and title.startswith("["):
        paren = re.search(r"\(([^()]+)\)\s*$", title)
        if paren:
            if re.fullmatch(r"\s*(?:entertainment|news)\s*", paren.group(1), I):
                kind = kind or paren.group(1).strip().title()
            else:
                shows, kind2 = read_shows(paren.group(1), year)
                kind = kind or kind2
    end = max(dates) if dates else None
    channels = sorted({s["channel"] for s in shows if s["channel"] and s["channel"] != "Syndicated"})
    transcripts = sorted({f["name"] for f in item.get("files") or []
                          if re.search(r"\.asr\.srt$", str(f.get("name") or ""), I)})
    return {
        "source": "Oakley Tapes (The Don Gorelick Television Collection)",
        "tape_number": tape_number,
        "tape_label": label,
        "tape_type": kind,
        "shows": shows,
        "channels": channels,
        "air_date": start.isoformat() if start else None,
        "air_date_end": end.isoformat() if end and start and end > start else None,
        "air_dates": [d.isoformat() for d in sorted(set(dates))],
        "air_month": month_only if not start else None,
        "air_date_from": "tape title" if dates else ("Archive date" if page_date else None),
        "lineup": lineup,
        "transcripts": transcripts,
        "commercials": "likely",
    }


def display_title(tape: dict[str, Any]) -> str:
    """'Tape 449: News + Nightline (Apr 11, 2001)'."""
    names = [s["name"] for s in tape["shows"]]
    what = " + ".join(names[:4]) + (" + more" if len(names) > 4 else "") if names else \
        (f"{tape['tape_type']} tape" if tape.get("tape_type") else "TV recording")
    when = date_label(tape)
    head = f"Tape {tape['tape_number']}: " if tape.get("tape_number") else ""
    return f"{head}{what}" + (f" ({when})" if when else "")


def date_label(tape: dict[str, Any]) -> str | None:
    if tape.get("air_date"):
        a = date.fromisoformat(tape["air_date"])
        b = date.fromisoformat(tape["air_date_end"]) if tape.get("air_date_end") else None
        fa = f"{a:%b} {a.day}"
        if not b:
            return f"{fa}, {a.year}"
        if b.year != a.year:
            return f"{fa}, {a.year} – {b:%b} {b.day}, {b.year}"
        if b.month == a.month:
            return f"{fa}–{b.day}, {a.year}"
        return f"{fa} – {b:%b} {b.day}, {a.year}"
    if tape.get("air_month"):
        y, m = tape["air_month"].split("-")
        return f"{date(int(y), int(m), 1):%b} {y}"
    return None


def _stem(name: str) -> str:
    stem = PurePosixPath(name).stem
    return DERIVATIVE.sub("", stem)


def is_tape_piece(name: str, tape: dict[str, Any]) -> bool:
    """A capture file of the tape itself, not something else uploaded with it
    (such as a screen recording of the capture software)."""
    stem = _stem(name)
    if CAPTURE_NAME.match(stem):
        return True
    num = tape.get("tape_number")
    return bool(num and re.match(rf"^\[?{re.escape(num)}\]?\s", stem))


def tape_pieces(names: Iterable[str], tape: dict[str, Any]) -> list[str]:
    """The tape's original capture files, in recording order."""
    return sorted({n for n in names if is_tape_piece(n, tape)})


def reasons(tape: dict[str, Any], piece: int | None, pieces: int | None) -> list[str]:
    """Short lines for the review app's "why" list."""
    out = ["Taped off live TV" + (f" (Oakley Tapes #{tape['tape_number']})" if tape.get("tape_number") else "")]
    when = date_label(tape)
    if tape.get("air_date") and not tape.get("air_date_end"):
        when = f"{date.fromisoformat(tape['air_date']):%a}, {when}"
    if when:
        out.append(f"Aired {when}")
    if tape["shows"]:
        out.append("On the tape: " + ", ".join(
            s["name"] + (f" ({s['channel']})" if s.get("channel") else "") for s in tape["shows"][:8]))
    if pieces and pieces > 1 and piece:
        out.append(f"Piece {piece} of {pieces} of the tape (play in order)")
    out.append("Commercials probably still in it")
    return out
