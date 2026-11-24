import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import tv_episodes as tv  # noqa: E402
from catalog_model import episode_title_pieces, interpret_filename  # noqa: E402


def ep(season, number, name, air=None):
    return {"season": season, "episode": number, "name": name, "air_date": air, "still": None,
            "overview": None, "url": None}


class FakeSource:
    """Stands in for TMDB / TVDB with a fixed catalogue of shows."""

    def __init__(self, name, shows, fail=False):
        self.name = name
        self.shows = shows  # {show name: (id, year, [episodes])}
        self.fail = fail
        self.searches = []

    def search_shows(self, query):
        if self.fail:
            raise RuntimeError("down")
        self.searches.append(query)
        q = tv.norm(query)
        return [{"source": self.name, "id": sid, "name": n, "year": y, "first_air_date": f"{y}-01-01",
                 "poster": None, "overview": None, "popularity": 1, "url": None}
                for n, (sid, y, _e) in self.shows.items() if q and (q in tv.norm(n) or tv.norm(n) in q)]

    def episodes(self, sid):
        for _n, (i, _y, eps) in self.shows.items():
            if i == sid:
                eps = [dict(e) for e in eps]
                tv._number_absolute(eps)
                return eps
        return []


ALLEGRA = ("Allegra's Window", (1, 1994, [ep(1, 14, "Read Me a Story"), ep(2, 15, "Strange Vet Fellows"),
                                          ep(2, 5, "Copy Cat, Copy Cat Go Away")]))
BLUES = ("Blue's Clues", (2, 1996, [ep(1, 20, "Blue's News")] + [ep(5, n, f"Ep {n}") for n in range(1, 40)]
                          + [ep(6, n, f"S6 {n}") for n in range(1, 30)]))
LITTLE_BILL = ("Little Bill", (3, 1999, [ep(3, 1, "Monty Joins the Class / Dad Goes to School"),
                                          ep(3, 2, "The Violin Lesson / Squirmy")]))
GULLAH = ("Gullah Gullah Island", (4, 1994, [ep(4, 13, "Binyah Goes to School"), ep(1, 5, "Going Places", "1994-10-16"),
                                             ep(3, 2, "Beat It")]))


def find(sources, filename, ceiling=None, require=False):
    i = interpret_filename(filename)
    if i["episode"] is None and i["show_dash_hint"]:
        h = i["show_dash_hint"]
        return tv.find_episode(sources, show=h["show"], season=None, episode=None, episode_title=h["episode_title"],
                               air_date=None, ceiling=ceiling, title_pieces=episode_title_pieces, require_title_match=True)
    return tv.find_episode(sources, show=i["title"], season=i["season"], episode=i["episode"],
                           episode_title=i["episode_title"], air_date=i["air_date"], ceiling=ceiling,
                           title_pieces=episode_title_pieces, require_title_match=require)


class FilenameEpisodeTests(unittest.TestCase):
    def check(self, name, title, season, episode, ep_title):
        i = interpret_filename(name)
        self.assertEqual((i["title"], i["season"], i["episode"], i["episode_title"]), (title, season, episode, ep_title), name)
        self.assertEqual(i["content_hint"], "tv_episode")

    def test_real_naming_styles(self):
        self.check("Blue's Clues S03E69 Environments.mp4", "Blue's Clues", 3, 69, "Environments")
        self.check("Allegra's Window - 1x09 - Don't Touch My Stuff.mp4", "Allegra's Window", 1, 9, "Don't Touch My Stuff")
        self.check("Little Bill Season 2 Episode 1 - Little Bill's Adventure with Captain Brainstorm.mp4",
                   "Little Bill", 2, 1, "Little Bill's Adventure with Captain Brainstorm")
        self.check("Little Bill Episode 4 - Guppies   The Magic Quilt.mp4", "Little Bill", None, 4, "Guppies / The Magic Quilt")
        self.check("The Maxx/Season 1/The Maxx - S01E08 - Chapter 8.mkv", "The Maxx", 1, 8, "Chapter 8")
        self.check("Supaidaman/24 - Cockroach Boy - Great War.flv", "Supaidaman", None, 24, "Cockroach Boy - Great War")
        self.check("S1-08 - 05-23-01 - That's My Bush! - Fare Thee Welfare.mp4", "That's My Bush!", 1, 8, "Fare Thee Welfare")
        self.assertEqual(interpret_filename("S1-08 - 05-23-01 - That's My Bush! - Fare Thee Welfare.mp4")["air_date"], "2001-05-23")

    def test_no_show_name_keeps_episode_title(self):
        i = interpret_filename("Season 2 Episode 15 - Strange Vet Fellows.mp4")
        self.assertIsNone(i["title"])
        self.assertEqual((i["season"], i["episode"], i["episode_title"]), (2, 15, "Strange Vet Fellows"))

    def test_show_dash_episode_is_only_a_hint(self):
        i = interpret_filename("Gullah Gullah Island - Beat It (Noggin Airing).mp4")
        self.assertEqual(i["title"], "Gullah Gullah Island - Beat It")   # title left alone
        self.assertIsNone(i["content_hint"])
        self.assertEqual(i["show_dash_hint"], {"show": "Gullah Gullah Island", "episode_title": "Beat It"})
        self.assertIsNone(interpret_filename("150 - Le Mangeur de Feu (Polish Voiceover).mp4")["show_dash_hint"])
        self.assertIsNone(interpret_filename("The Great Mouse Detective (1999 VHS) - 1of23.mp4")["show_dash_hint"])
        self.assertIsNone(interpret_filename("101 Dalmatians (1992 VHS) (Version 1).mp4")["show_dash_hint"])

    def test_movies_unchanged(self):
        i = interpret_filename("101 Dalmatians (1992 VHS) (Version 2).mp4")
        self.assertIsNone(i["episode"])
        self.assertEqual(i["title"], "101 Dalmatians")
        self.assertIsNone(interpret_filename("A Bug's Life (1999 VHS).mp4")["episode"])


class MatchingTests(unittest.TestCase):
    def setUp(self):
        self.tmdb = FakeSource("tmdb", dict([ALLEGRA, BLUES, LITTLE_BILL, GULLAH]))

    def test_number_and_title_agree(self):
        r = find([self.tmdb], "Allegra's Window - 1x14 - Read Me a Story.mp4")
        self.assertEqual(r["status"], "one_likely_episode")
        e = r["candidates"][0]["episode"]
        self.assertEqual((e["season"], e["episode"], e["name"]), (1, 14, "Read Me a Story"))
        self.assertIn("episode_title", e["basis"])
        self.assertIn("season_and_episode_number", e["basis"])

    def test_title_wins_when_numbers_are_off(self):
        # numbering differs, but the title is unmistakable
        r = find([self.tmdb], "Allegra's Window - 2x19 - Strange Vet Fellows.mp4")
        self.assertEqual(r["candidates"][0]["episode"]["name"], "Strange Vet Fellows")

    def test_number_counted_across_seasons(self):
        # S05E01 is the 2nd episode counted over seasons 1..; S06E136-style numbers
        r = find([self.tmdb], "Blue's Clues S06E41.mp4")
        e = r["candidates"][0]["episode"]
        self.assertEqual((e["season"], e["episode"]), (6, 1))
        self.assertEqual(r["status"], "episode_by_number_only")

    def test_two_story_episode_and_network_tag(self):
        r = find([self.tmdb], "Little Bill Season 3 Episode 1 - Monty Joins The Class  Dad Goes To School.mp4")
        self.assertEqual(r["candidates"][0]["episode"]["episode"], 1)
        self.assertEqual(r["status"], "one_likely_episode")
        r = find([self.tmdb], "Gullah.Gullah.Island.S04E13.Binyah.Goes.to.School.CBS.WEB-DL.AAC2.0.x264.mp4")
        self.assertEqual(r["status"], "one_likely_episode")
        self.assertIn("Gullah Gullah Island", self.tmdb.searches)

    def test_show_dash_episode_name(self):
        r = find([self.tmdb], "Gullah Gullah Island - Beat It (Noggin Airing).mp4")
        self.assertEqual(r["status"], "one_likely_episode")
        self.assertEqual((r["candidates"][0]["episode"]["season"], r["candidates"][0]["episode"]["episode"]), (3, 2))

    def test_show_dash_not_a_show_falls_through(self):
        r = find([self.tmdb], "Rock & Rule - Original Trailer.mkv")
        self.assertEqual(r["status"], "no_episode_found")
        r = find([self.tmdb], "Gullah Gullah Island - Not A Real Episode.mp4")
        self.assertEqual(r["status"], "no_episode_found")   # show alone isn't enough for a dash name

    def test_tvdb_backup_used_when_tmdb_misses(self):
        tvdb = FakeSource("tvdb", {"The Maxx": (77, 1995, [ep(1, 8, "Chapter 8")])})
        r = find([self.tmdb, tvdb], "The Maxx/Season 1/The Maxx - S01E08 - Chapter 8.mkv")
        self.assertEqual(r["source"], "tvdb")
        self.assertEqual(r["tried"], ["tmdb", "tvdb"])
        self.assertEqual(r["candidates"][0]["tvdb_id"], 77)
        self.assertIsNone(r["candidates"][0]["tmdb_id"])

    def test_tvdb_not_called_when_tmdb_finds_it(self):
        tvdb = FakeSource("tvdb", {})
        r = find([self.tmdb, tvdb], "Allegra's Window - 1x14 - Read Me a Story.mp4")
        self.assertEqual(r["tried"], ["tmdb"])
        self.assertEqual(tvdb.searches, [])

    def test_source_failure_moves_on(self):
        down = FakeSource("tmdb", {}, fail=True)
        tvdb = FakeSource("tvdb", dict([ALLEGRA]))
        r = find([down, tvdb], "Allegra's Window - 1x14 - Read Me a Story.mp4")
        self.assertEqual(r["source"], "tvdb")
        self.assertTrue(r["tried"][0].startswith("tmdb (error"))

    def test_air_date(self):
        self.assertEqual(interpret_filename("Gullah Gullah Island 1994-10-16 Episode 5.mp4")["title"], "Gullah Gullah Island")
        r = find([self.tmdb], "Gullah Gullah Island 1994-10-16 Episode 5.mp4")
        e = r["candidates"][0]["episode"]
        self.assertEqual(e["name"], "Going Places")
        self.assertIn("air_date", e["basis"])

    def test_aired_after_tape_flagged(self):
        r = find([self.tmdb], "Allegra's Window - 1x14 - Read Me a Story.mp4", ceiling=1990)
        self.assertFalse(r["candidates"][0]["year_consistent"])

    def test_show_found_but_no_episode(self):
        r = find([self.tmdb], "Allegra's Window - 9x99 - Nothing Like This.mp4")
        self.assertEqual(r["status"], "show_found_episode_not_matched")
        self.assertNotIn("episode", r["candidates"][0])

    def test_forgiving_title_match(self):
        self.assertGreaterEqual(tv.similarity("Copy Cat Copy Cat Go Away", "Copy Cat, Copy Cat Go Away"), 0.95)
        self.assertGreaterEqual(tv.similarity("The Messy Room", "Messy Room"), 0.95)
        self.assertGreaterEqual(tv.similarity("And Too Much Birthday", "The Berenstain Bears and Too Much Birthday"), 0.84)
        self.assertLess(tv.similarity("Toast", "Becca and the Band"), 0.5)


if __name__ == "__main__":
    unittest.main()
