import unittest

from scripts.validate_program_manifests import validate_program, validate_programs


def valid_program():
    return {
        "schema_version": 1,
        "program_id": "vhsf-program-example",
        "title": "Example Program",
        "assembly_status": "suggested",
        "match_confidence": "medium",
        "playback_mode": "sequential",
        "segments": [
            {"segment_id": "segment-001", "order": 1, "role": "opening",
             "archive_identifier": "example-item", "file_name": "opening.mp4", "duration_seconds": 30},
            {"segment_id": "segment-002", "order": 2, "role": "main_program",
             "archive_identifier": "example-item", "file_name": "feature.mp4", "duration_seconds": 120},
        ],
        "total_known_duration_seconds": 150,
        "duration_complete": True,
        "evidence": ["segment_role_in_filename:opening"],
        "review_notes": ["Review before confirmation."],
    }


class ProgramManifestValidationTests(unittest.TestCase):
    def test_accepts_valid_sequential_program(self):
        self.assertEqual(validate_program(valid_program()), [])

    def test_rejects_missing_source_reference(self):
        program = valid_program()
        program["segments"][0]["archive_identifier"] = ""
        self.assertTrue(any("archive_identifier" in error for error in validate_program(program)))

    def test_rejects_duplicate_segment_ids_and_orders(self):
        program = valid_program()
        program["segments"][1]["segment_id"] = "segment-001"
        program["segments"][1]["order"] = 1
        errors = validate_program(program)
        self.assertTrue(any("segment_id" in error for error in errors))
        self.assertTrue(any("order" in error for error in errors))

    def test_rejects_duplicate_source_file_within_program(self):
        program = valid_program()
        program["segments"][1]["file_name"] = "opening.mp4"
        self.assertTrue(any("duplicate source segment" in error for error in validate_program(program)))

    def test_requires_unknown_duration_to_make_total_incomplete(self):
        program = valid_program()
        program["segments"][1]["duration_seconds"] = None
        program["total_known_duration_seconds"] = 150
        program["duration_complete"] = True
        self.assertTrue(any("duration" in error for error in validate_program(program)))

    def test_checks_duration_sum(self):
        program = valid_program()
        program["total_known_duration_seconds"] = 149
        self.assertTrue(any("duration total" in error for error in validate_program(program)))

    def test_single_playback_requires_exactly_one_segment(self):
        program = valid_program()
        program["playback_mode"] = "single"
        self.assertTrue(any("single" in error and "segment" in error for error in validate_program(program)))

    def test_rejects_invalid_duration_values(self):
        program = valid_program()
        program["segments"][0]["duration_seconds"] = -1
        self.assertTrue(any("duration_seconds" in error for error in validate_program(program)))

    def test_detects_duplicate_program_ids_in_catalog(self):
        self.assertTrue(any("duplicate program_id" in error for error in validate_programs([valid_program(), valid_program()])))

    def test_rejects_unexpected_object_types_without_crashing(self):
        program = valid_program()
        program["assembly_status"] = {"unexpected": "object"}
        program["playback_mode"] = []
        errors = validate_program(program)
        self.assertTrue(any("assembly_status" in error for error in errors))
        self.assertTrue(any("playback_mode" in error for error in errors))

    def test_rejects_non_finite_duration(self):
        program = valid_program()
        program["segments"][0]["duration_seconds"] = float("inf")
        self.assertTrue(any("duration_seconds" in error for error in validate_program(program)))


if __name__ == "__main__":
    unittest.main()
