import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import auto_sort as a  # noqa: E402
import evaluate_auto_sort as ev  # noqa: E402
from catalog_model import interpret_filename  # noqa: E402

OK = {"verdict": "ok", "has_video_track": True, "video_codec": "h264", "audio_codec": "aac",
      "width": 640, "height": 480, "sample_max_db": [-8.0, -9.0]}


def rec(name, dur, *, parts=1, item="item1", probe_id="f1", role="original", fmt="MPEG4", playable=True):
    it = interpret_filename(name)
    src = {"source_id": probe_id, "original_name": name, "archive_identifier": item, "duration_seconds": dur,
           "role": role, "format": fmt, "browser_playable": playable}
    return {"recording_id": "r-" + probe_id, "interpretation": it, "part_count": parts, "flags": it.get("flags", []),
            "segments": [{"duration_seconds": dur, "sources": [src]}], "title_suggestion_id": None,
            "catalog_category": "vhs_release"}


def sort_one(name, dur, probe=OK, item=None, **kw):
    items = [item or {"archive_identifier": "item1", "collection": ["vhsvault", "vhsmovies"],
                      "video_file_count": 50, "note": "Container only."}]
    return a.sort_all([rec(name, dur, **kw)], items, [], [], {"f1": probe} if probe else {})[0]


class KindAndVerdict(unittest.TestCase):
    def test_full_vhs_movie_is_good(self):
        r = sort_one("Bambi (1989 VHS).mp4", 4200)
        self.assertEqual((r["kind"], r["format"], r["verdict"], r["shelf"]), ("movie", "VHS", "good", "vhs_release"))

    def test_split_piece_is_bad_clip(self):
        r = sort_one("Bambi (1989 VHS) - 13of23.mp4", 111, parts=23)
        self.assertEqual((r["kind"], r["verdict"], r["reason"]), ("clip", "bad", "Clip"))

    def test_broken_file_wont_play(self):
        r = sort_one("Movie (2001 DVD).mp4", 5000, probe={"verdict": "could_not_open"})
        self.assertEqual((r["verdict"], r["reason"]), ("bad", "Won’t play"))

    def test_silent_spot_checks_go_to_a_person(self):
        r = sort_one("101 Dalmatians (1992 VHS).mp4", 4700, probe=dict(OK, verdict="silent_audio"))
        self.assertEqual(r["verdict"], "review")

    def test_mp3_sound_and_maxed_out_sound_go_to_review(self):
        self.assertEqual(sort_one("A (1996 VHS).m4v", 1700, probe=dict(OK, audio_codec="mp3"))["verdict"], "review")
        self.assertEqual(sort_one("B (1996 VHS).mp4", 5200, probe=dict(OK, sample_max_db=[0.0, -0.0]))["verdict"],
                         "review")

    def test_dvd_rip_and_bluray_formats(self):
        self.assertEqual(sort_one("Bedazzled_2000_English_DVDRIP_300MB.mp4", 5300)["format"], "DVD")
        r = sort_one("All.Dogs.Go.To.Heaven.1989.1080p.BluRay.x264.mp4", 5000,
                     probe=dict(OK, width=1920, height=1040))
        self.assertEqual((r["format"], r["shelf"]), ("BLU-RAY", "other_media_release"))

    def test_commercial_and_tv_recording_with_ads(self):
        r = sort_one("1990 Michael Jordan Coca Cola Commercial.mp4", 30)
        self.assertEqual((r["kind"], r["verdict"]), ("commercial", "good"))
        r = sort_one("Saved by the Bell (taped off NBC, with commercials).mp4", 1800)
        self.assertEqual((r["kind"], r["builtin_commercials"]), ("tv_recording", "true"))
        r = sort_one("443 NEWS & NIGHT-LINE FRI 2001 MAR 09.mp4", 3600)
        self.assertEqual(r["kind"], "tv_recording")
        self.assertIn(r["builtin_commercials"], {"likely", "true"})

    def test_not_english_goes_to_review(self):
        r = sort_one("001 - Le Grand Depart (Polish Voiceover).mp4", 1300)
        self.assertEqual(r["verdict"], "review")

    def test_container_page_title_is_ignored(self):
        item = {"archive_identifier": "item1", "archive_title": "All Animated VHS and DVD Capture",
                "collection": ["vhsvault"], "video_file_count": 600, "note": "Container only."}
        r = sort_one("A Bug's Life (2003 DVD).mp4", 5700, item=item)
        self.assertEqual((r["format"], r["format_confidence"]), ("DVD", "high"))


class Evaluation(unittest.TestCase):
    def test_counts_agreement_and_lists_misses(self):
        rows = [{"file_id": "a", "verdict": "good", "reason": None, "verdict_why": [], "name": "A", "shelf": None,
                 "format": "VHS", "format_clues": []},
                {"file_id": "b", "verdict": "good", "reason": None, "verdict_why": [], "name": "B", "shelf": None,
                 "format": "VHS", "format_clues": []},
                {"file_id": "c", "verdict": "bad", "reason": "Clip", "verdict_why": [], "name": "C", "shelf": None,
                 "format": "VHS", "format_clues": []}]
        dec = {"files": {"a": {"status": "good", "updated_at": "2026-11-26T00:00:00Z"},
                         "b": {"status": "bad", "reasons": ["Poor quality"], "updated_at": "2026-11-20T00:00:00Z"},
                         "c": {"status": "bad", "reasons": ["Clip"], "updated_at": "2026-11-20T00:00:00Z"}}}
        rep = ev.evaluate(rows, dec)
        self.assertEqual(rep["good_pile"]["size"], 2)
        self.assertEqual(rep["good_pile"]["person_agreed"], 1)
        self.assertEqual(rep["bad_pile"]["reasons"], {"same reason": 1})
        self.assertEqual(len(rep["robot_good_person_bad"]), 1)
        self.assertEqual(rep["unseen_reviews"]["reviewed_files_compared"], 1)
        self.assertFalse(rep["good_pile"]["ready_to_switch_on"])
        self.assertIn("Good pile", ev.markdown(rep))


if __name__ == "__main__":
    unittest.main()
