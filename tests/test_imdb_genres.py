import gzip
import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import imdb_genres as g

HEADER = "tconst\ttitleType\tprimaryTitle\toriginalTitle\tisAdult\tstartYear\tendYear\truntimeMinutes\tgenres\n"
FILE = (HEADER
        + "tt0246578\tmovie\tDonnie Darko\tDonnie Darko\t0\t2001\t\\N\t113\tDrama,Mystery,Sci-Fi\n"
        + "tt0088763\tmovie\tBack to the Future\tBack to the Future\t0\t1985\t\\N\t116\tAdventure,Comedy,Sci-Fi\n"
        + "tt0000009\tmovie\tNo genres\tNo genres\t0\t1894\t\\N\t45\t\\N\n")


class FakeResp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def opener(req, timeout=None):
    return FakeResp(gzip.compress(FILE.encode()))


class ImdbGenres(unittest.TestCase):
    def test_donnie_darko_is_sci_fi_not_fantasy(self):
        tmdb = ["Fantasy", "Drama", "Mystery"]
        self.assertEqual(g.merge(tmdb, ["Drama", "Mystery", "Sci-Fi"]), ["Drama", "Mystery", "Sci-Fi"])

    def test_tmdb_order_decides_the_main_genre(self):
        self.assertEqual(g.merge(["Comedy", "Adventure", "Sci-Fi"], ["Adventure", "Comedy", "Sci-Fi"]),
                         ["Comedy", "Adventure", "Sci-Fi"])

    def test_nothing_from_imdb_keeps_tmdbs_genres(self):
        self.assertEqual(g.merge(["Drama"], None), ["Drama"])
        self.assertEqual(g.merge(["Drama"], []), ["Drama"])
        # IMDb only has tags no shelf uses (Sport, Short): TMDB's stay
        self.assertEqual(g.merge(["Drama"], ["Sport"]), ["Drama"])

    def test_imdb_names_become_ours(self):
        self.assertEqual(g.map_genres(["Musical", "Music", "Biography", "Film-Noir", "Sport", "Sci-Fi"]),
                         ["Music", "History", "Crime", "Sci-Fi"])

    def test_looks_up_only_what_is_missing_and_remembers_gaps(self):
        cache = {"tt0088763": ["Adventure", "Comedy", "Sci-Fi"]}
        n = g.fetch_missing(["tt0246578", "tt0088763", "tt0000009", "tt9999999", None, "junk"], cache, opener=opener)
        self.assertEqual(n, 3)
        self.assertEqual(cache["tt0246578"], ["Drama", "Mystery", "Sci-Fi"])
        self.assertEqual(cache["tt0000009"], [])
        self.assertEqual(cache["tt9999999"], [])          # not in IMDb's file: remembered, not searched again
        self.assertEqual(g.fetch_missing(["tt0246578"], cache, opener=opener), 0)   # nothing to do, no download

    def test_failed_download_leaves_the_cache_alone(self):
        def broken(req, timeout=None):
            raise OSError("no network")
        cache = {}
        with mock.patch("time.sleep"):
            self.assertEqual(g.fetch_missing(["tt0246578"], cache, opener=broken), 0)
        self.assertEqual(cache, {})

    def test_overrides(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "o.json"
            p.write_text('{"_how": "note", "tmdb:141": ["Science Fiction", "Drama"], "tt0088763": ["Comedy"]}')
            o = g.load_overrides(p)
        self.assertEqual(g.override_for(o, 141, "tt0246578"), ["Sci-Fi", "Drama"])
        self.assertEqual(g.override_for(o, 105, "tt0088763"), ["Comedy"])
        self.assertIsNone(g.override_for(o, 1, "tt1"))
        self.assertEqual(g.load_overrides(Path("/nonexistent.json")), {})


if __name__ == "__main__":
    unittest.main()
