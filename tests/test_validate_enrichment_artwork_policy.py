"""Tests for the URL/reference-only artwork storage policy."""
from __future__ import annotations

import unittest

from scripts.validate_enrichment import validate_enrichment_record


def valid_record() -> dict:
    return {
        "schema_version": 1,
        "program_id": "fixture-program",
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
            "assets": [
                {
                    "asset_id": "tmdb-poster-1",
                    "provider": "tmdb",
                    "source_id": "/poster/example.jpg",
                    "edition_id": None,
                    "kind": "tmdb_poster",
                    "source_url": "https://image.tmdb.org/t/p/w500/example.jpg",
                    "region": None,
                    "country": None,
                    "language": None,
                    "width": 500,
                    "height": 750,
                    "attribution": "The Movie Database (TMDB)",
                    "retrieved_at": None,
                    "reuse_policy": "provider_terms",
                    "display_status": "link_only",
                }
            ],
        },
    }


class ArtworkStoragePolicyTests(unittest.TestCase):
    def test_edition_provenance_fields_are_validated(self) -> None:
        record = valid_record()
        record["vhs_sleeve_lookup"].update({
            "status": "found",
            "checked_at": "2026-11-24T12:00:00Z",
            "editions": [{
                "edition_id": "edition-1",
                "provider": "videotapes_io",
                "source_url": "https://videotapes.io/sleeves/edition-1",
                "authenticity": "official",
                "source_id": "edition-1",
                "retrieved_at": "2026-11-24T12:00:00Z",
                "attribution": "Video Tapes dot io",
                "evidence": ["Provider record labels this as an official release"],
            }],
        })
        self.assertEqual(validate_enrichment_record(record), [])

    def test_rejects_invalid_edition_provenance(self) -> None:
        record = valid_record()
        record["vhs_sleeve_lookup"].update({
            "status": "found",
            "checked_at": "2026-11-24T12:00:00Z",
            "editions": [{
                "edition_id": "edition-1",
                "provider": "videotapes_io",
                "source_url": "https://videotapes.io/sleeves/edition-1",
                "authenticity": "official",
                "source_id": " ",
                "retrieved_at": "not-a-date",
                "attribution": " ",
                "evidence": ["", 4],
            }],
        })
        errors = validate_enrichment_record(record)
        self.assertTrue(any("source_id" in error for error in errors))
        self.assertTrue(any("retrieved_at" in error for error in errors))
        self.assertTrue(any("attribution" in error for error in errors))
        self.assertTrue(any("evidence" in error for error in errors))

    def test_source_url_reference_is_valid_without_local_image_file(self) -> None:
        self.assertEqual(validate_enrichment_record(valid_record()), [])

    def test_rejects_local_file_path(self) -> None:
        record = valid_record()
        record["artwork"]["assets"][0]["file_path"] = "artwork/poster.jpg"
        errors = validate_enrichment_record(record)
        self.assertTrue(any("URL/reference-only" in error for error in errors))

    def test_rejects_image_bytes_or_embedded_payload(self) -> None:
        for field in ("image_bytes", "image_data", "base64_data"):
            with self.subTest(field=field):
                record = valid_record()
                record["artwork"]["assets"][0][field] = "not-an-image"
                errors = validate_enrichment_record(record)
                self.assertTrue(any("URL/reference-only" in error for error in errors))

    def test_rejects_cache_and_mirror_storage_fields(self) -> None:
        for field in ("cache_path", "thumbnail_url", "storage_key", "mirror_url"):
            with self.subTest(field=field):
                record = valid_record()
                record["artwork"]["assets"][0][field] = "https://example.com/image"
                errors = validate_enrichment_record(record)
                self.assertTrue(any("URL/reference-only" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
