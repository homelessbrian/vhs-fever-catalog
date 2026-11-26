import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import adult  # noqa: E402
import pick_batch  # noqa: E402


class AdultTest(unittest.TestCase):
    def test_adult_from_collection_or_title_not_description(self):
        self.assertTrue(adult.is_adult(["adultvhs"], "Anything"))
        self.assertTrue(adult.is_adult([], "1994 Playboy Video Playmate Calendar"))
        self.assertFalse(adult.is_adult(["vhsvault"], "Basic Instinct", "erotic thriller"))
        self.assertFalse(adult.is_adult(["vhsvault"], "Sexton Blake"))

    def test_blocked(self):
        self.assertIsNotNone(adult.blocked(["adultvhs"], "Tape", None, "featuring real child actors"))
        self.assertIsNotNone(adult.blocked(["adultvhs"], "Lolita Fantasy"))
        self.assertIsNotNone(adult.blocked(["adultvhs"], "x", None, "she was a 16-year-old"))
        self.assertIsNone(adult.blocked(["adultvhs"], "x", None, "an 18 year old"))
        self.assertIsNone(adult.blocked(["vhsvault"], "Lolita (1962)"))   # not an adult page

    def test_picker_never_picks_blocked(self):
        row = {"identifier": "a", "kind_guess": "movie_or_unknown", "format_guess": "unknown", "size_bytes": 1e9,
               "collections": ["adultvhs"], "found_by": ["collection adultvhs"], "title": "Some Tape"}
        self.assertTrue(pick_batch.wanted(row, "vhs_movies"))
        self.assertFalse(pick_batch.wanted(dict(row, title="Barely Legal 3"), "vhs_movies"))


    def test_adult_collection_picked_with_the_clean_finds(self):
        import json, subprocess, tempfile
        d = Path(tempfile.mkdtemp())
        base = {"kind_guess": "movie_or_unknown", "format_guess": "VHS", "size_bytes": 1e9}
        rows = [dict(base, identifier=f"k{i}", found_by=["keyword vhs rip"], collections=["x"],
                     added_to_archive=f"2025-01-0{i}") for i in range(1, 5)]
        rows.append(dict(base, identifier="a1", found_by=["collection adultvhs"], collections=["adultvhs"],
                         added_to_archive="2020-01-01", format_guess="unknown"))
        (d / "more.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
        subprocess.run([sys.executable, str(Path(pick_batch.__file__)), "--from", "more", "--more-items",
                        str(d / "more.jsonl"), "--batches-dir", str(d / "b"), "--size", "1"], check=True,
                       capture_output=True)
        self.assertIn("a1", (d / "b" / "batch-001.txt").read_text())


if __name__ == "__main__":
    unittest.main()
