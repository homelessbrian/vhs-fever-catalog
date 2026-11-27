import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import unsure_kinds as uk  # noqa: E402


class UnsureKinds(unittest.TestCase):
    def test_kids_tv_and_series_tapes(self):
        for name in ("Kipper - The Little Ghost and Other Stories (1998, Full VHS).mp4",
                     "19b. Thomas and His Friends Get Along (US VHS) [2000].mp4",
                     "Postman Pat 2 on 1 More Adventures from Pat and his Friends (1999 UK VHS).mp4",
                     "The Very Best of Dad's Army Volume Two (1994 UK VHS).mp4",
                     "Bob the Builder - Busy Bob & Silly Spud (2002).mp4"):
            self.assertEqual(uk.reason(file_names=[name]), "kids' TV or series tape", name)

    def test_films_with_a_show_name_are_not_tv_tapes(self):
        self.assertIsNone(uk.reason(file_names=["Rugrats in Paris The Movie (2001 VHS).mp4"]))
        self.assertIsNone(uk.reason(file_names=["The Many Adventures of Winnie the Pooh (1996 VHS).mp4"],
                                    feature_film=True))

    def test_home_videos(self):
        for name in ("wedding of bill and jessaca.mp4", "1994-08 Italy Aug-Sept.m4v",
                     "1989-07 Florida Vacation - Donald and Kristy.m4v", "Levi's Favorite Home Videos 2016 VHS.mp4"):
            self.assertEqual(uk.reason(file_names=[name]), "home video", name)
        for name in ("The Wedding Banquet (1993).mkv", "BORN IN EAST L.A. 1996 GOODTIMES HOME VIDEO VHS RIP.mp4",
                     "Angels In The Endzone (Full 1998 Walt Disney Home Video VHS).mp4"):
            self.assertIsNone(uk.reason(file_names=[name]), name)

    def test_instructional(self):
        for name in ("2003 VHS • Play Piano in a Flash 60 FPS.mp4", "1990s VHS • Grooming the Bichon Frise 60 FPS.mp4",
                     "Rock 'N Learn - Phonics Volume 1 (2000).mp4"):
            self.assertEqual(uk.reason(file_names=[name]), "instructional tape", name)
        self.assertIsNone(uk.reason(file_names=["Shinobi 3 Hidden Techniques (English Dubbed).mp4"]))

    def test_foreign_only_when_every_file_is(self):
        for name in ("Asterix ja Kleopatra.MP4", "Der grosse Deutsche Märchenfilm: Die Bremer Stadtmusikanten (1997).mp4"):
            self.assertEqual(uk.reason(file_names=[name]), "not in English", name)
        self.assertIsNone(uk.reason(file_names=["Silent Möbius_ The Motion Picture (English Dub).m4v"]))
        self.assertIsNone(uk.reason(file_names=["Asterix ja Kleopatra.MP4", "Asterix and Cleopatra.mp4"]))


if __name__ == "__main__":
    unittest.main()
