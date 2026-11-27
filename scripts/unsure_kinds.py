#!/usr/bin/env python3
"""Kinds of tapes that are only kept when the bot is sure about them.

Kids' TV tapes (episode collections of Thomas, Postman Pat, Barney ...) and
other TV-series tapes, home videos, instructional tapes and foreign-language
tapes are left out of the movie list unless the bot found a clear movie match
for them. Brian's call (Nov 27 2026): "any that aren't bot sure are dropped".
Standard library only.
"""
from __future__ import annotations

import re

# Shows whose tapes are episode collections, not movies.
SHOWS = (
    r"thomas\s+(?:the\s+tank|&|and|\+)|thomas\s+&?\s*friends|postman\s+pat|kipper|noddy|barney|wombles?|"
    r"bob\s+the\s+builder|teletubbies|tweenies|fireman\s+sam|bear\s+in\s+the\s+big\s+blue\s+house|blue'?s\s+clues|"
    r"mr\.?\s+men|little\s+miss|paddington|sooty|balamory|tractor\s+tom|gumdrop|brollys|morph|sylvanian\s+families|"
    r"peppa\s+pig|pingu|rugrats|spongebob|lazytown|hey\s+arnold|dora\s+the\s+explorer|bananas\s+in\s+pyjamas|wiggles|"
    r"sesame\s+street|plaza\s+s[eé]samo|muppet\s+babies|care\s+bears|my\s+little\s+pony|he-?man|she-?ra|thundercats|"
    r"inspector\s+gadget|smurfs|tom\s+(?:&|and)\s+jerry|looney\s+tunes|bugs\s+bunny|rupert\s+bear|maisy|"
    r"angelina\s+ballerina|veggie\s*tales|magic\s+school\s+bus|wishbone|lamb\s+chop|"
    r"mister\s+rogers|captain\s+planet|teddy\s+ruxpin|playdays|button\s+moon|bananaman|danger\s*mouse|"
    r"rainbow\s+brite|charlie\s+brown|winnie\s+the\s+pooh|pooh\s+bear|telly\s*tubbies|boohbah|engie\s+benjy|"
    r"bill\s+(?:&|and)\s+ben|flowerpot\s+men|camberwick\s+green|trumpton|bagpuss|clangers|old\s+bear|"
    r"tots\s+tv|beebtots|dad'?s\s+army|only\s+fools|cheers|smiley'?s\s+people|jewel\s+in\s+the\s+crown|"
    r"muzzy|baby\s+(?:einstein|genius)|barney\s+&\s+friends")
SERIES_WORDS = (
    r"(?:&|and)\s+other\s+(?:stories|adventures|tales)|\bvol(?:ume)?\.?\s*\d+|\b\d\s+on\s+1\b|"
    r"\bfavou?rite\s+(?:episodes|adventures|stories)|\bepisodes\b|\bclassic\s+(?:episodes|stories)|"
    r"\bvery\s+best\s+of\b|\bbest\s+of\b|\bseries\s+\d+\b")
KIDS_TV = re.compile(rf"\b(?:{SHOWS})\b|{SERIES_WORDS}", re.I)

HOME_VIDEO = re.compile(
    r"\bwedding\s+(?:of|video|day|tape|reception|ceremony)\b|\b(?:our|my)\s+wedding\b|"
    r"\b\w+\s+(?:and|&)\s+\w+'?s?\s+wedding\b|\b\w+'?s\s+(?:\d+\w*\s+)?birthday\s+party\b|"
    r"\b(?:our|family|summer|\d{4})\s+vacation\b|\bvacation\s+(?:video|tape|\d{4})\b|\bhoneymoon\b|"
    r"\bgraduation\b|\brecital\b|\bfamily\s+(?:videos?|movies?|trip|tapes?)\b|\bhome\s+movies?\b|"
    r"\bfavou?rite\s+home\s+videos\b|"
    r"\bcamcorder\b|\bour\s+(?:trip|vacation|wedding|family)\b|\bbaby'?s\s+first\b|"
    r"^\s*(?:19|20)\d\d[-.]\d\d\b|\b(?:19|20)\d\d\s*[-–]\s*(?:19|20)\d\d\b.*\btrip\b|\btrip\s+to\b", re.I)

INSTRUCTIONAL = re.compile(
    r"\bhow\s+to\b|\blearn(?:ing)?\s+(?:to|with|the|about|english|spanish|french|piano|guitar)\b|\blessons?\b|"
    r"\bworkout\b|\bexercises?\b|\baerobics?\b|\byoga\b|\bfitness\b|\btutorial\b|\binstructional\b|"
    r"\btraining\s+(?:video|tape|film)\b|\bguide\s+to\b|\bvideo\s*guide\b|\bin\s+a\s+flash\b|\bgrooming\b|"
    r"\bsafety\b|\bhydroponics\b|\bfor\s+beginners\b|\bmade\s+easy\b|\bstep\s+by\s+step\b|\bteach\s+yourself\b|"
    r"\bsimple\s+science\b|\besl\b|\bself[\s-]defen[cs]e\b|\bphonics\b|\bschlessinger\b|"
    r"\bscience\s+library\b|\bdriving\s+(?:test|lessons?)\b|\bfirst\s+aid\b", re.I)

# Everyday words of other languages that English titles don't use.
FOREIGN_WORDS = re.compile(
    r"\b(?:ja|und|och|og|het|een|eine[mnrs]?|mit|nicht|ist|avec|pour|une|aux|con|para|kaj|ett|"
    r"grosse|märchen\w*|großen?|deutsche[nr]?|film\s+completo|película|pelicula|dublado|doblada?|legendado|"
    r"desenho|dessin|anim[eé]s?\s+(?:en|de)\s+fran[cç]ais)\b|\s y \s", re.I)
FOREIGN_LETTERS = re.compile(r"[äöüßåøæœãõ]", re.I)


THE_MOVIE = re.compile(r"\bmovie\b", re.I)


def _plain(text: object) -> str:
    t = str(text or "")
    t = re.sub(r"\.(?:ia\.)?[a-z0-9]{2,4}$", "", t, flags=re.I)
    return " ".join(t.replace("_", " ").split())


ENGLISH_DUB = re.compile(r"\b(?:english|eng|streamline)\s+(?:dub|dubbed|version|audio)\b|\bin\s+english\b", re.I)


def foreign(text: str) -> str | None:
    t = _plain(text)
    if ENGLISH_DUB.search(t):
        return None
    m = FOREIGN_WORDS.search(f" {t} ")
    if m:
        return m.group(0).strip()
    if FOREIGN_LETTERS.search(t):
        return "letters of another language"
    return None


def reason(*texts: object, foreign_check=None, file_names: list[str] | None = None,
           feature_film: bool = False) -> str | None:
    """Why a tape the bot isn't sure about is left out, or None to keep it for review.

    ``file_names``: the recording's files. It counts as foreign only when every
    one of them is (an English copy next to a "Spanish Copy" stays).
    ``feature_film``: TMDB's guess is a full-length film ("Rugrats in Paris",
    "The Many Adventures of Winnie the Pooh"), so a show's name doesn't make
    it a TV tape.
    """
    names = [_plain(n) for n in file_names or [] if n]
    ts = names + [_plain(t) for t in texts if t]
    for t in ts:
        if HOME_VIDEO.search(t):
            return "home video"
    for t in ts:
        if INSTRUCTIONAL.search(t):
            return "instructional tape"
    for t in ts:
        if not (feature_film or THE_MOVIE.search(t)) and KIDS_TV.search(t):
            return "kids' TV or series tape"
    is_foreign = lambda t: bool(foreign(t) or (foreign_check and foreign_check(t)))
    if names and all(is_foreign(n) for n in names):
        return "not in English"
    if not names and any(is_foreign(t) for t in ts):
        return "not in English"
    return None
