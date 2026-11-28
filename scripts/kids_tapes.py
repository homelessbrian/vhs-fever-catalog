#!/usr/bin/env python3
"""Which tapes go on the TV app's Kids shelf.

Kids is for tapes made for small children: preschool shows (Bob the Builder,
Thomas, Barney, Sesame Street ...), sing-alongs, nursery rhymes, ABC and
counting tapes, and the short cartoon and family TV specials. Full-length
cartoons and family movies (Aladdin, Home Alone) stay on Animation and Family.
Brian's call (Nov 27 2026). Standard library only.
"""
from __future__ import annotations

import re

I = re.I

# Shows and series made for small children.
SHOWS = re.compile(
    r"\b(?:bob the builder|thomas (?:the tank|&|and|the|christmas|goes)|thomas'|james goes buzz buzz|"
    r"there goes a \w+|barney(?! miller)|teletubbies|tweenies|sesame street|elmo|big bird|blue'?s clues|"
    r"wiggles|wee sing|baby (?:einstein|genius|mozart|bach|songs|bop)|mister rogers|mr\.? rogers|"
    r"shining time|lamb ?chop|reading rainbow|captain kangaroo|pee[\s-]?wee'?s playhouse|postman pat|"
    r"fireman sam|noddy|kipper|pingu|peppa pig|maisy|rupert bear|paddington|sooty|balamory|tractor tom|"
    r"bananas in pyjamas|lazytown|dora the explorer|go,? diego|little einsteins|mickey mouse clubhouse|"
    r"paw patrol|(?<!aretha )franklin(?:'s| and| the turtle)|clifford(?: the big red dog|'s)|berenstain bears|"
    r"arthur'?s (?:eyes|pet|treasure|perfect|lost|birthday|first|substitute|big|mystery)|"
    r"madeline(?:\s*[:'-]|'s)|strawberry shortcake|care bears|my little pony|angelina ballerina|"
    r"veggie ?tales|magic school bus|muppet babies|fraggle rock|mr\.? men|little miss (?!sunshine)|"
    r"gullah gullah|doodlebops|backyardigans|zoboo?mafoo|wishbone|bear in the big blue house|"
    r"play ?school|play ?days|rosie and jim|button moon|brum|animals of farthing wood|raggy dolls|"
    r"wombles|jackanory|fun song factory|singing kettle|animal jam|rock ?'?n'? ?learn|"
    r"disney learning adventures|disney'?s sing[\s-]?along|book of pooh|rolie polie olie|"
    r"spot(?: and friends|'s)|discover spot|abc for kids|abc kids|disney storybook|golden treasury|"
    r"little bill|dragon tales|miffy|babar|caillou|boohbah|yo gabba gabba|jay jay|bubble guppies|"
    r"super why|between the lions|puzzle place|winnie the pooh|pooh'?s|tigger|kids for character)(?!\w)", I)

# Learning, singing and story tapes for the very young.
LEARNING = re.compile(
    r"\b(?:abc'?s|alphabet|123'?s|numbers? (?:and|&) (?:letters|colou?rs|shapes)|colou?rs? (?:and|&) shapes|"
    r"shapes (?:and|&) \w+|phonics|sing[\s._-]*alongs?|nursery rhymes?|bedtime stor(?:y|ies)|potty|"
    r"toilet training|pre-?school(?:ers)?|toddlers?|first words|"
    r"learn(?:ing)? (?:the |your |about )?(?:abcs?|alphabet|numbers|colou?rs|shapes|to count|to read)|"
    r"let'?s learn|early learning|children'?s (?:songs|stories|favou?rites)|kids'? (?:songs|stories|favou?rites|club))\b", I)

# What TMDB's blurb says about it.
BLURB = re.compile(
    r"\b(?:pre-?school(?:ers)?|nursery rhymes?|alphabet|sing-?alongs?|songs and rhymes|teach(?:es)? (?:children|kids|young)|"
    r"learn(?:s|ing)? (?:the |their |about |to )?(?:abc|letters|numbers|colou?rs|shapes|counting|count))\b", I)

# Tags that make a tape grown-up (an Animation + Family tag together outweighs a stray one).
MATURE = {"Horror", "Action", "Sci-Fi", "Science Fiction", "Drama", "Documentary", "Crime", "Thriller", "War",
          "Romance", "Mystery", "History", "Western"}
ADULT_ONLY = {"Horror", "Crime", "Thriller", "War", "Romance"}

SHORT_MINUTES = 65      # a TV special or a cartoon collection, not a feature film
SHORTEST_YEAR = 1950    # older cartoons stay on Animation


def reason(names: list[str], overview: str | None = None, genres: list[str] | None = None,
           runtime: int | None = None, minutes: float = 0, year: str | int | None = None,
           collections: set[str] | list[str] | None = None) -> str | None:
    """Why a tape belongs on the Kids shelf, or None when it doesn't."""
    g = set(genres or [])
    text = " | ".join(str(n) for n in names if n)
    m = SHOWS.search(text)
    if m:
        return f"kids' show ({m.group(0).lower()})"
    # a clearly grown-up tag with nothing kid-like next to it
    if g & ADULT_ONLY and not g & {"Family", "Animation"}:
        return None
    m = LEARNING.search(text)
    if m:
        return f"learning or sing-along tape ({m.group(0).lower()})"
    if overview and BLURB.search(overview):
        return "TMDB says it's for preschoolers"
    if not g:
        return None
    kidsish = "Family" in g or ("Animation" in g and "vhskids" in set(collections or []))
    if not kidsish:
        return None
    mature = g & MATURE
    if mature and not ({"Animation", "Family"} <= g and not g & {"Documentary", "War", "Crime", "Thriller"}):
        return None
    length = runtime or minutes
    made = int(str(year)[:4]) if year and str(year)[:4].isdigit() else None
    if length <= SHORT_MINUTES and minutes <= 80 and (made is None or made >= SHORTEST_YEAR):
        return "short cartoon or family special"
    return None
