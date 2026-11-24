import copy
import unittest

from scripts.validate_enrichment import validate_enrichment_record


def valid_record():
    return {
        "schema_version": 1,
        "program_id": "vhsf-program-example",
        "identity_match": {
            "status": "unmatched",
            "provider": None,
            "provider_id": None,
            "media_type": None,
            "title": None,
            "year": None,
            "confidence": "none",
            "evidence": [],
            "candidates": [],
            "external_ids": {},
        },
        "vhs_sleeve_lookup": {
            "status": "not_checked",
            "provider": "videotapes_io",
            "checked_at": None,
            "editions": [],
        },
        "artwork": {
            "signature_asset_id": None,
            "assets": [],
        },
    }


def confirmed_record_with_regional_editions():
    record = valid_record()
    record["identity_match"] = {
        "status": "confirmed",
        "provider": "tmdb",
        "provider_id": "12345",
        "media_type": "movie",
        "title": "Example Film",
        "year": 1987,
        "confidence": "high",
        "evidence": ["exact_title", "year_match", "media_type_match"],
        "candidates": [],
        "external_ids": {"imdb_id": "tt1234567"},
    }
    record["vhs_sleeve_lookup"] = {
        "status": "found",
        "provider": "videotapes_io",
        "checked_at": "2026-11-24T12:00:00Z",
        "editions": [
            {
                "edition_id": "edition-us-1",
                "provider": "videotapes_io",
                "source_url": "https://videotapes.io/sleeves/edition-us-1",
                "region": "US",
                "country": "United States",
                "language": "en",
                "release_year": 1988,
                "authenticity": "official",
            },
            {
                "edition_id": "edition-uk-1",
                "provider": "videotapes_io",
                "source_url": "https://videotapes.io/sleeves/edition-uk-1",
                "region": "UK",
                "country": "United Kingdom",
                "language": "en",
                "release_year": 1989,
                "authenticity": "official",
            },
        ],
    }
    record["artwork"] = {
        "signature_asset_id": "tmdb-poster-1",
        "assets": [
            {
                "asset_id": "vhs-us-spread",
                "provider": "videotapes_io",
                "edition_id": "edition-us-1",
                "kind": "vhs_sleeve_spread",
                "source_url": "https://videotapes.io/images/us-spread.jpg",
                "region": "US",
                "country": "United States",
                "language": "en",
                "width": 2400,
                "height": 1600,
                "reuse_policy": "link_only",
                "display_status": "link_only",
                "attribution": "Video Tapes dot io; artwork belongs to its rights holder",
            },
            {
                "asset_id": "vhs-uk-front",
                "provider": "videotapes_io",
                "edition_id": "edition-uk-1",
                "kind": "vhs_front",
                "source_url": "https://videotapes.io/images/uk-front.jpg",
                "region": "UK",
                "country": "United Kingdom",
                "language": "en",
                "reuse_policy": "link_only",
                "display_status": "link_only",
            },
            {
                "asset_id": "tmdb-poster-1",
                "provider": "tmdb",
                "edition_id": None,
                "kind": "tmdb_poster",
                "source_url": "https://image.tmdb.org/t/p/original/poster.jpg",
                "width": 1000,
                "height": 1500,
                "reuse_policy": "provider_terms",
                "display_status": "approved",
                "attribution": "TMDB",
            },
        ],
    }
    return record


class EnrichmentValidationTests(unittest.TestCase):
    def test_accepts_a_minimal_unmatched_record(self):
        self.assertEqual(validate_enrichment_record(valid_record()), [])

    def test_accepts_tmdb_match_and_preserves_multiple_regional_editions(self):
        record = confirmed_record_with_regional_editions()
        self.assertEqual(validate_enrichment_record(record), [])
        editions = record["vhs_sleeve_lookup"]["editions"]
        self.assertEqual([edition["region"] for edition in editions], ["US", "UK"])

    def test_rejects_unsupported_schema_version(self):
        record = valid_record()
        record["schema_version"] = 2
        self.assertTrue(any("schema_version" in error for error in validate_enrichment_record(record)))

    def test_confirmed_identity_requires_a_tmdb_identifier(self):
        record = valid_record()
        record["identity_match"].update({
            "status": "confirmed",
            "provider": "tmdb",
            "provider_id": None,
            "media_type": "movie",
            "title": "Example Film",
            "confidence": "high",
        })
        self.assertTrue(any("provider_id" in error for error in validate_enrichment_record(record)))

    def test_candidate_identity_requires_candidate_evidence(self):
        record = valid_record()
        record["identity_match"]["status"] = "candidate"
        self.assertTrue(any("candidates" in error for error in validate_enrichment_record(record)))

    def test_rejects_imdb_as_a_primary_provider(self):
        record = valid_record()
        record["identity_match"].update({
            "status": "confirmed",
            "provider": "imdb",
            "provider_id": "tt1234567",
            "media_type": "movie",
            "title": "Example Film",
            "confidence": "high",
        })
        self.assertTrue(any("provider" in error for error in validate_enrichment_record(record)))

    def test_rejects_duplicate_asset_ids(self):
        record = confirmed_record_with_regional_editions()
        record["artwork"]["assets"].append(copy.deepcopy(record["artwork"]["assets"][0]))
        self.assertTrue(any("asset_id" in error for error in validate_enrichment_record(record)))

    def test_signature_asset_must_exist_and_be_approved_for_display(self):
        record = confirmed_record_with_regional_editions()
        record["artwork"]["signature_asset_id"] = "missing"
        self.assertTrue(any("signature_asset_id" in error for error in validate_enrichment_record(record)))
        record["artwork"]["signature_asset_id"] = "vhs-us-spread"
        self.assertTrue(any("display" in error for error in validate_enrichment_record(record)))

    def test_vhs_asset_must_reference_a_known_edition(self):
        record = confirmed_record_with_regional_editions()
        record["artwork"]["assets"][0]["edition_id"] = "unknown-edition"
        self.assertTrue(any("edition_id" in error for error in validate_enrichment_record(record)))

    def test_rejects_unsafe_or_malformed_artwork_urls(self):
        record = confirmed_record_with_regional_editions()
        record["artwork"]["assets"][0]["source_url"] = "javascript:alert(1)"
        self.assertTrue(any("source_url" in error for error in validate_enrichment_record(record)))

    def test_rejects_invalid_artwork_kind_and_dimensions(self):
        record = confirmed_record_with_regional_editions()
        record["artwork"]["assets"][0]["kind"] = "fake_vhs_front"
        record["artwork"]["assets"][0]["width"] = -1
        errors = validate_enrichment_record(record)
        self.assertTrue(any("kind" in error for error in errors))
        self.assertTrue(any("width" in error for error in errors))

    def test_found_sleeve_lookup_requires_at_least_one_edition(self):
        record = valid_record()
        record["vhs_sleeve_lookup"]["status"] = "found"
        record["vhs_sleeve_lookup"]["checked_at"] = "2026-11-24T12:00:00Z"
        self.assertTrue(any("editions" in error for error in validate_enrichment_record(record)))

    def test_rejects_duplicate_edition_ids(self):
        record = confirmed_record_with_regional_editions()
        record["vhs_sleeve_lookup"]["editions"][1]["edition_id"] = "edition-us-1"
        self.assertTrue(any("edition_id" in error for error in validate_enrichment_record(record)))

    def test_rejects_non_object_records_without_crashing(self):
        self.assertTrue(validate_enrichment_record([]))


if __name__ == "__main__":
    unittest.main()
