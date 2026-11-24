import unittest
from scripts.build_artwork_contact_sheet import is_thumbnail_derivative, frame_filter_decision

class ArtworkFrameFilterTests(unittest.TestCase):
    def test_archive_thumbs_directory_is_excluded(self):
        self.assertTrue(is_thumbnail_derivative("item.thumbs/frame_000001.jpg",{"source_hint":"original"}))
    def test_derivative_and_thumb_suffix_are_excluded(self):
        self.assertTrue(is_thumbnail_derivative("cover.jpg",{"source_hint":"derivative"})); self.assertTrue(is_thumbnail_derivative("cover_thumb.jpg",{"source_hint":"original"}))
    def test_explicit_video_frame_metadata_excludes_images(self):
        item={"title":"Home movie with video frames","vhs_fever_file_inventory":{"has_video_candidate":True}}
        safe,filtered=frame_filter_decision(item,[{"file_name":"scan (1).jpg"}]); self.assertEqual(safe,[]); self.assertEqual(filtered[0]["reason"],"item_metadata_identifies_video_frames")
    def test_numbered_packaging_scan_is_retained_for_review(self):
        item={"title":"Thomas VHS cover","vhs_fever_file_inventory":{"has_video_candidate":True}}
        safe,filtered=frame_filter_decision(item,[{"file_name":"cover_0001.jpg"}]); self.assertEqual(len(safe),1); self.assertEqual(safe[0]["frame_filter_status"],"ambiguous_numbered_image_kept_for_review"); self.assertEqual(filtered,[])

if __name__=="__main__": unittest.main()
