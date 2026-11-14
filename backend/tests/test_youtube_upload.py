import unittest

from app.youtube_upload import _requested_duration


class YouTubeDurationTests(unittest.TestCase):
    def test_numeric_hindi_minutes_and_seconds(self):
        self.assertEqual(_requested_duration("7 मिनट 12 सेकंड"), 432)

    def test_spoken_hindi_minutes(self):
        self.assertEqual(_requested_duration("सात मिनट कुछ सेकंड"), 420)

    def test_english_duration(self):
        self.assertEqual(_requested_duration("upload my 7 minutes 8 seconds video"), 428)

    def test_duration_not_provided(self):
        self.assertIsNone(_requested_duration("upload my latest video"))


if __name__ == "__main__":
    unittest.main()
