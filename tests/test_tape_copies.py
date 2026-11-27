import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import tape_copies as tc  # noqa: E402

FULL = [[0, 0, 640, 480, 640, 480]]            # picture fills a 4:3 frame
PILLAR = [[160, 0, 1120, 720, 1280, 720]]      # 4:3 picture with bars at the sides of a 16:9 file
FILLS_WIDE = [[0, 0, 1280, 720, 1280, 720]]    # picture fills a 16:9 file
LETTERBOX = [[0, 60, 640, 420, 640, 480]]      # bars top and bottom on a 4:3 tape


def f(i, name, dur=5040, w=640, h=480, boxes=FULL, item=None, year="1992", sound="ok", ch=2, role="original", notes=()):
    return {"id": i, "n": name, "item": item or f"page-{i}", "role": role, "dur": dur, "media": "VHS",
            "tape_year": year, "notes": list(notes), "w": w, "h": h, "sound": sound, "ch": ch, "boxes": boxes}


class PictureTest(unittest.TestCase):
    def shape(self, boxes, w, h, words=()):
        ca, fa = tc.picture_shape(boxes, w, h)
        return tc.picture_word(ca, fa, list(words))

    def test_shapes(self):
        self.assertEqual(self.shape(FULL, 640, 480), "Full screen")
        self.assertEqual(self.shape(PILLAR, 1280, 720), "Full screen")
        self.assertEqual(self.shape(LETTERBOX, 640, 480), "Widescreen")
        self.assertEqual(self.shape(FILLS_WIDE, 1280, 720), "Zoomed")
        self.assertEqual(self.shape(FILLS_WIDE, 1280, 720, ["Widescreen"]), "Widescreen")

    def test_analog_capture_counts_as_4_3(self):
        self.assertAlmostEqual(tc.frame_shape(720, 480), 4 / 3)
        self.assertAlmostEqual(tc.frame_shape(1280, 720), 16 / 9)


class NameMovieTest(unittest.TestCase):
    def test_same_recording_on_many_pages_is_one_tape(self):
        files = [f("a", "101 Dalmatians (1992 VHS).mp4", item="p1"),
                 f("a2", "101 Dalmatians (1992 VHS).ia.mp4", item="p1", role="archive_derivative"),
                 f("b", "101 Dalmatians (1992 VHS) (Version 2).mp4", dur=5050, item="p2"),
                 f("c", "101 Dalmatians (1992 VHS).mp4", dur=5050, item="p3", w=1280, h=720, boxes=FILLS_WIDE)]
        out = tc.name_movie(files, runtime=79, film_year="1961")["files"]
        self.assertEqual(out["a"]["tg"], out["b"]["tg"])
        self.assertEqual(out["a"]["tg"], out["a2"]["tg"])
        self.assertNotEqual(out["a"]["tg"], out["c"]["tg"])
        self.assertEqual(out["a"]["lab"], "1992 VHS · Full screen")
        self.assertEqual(out["c"]["lab"], "1992 VHS · Zoomed · Upscaled HD")
        self.assertLess(out["a"]["pk"], out["c"]["pk"])      # the real tape plays before the upscale
        self.assertLess(out["a"]["pk"], out["a2"]["pk"])     # the uploaded file before the Archive's copy

    def test_uploader_numbering_and_dedications_are_never_shown(self):
        files = [f("a", "101 Dalmatians (1992 VHS) (For Anthony J Weilenmann) (Version 2).mp4")]
        self.assertEqual(tc.name_movie(files, 79, "1961")["files"]["a"]["lab"], "1992 VHS · Full screen")

    def test_uk_tape_runs_fast_and_is_named(self):
        files = [f("a", "101 Dalmatians (1992 VHS).mp4"),
                 f("u", "101 Dalmatians (1961).mp4", dur=4770, w=768, h=576, boxes=[[0, 0, 768, 576, 768, 576]], year="1961")]
        out = tc.name_movie(files, 79, "1961")["files"]
        self.assertEqual(out["u"]["lab"], "UK VHS (PAL) · Full screen")   # 1961 is the film's year, not the tape's
        self.assertLess(out["a"]["pk"], out["u"]["pk"])

    def test_no_sound_is_kept_last_and_hidden(self):
        files = [f("a", "X (1992 VHS).mp4"), f("s", "X (1992 VHS) (Version 1).mp4", sound="silent_audio")]
        out = tc.name_movie(files, 84, "1961")["files"]
        self.assertTrue(out["s"].get("hide"))
        self.assertNotIn("hide", out["a"])
        self.assertGreater(out["s"]["pk"], out["a"]["pk"])

    def test_cut_short_mono_and_previews(self):
        files = [f("a", "X (1995 VHS).mp4", dur=5400, year="1995", item="p1"),
                 f("a2", "X (1995 VHS).mp4", dur=5400, year="1995", item="p2"),
                 f("p", "X (1995 VHS) (Version 2).mp4", dur=5700, year="1995", w=720),
                 f("m", "X (1995 VHS) (Version 3).mp4", dur=5410, year="1995", ch=1, w=704),
                 f("c", "X (1995 VHS) (Version 4).mp4", dur=3000, year="1995", w=352)]
        out = tc.name_movie(files, 90, "1994")["files"]
        self.assertEqual(out["p"]["lab"], "1995 VHS · Full screen · With previews")
        self.assertEqual(out["m"]["lab"], "1995 VHS · Full screen · Mono")
        self.assertEqual(out["c"]["lab"], "1995 VHS · Full screen · Cut short")
        # with previews is a bonus: it plays before the plain copy
        self.assertEqual(min(out.values(), key=lambda x: x["pk"])["tg"], out["p"]["tg"])

    def test_same_name_different_lengths_get_minutes(self):
        files = [f("a", "X (1993 VHS).mp4", dur=5640, year="1993"), f("b", "X (1993 VHS).mp4", dur=5460, year="1993", w=720)]
        labs = {v["lab"] for v in tc.name_movie(files, 90, "1992")["files"].values()}
        self.assertEqual(labs, {"1993 VHS · Full screen · 94 min", "1993 VHS · Full screen · 91 min"})

    def test_length_note_for_a_likely_wrong_match(self):
        files = [f("a", "X (1997 VHS).mp4", dur=4560, year="1997")]
        self.assertIsNotNone(tc.name_movie(files, 132, "1991")["note"])
        self.assertIsNone(tc.name_movie(files, 76, "1991")["note"])


class NameFeedTest(unittest.TestCase):
    def test_writes_names_into_the_feed(self):
        feed = {"titles": [{"id": "t1", "t": "X", "movie": {"tmdb_id": 5, "title": "X", "runtime": 84, "year": "1961"},
                            "eds": [{"media": "VHS", "year": "1992", "notes": ["Canadian Copy"], "recs": [
                                {"segs": [{"src": [{"id": "a", "n": "X (1992 VHS).mp4", "item": "p", "role": "original", "dur": 5040},
                                                   {"id": "a2", "n": "X (1992 VHS).ia.mp4", "item": "p", "role": "archive_derivative", "dur": 5040}]}]}]}]}]}
        audio = {"a": {"width": 640, "height": 480, "verdict": "ok", "audio_channels": 2},
                 "a2": {"width": 640, "height": 480, "verdict": "ok", "audio_channels": 2}}
        tc.name_feed(feed, audio, {"a": {"boxes": FULL}})
        srcs = feed["titles"][0]["eds"][0]["recs"][0]["segs"][0]["src"]
        # an edition's notes are what any copy said, so "Canadian" isn't put on every copy
        self.assertEqual([s["lab"] for s in srcs], ["1992 VHS · Full screen"] * 2)
        self.assertEqual(srcs[0]["tg"], srcs[1]["tg"])


if __name__ == "__main__":
    unittest.main()
