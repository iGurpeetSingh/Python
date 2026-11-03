import unittest

from app.intents import parse_chatgpt_command


class ChatGPTIntentTests(unittest.TestCase):
    def test_hindi_new_tab_and_message(self):
        command = parse_chatgpt_command(
            "New Tab में ChatGPT को ओपन करो and उसको मैसेज करो कि क्या काम कर रहे हो, बस."
        )
        self.assertIsNotNone(command)
        self.assertEqual(command.message, "क्या काम कर रहे हो")
        self.assertFalse(command.search_first)

    def test_hindi_new_tab_search_and_hi_message(self):
        command = parse_chatgpt_command(
            "New Tab open karo aur usme search ChatGPT aur usko message Hi"
        )
        self.assertIsNotNone(command)
        self.assertEqual(command.message, "Hi")
        self.assertTrue(command.search_first)

    def test_devanagari_search_message(self):
        command = parse_chatgpt_command(
            "न्यू टैब खोलो और ChatGPT सर्च करो और उसको मैसेज करो कि Hi"
        )
        self.assertIsNotNone(command)
        self.assertEqual(command.message, "Hi")
        self.assertTrue(command.search_first)

    def test_hindi_open_only(self):
        command = parse_chatgpt_command("New Tab में ChatGPT खोलो")
        self.assertIsNotNone(command)
        self.assertIsNone(command.message)
        self.assertFalse(command.search_first)

    def test_english_message(self):
        command = parse_chatgpt_command(
            "Open ChatGPT in a new tab and message it saying what are you working on"
        )
        self.assertIsNotNone(command)
        self.assertEqual(command.message, "what are you working on")

    def test_search_chatgpt_without_message(self):
        command = parse_chatgpt_command("New tab open and search ChatGPT")
        self.assertIsNotNone(command)
        self.assertIsNone(command.message)
        self.assertTrue(command.search_first)

    def test_unrelated_command_is_ignored(self):
        self.assertIsNone(parse_chatgpt_command("Open Instagram and scroll Reels"))


if __name__ == "__main__":
    unittest.main()
