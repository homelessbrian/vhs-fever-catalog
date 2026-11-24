import unittest

from scripts.enrich_program_catalog import build_enrichment_records


class FakeTMDBClient:
    def __init__(self, responses=None):
        self.responses = responses or {}
        self.calls = []

    def search_candidates(self, title, *, year=None, media_type=None):
        self.calls.append((title, year, media_type))
        return self.responses.get((title, year), [])


class TMDBCatalogEnrichmentTests(unittest.TestCase):
    def source(self, identifier, title, year=1982, classification="movie_or_film"):
        return {
            "identifier": identifier,
            "title": title,
            "year": year,
            "date": f"{year}-01-01T00:00:00Z" if year else None,
            "vhs_fever_classification": {"label": classification},
        }

    def program(self, program_id, archive_id, title, classification="movie_or_film", entry_type="standalone"):
        return {
            "program_id": program_id,
            "title": title,
            "display_title": title,
            "entry_type": entry_type,
            "source_classification": classification,
            "segments": [{
                "archive_identifier": archive_id,
                "file_name": "program.mp4",
                "role": "main_program",
                "order": 1,
            }],
        }

    def test_joins_standalone_program_to_original_archive_title_and_year(self):
        client = FakeTMDBClient({("The Thing", 1982): []})
        programs = [self.program("program-1", "archive-1", "Filename-derived title")]
        sources = [self.source("archive-1", "The Thing", 1982)]
        records, summary = build_enrichment_records(programs, sources, client)
        self.assertEqual(client.calls, [("The Thing", 1982, None)])
        self.assertEqual(records[0]["program_id"], "program-1")
        self.assertEqual(records[0]["identity_match"]["status"], "unmatched")
        self.assertEqual(records[0]["vhs_sleeve_lookup"]["status"], "not_checked")
        self.assertEqual(summary["processed_programs"], 1)

    def test_skips_non_movie_classifications_and_assembled_candidates(self):
        client = FakeTMDBClient()
        programs = [
            self.program("tv-1", "archive-tv", "TV Recording", classification="tv_episode_or_recording"),
            self.program("assembly-1", "archive-movie", "Movie", entry_type="assembled_candidate"),
        ]
        sources = [
            self.source("archive-tv", "TV Recording", classification="tv_episode_or_recording"),
            self.source("archive-movie", "Movie"),
        ]
        records, summary = build_enrichment_records(programs, sources, client)
        self.assertEqual(records, [])
        self.assertEqual(client.calls, [])
        self.assertEqual(summary["skipped_classification"], 1)
        self.assertEqual(summary["skipped_non_standalone"], 1)

    def test_caches_searches_for_multiple_program_files_from_same_archive(self):
        client = FakeTMDBClient({("The Thing", 1982): []})
        programs = [
            self.program("program-1", "archive-1", "Part One"),
            self.program("program-2", "archive-1", "Part Two"),
        ]
        sources = [self.source("archive-1", "The Thing", 1982)]
        records, summary = build_enrichment_records(programs, sources, client)
        self.assertEqual(len(records), 2)
        self.assertEqual([r["program_id"] for r in records], ["program-1", "program-2"])
        self.assertEqual(len(client.calls), 1)
        self.assertEqual(summary["unique_tmdb_searches"], 1)

    def test_limit_caps_number_of_programs_processed(self):
        client = FakeTMDBClient()
        programs = [
            self.program(f"program-{i}", f"archive-{i}", f"Movie {i}")
            for i in range(3)
        ]
        sources = [self.source(f"archive-{i}", f"Movie {i}") for i in range(3)]
        records, summary = build_enrichment_records(programs, sources, client, limit=2)
        self.assertEqual(len(records), 2)
        self.assertEqual(summary["eligible_programs"], 3)
        self.assertEqual(summary["processed_programs"], 2)

    def test_skips_programs_without_a_source_archive_record(self):
        client = FakeTMDBClient()
        records, summary = build_enrichment_records(
            [self.program("program-1", "missing-archive", "Unknown")],
            [],
            client,
        )
        self.assertEqual(records, [])
        self.assertEqual(summary["skipped_missing_source"], 1)
        self.assertEqual(client.calls, [])

    def test_candidate_search_result_is_written_as_reviewable_sidecar(self):
        client = FakeTMDBClient({
            ("The Thing", 1982): [{
                "id": 1091,
                "title": "The Thing",
                "release_date": "1982-06-25",
                "media_type": "movie",
            }]
        })
        records, _ = build_enrichment_records(
            [self.program("program-1", "archive-1", "The Thing")],
            [self.source("archive-1", "The Thing", 1982)],
            client,
        )
        identity = records[0]["identity_match"]
        self.assertEqual(identity["status"], "candidate")
        self.assertIsNone(identity["provider_id"])
        self.assertEqual(identity["candidates"][0]["provider_id"], "1091")

    def test_invalid_limit_is_rejected(self):
        with self.assertRaises(ValueError):
            build_enrichment_records([], [], FakeTMDBClient(), limit=0)


if __name__ == "__main__":
    unittest.main()
