import json
import tempfile
import unittest
from pathlib import Path

from scripts.build_artwork_contact_sheet import collect_previews, infer_geometry_role, preview_name


class ArtworkContactSheetTests(unittest.TestCase):
    def test_preview_name_uses_archive_thumbnail(self):
        self.assertEqual(preview_name("cover.jpg"), "cover_thumb.jpg")
        self.assertEqual(preview_name("cover_thumb.jpg"), "cover_thumb.jpg")

    def test_infers_packaging_candidates_from_scan_geometry(self):
        self.assertEqual(infer_geometry_role(180, 900), ("vhs_spine_candidate", "very_narrow_portrait_scan", "medium"))
        self.assertEqual(infer_geometry_role(600, 900), ("vhs_cover_panel_candidate", "portrait_cover_scan", "medium"))
        self.assertEqual(infer_geometry_role(1600, 900), ("vhs_full_wrap_candidate", "wide_landscape_scan", "medium"))
        self.assertEqual(infer_geometry_role(900, 900), ("generic_packaging_art", "unclassified_geometry", "low"))

    def test_explicit_filename_role_overrides_geometry(self):
        self.assertEqual(infer_geometry_role(1600, 900, "vhs_back_cover"), ("vhs_back_cover", "filename", "high"))

    def test_collects_one_preview_per_archive_image(self):
        item = {
            "identifier": "sample-item",
            "title": "Sample VHS",
            "licenseurl": "https://creativecommons.org/publicdomain/zero/1.0/",
            "vhs_fever_metadata_artwork": {
                "artwork_candidates": [
                    {
                        "archive_identifier": "sample-item",
                        "file_name": "cover.jpg",
                        "asset_url": "https://archive.org/download/sample-item/cover.jpg",
                        "candidate_role": "generic_packaging_art",
                    },
                    {
                        "archive_identifier": "sample-item",
                        "file_name": "cover_thumb.jpg",
                        "asset_url": "https://archive.org/download/sample-item/cover_thumb.jpg",
                        "candidate_role": "generic_packaging_art",
                    },
                    {
                        "archive_identifier": "sample-item",
                        "file_name": "private.jpg",
                        "asset_url": "https://archive.org/download/sample-item/private.jpg",
                        "private": True,
                    },
                ]
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "items.jsonl"
            path.write_text(json.dumps(item) + "\n", encoding="utf-8")
            previews = collect_previews(path, 40)
        self.assertEqual(len(previews), 1)
        self.assertEqual(previews[0]["file_name"], "cover_thumb.jpg")
        self.assertEqual(previews[0]["archive_identifier"], "sample-item")


if __name__ == "__main__":
    unittest.main()
