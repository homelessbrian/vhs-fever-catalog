import unittest
from scripts.build_final_catalog import build_final_catalog


class FinalCatalogApprovalTests(unittest.TestCase):
    def setUp(self):
        self.candidate = {"program_id": "p1", "title": "Godzilla",
                          "title_match_suggestion": {"status": "candidate"},
                          "source_classification": "movie_or_film"}

    def test_unreviewed_candidate_never_enters_final(self):
        self.assertEqual(build_final_catalog([self.candidate], {}), [])

    def test_title_confirmation_without_classification_confirmation_is_not_enough(self):
        decisions = {"p1": {"title_match": {"status": "confirmed", "provider_id": "1678"}}}
        self.assertEqual(build_final_catalog([self.candidate], decisions), [])

    def test_both_human_confirmations_promote_record(self):
        decisions = {"p1": {
            "title_match": {"status": "confirmed", "provider": "tmdb",
                            "provider_id": "1678", "title": "Godzilla",
                            "release_date": "1954-11-03"},
            "classification": {"status": "confirmed", "value": "movie_or_film"},
            "updated_at": "2026-11-24T12:00:00Z", "notes": "Verified"
        }}
        result = build_final_catalog([self.candidate], decisions)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["matched_title_id"], "1678")
        self.assertEqual(result[0]["confirmed_classification"], "movie_or_film")
        self.assertNotIn("title_match_suggestion", result[0])
        self.assertEqual(result[0]["human_approval"]["status"], "approved")


if __name__ == "__main__":
    unittest.main()
