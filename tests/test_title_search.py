import sys
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import title_search as ts  # noqa: E402


FILMS = [
    {"tmdb_id": 12230, "title": "One Hundred and One Dalmatians", "release_date": "1961-01-25", "popularity": 8.1,
     "runtime": 79},
    {"tmdb_id": 11674, "title": "101 Dalmatians", "release_date": "1996-11-27", "popularity": 7.8, "runtime": 103},
    {"tmdb_id": 812, "title": "Aladdin", "release_date": "1992-11-25", "popularity": 13.8, "runtime": 90},
    {"tmdb_id": 420817, "title": "Aladdin", "release_date": "2019-05-22", "popularity": 30.0, "runtime": 128},
    {"tmdb_id": 99, "title": "Aladdin", "release_date": "1986-12-23", "popularity": 1.1, "runtime": 48},
    {"tmdb_id": 23544, "title": "Alvin and the Chipmunks: The Squeakquel", "release_date": "2009-12-21",
     "popularity": 6.7, "runtime": 88},
    {"tmdb_id": 11135, "title": "The Rescuers Down Under", "release_date": "1990-11-16", "popularity": 5, "runtime": 77},
    {"tmdb_id": 862, "title": "Toy Story", "release_date": "1995-11-22", "popularity": 40, "runtime": 81},
    {"tmdb_id": 15655, "title": "Return to Never Land", "release_date": "2002-02-14", "popularity": 4, "runtime": 72},
]


class FakeTmdb:
    """Rough stand-in for TMDB search: matches when every query word is in the title."""

    def __init__(self):
        self.queries = []

    def find(self, query, year):
        self.queries.append((query, year))
        q = ts.compare_key(query).split()
        out = []
        for f in FILMS:
            key = ts.compare_key(f["title"])
            if q and all(w in key.split() for w in q) and (not year or f["release_date"].startswith(str(year))):
                out.append({k: v for k, v in f.items() if k != "runtime"} | {"media_type": "movie"})
        return out

    def details(self, c):
        return {"runtime": next(f["runtime"] for f in FILMS if f["tmdb_id"] == c["tmdb_id"])}


VOCAB = ts.build_vocabulary(["101 Dalmatians", "101 Dalmatians II", "The Rescuers", "The Rescuers Down Under",
                             "Down and Out", "Way Down", "101 Dalmatians (Live Action)", "Dalmatians Forever",
                             "Up and Down"])


class NameTests(unittest.TestCase):
    def test_numbers_and_punctuation_even_out(self):
        self.assertEqual(ts.name_similarity("101 Dalmatians", "One Hundred and One Dalmatians"), 1.0)
        self.assertEqual(ts.name_similarity("Alvin and the Chipmunks The Squeakquel",
                                            "Alvin and the Chipmunks: The Squeakquel"), 1.0)
        self.assertEqual(ts.name_similarity("Toy Story II", "Toy Story 2"), 1.0)

    def test_typo_fixed_from_collection_words(self):
        self.assertEqual(ts.fix_spelling("The Rescuers Diwn Under", VOCAB), "the rescuers down under")
        self.assertEqual(ts.fix_spelling("101 Dalmatisns", VOCAB), "101 dalmatians")

    def test_tries_drop_extra_words(self):
        tries = [q for q, _ in ts.search_tries("Opening to Toy Story 1996 VHS")]
        self.assertIn("Toy Story", tries)
        tries = [q for q, _ in ts.search_tries("Ariel's Undersea Adventure Volume 1 - Whale of a Tale")]
        self.assertIn("Ariel's Undersea Adventure", tries)
        tries = [q for q, _ in ts.search_tries("Peter Pan 2 Return to Neverland")]
        self.assertIn("Return to Neverland", tries)

    def test_generic_dash_part_is_not_searched(self):
        tries = [q.casefold() for q, _ in ts.search_tries("Supaidaman - The Movie")]
        self.assertNotIn("the movie", tries)


class SearchTests(unittest.TestCase):
    def run_search(self, title, **kw):
        tm = FakeTmdb()
        return ts.search(title, find=tm.find, details=tm.details, vocab=VOCAB, **kw), tm

    def test_typo_title_now_found(self):
        res, _ = self.run_search("The Rescuers Diwn Under", minutes=76)
        self.assertEqual(res["candidates"][0]["tmdb_id"], 11135)
        self.assertIn("spelling", res["found_with"])

    def test_number_words_find_the_1961_film_by_year_and_length(self):
        res, _ = self.run_search("101 Dalmatians", ceiling=1992, minutes=78)
        self.assertEqual(res["candidates"][0]["tmdb_id"], 12230)
        self.assertEqual(res["confidence"], "sure")

    def test_wrong_tape_year_no_longer_sinks_exact_match(self):
        # The file said (2008 DVD) but the film is from December 2009.
        res, _ = self.run_search("Alvin and the Chipmunks The Squeakquel", ceiling=2008, minutes=88)
        self.assertEqual(res["candidates"][0]["tmdb_id"], 23544)
        self.assertIn(res["confidence"], {"sure", "likely"})

    def test_same_name_films_told_apart_by_tape_year_and_length(self):
        res, _ = self.run_search("Aladdin", ceiling=1993, minutes=90)
        self.assertEqual(res["candidates"][0]["tmdb_id"], 812)
        self.assertEqual(res["confidence"], "sure")

    def test_nothing_found(self):
        res, _ = self.run_search("Completely Unknown Home Movie")
        self.assertEqual(res["status"], "no_candidates")


if __name__ == "__main__":
    unittest.main()


# --------------------------------------------------------------------------
# Real file names that found nothing before (batches 1-4), matched by hand.
# --------------------------------------------------------------------------
REAL_FILMS = [
    {"tmdb_id": 1, "title": "Thomas' Sodor Celebration!", "release_date": "2004-01-01", "runtime": 60},
    {"tmdb_id": 2, "title": "Boo to You Too! Winnie the Pooh", "release_date": "1996-10-25", "runtime": 25},
    {"tmdb_id": 3, "title": "Star Wars", "release_date": "1977-05-25", "runtime": 121},
    {"tmdb_id": 4, "title": "8 Mile", "release_date": "2002-11-08", "runtime": 110},
    {"tmdb_id": 5, "title": "The Natural", "release_date": "1984-05-11", "runtime": 138},
    {"tmdb_id": 6, "title": "The Big Country", "release_date": "1958-10-01", "runtime": 166},
    {"tmdb_id": 7, "title": "Barney's Halloween Party", "release_date": "1998-08-11", "runtime": 50},
    {"tmdb_id": 8, "title": "Barney Live! In New York City", "release_date": "1994-01-01", "runtime": 70},
    {"tmdb_id": 9, "title": "Rock with Barney", "release_date": "1991-01-01", "runtime": 40},
    {"tmdb_id": 10, "title": "The Adventures of Elmo in Grouchland", "release_date": "1999-10-01", "runtime": 73},
    {"tmdb_id": 11, "title": "Luxo Jr.", "release_date": "1986-08-17", "runtime": 2},
    {"tmdb_id": 12, "title": "Charlie St. Cloud", "release_date": "2010-07-30", "runtime": 99},
    {"tmdb_id": 13, "title": "Elmo's World: Singing, Drawing & More!", "release_date": "2000-01-01", "runtime": 30},
    {"tmdb_id": 14, "title": "Mickey Loves Minnie", "release_date": "1996-01-01", "runtime": 30},
    {"tmdb_id": 15, "title": "Thomas and the Magic Railroad", "release_date": "2000-07-26", "runtime": 85},
    {"tmdb_id": 16, "title": "The Wiggles: Wiggledance!", "release_date": "1997-01-01", "runtime": 40},
    {"tmdb_id": 17, "title": "Shrek 2", "release_date": "2004-05-19", "runtime": 93},
]


class StrictTmdb:
    """Like TMDB search: a film comes back only if every query word is in its title."""

    def __init__(self):
        self.queries = []

    def find(self, query, year):
        self.queries.append(query)
        q = ts.plain(query).split()
        out = []
        for f in REAL_FILMS:
            tw = set(ts.plain(f["title"]).split())
            if q and all(w in tw for w in q) and (not year or f["release_date"].startswith(str(year))):
                out.append({k: v for k, v in f.items() if k != "runtime"} | {"media_type": "movie", "popularity": 5})
        return out

    def details(self, c):
        return {"runtime": next(f["runtime"] for f in REAL_FILMS if f["tmdb_id"] == c["tmdb_id"])}


class RealNameTests(unittest.TestCase):
    CASES = [
        # (file name, Archive page title or None, film year hint, expected TMDB title)
        ("02 Thomas' Sodor Celebration", None, None, "Thomas' Sodor Celebration!"),
        ("02 Winnie the Pooh - Boo to You Too!", None, None, "Boo to You Too! Winnie the Pooh"),
        ("Winnie the Pooh Boo to You Too", None, None, "Boo to You Too! Winnie the Pooh"),
        ("Star Wars CBS FOX", None, 1984, "Star Wars"),
        ("VH1 movie 8 mile February 2005 WOC", None, None, "8 Mile"),
        ("NBC Network Movie Premiere The Natural 10-26-1986 WOC", None, None, "The Natural"),
        ("ABC 13 Saturday Night Movie The Big Country 1988 WOC", None, None, "The Big Country"),
        ("Barney's Halloween Party 2000 Screener VHS", None, None, "Barney's Halloween Party"),
        ("Barney Live! In New York City Custom Lyrick Studios", None, None, "Barney Live! In New York City"),
        ("Rock with Barney Custom Lyrick Studios", None, None, "Rock with Barney"),
        ("The Adventures of Elmo in Grouchland 2005 Disney VHS (Fanmade)", None, None,
         "The Adventures of Elmo in Grouchland"),
        ("Pixar - Luxo Jr Short Film (1986-2000) (HQ VHS Capture)", None, None, "Luxo Jr."),
        ("Full VHS tape Disney Presents Mickey Loves Minnie", None, None, "Mickey Loves Minnie"),
        ("The Wiggles Wiggledance FULL 2001 US VHS (Fanmade)", None, None, "The Wiggles: Wiggledance!"),
        ("Singing Drawimng and More", "Elmo's World: Singing Drawing And More (2000 VHS)", None,
         "Elmo's World: Singing, Drawing & More!"),
        ("Charlie St. Cloud (2010) WMax (September 2nd, 2011) Full Movie", None, None, "Charlie St. Cloud"),
        ("1. Opening to Shrek 2", None, None, "Shrek 2"),
    ]

    def test_hand_matched_names_now_match(self):
        for name, page, year, want in self.CASES:
            with self.subTest(name=name):
                fake = StrictTmdb()
                extra = [(page, "Archive page title")] if page else None
                res = ts.search(name, find=fake.find, details=fake.details, hint_year=year, extra=extra)
                self.assertTrue(res["candidates"], f"nothing found; searched {fake.queries}")
                self.assertEqual(res["candidates"][0]["title"], want)
                self.assertNotIn(res["confidence"], {"none", "weak"})

    def test_track_numbers_removed_but_real_numbers_kept(self):
        self.assertEqual(ts.clean_name("02 Thomas' Sodor Celebration"), "Thomas' Sodor Celebration")
        self.assertEqual(ts.clean_name("8e. Thomas, Percy and the Dragon (US VHS) (Anchor Bay) ([2003])"),
                         "Thomas, Percy and the Dragon")
        self.assertEqual(ts.clean_name("#205. Little Ones [2000]"), "Little Ones")
        self.assertEqual(ts.clean_name("003 Pingu Helps To Deliver Mail"), "Pingu Helps To Deliver Mail")
        self.assertEqual(ts.clean_name("24. 10 Years of Thomas the Tank Engine and Friends (US VHS) ([1999])"),
                         "10 Years of Thomas the Tank Engine and Friends")
        for keep in ("2 Girls in Love", "60 Minutes", "101 Dalmatians", "8 Mile"):
            self.assertEqual(ts.clean_name(keep), keep)

    def test_clutter_removed(self):
        cases = {
            "y2mate.is - The Circus Musical Landscape-1zIwJNKveKM-360p-1692124082 2": "The Circus Musical Landscape",
            "022 Pingu's Dream.avi-i0Uq5oQ613k-nomusic": "Pingu's Dream",
            "1996 VHS • Over Canada, An Aerial Adventure 60 FPS": "Over Canada, An Aerial Adventure",
            "Fireman Sam Deep Trouble 1995 Australian VHS 1996 Extermley Rare Reprint": "Fireman Sam Deep Trouble",
            "Trust Thomas Other Stories Australian VHS Roadshow Re Release": "Trust Thomas",
            "Our House QTQ-9, 30-07-97": "Our House",
            "60 Minutes partial NWS-9, 01-11-92": "60 Minutes",
            "Opening-to-The-Mexican": "Opening to The Mexican",
            "OpeningClosing to The Matrix US VHS": "The Matrix",
            "The Sick Engine Custom VHSDVD": "The Sick Engine",
        }
        for raw, want in cases.items():
            with self.subTest(raw=raw):
                self.assertEqual(ts.clean_name(raw), want)

    def test_camera_names_have_nothing_to_search(self):
        for n in ("20240102 222243", "VHS1984 VID 20261016 200948", "videoplayback (3)", "CaptureA 11173",
                  "clideo editor 87a1232950044eef9eb4f131dfb550cc", "bandicam 2026-05-22 20-06-19-856",
                  "vhs sp 20261107 151756", "VTS 01 1", "Untitled 471", "pastedownload cnuxE9z"):
            self.assertTrue(ts.looks_like_no_name(n), n)
        for n in ("Video Aquarium", "Tape 4 Jake 1", "2 Girls in Love", "Dumbo", "Capture the Flag"):
            self.assertFalse(ts.looks_like_no_name(n), n)

    def test_show_name_taken_off(self):
        self.assertEqual(ts.without_show("Barney Our Earth, Our Home", "Barney"), "Our Earth, Our Home")
        self.assertEqual(ts.without_show("Barneys Home Sweet Homes", "Barney"), "Home Sweet Homes")
        self.assertIsNone(ts.without_show("Dumbo", "Barney"))

    def test_tape_year_after_the_film_is_not_held_against_it(self):
        ranked = ts.rank("Star Wars", [{"title": "Star Wars", "release_date": "1977-05-25", "popularity": 5}],
                         ceiling=None, hint_year=1984, minutes=None)
        self.assertIsNot(ranked[0]["year_consistent"], False)
        # A film made after the year in the name still counts against it.
        ranked = ts.rank("Aladdin", [{"title": "Aladdin", "release_date": "2019-05-22", "popularity": 5}],
                         ceiling=None, hint_year=1992, minutes=None)
        self.assertIs(ranked[0]["year_consistent"], False)

    def test_shortened_search_is_not_overconfident(self):
        films = [{"tmdb_id": 50, "title": "Jealous", "release_date": "2017-01-01", "popularity": 5}]
        res = ts.search("Absolutely Fabulous Jealous", find=lambda q, y: [dict(f, media_type="movie") for f in films]
                        if ts.plain(q) == "jealous" else [], show="Absolutely Fabulous")
        self.assertIn(res["confidence"], {"none", "weak"})

    def test_show_named_at_the_end_is_taken_off(self):
        self.assertEqual(ts.without_show("Alphabet Soup With Barney", "Barney"), "Alphabet Soup")
        self.assertEqual(ts.without_show("Imagine with Barney & Friends", "Barney"), "Imagine")
        self.assertEqual(ts.without_show("Shapes and Colors - Barney", "Barney"), "Shapes and Colors")
        # A title that only happens to end in the show's name isn't split.
        self.assertIsNone(ts.without_show("Just Friends", "Friends"))
        self.assertIsNone(ts.without_show("Barney Miller Pilot", "Miller"))

    def test_film_that_isnt_the_show_is_not_trusted(self):
        # The real case: "Alphabet Soup With Barney" was matched to William
        # Wegman's dog film "Alphabet Soup" (1995) by dropping "With Barney".
        wegman = {"tmdb_id": 106082, "title": "Alphabet Soup", "release_date": "1995-01-01", "popularity": 0.2,
                  "overview": "William Wegman and his dogs teach children the alphabet."}
        other = {"tmdb_id": 1492638, "title": "Alphabet Soup", "release_date": None, "popularity": 0.1,
                 "overview": "Three friends eat a magical soup."}
        find = lambda q, y: [dict(f, media_type="movie") for f in (wegman, other)] \
            if ts.plain(q) == "alphabet soup" else []
        res = ts.search("Alphabet Soup With Barney 2001 VHS", find=find, minutes=32.5, ceiling=2001, show="Barney")
        self.assertEqual(res["confidence"], "weak")
        self.assertTrue(res["candidates"][0]["not_the_show"])
        self.assertIn("nothing about Barney in it", res["candidates"][0]["why"])

    def test_match_that_names_the_show_still_counts(self):
        film = {"tmdb_id": 7, "title": "Alphabet Soup", "release_date": "1995-01-01", "popularity": 1,
                "overview": "Barney and his friends sing about letters."}
        find = lambda q, y: [dict(film, media_type="movie")] if ts.plain(q) == "alphabet soup" else []
        res = ts.search("Alphabet Soup With Barney", find=find, show="Barney")
        self.assertNotEqual(res["confidence"], "weak")
        self.assertFalse(res["candidates"][0].get("not_the_show"))
