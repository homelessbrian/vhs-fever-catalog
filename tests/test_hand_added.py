import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import hand_added as ha  # noqa: E402
import vhs_movies as vm  # noqa: E402

ENTRIES = [{"page": "pg", "file": "Dir/Behind Enemy Lines.mp4", "imdb_id": "tt0159273", "title": "Behind Enemy Lines", "year": 2001}]


def rec(rid, name, page="pg", minutes=100, cat="other_media_release"):
    return {"recording_id": rid, "duration_seconds": minutes * 60, "part_count": 1, "catalog_category": cat,
            "archive_identifiers": [page],
            "segments": [{"sources": [{"archive_identifier": page, "original_name": name}]}]}


class HandAdded(unittest.TestCase):
    def test_stem_ignores_extension_copy_marks_and_case(self):
        self.assertEqual(ha.stem("Dir/Behind Enemy Lines.mkv"), ha.stem("dir/behind enemy lines.ia.mp4"))

    def test_listed_file_matches_its_copies(self):
        self.assertTrue(ha.is_listed(rec("a", "Dir/Behind Enemy Lines.mkv"), ENTRIES))
        self.assertFalse(ha.is_listed(rec("b", "Dir/Other Movie.mkv"), ENTRIES))
        self.assertFalse(ha.is_listed(rec("c", "Dir/Behind Enemy Lines.mkv", page="elsewhere"), ENTRIES))

    def test_keep_long_keeps_only_listed_files_from_a_hand_page(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            recs = [rec("a", "Dir/Behind Enemy Lines.mkv"), rec("b", "Dir/Other Movie.mkv"),
                    rec("c", "x.mp4", page="normal", cat="vhs_release"), rec("d", "y.mp4", page="normal")]
            for n in vm.MODEL_FILES:
                vm.write_jsonl(d / f"{n}.jsonl", recs if n == "recordings" else [])
            hand = d / "hand.json"
            hand.write_text(json.dumps({"files": ENTRIES}))
            vm.keep_long(d, hand)
            kept = {r["recording_id"] for r in vm.read_jsonl(d / "recordings.jsonl")}
        # a: listed (kept although a disc release); b: same page, not listed; c: normal tape; d: normal disc release
        self.assertEqual(kept, {"a", "c"})

    def test_listed_file_is_only_dropped_when_short_or_broken(self):
        row = {"kind": "tv_episode", "format": "BLU-RAY", "verdict": "good", "duration_seconds": 100 * 60}
        self.assertIsNone(vm.why_not_kept(row, None, None, check_tape=True, by_hand=True))
        self.assertIn("not VHS", vm.why_not_kept(dict(row, kind="movie"), None, None, check_tape=True))
        self.assertIn("40 minutes", vm.why_not_kept(dict(row, duration_seconds=600), None, by_hand=True))
        self.assertIn("doesn't play", vm.why_not_kept(dict(row, verdict="bad", reason="No audio"), None, by_hand=True))

    def test_listed_file_is_never_bot_sure(self):
        row = {"format_confidence": "high", "verdict": "good"}
        self.assertTrue(vm.bot_sure(row, None, by_hand=True))

    def test_title_is_matched_to_its_entry(self):
        title = {"un": [{"segs": [{"src": [{"item": "pg", "n": "Dir/Behind Enemy Lines.ia.mp4"}]}]}], "eds": []}
        self.assertEqual(vm.hand_entry_for(title, ENTRIES)["imdb_id"], "tt0159273")
        self.assertIsNone(vm.hand_entry_for({"un": [], "eds": []}, ENTRIES))

    def test_tmdb_find_uses_the_cache_first(self):
        cache = {"100": {"imdb_id": "tt0159273"}}
        self.assertEqual(vm.tmdb_find(["tt0159273", "tt1"], cache, None), {"tt0159273": 100})

    def test_the_real_list_is_well_formed(self):
        entries = ha.load()
        self.assertTrue(entries)
        for e in entries:
            self.assertTrue(e["page"] and e["file"], e)
            # An IMDb id is optional (the bot then matches the name on TMDB, as for any tape).
            self.assertTrue(e.get("imdb_id", "tt").startswith("tt"), e)


if __name__ == "__main__":
    unittest.main()
