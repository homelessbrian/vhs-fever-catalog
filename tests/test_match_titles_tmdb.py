import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from match_titles_tmdb import score_candidates, verdict  # noqa: E402

RESULTS = [  # shape of TMDB /search/movie results for "101 Dalmatians"
    {"id": 11674, "title": "101 Dalmatians", "original_title": "101 Dalmatians", "release_date": "1996-11-27", "popularity": 40},
    {"id": 12230, "title": "One Hundred and One Dalmatians", "original_title": "One Hundred and One Dalmatians", "release_date": "1961-01-25", "popularity": 60},
]


class TapeYearIsACeiling(unittest.TestCase):
    def test_1992_tape_cannot_hold_the_1996_remake(self):
        c = score_candidates("101 Dalmatians", RESULTS, kind="movie", latest_allowed_year=1992, hint_year=None)
        remake = next(x for x in c if x["tmdb_id"] == 11674)
        self.assertFalse(remake["year_consistent"])
        self.assertNotEqual(c[0]["tmdb_id"], 11674)
        self.assertNotEqual(verdict(c), "one_likely_candidate")  # 1961 title wording differs: a person decides

    def test_2015_dvd_leaves_both_possible(self):
        c = score_candidates("101 Dalmatians", RESULTS, kind="movie", latest_allowed_year=2015, hint_year=None)
        self.assertTrue(all(x["year_consistent"] for x in c))

    def test_filename_film_year_hint(self):
        c = score_candidates("101 Dalmatians", RESULTS, kind="movie", latest_allowed_year=None, hint_year=1961)
        self.assertEqual(c[0]["tmdb_id"], 12230)

    def test_no_results(self):
        self.assertEqual(verdict([]), "no_candidates")


if __name__ == "__main__":
    unittest.main()
