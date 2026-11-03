import unittest

from app.intents import parse_chatgpt_command


class ChatGPTIntentTests(unittest.TestCase):
    def test_hindi_new_tab_and_message(self):
        command = parse_chatgpt_command(
            "New Tab में ChatGPT को ओपन करो and उसको मैसेज करो कि क्या काम कर रहे हो, बस."
        )
        self.assertIsNotNone(command)
        self.assertEqual(command.message, "क्या काम कर रहे हो")

    def test_hindi_open_only(self):
        command = parse_chatgpt_command("New Tab में ChatGPT खोलो")
        self.assertIsNotNone(command)
        self.assertIsNone(command.message)

    def test_english_message(self):
        command = parse_chatgpt_command(
            "Open ChatGPT in a new tab and message it saying what are you working on"
        )
        self.assertIsNotNone(command)
        self.assertEqual(command.message, "what are you working on")

    def test_unrelated_command_is_ignored(self):
        self.assertIsNone(parse_chatgpt_command("Open Instagram and scroll Reels"))


if __name__ == "__main__":
    unittest.main()
