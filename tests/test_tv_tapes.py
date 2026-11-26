"""Off-air TV tapes (Oakley Tapes / The Don Gorelick Television Collection).

Titles, dates and file names below are copied from real Archive pages
(checked 2026-11-25).
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import auto_sort  # noqa: E402
import tv_tapes as T  # noqa: E402
from catalog_model import build  # noqa: E402


def page(ident, title, date=None, files=(), description=None, subject=None):
    return {"identifier": ident, "title": title, "date": date, "collection": ["gorelicktv", "vhsvault"],
            "description": description if description is not None else title,
            "subject": subject if subject is not None else title, "files": list(files)}


def video(name, length="7800.0", md5=None, source="original"):
    return {"name": name, "source": source, "format": "MPEG4", "length": length, "size": "4000000000",
            "md5": md5 or (name.encode().hex()[:32])}


NIGHTLINE = page("OakleyTapes_449NEWSNIGHTLINEWED2001APR11", "449 NEWS & NIGHTLINE WED 2001 APR 11", "2001-04-11",
                 [video("0001_0102_024241.mp4", "7801.11"), video("0002_0102_045252.mp4", "7804.35"),
                  video("0003_0102_070259.mp4", "7797.24"), video("0004_0102_091305.mp4", "6440.5")])


class ReadPage(unittest.TestCase):
    def test_nightline(self):
        t = T.read_page(NIGHTLINE)
        self.assertEqual(t["tape_number"], "449")
        self.assertEqual([s["name"] for s in t["shows"]], ["News", "Nightline"])
        self.assertEqual(t["channels"], ["ABC"])
        self.assertEqual(t["air_date"], "2001-04-11")
        self.assertIsNone(t["air_date_end"])
        self.assertEqual(T.display_title(t), "Tape 449: News + Nightline (Apr 11, 2001)")

    def test_letterman_network_by_year_and_range(self):
        t = T.read_page(page("OakleyTapes_1621", "1621 NEWS, ENT. TONIGHT, D. LETT SUN 2001 FEB 11 - FEB 12"))
        self.assertEqual([s["name"] for s in t["shows"]],
                         ["News", "Entertainment Tonight", "Late Show with David Letterman"])
        self.assertEqual(t["channels"], ["CBS"])
        self.assertEqual((t["air_date"], t["air_date_end"]), ("2001-02-11", "2001-02-12"))

    def test_carson_two_dates(self):
        t = T.read_page(page("OakleyTapes_11", "11 J. CARSON FRI. 1990 JAN 12, TUES 1990 MAR 10", "1990-03-10"))
        self.assertEqual(t["shows"][0]["name"], "The Tonight Show Starring Johnny Carson")
        self.assertEqual(t["air_dates"], ["1990-01-12", "1990-03-10"])
        self.assertEqual(t["air_date"], "1990-01-12")

    def test_new_year_slip(self):
        t = T.read_page(page("OakleyTapes_9", "9 J. CARSON WED 1989 DEC 27 - WED 1989 JAN 3"))
        self.assertEqual((t["air_date"], t["air_date_end"]), ("1989-12-27", "1990-01-03"))

    def test_short_ranges(self):
        self.assertEqual(T.read_page(page("OakleyTapes_1025", "1025 ENTERTAIN TAPE FRI 1997 JUNE 06 - 07"))["air_dates"],
                         ["1997-06-06", "1997-06-07"])
        t = T.read_page(page("OakleyTapes_470", "470 NEWS & ENT. TONIGHT SAT 94 MAY 21-24"))
        self.assertEqual((t["air_date"], t["air_date_end"]), ("1994-05-21", "1994-05-24"))
        t = T.read_page(page("OakleyTapes_3373", "3373 Entertain Tape Sat 2001 Sept 29 30"))
        self.assertEqual((t["air_date"], t["air_date_end"], t["tape_type"]), ("2001-09-29", "2001-09-30", "Entertainment"))
        self.assertEqual(T.display_title(t), "Tape 3373: Entertainment tape (Sep 29–30, 2001)")

    def test_saturday_night_live_is_not_a_weekday(self):
        t = T.read_page(page("OakleyTapes_9SNL", "9 SAT. NITE LIVE APR 21 1990, MAY 12 1990, MAY 19 1990"))
        self.assertEqual([s["name"] for s in t["shows"]], ["Saturday Night Live"])
        self.assertEqual(t["air_dates"], ["1990-04-21", "1990-05-12", "1990-05-19"])
        t = T.read_page(page("OakleyTapes_20010106", "20010106 SAT NIGHT LIVE"))
        self.assertEqual((t["tape_number"], t["air_date"]), (None, "2001-01-06"))

    def test_show_in_parentheses(self):
        t = T.read_page(page("OakleyTapes_8400", "8400 ENTERTAIN TAPE (ELLEN DEGENERES) THURS 2008 AUG 28"))
        self.assertEqual([s["name"] for s in t["shows"]], ["The Ellen DeGeneres Show"])
        t = T.read_page(page("OakleyTapes_8401", "8401 ENTERTAIN TAPE (NEWS) THURS 2008 AUG 28"))
        self.assertEqual([s["name"] for s in t["shows"]], ["News"])

    def test_weekday_before_numeric_date(self):
        t = T.read_page(page("OakleyTapes_19980523",
                             "19980523 GODZILLA-KING OF THE MONSTERS GODZILLA VS MOTHRA SAT 5-23-98"))
        self.assertNotIn("Sat", t["shows"][0]["name"])
        self.assertEqual(t["air_date"], "1998-05-23")

    def test_month_only(self):
        t = T.read_page(page("OakleyTapes_18", "18 PARASITE EVIL LETS DO IT WITCHBOARD 1988 AUG"))
        self.assertEqual((t["air_date"], t["air_month"]), (None, "1988-08"))
        self.assertTrue(T.display_title(t).endswith("(Aug 1988)"))

    def test_lineup_from_description(self):
        desc = ("<div>53 Natl Geographic Explorer - Sun 98 Nov 22</div><div><br /></div>"
                "<div>This tape will be rerecorded in better quality soon.</div><div><br /></div>"
                "<div><b>Sunday, November 22, 1998</b></div><div>National Geographic Explorer (TBS)</div>"
                "<div><br /></div><div><b>Tuesday, November 24, 1998</b></div><div>The Rosie O'Donnell Show (NBC)</div>"
                "<div><br /></div><div><b>Unknown Date, 1998</b></div><div>Pinnacle (CNN)</div>")
        t = T.read_page(page("OakleyTapes_53_NatGeo", "[53] 19981122 - 19981206 (Nat Geo+CNN)", "1998-11-22",
                             description=desc))
        self.assertEqual(t["tape_number"], "53")
        self.assertEqual(t["lineup"][0], {"date": "1998-11-22", "year": 1998, "show": "National Geographic Explorer",
                                          "channel": "TBS", "partial": False})
        self.assertIsNone(t["lineup"][2]["date"])
        self.assertEqual(t["channels"], ["CNN", "NBC", "TBS"])
        self.assertEqual(t["air_date"], "1998-11-22")

    def test_only_tv_tape_pages(self):
        self.assertIsNone(T.read_page({"identifier": "a-goofy-movie-1995-vhs", "title": "A Goofy Movie",
                                       "collection": ["vhsvault"]}))


class Pieces(unittest.TestCase):
    def test_capture_files_in_order_screen_recording_left_out(self):
        t = T.read_page(page("OakleyTapes_3373_Entertain", "3373 Entertain Tape Sat 2001 Sept 29 30"))
        names = ["3373 Entertain Tape Sat 2001 Sept 29-30.mp4", "2024-09-22_00-41-11 - Main Terminal.mp4"]
        self.assertEqual(T.tape_pieces(names, t), ["3373 Entertain Tape Sat 2001 Sept 29-30.mp4"])
        self.assertEqual(T.tape_pieces(["20240103_150135.mp4", "20240103_105103.mp4"], t),
                         ["20240103_105103.mp4", "20240103_150135.mp4"])


class CatalogAndAutoSort(unittest.TestCase):
    def test_nightline_files_become_one_tv_tape(self):
        m = build([NIGHTLINE])
        recs = sorted(m["recordings"], key=lambda r: r["display_name"])
        self.assertEqual(len(recs), 4)
        r = recs[1]
        self.assertEqual(r["catalog_category"], "tv_recording")
        self.assertIn("Aired Wed, Apr 11, 2001", r["category_reasons"])
        self.assertIn("Piece 2 of 4 of the tape (play in order)", r["category_reasons"])
        self.assertNotIn("no VHS/DVD format in filename", r["category_reasons"])
        self.assertEqual(r["interpretation"]["title"], "Tape 449: News + Nightline (Apr 11, 2001)")
        self.assertEqual({t["display_title"] for t in m["titles"]}, {"Tape 449: News + Nightline (Apr 11, 2001)"})

        items = m["archive_items"]
        rows = auto_sort.sort_all(m["recordings"], items, m["artwork"], m["titles"], {})
        self.assertEqual({(x["kind"], x["format"], x["shelf"], x["builtin_commercials"]) for x in rows},
                         {("tv_recording", "TV", "tv_recording", "likely")})
        ads = auto_sort.build_ad_list(rows)
        self.assertEqual(len(ads), 4)
        self.assertEqual((ads[0]["channel"], ads[0]["shows"], ads[0]["year"]), ("ABC", ["News", "Nightline"], 2001))

    def test_other_file_on_tape_page_stays_in_manual_review(self):
        p = page("OakleyTapes_3373_Entertain", "3373 Entertain Tape Sat 2001 Sept 29 30", "2001-09-29",
                 [video("2024-09-22_00-41-11 - Main Terminal.mp4", "2709.14"),
                  video("3373 Entertain Tape Sat 2001 Sept 29-30.mp4", "30600.18")])
        cats = {r["display_name"]: r["catalog_category"] for r in build([p])["recordings"]}
        self.assertEqual(cats["3373 Entertain Tape Sat 2001 Sept 29-30.mp4"], "tv_recording")
        self.assertEqual(cats["2024-09-22_00-41-11 - Main Terminal.mp4"], "manual_review")


if __name__ == "__main__":
    unittest.main()
