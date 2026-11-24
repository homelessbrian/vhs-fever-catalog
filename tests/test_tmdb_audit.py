import unittest

from scripts.build_tmdb_audit import build_audit


class TmdbAuditTests(unittest.TestCase):
    def row(self, filename, tmdb_id, title, release_date, search_year=None, search_title=None):
        return {
            "program_id": filename,
            "segments": [{"file_name": filename}],
            "title_match_suggestion": {
                "status": "candidate" if tmdb_id else "not_found_by_title",
                "source_filename": filename,
                "search_title": search_title or title,
                "search_year": search_year,
                "provider_id": str(tmdb_id) if tmdb_id else None,
                "title": title,
                "release_date": release_date,
            },
        }

    def test_flags_letter_suffixed_year_prefix_without_rewriting_filename(self):
        filename = "(1964a) Mothra vs. Godzilla.mp4"
        audit = build_audit([self.row(filename, 1682, "Mothra vs. Godzilla", "1964-04-29")])
        flags = audit["audit_flags"]["malformed_year_prefixes"]
        self.assertEqual(len(flags), 1)
        self.assertEqual(flags[0]["source_filename"], filename)
        self.assertEqual(flags[0]["prefix_year"], "1964")
        self.assertEqual(flags[0]["suffix_letter"], "a")
        self.assertIsNone(flags[0]["search_year"])

    def test_flags_source_and_tmdb_release_year_discrepancy(self):
        filename = "Paw Patrol Meet Everest! (2015).mp4"
        audit = build_audit([self.row(filename, 123, "Meet Everest!", "2016-03-23", "2015")])
        flags = audit["audit_flags"]["source_tmdb_year_discrepancies"]
        self.assertEqual(len(flags), 1)
        self.assertEqual(flags[0]["source_filename"], filename)
        self.assertEqual(flags[0]["source_year"], "2015")
        self.assertEqual(flags[0]["candidate_year"], "2016")

    def test_groups_multiple_distinct_source_files_for_same_tmdb_candidate(self):
        files = ["Farrah's Story (2009).mp4", "Farrah's Story (2009).mpg", "Farrah's Story (2009).mkv"]
        audit = build_audit([self.row(name, 202977, "Farrah's Story", "2009-01-01", "2009") for name in files])
        groups = audit["audit_flags"]["shared_tmdb_candidates"]
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]["candidate_id"], "202977")
        self.assertEqual(groups[0]["source_count"], 3)
        self.assertEqual({x["source_filename"] for x in groups[0]["sources"]}, set(files))

    def test_preserves_suspicious_candidate_for_manual_review_without_auto_approval(self):
        filename = "Pretty Girls 1991.mp4"
        audit = build_audit([self.row(filename, 935127, "Pretty Girls", "1991-01-01", "1991")])
        self.assertEqual(audit["matched_suggestions"][0]["segments"][0]["file_name"], filename)
        self.assertTrue(audit["summary"]["human_approval_required"])
        self.assertIsNone(audit["matched_suggestions"][0].get("matched_title_id"))


if __name__ == "__main__":
    unittest.main()
