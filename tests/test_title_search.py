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
