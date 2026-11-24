import unittest
from urllib.parse import urlparse, parse_qs

from scripts.tmdb_enrichment import (
    TMDBClient,
    build_identity_match,
    normalize_title,
    score_tmdb_candidate,
)


class TMDBTitleMatchingTests(unittest.TestCase):
    def test_normalizes_case_punctuation_and_whitespace(self):
        self.assertEqual(normalize_title("  THE  Thing: Part II! "), "the thing part ii")

    def test_exact_title_and_matching_year_scores_high(self):
        result = score_tmdb_candidate(
            {"title": "The Thing", "year": 1982, "media_type": "movie"},
            {"title": "The Thing", "release_date": "1982-06-25"},
        )
        self.assertEqual(result["confidence"], "high")
        self.assertGreaterEqual(result["score"], 90)
        self.assertIn("exact_title", result["evidence"])
        self.assertIn("year_match", result["evidence"])

    def test_title_match_with_conflicting_year_is_not_high_confidence(self):
        result = score_tmdb_candidate(
            {"title": "The Thing", "year": 1982, "media_type": "movie"},
            {"title": "The Thing", "release_date": "2011-10-14"},
        )
        self.assertNotEqual(result["confidence"], "high")
        self.assertIn("year_conflict", result["evidence"])

    def test_missing_source_year_prevents_high_confidence(self):
        result = score_tmdb_candidate(
            {"title": "The Thing", "year": None, "media_type": "movie"},
            {"title": "The Thing", "release_date": "1982-06-25"},
        )
        self.assertNotEqual(result["confidence"], "high")

    def test_weak_title_similarity_is_low_confidence(self):
        result = score_tmdb_candidate(
            {"title": "The Thing", "year": 1982, "media_type": "movie"},
            {"title": "Thing from Another World", "release_date": "1951-06-01"},
        )
        self.assertEqual(result["confidence"], "low")

    def test_build_identity_match_keeps_candidates_reviewable(self):
        candidates = [{
            "id": 1091,
            "title": "The Thing",
            "release_date": "1982-06-25",
            "media_type": "movie",
        }]
        result = build_identity_match(
            {"title": "The Thing", "year": 1982, "media_type": "movie"},
            candidates,
        )
        self.assertEqual(result["status"], "candidate")
        self.assertIsNone(result["provider_id"])
        self.assertEqual(result["candidates"][0]["provider_id"], "1091")
        self.assertEqual(result["confidence"], "high")

    def test_no_results_remain_unmatched(self):
        result = build_identity_match(
            {"title": "Unreleased Imaginary VHS", "year": 1982, "media_type": "movie"},
            [],
        )
        self.assertEqual(result["status"], "unmatched")
        self.assertEqual(result["confidence"], "none")
        self.assertEqual(result["candidates"], [])

    def test_movie_and_tv_searches_are_both_requested(self):
        requests = []

        def fake_get(url, headers, timeout):
            requests.append((url, headers, timeout))
            if "/search/movie" in url:
                return {"results": [{"id": 1, "title": "Example", "release_date": "1980-01-01"}]}
            return {"results": [{"id": 2, "name": "Example", "first_air_date": "1980-01-01"}]}

        client = TMDBClient("test-token", http_get=fake_get)
        results = client.search_candidates("Example", year=1980)
        self.assertEqual({r["media_type"] for r in results}, {"movie", "tv"})
        self.assertEqual(len(requests), 2)
        self.assertTrue(all(r[1]["Authorization"] == "Bearer test-token" for r in requests))
        self.assertTrue(all(parse_qs(urlparse(r[0]).query)["query"] == ["Example"] for r in requests))

    def test_missing_token_fails_before_network_access(self):
        called = []
        client = TMDBClient(None, http_get=lambda *args: called.append(args))
        with self.assertRaisesRegex(ValueError, "TMDB_BEARER_TOKEN"):
            client.search_candidates("Example")
        self.assertEqual(called, [])

    def test_search_results_are_tagged_with_their_media_type(self):
        def fake_get(url, headers, timeout):
            if "/search/movie" in url:
                return {"results": [{"id": 1, "title": "Example"}]}
            return {"results": [{"id": 2, "name": "Example"}]}

        results = TMDBClient("token", http_get=fake_get).search_candidates("Example")
        by_id = {item["id"]: item["media_type"] for item in results}
        self.assertEqual(by_id, {1: "movie", 2: "tv"})

    def test_media_type_conflict_is_low_confidence(self):
        result = score_tmdb_candidate(
            {"title": "Example", "year": 1980, "media_type": "movie"},
            {"id": 9, "name": "Example", "first_air_date": "1980-01-01", "media_type": "tv"},
        )
        self.assertEqual(result["confidence"], "low")
        self.assertIn("media_type_conflict", result["evidence"])

    def test_known_media_type_searches_only_that_endpoint(self):
        requests = []
        def fake_get(url, headers, timeout):
            requests.append(url)
            return {"results": []}
        TMDBClient("token", http_get=fake_get).search_candidates("Example", media_type="movie")
        self.assertEqual(len(requests), 1)
        self.assertIn("/search/movie", requests[0])

    def test_candidate_output_is_sorted_by_score(self):
        candidates = [
            {"id": 2, "title": "The Thing", "release_date": "2011-01-01", "media_type": "movie"},
            {"id": 1, "title": "The Thing", "release_date": "1982-01-01", "media_type": "movie"},
        ]
        result = build_identity_match(
            {"title": "The Thing", "year": 1982, "media_type": "movie"},
            candidates,
        )
        self.assertEqual(result["candidates"][0]["provider_id"], "1")


if __name__ == "__main__":
    unittest.main()
