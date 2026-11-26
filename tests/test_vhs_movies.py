import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import vhs_movies as vm  # noqa: E402


def rec(rid, minutes, cat="vhs_release", parts=1, title="t1", edition=None):
    return {"recording_id": rid, "duration_seconds": minutes * 60, "part_count": parts, "catalog_category": cat,
            "archive_identifiers": ["page-" + rid], "title_suggestion_id": title, "edition_suggestion_id": edition}


def row(**kw):
    base = {"file_id": "f", "recording_id": "r", "role": "original", "name": "Bambi (1989 VHS).mp4", "kind": "movie",
            "format": "VHS", "format_confidence": "high", "duration_seconds": 70 * 60, "verdict": "good",
            "reason": None, "verdict_why": [], "title_suggestion_id": "t1"}
    base.update(kw)
    return base


def title(conf="sure", media="movie", status="one_likely_candidate", display="Bambi"):
    return {"title_suggestion_id": "t1", "display_title": display,
            "tmdb": {"status": status, "confidence": conf,
                     "candidates": [{"tmdb_id": 3170, "media_type": media, "title": "Bambi"}] if conf else []}}


class KeepLong(unittest.TestCase):
    def test_only_long_whole_tape_recordings_are_kept(self):
        self.assertTrue(vm.is_long_candidate(rec("a", 75)))
        self.assertTrue(vm.is_long_candidate(rec("a", 40)))
        self.assertFalse(vm.is_long_candidate(rec("a", 39)))
        self.assertFalse(vm.is_long_candidate(rec("a", 90, parts=3)))
        for cat in ("tv_recording", "vhs_opening_or_previews", "other_media_release"):
            self.assertFalse(vm.is_long_candidate(rec("a", 90, cat=cat)))

    def test_trim_keeps_matching_titles_editions_and_pages(self):
        model = {
            "recordings": [rec("a", 80, edition="e1"), rec("b", 5, edition="e1"), rec("c", 90, title="t2")],
            "editions": [{"edition_suggestion_id": "e1", "recording_ids": ["a", "b"]}],
            "titles": [{"title_suggestion_id": "t1", "edition_suggestion_ids": ["e1"], "unassigned_recording_ids": []},
                       {"title_suggestion_id": "t2", "edition_suggestion_ids": [], "unassigned_recording_ids": ["c"]},
                       {"title_suggestion_id": "t3", "edition_suggestion_ids": [], "unassigned_recording_ids": ["z"]}],
            "archive-items": [{"archive_identifier": "page-a"}, {"archive_identifier": "page-b"}],
            "artwork": [{"recording_id": "a"}, {"recording_id": "b"}],
        }
        t = vm.trim_model(model, {"a", "c"})
        self.assertEqual(["a", "c"], [r["recording_id"] for r in t["recordings"]])
        self.assertEqual(["a"], t["editions"][0]["recording_ids"])
        self.assertEqual(["t1", "t2"], [x["title_suggestion_id"] for x in t["titles"]])
        self.assertEqual(["page-a"], [i["archive_identifier"] for i in t["archive-items"]])
        self.assertEqual(1, len(t["artwork"]))


class WhatIsKept(unittest.TestCase):
    def test_vhs_movie_is_kept(self):
        self.assertIsNone(vm.why_not_kept(row(), title()))

    def test_everything_else_is_left_out(self):
        self.assertIn("not a movie", vm.why_not_kept(row(kind="tv_episode"), title()))
        self.assertIn("not VHS", vm.why_not_kept(row(format="DVD"), title()))
        self.assertIn("40 minutes", vm.why_not_kept(row(duration_seconds=30 * 60), title()))
        self.assertIn("doesn't play", vm.why_not_kept(row(verdict="bad", reason="No audio"), title()))
        self.assertIn("TV show", vm.why_not_kept(row(), title(media="tv")))
        self.assertIn("English", vm.why_not_kept(row(), title(status="skipped_not_english")))

    def test_taped_off_tv_is_left_out(self):
        for name in ["MST 3000 S9E13 Nightwatch.mp4", "BBC1 soaps from May 1996.mp4", "CBeebies shows 2002.mp4",
                     "ABC 13 Saturday Night Movie The Big Country 1988.mp4", "Pingu Full Episodes.mp4",
                     "The Worst Witch (1986, TV).mp4"]:
            self.assertIn("TV", vm.why_not_kept(row(name=name), title()), name)
        for name in ["Snoopy Come Home (1996 VHS).mp4", "Step Up (2006 VHS).mp4", "The Muppet Movie (1979 VHS).mp4"]:
            self.assertIsNone(vm.why_not_kept(row(name=name), title()), name)

    def test_bot_is_sure_only_with_a_clear_match_a_clear_tape_and_good_sound(self):
        self.assertEqual([], vm.bot_sure(row(), title()))
        self.assertTrue(vm.bot_sure(row(), title(conf="likely")))
        self.assertTrue(vm.bot_sure(row(), title(conf=None)))
        self.assertTrue(vm.bot_sure(row(format_confidence="low"), title()))
        self.assertTrue(vm.bot_sure(row(verdict="review", verdict_why=["sound is maxed out"]), title()))


class CleanNames(unittest.TestCase):
    def test_names(self):
        cases = {
            "19b. Thomas and His Friends Get Along (US VHS) ([2000])": "Thomas and His Friends Get Along",
            "Cabin.By.The.Lake.2000.mkv": "Cabin By The Lake",
            "Silas Marner": "Silas Marner",
            "World of Beanie Babies, The": "The World of Beanie Babies",
            "Barbie In The Nutcracker 2026-2000 VHS": "Barbie In The Nutcracker",
            "Inspector - Gadget - Beats - The - Trap (Australian VHS)": "Inspector Gadget Beats The Trap",
            "2001 A Space Odyssey": "2001 A Space Odyssey",
            "1941": "1941",
            "02 Rugrats Tales from the Crib Snow White": "Rugrats Tales from the Crib Snow White",
        }
        for raw, want in cases.items():
            self.assertEqual(want, vm.clean_title(raw), raw)


if __name__ == "__main__":
    unittest.main()
