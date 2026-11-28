import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

import kids_tapes  # noqa: E402


def kids(*names, **kw):
    return kids_tapes.reason(list(names), **kw)


class KidsTapesTest(unittest.TestCase):
    def test_preschool_shows_and_learning_tapes(self):
        for name in ("Bob The Builder - Building Friendships", "Thomas & Friends: Best of Gordon",
                     "Barney's Magical Musical Adventure", "Sesame Street: Elmopalooza!",
                     "Blue's Clues: ABC's and 123's", "Wee Sing: Wee Singdom", "Classic Nursery Rhymes",
                     "Alphabet Castle A to M Pickwick Video", "Winnie the Pooh: 123's",
                     "Children's Pre-School Favourites", "Rock 'N Learn: Colors, Shapes & Counting",
                     "Thomas Christmas Party", "Disney's Sing-Along Songs: Sing a Song With Pooh Bear"):
            self.assertTrue(kids(name), name)

    def test_grown_up_tapes_with_similar_words_stay_out(self):
        for name in ("Aretha Franklin: The Queen of Soul", "Cry-Baby", "Legally Blonde 2 on ABC Network",
                     "Kindergarten Cop", "Little Miss Sunshine", "A Kid In King Arthur's Court",
                     "Port Arthur - MP4", "Barney Miller", "123 Mile Super Fat Burner In-Home Walking",
                     "Counting Crows Live"):
            self.assertIsNone(kids(name), name)

    def test_blurb_for_preschoolers(self):
        self.assertTrue(kids("Discover Pet", overview="A preschool adventure about sharing."))
        # a thriller that only mentions an alphabet
        self.assertIsNone(kids("The Alphabet Killer", overview="A detective hunts a killer who uses the alphabet.",
                               genres=["Crime", "Thriller"]))

    def test_short_cartoon_and_family_specials(self):
        self.assertTrue(kids("Rudolph the Red-Nosed Reindeer", genres=["Animation", "Family", "Adventure"],
                             runtime=52, minutes=55, year="1964"))
        self.assertTrue(kids("A Muppet Family Christmas", genres=["Family", "Comedy", "Music"], runtime=47, minutes=48, year="1987"))
        # a hand-me-down Disney special with a stray Horror tag
        self.assertTrue(kids("Disney's Halloween Treat", genres=["Family", "Animation", "Horror"], runtime=47, minutes=47, year="1982"))

    def test_feature_films_stay_on_their_shelves(self):
        self.assertIsNone(kids("Aladdin", genres=["Animation", "Family", "Adventure"], runtime=91, minutes=95, year="1992"))
        self.assertIsNone(kids("Home Alone", genres=["Comedy", "Family"], runtime=103, minutes=105, year="1990"))
        # an old short Disney classic stays in Animation
        self.assertIsNone(kids("Dumbo", genres=["Animation", "Family"], runtime=64, minutes=65, year="1941"))
        # a long recording of a short special is not a special
        self.assertIsNone(kids("Ray Stevens Comedy Video Classics", genres=["Comedy", "Family", "Music"],
                               runtime=31, minutes=88, year="1992"))

    def test_grown_up_cartoons_stay_in_animation(self):
        self.assertIsNone(kids("Slayers Return", genres=["Animation", "Comedy", "Fantasy"], runtime=60, minutes=59, year="1996"))
        self.assertIsNone(kids("Urotsukidoji V", genres=["Animation", "Horror", "Fantasy"], runtime=50, minutes=51, year="1996"))
        self.assertIsNone(kids("Batman Beyond: The Movie", genres=["Animation", "Action", "Sci-Fi"], runtime=41, minutes=41, year="1999"))
        self.assertIsNone(kids("Computer Animation Showcase", genres=["Animation"], runtime=46, minutes=49, year="1997"))
        # but a short Animation tape from the Archive's kids' collection is a kids' tape
        self.assertTrue(kids("Walt Disney Cartoon Classics - Disney's Tall Tales", genres=["Animation"], runtime=45,
                             minutes=60, year="1985", collections={"vhskids"}))

    def test_nothing_known_means_not_kids(self):
        self.assertIsNone(kids("Some Tape"))


if __name__ == "__main__":
    unittest.main()
