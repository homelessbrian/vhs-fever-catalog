import unittest
from scripts.build_program_candidates import build_candidates

class ProgramCandidateTests(unittest.TestCase):
    def record(self, identifier, title, files):
        return {
            "identifier": identifier,
            "title": title,
            "vhs_fever_file_inventory": {"inventory_status": "ok", "video_files": files},
        }

    def test_groups_distinct_title_roles_across_archive_items(self):
        records = [
            self.record("opening-id", "Opening to The Great Cartoon Show", [
                {"name": "opening.mp4", "duration_seconds": 45, "format": "MPEG4"}
            ]),
            self.record("feature-id", "The Great Cartoon Show", [
                {"name": "feature.mp4", "duration_seconds": 1320, "format": "MPEG4"}
            ]),
            self.record("bonus-id", "The Great Cartoon Show Bonus Cartoon", [
                {"name": "cartoon.mp4", "duration_seconds": 720, "format": "MPEG4"}
            ]),
            self.record("closing-id", "Closing to The Great Cartoon Show", [
                {"name": "closing.mp4", "duration_seconds": 60, "format": "MPEG4"}
            ]),
        ]
        candidates = build_candidates(records)
        self.assertEqual(len(candidates), 1)
        candidate = candidates[0]
        self.assertEqual(candidate["assembly_status"], "suggested")
        self.assertEqual([s["role"] for s in candidate["segments"]],
                         ["opening", "main_program", "bonus", "closing"])
        self.assertEqual(candidate["total_known_duration_seconds"], 2145)
        self.assertEqual(len({s["archive_identifier"] for s in candidate["segments"]}), 4)

    def test_numeric_prefixes_and_same_archive_segments(self):
        item = self.record("fanmade-vhs", "Dora Halloween Fanmade VHS", [
            {"name": "01 Opening.mp4", "duration_seconds": 45, "format": "MPEG4", "origin_hint": "original"},
            {"name": "02 Dora Halloween.mp4", "duration_seconds": 1320, "format": "MPEG4", "origin_hint": "original"},
            {"name": "03 Bonus Cartoon.mp4", "duration_seconds": 720, "format": "MPEG4", "origin_hint": "original"},
            {"name": "04 Closing.mp4", "duration_seconds": 60, "format": "MPEG4", "origin_hint": "original"},
        ])
        candidates = build_candidates([item])
        self.assertEqual(len(candidates), 1)
        self.assertEqual([s["role"] for s in candidates[0]["segments"]],
                         ["opening", "main_program", "bonus_cartoon", "closing"])
        self.assertEqual({s["archive_identifier"] for s in candidates[0]["segments"]},
                         {"fanmade-vhs"})

    def test_unrelated_or_single_role_records_are_not_joined(self):
        records = [
            self.record("a", "Opening to One Show", [{"name": "open.mp4", "duration_seconds": 30, "format": "MPEG4"}]),
            self.record("b", "Opening to Another Show", [{"name": "open.mp4", "duration_seconds": 30, "format": "MPEG4"}]),
        ]
        self.assertEqual(build_candidates(records), [])

    def test_unknown_duration_makes_total_incomplete(self):
        records = [
            self.record("a", "Opening to Test Program", [{"name": "open.mp4", "duration_seconds": 45, "format": "MPEG4"}]),
            self.record("b", "Closing to Test Program", [{"name": "end.mp4", "duration_seconds": None, "format": "MPEG4"}]),
        ]
        candidate = build_candidates(records)[0]
        self.assertIsNone(candidate["total_known_duration_seconds"])
        self.assertFalse(candidate["duration_complete"])

if __name__ == "__main__":
    unittest.main()
