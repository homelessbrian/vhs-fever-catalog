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


class PickPages(unittest.TestCase):
    def test_page_with_several_titles_and_what_gets_picked(self):
        import pick_batch as pb
        page = {"identifier": "x", "kind_guess": "movie_or_unknown", "size_bytes": 900_000_000, "collections": ["vhsvault"],
                "format_guess": "VHS", "title": ["Bambi", "Bambi (1989 VHS)"]}
        self.assertTrue(pb.wanted(page, "vhs_movies"))
        self.assertFalse(pb.wanted(dict(page, title="Opening to Bambi 1989 VHS"), "vhs_movies"))
        self.assertFalse(pb.wanted(dict(page, kind_guess="commercials"), "vhs_movies"))
        self.assertFalse(pb.wanted(dict(page, format_guess="DVD"), "vhs_movies"))
        self.assertFalse(pb.wanted(dict(page, size_bytes=50_000_000), "vhs_movies"))
        self.assertFalse(pb.wanted(dict(page, found_by=["keyword vhsrip"], found_by_uploader=False, format_guess="unknown"),
                                   "vhs_movies"))


class StoreLabelsAreNotTV(unittest.TestCase):
    """A VHS label named after a TV company (CBS/FOX, HBO Video) was sold in stores."""

    def test_video_labels_kept(self):
        for name in ("Star Wars CBS FOX 1984.mp4", "WARGAMES1984 CBS FOX VIDEO VHS RIP.mp4",
                     "DOOMSDAY GUN 1994 HBO HOME VIDEO VHS RIP.mp4", "Play School (1984, BBC Video - VHSrip).mp4"):
            self.assertIsNone(vm.why_not_kept(row(name=name), None), name)

    def test_real_tv_recordings_still_left_out(self):
        for name in ("1955 Youre Never Too Young (1989 AMC).mp4", "CBS Late Movie - Parts The Clonus Horror.mp4",
                     "1993-1994 Fox Kids.mp4"):
            self.assertEqual(vm.why_not_kept(row(name=name), None), "taped off TV or a TV episode", name)


class KindWordsInMovieTitles(unittest.TestCase):
    def test_movie_titles_not_mistaken_for_other_kinds(self):
        import auto_sort as a
        self.assertFalse(a.EDUCATIONAL.search("A Hazard of Hearts (1987)"))
        self.assertFalse(a.RELIGIOUS.search("THE CROW SALVATION 2000 VHS RIP"))
        self.assertFalse(a.RELIGIOUS.search("Revival of Evil"))
        self.assertFalse(a.TRAVEL.search("Gulliver's Travels"))
        self.assertFalse(a.DOCUMENTARY.search("Puss in Boots Travels Around the World"))
        self.assertTrue(a.EDUCATIONAL.search("Hazardous Materials Training"))
        self.assertTrue(a.TRAVEL.search("Caribbean Cruise Ship Tour"))

    def test_tape_era_label_counts_as_vhs(self):
        import auto_sort as a
        self.assertTrue(any(f == "VHS" and rx.search("Star Wars CBS FOX 1984") for rx, f, _s in a.RIP_TAGS))


class TapeSign(unittest.TestCase):
    def test_no_sign_of_a_tape_is_left_out(self):
        r = row(name="Blawan set.mp4", format_confidence="low")
        self.assertIn("not VHS", vm.why_not_kept(r, title(conf=None), {"collection": ["opensource_movies"]},
                                                 check_tape=True))

    def test_any_tape_sign_keeps_it(self):
        low = row(format_confidence="low")
        self.assertIsNone(vm.why_not_kept(low, title(conf=None), {"collection": ["vhsvault"]}, check_tape=True))
        self.assertIsNone(vm.why_not_kept(low, title(conf=None), {"archive_title": "Kuffs (1992 VHS)"},
                                          check_tape=True))
        old_film = title()
        old_film["tmdb"]["candidates"][0]["release_date"] = "1942-08-13"
        self.assertIsNone(vm.why_not_kept(low, old_film, {}, check_tape=True))
        new_film = title()
        new_film["tmdb"]["candidates"][0]["release_date"] = "2019-01-01"
        self.assertIn("not VHS", vm.why_not_kept(row(name="Bambi.mp4", format_confidence="low"), new_film, {},
                                                 check_tape=True))
        self.assertIsNone(vm.why_not_kept(row(name="Old Bear (UK VHS).mp4", format_confidence="low"),
                                          title(conf=None), {}, check_tape=True))

    def test_dj_sets_and_talks_are_not_movies(self):
        for name in ("Bonobo Boiler Room New York DJ Set.mp4", "CppCon 2019 Andrei Alexandrescu.mp4",
                     "Black Hat USA 2019 Command Injection.ogv", "LIVE Press briefing on the storm.mp4"):
            self.assertIn("not a movie", vm.why_not_kept(row(name=name), title()))
        self.assertIsNone(vm.why_not_kept(row(name="Black Hawk Down (2002 VHS).mp4"), title()))


class TvFeed(unittest.TestCase):
    def test_keeps_only_what_the_tv_reads(self):
        src = {"id": "s1", "ok": True, "role": "original", "n": "Bambi (1989).mp4", "item": "bambi-vhs", "dur": 4200.0,
               "fmt": "MPEG4", "url": vm.archive_url("bambi-vhs", "Bambi (1989).mp4"), "why": ["long"], "flags": []}
        odd = dict(src, id="s2", url="https://example.org/other.mp4")
        feed = {"generated_at": "2026-11-26", "summary": {"x": 1}, "titles": [{
            "id": "t1", "t": "Bambi", "adult": False, "genres": ["Animation"], "raw": "bambi_1989", "guess": {"title": "x"},
            "movie": {"tmdb_id": 3170, "title": "Bambi", "year": "1942", "poster": "p", "why": "sure"},
            "tmdb": {"q": "bambi", "c": [{"tmdb_id": 3170, "title": "Bambi", "release_date": "1942-08-13", "why": "w"},
                                         {"tmdb_id": 9, "title": "Other"}]},
            "un": [{"ver": "1", "notes": ["Hi-Fi"], "name": "x", "also": [], "segs": [{"src": [src, odd]}]}],
            "eds": [{"media": "VHS", "year": "1989", "flags": [], "recs": [{"segs": [{"src": [src]}]}]}],
        }]}
        out = vm.tv_feed(feed)
        self.assertEqual(set(out), {"generated_at", "titles"})
        t = out["titles"][0]
        self.assertEqual(set(t), {"id", "t", "adult", "genres", "movie", "tmdb", "un", "eds"})
        self.assertEqual(t["movie"], {"tmdb_id": 3170, "title": "Bambi", "year": "1942", "poster": "p"})
        self.assertEqual(len(t["tmdb"]["c"]), 1)
        s1, s2 = t["un"][0]["segs"][0]["src"]
        self.assertNotIn("url", s1)          # the usual Archive link is worked out by the TV
        self.assertEqual(s2["url"], "https://example.org/other.mp4")
        self.assertNotIn("why", s1)
        self.assertEqual(t["eds"][0]["media"], "VHS")
        self.assertEqual(t["un"][0]["notes"], ["Hi-Fi"])

    def test_archive_url_encodes_like_the_tv(self):
        self.assertEqual(vm.archive_url("item", "A B/[c] é.mp4"), "https://archive.org/download/item/A%20B/%5Bc%5D%20%C3%A9.mp4")
