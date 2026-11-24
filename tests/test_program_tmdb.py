import unittest
from scripts.enrich_program_tmdb import filename_title, enrich_programs
from unittest.mock import patch

class ProgramTmdbTests(unittest.TestCase):
    def test_extracts_year_and_title_from_filename(self):
        p={"segments":[{"file_name":"(1954) Godzilla.mp4"}]}
        self.assertEqual(filename_title(p),("Godzilla","1954"))
    def test_uses_each_video_filename_not_parent_archive_title(self):
        rows=[{"entry_type":"standalone","title":"(1954) Godzilla","display_title":"BigGMovies","segments":[{"file_name":"(1954) Godzilla.mp4"}],"program_id":"1"}]
        with patch("scripts.enrich_program_tmdb.find_tmdb_match",return_value={"tmdb_id":1678,"title":"Godzilla","match_method":"high_similarity_title_search"}) as lookup:
            result,counts=enrich_programs(rows,"token",0)
        self.assertEqual(lookup.call_args.args[0]["title"],"Godzilla")
        self.assertEqual(lookup.call_args.args[0]["year"],"1954")
        self.assertEqual(result[0]["title_match_suggestion"]["provider_id"],"1678")
        self.assertIsNone(result[0]["matched_title_id"])
        self.assertTrue(result[0]["review_required"])
    def test_caches_duplicate_filename_queries(self):
        rows=[{"entry_type":"standalone","segments":[{"file_name":"(1954) Godzilla.mp4"}]},{"entry_type":"standalone","segments":[{"file_name":"(1954) Godzilla.mp4"}]}]
        with patch("scripts.enrich_program_tmdb.find_tmdb_match",return_value={"tmdb_id":1678}) as lookup:
            enrich_programs(rows,"token",0)
        self.assertEqual(lookup.call_count,1)
    def test_does_not_lookup_assembled_candidates(self):
        rows=[{"entry_type":"assembled_candidate","title":"Godzilla"}]
        with patch("scripts.enrich_program_tmdb.find_tmdb_match") as lookup:
            result,_=enrich_programs(rows,"token",0)
        lookup.assert_not_called()
        self.assertNotIn("title_match_suggestion",result[0])

if __name__=="__main__": unittest.main()
