import unittest

from scripts.classify_catalog import classify


class CatalogClassificationTests(unittest.TestCase):
    def test_year_prefixed_godzilla_is_classified_as_film(self):
        result = classify({"mediatype": "movies", "title": "(1954) Godzilla"})
        self.assertEqual(result[0], "movie_or_film")
        self.assertEqual(result[1], "medium")
        self.assertEqual(result[2], "title_release_year_prefix")
        self.assertEqual(result[3], "movie_or_film")

    def test_personal_archive_recordings_stay_out_of_film_classification(self):
        result = classify({"mediatype": "movies",
                           "title": "(1982) Home Movies - Donated by Jane"})
        self.assertEqual(result[0], "other_or_review")

    def test_year_prefixed_promo_is_still_classified_as_promo(self):
        result = classify({"mediatype": "movies", "title": "(1990) Godzilla TV Commercial"})
        self.assertEqual(result[0], "promo_or_commercial")


if __name__ == "__main__":
    unittest.main()
