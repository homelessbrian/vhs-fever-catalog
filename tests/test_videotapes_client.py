import io
import json
import unittest
import urllib.error

from scripts.videotapes_client import (
    VideoTapesAPIError,
    VideoTapesClient,
    image_reference,
)


class FakeResponse:
    def __init__(self, value):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return json.dumps(self.value).encode("utf-8")


class VideoTapesClientTests(unittest.TestCase):
    def test_film_lookup_uses_bearer_token_and_expected_path(self):
        seen = []

        def opener(request, timeout):
            seen.append(request)
            return FakeResponse({"film": {"tmdb_id": 603}})

        client = VideoTapesClient("secret", opener=opener)
        result = client.get_film(603)
        self.assertEqual(result["film"]["tmdb_id"], 603)
        self.assertEqual(seen[0].full_url, "https://videotapes.io/api/v1/films/603")
        self.assertEqual(seen[0].get_header("Authorization"), "Bearer secret")

    def test_batch_rejects_more_than_500_ids(self):
        client = VideoTapesClient("secret", opener=lambda *_args, **_kwargs: None)
        with self.assertRaises(ValueError):
            client.lookup_preferred_batch(list(range(501)))

    def test_batch_posts_tmdb_ids(self):
        seen = []

        def opener(request, timeout):
            seen.append(json.loads(request.data.decode("utf-8")))
            return FakeResponse({"results": []})

        client = VideoTapesClient("secret", opener=opener)
        client.lookup_preferred_batch([603, 604])
        self.assertEqual(seen, [{"tmdbIds": [603, 604]}])

    def test_429_retries_using_retry_after(self):
        calls, delays = [], []

        def opener(request, timeout):
            calls.append(request)
            if len(calls) == 1:
                raise urllib.error.HTTPError(
                    request.full_url, 429, "rate limited", {"Retry-After": "3"}, io.BytesIO()
                )
            return FakeResponse({"ok": True})

        client = VideoTapesClient("secret", opener=opener, sleep=delays.append)
        self.assertEqual(client.get_film(1), {"ok": True})
        self.assertEqual(delays, [3.0])
        self.assertEqual(len(calls), 2)

    def test_withdrawn_sleeve_returns_withdrawn_status(self):
        def opener(request, timeout):
            raise urllib.error.HTTPError(
                request.full_url, 410, "gone", {}, io.BytesIO()
            )

        client = VideoTapesClient("secret", opener=opener)
        self.assertEqual(client.get_sleeve("abc"), {"status": "withdrawn", "http_status": 410})

    def test_image_reference_supports_panels_without_fetching(self):
        self.assertEqual(
            image_reference("abc", "spine", 800),
            "https://videotapes.io/api/v1/sleeves/abc/image?part=spine&w=800",
        )

    def test_invalid_image_part_is_rejected(self):
        with self.assertRaises(ValueError):
            image_reference("abc", "cassette")


if __name__ == "__main__":
    unittest.main()
