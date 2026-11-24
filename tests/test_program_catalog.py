import unittest
from scripts.build_program_catalog import build_catalog


class ProgramCatalogTests(unittest.TestCase):
    def item(self, identifier, title, files, status="ok", restricted=False):
        return {
            "identifier": identifier,
            "title": title,
            "vhs_fever_classification": {"label": "movie_or_film"},
            "vhs_fever_file_inventory": {
                "inventory_status": status,
                "video_files": files,
                "access_restricted": restricted,
            },
        }

    def test_every_video_file_becomes_standalone_entry(self):
        records = [
            self.item("one", "One Movie", [
                {"name": "movie.mp4", "duration_seconds": 1200},
            ]),
            self.item("two", "Tape With Two Files", [
                {"name": "part-a.mp4", "duration_seconds": 600},
                {"name": "part-b.mp4", "duration_seconds": 600},
            ]),
        ]
        catalog, review = build_catalog(records)
        standalone = [x for x in catalog if x["entry_type"] == "standalone"]
        self.assertEqual(len(standalone), 3)
        self.assertEqual({s["file_name"] for x in standalone for s in x["segments"]},
                         {"movie.mp4", "part-a.mp4", "part-b.mp4"})
        self.assertEqual(review, [])

    def test_assembled_suggestion_is_additive_not_replacement(self):
        records = [
            self.item("open", "Opening to Test Show", [{"name": "opening.mp4", "duration_seconds": 30}]),
            self.item("main", "Test Show", [{"name": "feature.mp4", "duration_seconds": 900}]),
            self.item("close", "Closing to Test Show", [{"name": "closing.mp4", "duration_seconds": 30}]),
        ]
        catalog, _ = build_catalog(records)
        self.assertEqual(sum(x["entry_type"] == "standalone" for x in catalog), 3)
        assembled = [x for x in catalog if x["entry_type"] == "assembled_candidate"]
        self.assertEqual(len(assembled), 1)
        self.assertEqual(assembled[0]["assembly_status"], "suggested")
        self.assertTrue(assembled[0]["review_required"])

    def test_duplicate_file_reference_is_not_duplicated(self):
        item = self.item("same", "Same", [
            {"name": "same.mp4", "duration_seconds": 60},
            {"name": "same.mp4", "duration_seconds": 60},
        ])
        catalog, _ = build_catalog([item])
        self.assertEqual(sum(x["entry_type"] == "standalone" for x in catalog), 1)

    def test_derivative_is_preserved_as_an_alternate_source_when_original_is_present(self):
        item = self.item("movie", "Movie", [
            {
                "name": "Movie.ia.mp4",
                "duration_seconds": 1200,
                "origin_hint": "derivative",
                "original_file_reference": "Movie.mp4",
            },
            {
                "name": "Movie.mp4",
                "duration_seconds": 1200,
                "origin_hint": "original",
                "original_file_reference": None,
            },
        ])
        catalog, _ = build_catalog([item])
        standalone = [x for x in catalog if x["entry_type"] == "standalone"]
        self.assertEqual(len(standalone), 2)
        self.assertEqual({x["segments"][0]["file_name"] for x in standalone}, {"Movie.mp4", "Movie.ia.mp4"})

    def test_derivative_is_retained_when_original_is_missing(self):
        item = self.item("movie", "Movie", [
            {
                "name": "Movie.ia.mp4",
                "duration_seconds": 1200,
                "origin_hint": "derivative",
                "original_file_reference": "Movie.mp4",
            },
        ])
        catalog, _ = build_catalog([item])
        standalone = [x for x in catalog if x["entry_type"] == "standalone"]
        self.assertEqual(len(standalone), 1)
        self.assertEqual(standalone[0]["segments"][0]["file_name"], "Movie.ia.mp4")

    def test_missing_video_and_restricted_items_enter_review_queue(self):
        records = [
            self.item("none", "No Video", []),
            self.item("locked", "Restricted", [{"name": "locked.mp4", "duration_seconds": 60}], restricted=True),
            self.item("error", "Inventory Error", [], status="error"),
        ]
        catalog, review = build_catalog(records)
        self.assertEqual(len([x for x in catalog if x["entry_type"] == "standalone"]), 1)
        self.assertEqual(len(review), 3)
        self.assertIn("no_video_file_candidates", review[0]["reasons"] + review[1]["reasons"] + review[2]["reasons"])
        locked = next(x for x in catalog if x["entry_type"] == "standalone")
        self.assertTrue(locked["review_required"])
        self.assertTrue(locked["source_access_restricted"])

    def test_unknown_duration_is_retained_and_flagged(self):
        catalog, _ = build_catalog([
            self.item("unknown", "Unknown Runtime", [{"name": "unknown.mp4", "duration_seconds": None}])
        ])
        entry = next(x for x in catalog if x["entry_type"] == "standalone")
        self.assertIsNone(entry["total_known_duration_seconds"])
        self.assertTrue(entry["review_required"])

    def test_tmdb_match_is_attached_as_unconfirmed_candidate(self):
        item = self.item("movie", "Movie", [{"name": "movie.mp4", "duration_seconds": 1200}])
        item["vhs_fever_metadata_artwork"] = {
            "tmdb": {
                "tmdb_id": 123,
                "title": "The Movie",
                "release_date": "1995-01-01",
                "match_method": "high_similarity_title_search",
                "tmdb_poster": "https://image.tmdb.org/t/p/w500/poster.jpg",
                "tmdb_backdrop": "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
                "review_status": "needs_review",
            }
        }
        catalog, _ = build_catalog([item])
        entry = next(x for x in catalog if x["entry_type"] == "standalone")
        self.assertIsNone(entry["matched_title_id"])
        self.assertEqual(entry["title_match_suggestion"]["status"], "candidate")
        self.assertEqual(entry["title_match_suggestion"]["provider_id"], "123")
        self.assertEqual(entry["title_match_suggestion"]["review_status"], "needs_review")

    def test_ambiguous_tmdb_results_are_carried_without_selecting_one(self):
        item = self.item("movie", "Movie", [{"name": "movie.mp4", "duration_seconds": 1200}])
        item["vhs_fever_metadata_artwork"] = {
            "tmdb": {
                "status": "ambiguous_title_match",
                "candidate_results": [
                    {"tmdb_id": 1, "title": "Movie One", "similarity": 0.91},
                    {"tmdb_id": 2, "title": "Movie Two", "similarity": 0.89},
                ],
            }
        }
        catalog, _ = build_catalog([item])
        entry = next(x for x in catalog if x["entry_type"] == "standalone")
        self.assertIsNone(entry["matched_title_id"])
        self.assertEqual(entry["title_match_suggestion"]["status"], "candidate")
        self.assertEqual(len(entry["title_match_suggestion"]["candidates"]), 2)

    def test_missing_tmdb_enrichment_is_explicitly_unmatched(self):
        item = self.item("movie", "Movie", [{"name": "movie.mp4", "duration_seconds": 1200}])
        catalog, _ = build_catalog([item])
        entry = next(x for x in catalog if x["entry_type"] == "standalone")
        self.assertEqual(entry["title_match_suggestion"]["status"], "unmatched")
        self.assertEqual(entry["title_match_suggestion"]["reason"], "not_enriched")


if __name__ == "__main__":
    unittest.main()
