import unittest
from scripts.classify_artwork_ai import decide_two_stage, _contextual_prompt
from scripts.evaluate_artwork_ai import evaluate

class ArtworkAITwoStageTests(unittest.TestCase):
    def test_packaging_runs_component_stage(self):
        result=decide_two_stage([{"label":"vhs_packaging","score":.72},{"label":"video_frame_or_screenshot","score":.08}],[{"label":"spine","score":.71},{"label":"front","score":.1}])
        self.assertEqual(result["stage_a"]["label"],"vhs_packaging"); self.assertEqual(result["suggested_role"],"spine"); self.assertFalse(result["auto_approved"])
    def test_screenshot_skips_component_stage(self):
        result=decide_two_stage([{"label":"video_frame_or_screenshot","score":.72},{"label":"vhs_packaging","score":.08}],None)
        self.assertIsNone(result["stage_b"]); self.assertFalse(result["is_vhs_packaging_candidate"])
    def test_ambiguous_stage_a_is_uncertain(self):
        result=decide_two_stage([{"label":"vhs_packaging","score":.32},{"label":"poster_or_promotional_art","score":.31}],None)
        self.assertEqual(result["final_decision"],"uncertain")
    def test_context_contains_title_and_original_filename(self):
        prompt=_contextual_prompt("physical VHS sleeve","Thomas VHS cover","cover 1.jpg")
        self.assertIn("Thomas VHS cover",prompt); self.assertIn("cover 1.jpg",prompt)
    def test_evaluator_counts_frame_filter_as_negative(self):
        labels=[{"archive_identifier":"a","source_file_name":"frame.jpg","ground_truth":"negative"}]
        manifest={"frame_filter":{"excluded_items":[{"archive_identifier":"a","files":[{"file_name":"frame.jpg"}]}]},"items":[]}
        result=evaluate(manifest,labels); self.assertEqual(result["counts"]["tn"],1); self.assertTrue(result["rows"][0]["excluded_by_frame_filter"])
    def test_evaluator_counts_abstentions(self):
        labels=[{"archive_identifier":"a","source_file_name":"x.jpg","ground_truth":"positive"}]
        manifest={"items":[{"archive_identifier":"a","source_file_name":"x.jpg","ai_visual_classification":{"stage_a":{"label":"uncertain"}}}]}
        result=evaluate(manifest,labels); self.assertEqual(result["counts"]["abstain_positive"],1); self.assertEqual(result["coverage"],0)

if __name__=="__main__": unittest.main()
