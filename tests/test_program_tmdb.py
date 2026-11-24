import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.enrich_program_tmdb import filename_title, enrich_programs, write_jsonl, read_jsonl


class ProgramTmdbTests(unittest.TestCase):
    def program(self, filename):
        return {"entry_type": "standalone", "segments": [{"file_name": filename}]}

    def test_parses_common_year_title_filename_formats(self):
        cases = [
            ("(1954) Godzilla.avi", ("Godzilla", "1954")),
            ("1954 Godzilla.mp4", ("Godzilla", "1954")),
            ("Godzilla (1954).mkv", ("Godzilla", "1954")),
            ("Godzilla.1954.avi", ("Godzilla", "1954")),
            ("Godzilla_1954.mp4", ("Godzilla", "1954")),
            ("Godzilla (1954) [VHS].mp4", ("Godzilla", "1954")),
        ]
        for filename, expected in cases:
            with self.subTest(filename=filename):
                self.assertEqual(filename_title(self.program(filename)), expected)

    def test_preserves_filename_exactly_and_searches_parsed_title_and_year(self):
        filename = "(1954) Godzilla.avi"
        rows = [{
            "entry_type": "standalone", "title": filename,
            "display_title": "BigGMovies",
            "segments": [{"file_name": filename}],
            "program_id": "1",
        }]
        with patch(
            "scripts.enrich_program_tmdb.find_tmdb_match",
            return_value={"tmdb_id": 1678, "title": "Godzilla",
                          "match_method": "high_similarity_title_search"},
        ) as lookup:
            result, _ = enrich_programs(rows, "token", 0)
        self.assertEqual(lookup.call_args.args[0], {"title": "Godzilla", "year": "1954"})
        self.assertEqual(result[0]["segments"][0]["file_name"], filename)
        self.assertEqual(result[0]["title_match_suggestion"]["source_filename"], filename)
        self.assertEqual(result[0]["title_match_suggestion"]["search_title"], "Godzilla")
        self.assertEqual(result[0]["title_match_suggestion"]["search_year"], "1954")
        self.assertEqual(result[0]["title_match_suggestion"]["provider_id"], "1678")
        self.assertIsNone(result[0]["matched_title_id"])
        self.assertTrue(result[0]["review_required"])

    def test_caches_duplicate_filename_queries(self):
        rows = [self.program("(1954) Godzilla.mp4"), self.program("(1954) Godzilla.mp4")]
        with patch("scripts.enrich_program_tmdb.find_tmdb_match", return_value={"tmdb_id": 1678}) as lookup:
            enrich_programs(rows, "token", 0)
        self.assertEqual(lookup.call_count, 1)

    def test_does_not_lookup_assembled_candidates(self):
        rows = [{"entry_type": "assembled_candidate", "title": "Godzilla"}]
        with patch("scripts.enrich_program_tmdb.find_tmdb_match") as lookup:
            result, _ = enrich_programs(rows, "token", 0)
        lookup.assert_not_called()
        self.assertNotIn("title_match_suggestion", result[0])

    def test_jsonl_writer_uses_real_line_breaks_and_round_trips(self):
        rows = [{"title": "Godzilla"}, {"title": "King Kong"}]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "programs.jsonl"
            write_jsonl(path, rows)
            raw = path.read_text(encoding="utf-8")
            self.assertEqual(raw.count("\n"), 2)
            self.assertNotIn("\\n", raw)
            self.assertEqual(read_jsonl(path), rows)


if __name__ == "__main__":
    unittest.main()
