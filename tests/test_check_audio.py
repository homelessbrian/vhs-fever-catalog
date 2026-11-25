import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import check_audio as c  # noqa: E402


def recording(rid, sources):
    return {"recording_id": rid, "segments": [{"sources": [
        {"source_id": sid, "playback_url": f"https://archive.org/download/x/{sid}", "original_name": sid,
         "role": role, "duration_seconds": 600} for sid, role in sources]}]}


class SoundCheck(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self._probe, self._loud, self._direct = c.probe, c.loudest_db, c.direct_url
        c.direct_url = lambda url, timeout=30: url
        c.probe = lambda url, timeout=60: {"streams": [{"codec_type": "audio", "codec_name": "aac"},
                                                       {"codec_type": "video", "codec_name": "h264"}]}

        def loud(url, start, timeout=60):
            self.calls.append(url)
            return -9.0
        c.loudest_db = loud

    def tearDown(self):
        c.probe, c.loudest_db, c.direct_url = self._probe, self._loud, self._direct

    def test_file_and_its_copy_share_a_shard_and_the_copy_levels(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "recordings.jsonl"
            p.write_text(json.dumps(recording("r1", [("a.mpg", "original"), ("a.ia.mp4", "archive_derivative")])) + "\n")
            files = c.files_from_recordings(p)
            self.assertEqual(files[0]["copy_id"], "a.ia.mp4")
            self.assertIsNone(files[1]["copy_id"])
            self.assertEqual(len({c.shard_of(f["recording_id"], 10) for f in files}), 1)
            copy = c.check(files[1])
            orig = c.check(files[0], copy)
        self.assertEqual(orig["verdict"], "ok")
        self.assertEqual(orig["levels_from_copy"], "a.ia.mp4")
        self.assertEqual(orig["sample_max_db"], copy["sample_max_db"])
        self.assertEqual(len(self.calls), 2)   # only the copy was measured

    def test_silent_copy_stays_silent(self):
        silent = {"file_id": "a.ia.mp4", "has_audio_track": True, "verdict": "silent_audio", "sample_max_db": [None, None]}
        r = c.check({"file_id": "a.mpg", "url": "u", "name": "a.mpg", "duration": 600}, silent)
        self.assertEqual(r["verdict"], "silent_audio")


if __name__ == "__main__":
    unittest.main()
