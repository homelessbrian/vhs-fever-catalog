import unittest

from scripts.classify_artwork_ai import decide_review_label, VHS_LABELS
from scripts.evaluate_artwork_ai import evaluate


class ArtworkAITests(unittest.TestCase):
    def test_vhs_cover_candidate_remains_review_only(self):
        result = decide_review_label([
            {"label": "vhs_front_cover", "score": 0.62},
            {"label": "movie_poster", "score": 0.20},
        ])
        self.assertEqual(result["suggested_role"], "vhs_front_cover")
        self.assertEqual(result["review_status"], "needs_review")
        self.assertTrue(result["is_vhs_packaging_candidate"])
        self.assertIn(result["suggested_role"], VHS_LABELS)

    def test_ambiguous_scores_are_not_auto_classified(self):
        result = decide_review_label([
            {"label": "vhs_back_cover", "score": 0.31},
            {"label": "movie_poster", "score": 0.30},
        ])
        self.assertEqual(result["suggested_role"], "unknown")
        self.assertEqual(result["review_status"], "needs_review")

    def test_low_similarity_is_not_auto_classified(self):
        result = decide_review_label([
            {"label": "vhs_spine", "score": 0.12},
            {"label": "other_or_unclear", "score": 0.10},
        ])
        self.assertEqual(result["suggested_role"], "unknown")

    def test_dvd_is_not_vhs_packaging(self):
        result = decide_review_label([
            {"label": "dvd_packaging", "score": 0.58},
            {"label": "vhs_front_cover", "score": 0.18},
        ])
        self.assertFalse(result["is_vhs_packaging_candidate"])

    def test_empty_scores_require_review(self):
        result = decide_review_label([])
        self.assertEqual(result["suggested_role"], "unknown")
        self.assertEqual(result["review_status"], "needs_review")

    def test_low_resolution_skips_are_not_classifier_abstentions(self):
        manifest = {"items": [{
            "archive_identifier": "sample",
            "source_file_name": "cover.jpg",
            "ai_visual_classification": {
                "final_decision": "skipped_low_resolution",
                "review_status": "skipped",
                "image_dimensions": [192, 107],
            },
        }]}
        labels = [{"archive_identifier": "sample", "source_file_name": "cover.jpg",
                   "ground_truth": "positive", "component": "front"}]
        result = evaluate(manifest, labels)
        self.assertEqual(result["counts"]["skipped_low_resolution"], 1)
        self.assertEqual(result["counts"]["abstain_positive"], 0)
        self.assertEqual(result["eligible_after_resolution_filter"], 0)
        self.assertEqual(result["coverage_after_resolution_filter"], 0)
        self.assertEqual(result["rows"][0]["prediction"], "skipped_low_resolution")


if __name__ == "__main__":
    unittest.main()
