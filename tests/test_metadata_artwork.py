import unittest
from scripts.enrich_metadata_artwork import (
    artwork_role, build_artwork_candidates, enrich_record,
    normalize_title, tmdb_image_url, tmdb_search_title, find_tmdb_match,
)


class MetadataArtworkTests(unittest.TestCase):
    def test_detects_front_back_and_spine_separately(self):
        item = {"identifier": "vhs-one", "vhs_fever_file_inventory": {"image_files": [
            {"name": "front_cover.jpg", "size_bytes": 100},
            {"name": "back-cover-scan.png", "size_bytes": 200},
            {"name": "spine.tif", "size_bytes": 300},
        ]}}
        found = {x["candidate_role"] for x in build_artwork_candidates(item)}
        self.assertEqual(found, {"vhs_front_cover", "vhs_back_cover", "vhs_spine"})

    def test_ignores_private_and_uninformative_images(self):
        item = {"identifier": "x", "vhs_fever_file_inventory": {"image_files": [
            {"name": "private_front_cover.jpg", "private": True},
            {"name": "random-photo.jpg"},
        ]}}
        self.assertEqual(build_artwork_candidates(item), [])

    def test_ignores_video_thumbnail_frames_even_when_filename_says_back(self):
        item = {"identifier": "x", "vhs_fever_file_inventory": {"image_files": [
            {"name": "movie.thumbs/scene_is_back_000001.jpg"},
            {"name": "movie.thumbs/scene_is_back_000057.jpg"},
            {"name": "actual_back_cover.jpg"},
        ]}}
        candidates = build_artwork_candidates(item)
        self.assertEqual([x["file_name"] for x in candidates], ["actual_back_cover.jpg"])
        self.assertEqual(candidates[0]["candidate_role"], "vhs_back_cover")

    def test_detects_pdf_packaging_scans(self):
        item = {"identifier": "x", "vhs_fever_file_inventory": {"artwork_document_files": [
            {"name": "full_wrap_insert.pdf"}
        ]}}
        candidates = build_artwork_candidates(item)
        self.assertEqual(candidates[0]["file_extension"], ".pdf")
        self.assertEqual(candidates[0]["candidate_role"], "vhs_full_wrap")

    def test_tmdb_images_require_absolute_file_paths(self):
        self.assertEqual(tmdb_image_url("/poster.jpg"), "https://image.tmdb.org/t/p/w500/poster.jpg")
        self.assertIsNone(tmdb_image_url("https://bad.example/poster.jpg"))

    def test_non_movie_metadata_is_not_sent_to_tmdb(self):
        item = {"identifier": "promo", "title": "Movie Promo",
                "vhs_fever_classification": {"label": "promo_or_commercial"}}
        result = enrich_record(item, token=None)
        self.assertEqual(result["vhs_fever_metadata_artwork"]["tmdb"]["status"],
                         "not_applicable_to_classification")

    def test_normalizes_titles_for_reviewable_exact_match(self):
        self.assertEqual(normalize_title("The Movie: Part II"), "movie part ii")

    def test_cleans_vhs_suffixes_before_tmdb_search(self):
        item = {"title": "The Care Bears: The Land Without Feelings (Animated)"}
        self.assertEqual(tmdb_search_title(item), ("The Care Bears: The Land Without Feelings", "cleaned_title"))

    def test_strips_vhs_variant_and_year_suffix(self):
        item = {"title": "Disney Sing-Along Songs: Very Merry Christmas Songs (1988) US VHS Variants"}
        title, status = tmdb_search_title(item)
        self.assertEqual(title, "Disney Sing-Along Songs: Very Merry Christmas Songs")
        self.assertEqual(status, "cleaned_title")

    def test_skips_home_movie_archive_records(self):
        item = {"identifier": "home-movie", "title": "Manitoba Home Movie Archives - Donated by Cheryl Besser"}
        title, status = tmdb_search_title(item)
        self.assertIsNone(title)
        self.assertEqual(status, "likely_archive_recording_or_compilation")

    def test_strips_year_before_vhs_variant_suffix(self):
        title, status = tmdb_search_title({"title": "Disney Sing-Along Songs: The Twelve Days of Christmas (1993) VHS Variants"})
        self.assertEqual(title, "Disney Sing-Along Songs: The Twelve Days of Christmas")
        self.assertEqual(status, "cleaned_title")

    def test_tmdb_high_similarity_title_is_reviewable_match(self):
        import unittest.mock as mock
        item = {"title": "The Care Bears: The Land Without Feelings",
                "vhs_fever_classification": {"label": "movie_or_film"}}
        payload = {"results": [{"id": 123, "title": "The Care Bears in the Land Without Feelings",
                                "release_date": "1983-01-01"}]}
        with mock.patch("scripts.enrich_metadata_artwork.request_json", return_value=payload):
            result = find_tmdb_match(item, "token")
        self.assertEqual(result["tmdb_id"], 123)
        self.assertEqual(result["match_method"], "high_similarity_title_search")
        self.assertEqual(result["review_status"], "needs_review")

    def test_skips_capture_and_tv_spot_titles(self):
        for title in ("All Animated VHS and DVD Capture (Hauppauge Capture)",
                      "1990s Movie TV Spots - The New York TV Archive Part 1 (2019)"):
            self.assertIsNone(tmdb_search_title({"title": title})[0])


if __name__ == "__main__":
    unittest.main()
