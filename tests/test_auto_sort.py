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


MATCHED = {"tmdb": {"status": "one_likely_candidate", "confidence": "likely", "candidates": [{"title": "Bambi"}]}}


def sort_one(name, dur, probe=OK, item=None, title=None, **kw):
    items = [item or {"archive_identifier": "item1", "collection": ["vhsvault", "vhsmovies"],
                      "video_file_count": 50, "note": "Container only."}]
    r = rec(name, dur, **kw)
    titles = []
    if title is not None:
        r["title_suggestion_id"] = "t1"
        titles = [dict(title, title_suggestion_id="t1", display_title=name)]
    return a.sort_all([r], items, [], titles, {"f1": probe} if probe else {})[0]


class KindAndVerdict(unittest.TestCase):
    def test_full_vhs_movie_is_good(self):
        r = sort_one("Bambi (1989 VHS).mp4", 4200, title=MATCHED)
        self.assertEqual((r["kind"], r["format"], r["verdict"], r["shelf"], r["confidence"]),
                         ("movie", "VHS", "good", "vhs_release", "high"))

    def test_plays_fine_but_unknown_is_not_good(self):
        # A capture-card name, no title match: all we know is that it plays.
        nomatch = {"tmdb": {"status": "no_candidates", "confidence": "none", "candidates": []}}
        r = sort_one("0001_0101_000419.mp4", 3500, title=nomatch, item=page("Some tape", 1))
        self.assertEqual(r["verdict"], "review")
        self.assertIn("don't know what it is", r["verdict_why"][0])
        r = sort_one("Bambi (1989 VHS).mp4", 4200, title={"tmdb": {"status": "weak_candidates_only", "confidence": "weak"}})
        self.assertEqual(r["verdict"], "review")
        # an ad is known from its name, but only a title match earns high confidence
        r = sort_one("1990 Michael Jordan Coca Cola Commercial.mp4", 30)
        self.assertEqual((r["verdict"], r["confidence"]), ("good", "medium"))

    def test_episode_found_by_name_is_an_episode_not_a_movie(self):
        # A half-hour file with no episode number: the show's episode list
        # had it by name, so it is an episode.
        ep = {"tmdb": {"status": "one_likely_episode", "search_kind": "tv_episode", "search_title": "Ricky's Room",
                       "candidates": [{"title": "Ricky's Room"}]}}
        r = sort_one("Little Ones (2000 VHS).mp4", 1379, title=ep)
        self.assertEqual(r["kind"], "tv_episode")
        self.assertIn("Ricky's Room", r["kind_why"][0])
        r = sort_one("Little Ones (2000 VHS).mp4", 1379, title=MATCHED)
        self.assertEqual(r["kind"], "movie")

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


def page(title, n=2, cols=("vhsvault",)):
    return {"archive_identifier": "item1", "archive_title": title, "collection": list(cols),
            "video_file_count": n, "note": "Container only."}


MPEG2 = dict(OK, video_codec="mpeg2video", audio_codec="ac3")


class PatternsFromRealPages(unittest.TestCase):
    def two(self, name, dur, orig_probe, copy_probe, item=None):
        """An uploaded file plus the Archive's MP4 copy of it, in one recording."""
        it = interpret_filename(name)
        srcs = [{"source_id": "o", "original_name": name, "archive_identifier": "item1", "duration_seconds": dur,
                 "role": "original", "format": "MPEG2", "browser_playable": False},
                {"source_id": "c", "original_name": name.rsplit(".", 1)[0] + ".ia.mp4", "archive_identifier": "item1",
                 "duration_seconds": dur, "role": "archive_derivative", "format": "h.264", "browser_playable": True}]
        r = {"recording_id": "r", "interpretation": it, "part_count": 1, "flags": [], "title_suggestion_id": None,
             "segments": [{"duration_seconds": dur, "sources": srcs}], "catalog_category": "vhs_release"}
        rows = a.sort_all([r], [item or page("x", 50)], [], [], {"o": orig_probe, "c": copy_probe})
        return {x["file_id"]: x for x in rows}

    def test_odd_file_type_is_fine_when_the_archive_copy_plays(self):
        rows = self.two("Burke's Backyard NWS-9, 28-10-95.mpg", 3900, MPEG2, OK,
                        page("Burke's Backyard (NWS-9, 28/10/95)", 2, ["flemishdog", "vhsvault"]))
        self.assertEqual(rows["o"]["verdict"], "good")
        self.assertIn("Archive's MP4 copy", " ".join(rows["o"]["verdict_why"]))
        self.assertEqual((rows["o"]["kind"], rows["o"]["air_date"], rows["o"]["channel"]),
                         ("tv_recording", "1995-10-28", "NWS-9"))
        # without a working copy the worry stays
        rows = self.two("Movie (1990 VHS).mpg", 5000, MPEG2, dict(OK, verdict="could_not_open"))
        self.assertEqual(rows["o"]["verdict"], "review")

    def test_australian_commercial_breaks(self):
        r = sort_one("Break HSV-7, 13-05-86.mpg", 156, item=page("Channel Seven Commercial Break (HSV-7, 13/05/86)"))
        self.assertEqual((r["kind"], r["verdict"], r["air_date"]), ("commercial_compilation", "good", "1986-05-13"))
        r = sort_one("Commercials ATV-10, 02-08-90.mpg", 1500, item=page("Network TEN Commercials (ATV-10, 02/08/90)"))
        self.assertEqual(r["kind"], "commercial_compilation")

    def test_opening_page_pieces_are_the_opening(self):
        item = page("Opening to Lethal Weapon 4 1998 VHS", 5, ["vhsopenings", "vhsvault"])
        r = sort_one("videoplayback (4).mp4", 174, item=item)
        self.assertEqual((r["kind"], r["shelf"], r["verdict"]), ("tape_opening", "vhs_opening_or_previews", "good"))
        r = sort_one("20261015_201323.mp4", 18, item=page("Closing Logos Crystal Lake (DVS)"))
        self.assertEqual(r["kind"], "tape_opening")

    def test_camera_named_pieces_of_one_recording_are_clips(self):
        recs = [rec(f"VHS1984_VID_20261018_12{i:02d}11.mp4", 160, probe_id=f"f{i}") for i in range(10)]
        item = page("All About the Benjamins (2002) Starz InBlack (April 1st, 2011) Full Movie", 46,
                    ["vhsmovies", "vhsvault"])
        rows = a.sort_all(recs, [item], [], [], {f"f{i}": OK for i in range(10)})
        self.assertEqual({(r["kind"], r["verdict"], r["reason"]) for r in rows}, {("clip", "bad", "Clip")})
        # screen recordings on a page are not pieces of one capture
        recs = [rec(f"bandicam 2026 05 2{i} 11 10 53 991.mp4", 300, probe_id=f"f{i}") for i in range(5)]
        rows = a.sort_all(recs, [page("Pingu Barrel Of Fun ( 1991 UK VHS)", 12)], [], [], {f"f{i}": OK for i in range(5)})
        self.assertNotIn("bad", {r["verdict"] for r in rows})

    def test_new_buckets(self):
        self.assertEqual(sort_one("Callanetics.mov", 3560, item=page("Callanetics", 2, ["vhsinstructionals"]))["kind"],
                         "workout")
        self.assertEqual(sort_one("Soccer's Hard Men (1992 UK VHS).mp4", 4600)["kind"], "sports")
        self.assertEqual(sort_one("CBS 2 10PM News 21 Nov 2001.mp4", 1200,
                                  item=page("WBBM Channel 2 CBS News at 10", 2, ["vhsnews"]))["kind"], "news")
        r = sort_one("1990 Found VHS • Balloon Stampede Dance (5-12-90).mp4", 850,
                     item=page("1990 Found VHS • Balloon Stampede Dance (5-12-90)", 1))
        self.assertEqual((r["kind"], r["shelf"]), ("home_video", "home_video"))
        r = sort_one("BBC One Continuity (2011-11-23) November 23rd 2011.mp4", 250)
        self.assertEqual(r["kind"], "promo_or_bump")
        r = sort_one("elvis_on_ice._hevc_nvenc.mkv", 22200, item=page("Found VHS Tape - 1990s - Elvis Stojko on Ice"))
        self.assertEqual(r["kind"], "tv_recording")

    def test_educational_how_to_and_documentary(self):
        k = lambda n, d=1800: sort_one(n, d, item=page(n, 1, ["the-vista-group-video"]))["kind"]
        self.assertEqual(k("1986 VHS • Seatbelts and the Family, Shattering Some Myths 60 FPS.mp4", 900), "educational")
        self.assertEqual(k("2001 VHS • Using the ProNeb Ultra Nebulizer System 60 FPS.mp4"), "educational")
        self.assertEqual(k("1997 VHS • Introducing the New Bernina Line Sewing Machines 60 FPS.mp4"), "educational")
        self.assertEqual(k("1994 VHS • Plumbing Preparation and Installation How-To Video Guide 60 FPS.mp4"), "instructional")
        self.assertEqual(k("2000 VHS • How To Use Your Cuisinart Food Processor 60 FPS.mp4"), "instructional")
        self.assertEqual(k("1996 VHS • Over Canada, An Aerial Adventure 60 FPS.mp4"), "documentary")
        self.assertEqual(k("TV Commercials from May-June 2006, on KIRO 7 (CBS) in Seattle, WA • VHS 60 FPS.mp4", 7200),
                         "commercial_compilation")
        self.assertEqual(k("TV Programming from March 2003 on ABC • VHS 60 FPS.mp4", 7200), "tv_recording")

    def test_thrift_store_shelf_kinds(self):
        k = lambda n, d=1800: sort_one(n, d, item=page(n, 1))["kind"]
        self.assertEqual(k("1992 VHS • Rodeo Bloopers and Wrecks 60 FPS.mp4"), "sports_bloopers")
        self.assertEqual(k("1990 VHS • NFL Follies Goes Hollywood.mp4"), "sports_bloopers")
        self.assertEqual(k("America's Funniest Home Videos Volume 2 (1991 VHS).mp4"), "bloopers")
        self.assertEqual(k("Tae Bo Basic Workout (1998 VHS).mp4", 2800), "workout")
        self.assertEqual(k("Golf Lessons with Arnold Palmer (1988 VHS).mp4"), "hobby")
        self.assertEqual(k("Barney - Waiting for Santa (1990 VHS).mp4", 2700), "kids_show")
        self.assertEqual(k("Wee Sing Sing-Along (1986 VHS).mp4"), "kids_learning")
        self.assertEqual(k("Learn the ABCs with Bert (1989 VHS).mp4"), "kids_learning")
        self.assertEqual(k("Karaoke Hits Volume 3 (1995 VHS).mp4"), "karaoke")
        self.assertEqual(k("Eddie Murphy Raw stand-up (1987 VHS).mp4", 5400), "standup")
        self.assertEqual(k("VeggieTales - Where's God When I'm S-Scared (1993 VHS).mp4"), "religious")
        self.assertEqual(k("Hawaii Travelogue (1987 VHS).mp4"), "travel")
        self.assertEqual(k("Video Fireplace (1985 VHS).mp4", 3600), "travel")
        self.assertEqual(k("Westwood Timeshare Video Brochure (1994 VHS).mp4", 900), "infomercial")
        self.assertEqual(k("Drivers Ed - Signal 30 (1959).mp4", 1500), "school")
        self.assertEqual(k("Emily's Dance Recital June 1996.mp4", 3600), "recital")
        self.assertEqual(k("A Garfield Christmas Special (1987 VHS).mp4", 1440), "tv_special")
        self.assertEqual(k("Bridge Across Time - made for TV movie (1985 VHS).mp4", 5400), "tv_movie")
        # ABC the network is not a kids' learning tape
        self.assertNotEqual(k("ABC Afterschool Special - The Bully (1985 VHS).mp4", 2700), "kids_learning")
        # a rodeo tape with no blooper words is sports, not a movie
        self.assertEqual(k("1989 VHS • Flip Side of Rodeo 60 FPS.mp4", 2530), "sports")

    def test_one_collection_as_plain_text(self):
        r = sort_one("Bambi (1989 VHS).mp4", 4200, item=dict(page("Bambi"), collection="vhsvault"), title=MATCHED)
        self.assertEqual(r["verdict"], "good")


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
