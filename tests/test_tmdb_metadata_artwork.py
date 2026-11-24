import unittest
from urllib.parse import parse_qs, urlparse

from scripts.tmdb_enrichment import (
    TMDBClient,
    build_tmdb_artwork_assets,
    build_tmdb_metadata,
)


def image_configuration():
    return {
        "images": {
            "secure_base_url": "https://image.tmdb.org/t/p/",
            "poster_sizes": ["w185", "w500", "original"],
            "backdrop_sizes": ["w300", "w1280", "original"],
            "logo_sizes": ["w45", "w500", "original"],
        }
    }


def movie_details():
    return {
        "id": 1091,
        "title": "The Thing",
        "original_title": "The Thing",
        "release_date": "1982-06-25",
        "runtime": 109,
        "overview": "A research team encounters a shape-shifting alien.",
        "original_language": "en",
        "genres": [{"id": 27, "name": "Horror"}],
        "external_ids": {"imdb_id": "tt0084787"},
        "poster_path": "/poster.jpg",
        "backdrop_path": "/backdrop.jpg",
        "images": {
            "posters": [
                {"file_path": "/poster.jpg", "width": 1000, "height": 1500, "iso_639_1": "en", "vote_average": 6.0, "vote_count": 20},
                {"file_path": "/poster-alt.jpg", "width": 800, "height": 1200, "iso_639_1": None, "vote_average": 5.0, "vote_count": 5},
            ],
            "backdrops": [
                {"file_path": "/backdrop.jpg", "width": 1920, "height": 1080, "iso_639_1": None, "vote_average": 6.5, "vote_count": 30},
            ],
            "logos": [
                {"file_path": "/logo.svg", "width": 800, "height": 300, "iso_639_1": "en", "vote_average": 7.0, "vote_count": 10},
            ],
        },
    }


class TMDBMetadataArtworkTests(unittest.TestCase):
    def test_movie_details_append_external_ids_and_images_in_one_request(self):
        requests = []
        def fake_get(url, headers, timeout):
            requests.append(url)
            return movie_details()
        result = TMDBClient("token", http_get=fake_get).get_title_details("movie", 1091)
        self.assertEqual(result["id"], 1091)
        self.assertEqual(len(requests), 1)
        parsed = urlparse(requests[0])
        self.assertEqual(parsed.path, "/3/movie/1091")
        query = parse_qs(parsed.query)
        self.assertEqual(query["append_to_response"], ["external_ids,images"])
        self.assertEqual(query["include_image_language"], ["en,null"])

    def test_tv_details_use_tv_endpoint(self):
        requests = []
        def fake_get(url, headers, timeout):
            requests.append(url)
            return {"id": 1396, "name": "Breaking Bad"}
        TMDBClient("token", http_get=fake_get).get_title_details("tv", 1396)
        self.assertIn("/3/tv/1396", requests[0])

    def test_rejects_invalid_media_type_or_identifier_before_request(self):
        calls = []
        client = TMDBClient("token", http_get=lambda *args: calls.append(args) or {})
        with self.assertRaises(ValueError):
            client.get_title_details("person", 1)
        with self.assertRaises(ValueError):
            client.get_title_details("movie", "not-a-number")
        self.assertEqual(calls, [])

    def test_metadata_normalizes_movie_and_tv_fields(self):
        movie = build_tmdb_metadata(movie_details(), "movie")
        self.assertEqual(movie["provider_id"], "1091")
        self.assertEqual(movie["title"], "The Thing")
        self.assertEqual(movie["year"], 1982)
        self.assertEqual(movie["runtime_minutes"], 109)
        self.assertEqual(movie["external_ids"]["imdb_id"], "tt0084787")
        tv = build_tmdb_metadata(
            {"id": 1396, "name": "Breaking Bad", "first_air_date": "2008-01-20", "episode_run_time": [47, 48]},
            "tv",
        )
        self.assertEqual(tv["year"], 2008)
        self.assertEqual(tv["runtime_minutes"], 47)

    def test_artwork_builds_poster_backdrop_and_logo_urls(self):
        assets = build_tmdb_artwork_assets(movie_details(), image_configuration())
        self.assertEqual({a["kind"] for a in assets}, {"tmdb_poster", "tmdb_backdrop", "tmdb_logo"})
        by_kind = {}
        for asset in assets:
            by_kind.setdefault(asset["kind"], asset)
        self.assertEqual(by_kind["tmdb_poster"]["source_url"], "https://image.tmdb.org/t/p/w500/poster.jpg")
        self.assertEqual(by_kind["tmdb_poster"]["width"], 1000)
        self.assertEqual(by_kind["tmdb_backdrop"]["source_url"], "https://image.tmdb.org/t/p/w1280/backdrop.jpg")
        self.assertEqual(by_kind["tmdb_logo"]["source_url"], "https://image.tmdb.org/t/p/original/logo.svg")

    def test_artwork_remains_pending_review_and_has_attribution(self):
        assets = build_tmdb_artwork_assets(movie_details(), image_configuration())
        self.assertTrue(all(a["display_status"] == "pending_review" for a in assets))
        self.assertTrue(all(a["reuse_policy"] == "provider_terms" for a in assets))
        self.assertTrue(all(a["attribution"] == "TMDB" for a in assets))

    def test_artwork_deduplicates_primary_paths_against_image_lists(self):
        assets = build_tmdb_artwork_assets(movie_details(), image_configuration())
        posters = [a for a in assets if a["kind"] == "tmdb_poster"]
        backdrops = [a for a in assets if a["kind"] == "tmdb_backdrop"]
        self.assertEqual(len(posters), 2)
        self.assertEqual(len(backdrops), 1)

    def test_missing_image_configuration_returns_no_artwork(self):
        self.assertEqual(build_tmdb_artwork_assets(movie_details(), {}), [])

    def test_configuration_is_cached_per_client(self):
        calls = []
        def fake_get(url, headers, timeout):
            calls.append(url)
            return image_configuration()
        client = TMDBClient("token", http_get=fake_get)
        self.assertEqual(client.get_configuration(), image_configuration())
        self.assertEqual(client.get_configuration(), image_configuration())
        self.assertEqual(len(calls), 1)

    def test_rejects_unsupported_display_status(self):
        with self.assertRaises(ValueError):
            build_tmdb_artwork_assets(movie_details(), image_configuration(), display_status="approved_by_default")

    def test_image_urls_reject_untrusted_or_invalid_paths(self):
        details = movie_details()
        details["poster_path"] = "https://evil.example/poster.jpg"
        assets = build_tmdb_artwork_assets(details, image_configuration())
        self.assertFalse(any(a["source_url"].startswith("https://evil.example") for a in assets))
        details["poster_path"] = "/../evil.jpg"
        assets = build_tmdb_artwork_assets(details, image_configuration())
        self.assertFalse(any("/../" in a["source_url"] for a in assets))


if __name__ == "__main__":
    unittest.main()
