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


class EarlierMissesAreSearchedAgain(unittest.TestCase):
    """A title that found nothing with an older, dumber search is searched again;
    a confident earlier match is kept; a camera file name isn't searched."""

    def test_rerun(self):
        import json, os, tempfile
        import match_titles_tmdb as m

        def title(tid, name):
            return {"title_suggestion_id": tid, "display_title": name, "series": None, "show_dash_hint": None,
                    "edition_suggestion_ids": [], "unassigned_recording_ids": [], "tmdb": {"search_title": name}}

        def rec(tid, name, item):
            return {"recording_id": "r-" + tid, "title_suggestion_id": tid, "display_name": name,
                    "catalog_category": "manual_review", "duration_seconds": 3600,
                    "archive_identifiers": [item], "interpretation": {}}

        titles = [title("a", "02 Thomas' Sodor Celebration"), title("b", "Dumbo"), title("c", "20240102 222243")]
        recs = [rec("a", "02 Thomas' Sodor Celebration.mp4", "p1"), rec("b", "Dumbo.mp4", "p2"),
                rec("c", "20240102 222243.mp4", "p3")]
        items = [{"archive_identifier": "p1", "archive_title": "Thomas Tape", "video_file_count": 6},
                 {"archive_identifier": "p2", "archive_title": "Dumbo", "video_file_count": 1},
                 {"archive_identifier": "p3", "archive_title": "News tapes", "video_file_count": 30}]
        old = [dict(titles[0], tmdb={"status": "no_candidates", "candidates": []}),
               dict(titles[1], tmdb={"status": "one_likely_candidate", "candidates": [{"title": "Dumbo"}]})]
        calls = []

        def fake_get(path, params, token):
            calls.append(params.get("query"))
            if path == "/search/movie" and params.get("query") == "Thomas' Sodor Celebration":
                return {"results": [{"id": 1, "title": "Thomas' Sodor Celebration!", "release_date": "2004-01-01"}]}
            return {"results": []} if path.startswith("/search") else {"runtime": 60}

        with tempfile.TemporaryDirectory() as d:
            for n, rows in (("titles", titles), ("recordings", recs), ("editions", []), ("archive-items", items)):
                Path(d, n + ".jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            Path(d, "old.jsonl").write_text("".join(json.dumps(r) + "\n" for r in old))
            saved = (m._get, m.time.sleep, sys.argv, os.environ.get("TMDB_API_READ_ACCESS_TOKEN"))
            m._get, m.time.sleep = fake_get, (lambda s: None)
            sys.argv = ["x", "--model-dir", d, "--reuse", str(Path(d, "old.jsonl"))]
            os.environ["TMDB_API_READ_ACCESS_TOKEN"] = "x"
            try:
                m.main()
            finally:
                m._get, m.time.sleep, sys.argv = saved[:3]
                if saved[3] is None:
                    os.environ.pop("TMDB_API_READ_ACCESS_TOKEN", None)
            out = {r["title_suggestion_id"]: r["tmdb"] for r in map(json.loads, Path(d, "titles.jsonl").read_text().splitlines())}
        self.assertEqual(out["a"]["candidates"][0]["title"], "Thomas' Sodor Celebration!")
        self.assertEqual(out["a"]["matcher"], m.MATCHER_VERSION)
        self.assertEqual(out["b"]["status"], "one_likely_candidate")       # kept, not searched again
        self.assertNotIn("Dumbo", calls)
        self.assertEqual(out["c"]["status"], "no_usable_name")
