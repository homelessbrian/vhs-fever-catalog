import unittest

from scripts.build_title_groups import build_title_groups, normalize_title, runtime_compatible


def source(program_id, archive_id, duration=7200, title="Godzilla (1954)", tmdb_id="1678"):
    return {
        "program_id": program_id,
        "source_id": program_id,
        "entry_type": "standalone",
        "display_title": title,
        "total_known_duration_seconds": duration,
        "segments": [{"archive_identifier": archive_id, "file_name": "feature.mp4"}],
        "title_match_suggestion": {
            "status": "candidate", "provider": "tmdb", "provider_id": tmdb_id,
            "title": "Godzilla", "review_status": "needs_review"
        }
    }


class TitleGroupTests(unittest.TestCase):
    def test_normalizes_year_and_punctuation(self):
        self.assertEqual(normalize_title("Godzilla (1954): VHS!"), "godzilla vhs")

    def test_groups_distinct_archive_sources_with_same_identity_and_runtime(self):
        groups = build_title_groups([
            source("p1", "archive-one", 7200),
            source("p2", "archive-two", 7500),
        ])
        self.assertEqual(len(groups), 1)
        group = groups[0]
        self.assertEqual(group["status"], "suggested")
        self.assertTrue(group["review_required"])
        self.assertEqual(group["source_count"], 2)
        self.assertEqual(group["provider_id"], "1678")
        self.assertEqual(len(group["sources"]), 2)

    def test_does_not_group_different_tmdb_candidates(self):
        groups = build_title_groups([
            source("p1", "archive-one", tmdb_id="1"),
            source("p2", "archive-two", tmdb_id="2"),
        ])
        self.assertEqual(groups, [])

    def test_does_not_group_same_archive_item(self):
        groups = build_title_groups([
            source("p1", "archive-one"),
            source("p2", "archive-one"),
        ])
        self.assertEqual(groups, [])

    def test_does_not_group_multiple_files_from_one_archive_item(self):
        first = source("p1", "archive-one")
        second = source("p2", "archive-one")
        third = source("p3", "archive-two")
        groups = build_title_groups([first, second, third])
        self.assertEqual(groups, [])

    def test_does_not_group_incompatible_runtimes(self):
        groups = build_title_groups([
            source("p1", "archive-one", 7200),
            source("p2", "archive-two", 1800),
        ])
        self.assertEqual(groups, [])

    def test_requires_known_runtime(self):
        groups = build_title_groups([
            source("p1", "archive-one", None),
            source("p2", "archive-two", 7200),
        ])
        self.assertEqual(groups, [])

    def test_does_not_group_assembled_candidates(self):
        first = source("p1", "archive-one")
        second = source("p2", "archive-two")
        first["entry_type"] = "assembled_candidate"
        groups = build_title_groups([first, second])
        self.assertEqual(groups, [])


if __name__ == "__main__":
    unittest.main()
