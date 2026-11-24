"""Regression tests for the Title -> Edition -> Recording -> Source model.

All fixtures are real Internet Archive file entries (tests/fixtures/
archive_real_items.json), captured 2026-11-24. The main proof case is
``fun-and-fancy-free-1997-vhs``, an item titled "All Animated VHS and DVD
Capture (Hauppauge USB-Live 2)" that holds hundreds of unrelated movies.
"""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from catalog_model import archive_file_id, build, interpret_filename  # noqa: E402

FIXTURE = json.loads((ROOT / "tests/fixtures/archive_real_items.json").read_text(encoding="utf-8"))
DISNEY = "fun-and-fancy-free-1997-vhs"


def model():
    return build(FIXTURE["items"])


def editions_by(m, title, media, year):
    return [e for e in m["editions"]
            if e["display_title"] == title and e["media_format"] == media and e["release_year"] == year]


def recordings_of(m, edition):
    ids = set(edition["recording_ids"])
    return [r for r in m["recordings"] if r["recording_id"] in ids]


class FilenameInterpretation(unittest.TestCase):
    def test_vhs_release_with_version(self):
        i = interpret_filename("101 Dalmatians (1992 VHS) (Version 1).mp4")
        self.assertEqual(i["title"], "101 Dalmatians")
        self.assertEqual(i["media_format"], "VHS")
        self.assertEqual(i["release_year"], "1992")
        self.assertEqual(i["version_label"], "1")
        # The bracket year is the tape's year, never the film's year.
        self.assertIsNone(i["work_year"])

    def test_archive_derivative_suffix(self):
        i = interpret_filename("A Bug's Life (1999 VHS) (Version 2).ia.mp4")
        self.assertEqual(i["title"], "A Bug's Life")
        self.assertEqual(i["archive_derivative_suffix"], ".ia")
        self.assertEqual(i["version_label"], "2")

    def test_dvd_is_not_vhs(self):
        i = interpret_filename("101 Dalmatians (2015 DVD).mp4")
        self.assertEqual(i["media_format"], "DVD")

    def test_meaningful_parentheses_kept_as_title_qualifier(self):
        i = interpret_filename("101 Dalmatians (Live Action) (1997 VHS) (Version 2).mp4")
        self.assertEqual(i["title"], "101 Dalmatians")
        self.assertEqual(i["title_qualifiers"], ["Live Action"])

    def test_notes_after_release_are_edition_notes(self):
        i = interpret_filename("A Bug's Life (2003 DVD) (Widescreen Edition).mp4")
        self.assertEqual(i["edition_notes"], ["Widescreen Edition"])
        self.assertEqual(i["title_qualifiers"], [])

    def test_malformed_year_is_flagged_not_guessed(self):
        i = interpret_filename("A Bug's Life (199 VHS) (Version 2).mp4")
        self.assertIsNone(i["release_year"])
        self.assertIn("release_year_malformed", i["flags"])

    def test_irregular_media_case_flagged(self):
        i = interpret_filename("A Goofy Movie (2000 vHS).mp4")
        self.assertEqual(i["media_format"], "VHS")
        self.assertIn("media_label_irregular_case", i["flags"])

    def test_stray_parenthesis_flagged(self):
        i = interpret_filename("An Extremely Goofy Movie (2000 VHS)( (Version 2).mp4")
        self.assertIn("unbalanced_parentheses", i["flags"])

    def test_part_numbers(self):
        i = interpret_filename("101 Dalmatians (1992 VHS) (Version 2) - 01of20.ia.mp4")
        self.assertEqual((i["part_index"], i["part_total"]), (1, 20))
        i = interpret_filename("Alice in Wonderland (1996 VHS) part 11.mp4")
        self.assertEqual(i["part_index"], 11)

    def test_leading_film_year(self):
        i = interpret_filename("(1955b) Godzilla Raids Again.mp4")
        self.assertEqual(i["title"], "Godzilla Raids Again")
        self.assertEqual(i["work_year"], "1955")
        self.assertIn("year_has_letter_suffix", i["flags"])
        self.assertIsNone(i["media_format"])

    def test_tv_episode(self):
        i = interpret_filename("Gullah.Gullah.Island.S01E15.The.School.Show.CBS.WEB-DL.AAC2.0.x264.mp4")
        self.assertEqual(i["title"], "Gullah Gullah Island")
        self.assertEqual((i["season"], i["episode"]), (1, 15))
        self.assertIn("web_download_not_tape", i["flags"])
        self.assertIsNone(i["media_format"])

    def test_unknown_or_unusable_names_flagged(self):
        i = interpret_filename("Richard Scarry's Busy Town/Disk 2/9.ogv")
        self.assertIsNone(i["title"])
        self.assertIn("no_usable_title", i["flags"])
        self.assertIn("file_in_subfolder", i["flags"])
        self.assertIn("no_media_format_in_filename", interpret_filename("My Movie 259.mp4")["flags"])

    def test_tape_without_stated_format(self):
        i = interpret_filename("Mickeys Christmas Carol Walt Disney Mini-Classics 1983 Tape.mpg")
        self.assertEqual(i["release_year"], "1983")
        self.assertEqual(i["media_format"], "TAPE_UNSPECIFIED")


class RealArchiveItem(unittest.TestCase):
    def setUp(self):
        self.m = model()

    def test_many_movies_in_one_item_are_not_one_record(self):
        titles = {t["display_title"] for t in self.m["titles"]
                  if any(r["archive_identifiers"] == [DISNEY] for r in self.m["recordings"]
                         if r.get("title_suggestion_id") == t["title_suggestion_id"])}
        for expected in ["101 Dalmatians", "101 Dalmatians (Live Action)", "A Bug's Life",
                         "A Goofy Movie", "An Extremely Goofy Movie"]:
            self.assertIn(expected, titles)
        archive_title = next(i["archive_title"] for i in self.m["archive_items"]
                             if i["archive_identifier"] == DISNEY)
        self.assertNotIn(archive_title, {t["display_title"] for t in self.m["titles"]})

    def test_different_release_years_are_separate_editions(self):
        for year in ("1992", "1999"):
            self.assertEqual(len(editions_by(self.m, "101 Dalmatians", "VHS", year)), 1)
        self.assertEqual(len(editions_by(self.m, "A Bug's Life", "VHS", "1999")), 1)
        self.assertEqual(len(editions_by(self.m, "A Bug's Life", "VHS", "2000")), 1)

    def test_dvd_edition_is_separate_and_not_vhs(self):
        dvd = editions_by(self.m, "101 Dalmatians", "DVD", "2015")
        self.assertEqual(len(dvd), 1)
        self.assertFalse(dvd[0]["is_vhs"])

    def test_version_1_and_2_are_separate_recordings_of_one_edition(self):
        ed = editions_by(self.m, "A Bug's Life", "VHS", "1999")[0]
        names = {r["display_name"] for r in recordings_of(self.m, ed)}
        self.assertIn("A Bug's Life (1999 VHS) (Version 1).mp4", names)
        self.assertIn("A Bug's Life (1999 VHS) (Version 2).mp4", names)
        # plain .mp4 and the .avi are kept apart too (no evidence they match)
        self.assertIn("A Bug's Life (1999 VHS).mp4", names)
        self.assertIn("A Bug's Life (1999 VHS).avi", names)
        self.assertEqual(len(names), 4)

    def test_archive_derivative_is_alternate_source_not_new_recording(self):
        ed = editions_by(self.m, "A Goofy Movie", "VHS", "2000")[0]
        (rec,) = recordings_of(self.m, ed)
        roles = sorted((s["role"], s["original_name"]) for s in rec["segments"][0]["sources"])
        self.assertEqual(roles, [("archive_derivative", "A Goofy Movie (2000 vHS).ia.mp4"),
                                 ("original", "A Goofy Movie (2000 vHS).mp4")])

    def test_original_and_derivative_encodes_of_tape(self):
        ed = [e for e in self.m["editions"] if e["release_year"] == "1983"][0]
        (rec,) = recordings_of(self.m, ed)
        roles = sorted(s["role"] for s in rec["segments"][0]["sources"])
        self.assertEqual(roles, ["archive_derivative", "archive_derivative", "original"])

    def test_identical_bytes_in_two_items_collapse(self):
        rec = [r for r in self.m["recordings"]
               if r["archive_identifiers"] == ["a-goofy-movie-1995-vhs_20221030", "sleeping-beauty-1986-vhs"]]
        self.assertEqual(len(rec), 1)
        self.assertEqual(rec[0]["identity_basis"], "md5")

    def test_same_filename_different_bytes_stay_separate(self):
        name = "101 Dalmatians (1992 VHS) (Version 2).mp4"
        recs = [r for r in self.m["recordings"] if name in r["also_named"]]
        self.assertEqual(len(recs), 2)  # Disney item vs. the identical pair

    def test_split_parts_form_one_multipart_recording(self):
        (rec,) = [r for r in self.m["recordings"] if r["part_count"] > 1]
        self.assertEqual(rec["part_count"], 3)
        self.assertEqual([s["order"] for s in rec["segments"]], [1, 2, 3])
        self.assertIn("parts_incomplete_3_of_20", rec["flags"])

    def test_typo_titles_not_merged_but_hinted(self):
        typo = [t for t in self.m["titles"] if t["display_title"] == "101 Dalmatisns (Live Action)"]
        self.assertEqual(len(typo), 1)
        self.assertTrue(typo[0].get("possibly_same_as"))

    def test_no_film_year_without_evidence_and_nothing_auto_confirmed(self):
        for t in self.m["titles"]:
            self.assertEqual(t["review_status"], "suggested")
        dal = [t for t in self.m["titles"] if t["display_title"] == "101 Dalmatians"][0]
        self.assertEqual(dal["work_years_seen"], [])

    def test_recordings_without_media_info_are_kept_unassigned(self):
        g = [r for r in self.m["recordings"] if r["display_name"] == "(1954) Godzilla.mp4"]
        self.assertEqual(len(g), 1)
        self.assertIsNone(g[0]["edition_suggestion_id"])
        self.assertEqual(g[0]["unassigned_reason"], "media_or_release_year_missing")

    def test_packaging_linked_to_its_own_tape_and_thumbnails_ignored(self):
        arts = self.m["artwork"]
        self.assertEqual(sorted(a["role_guess"] for a in arts), ["vhs_back", "vhs_front", "vhs_label"])
        self.assertTrue(all(a["recording_id"] for a in arts))
        self.assertFalse(any(a["archive_identifier"] == DISNEY for a in arts))

    def test_every_file_preserved_byte_for_byte(self):
        given = {(i["identifier"], f["name"]) for i in FIXTURE["items"] for f in i["files"]}
        kept = {(f["archive_identifier"], f["original_name"]) for f in self.m["archive_files"]}
        self.assertEqual(given, kept)
        video_sources = {(s["archive_identifier"], s["original_name"])
                         for r in self.m["recordings"] for seg in r["segments"] for s in seg["sources"]}
        videos = {(f["archive_identifier"], f["original_name"])
                  for f in self.m["archive_files"] if f["kind"] == "video"}
        self.assertEqual(video_sources, videos)

    def test_legacy_program_ids_map_one_to_one(self):
        f = next(x for x in self.m["archive_files"] if x["kind"] == "video")
        self.assertEqual(f["file_id"], archive_file_id(f["archive_identifier"], f["original_name"]))
        self.assertEqual(len(self.m["legacy_program_map"]),
                         sum(x["kind"] == "video" for x in self.m["archive_files"]))

    def test_proof_case_counts(self):
        disney = [r for r in self.m["recordings"] if DISNEY in r["archive_identifiers"]]
        eds = {r["edition_suggestion_id"] for r in disney}
        self.assertEqual(len(disney), 19)
        self.assertEqual(len(eds), 11)  # 10 + the typo'd live-action title kept apart
        self.assertEqual(sum(r["source_count"] for r in disney), 36)


if __name__ == "__main__":
    unittest.main()
