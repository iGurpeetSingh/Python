import unittest

from fastapi import HTTPException

from app.device_hub import DeviceHub
from app.main import command_target, is_supported_cloud_command
import app.device_hub as hub_module


class CloudRoutingTests(unittest.TestCase):
    def test_instagram_routes_to_android(self):
        self.assertEqual(
            command_target("Instagram open karo aur Reels scroll karo"),
            "android",
        )

    def test_reels_routes_to_android(self):
        self.assertEqual(command_target("रील्स scroll करो"), "android")

    def test_chatgpt_routes_to_windows(self):
        self.assertEqual(
            command_target("New Tab खोलो ChatGPT search करो और उसको message Hi"),
            "windows",
        )

    def test_browser_commands_are_supported(self):
        self.assertTrue(is_supported_cloud_command("Open Amazon"))
        self.assertTrue(is_supported_cloud_command("Open ChatGPT and message Hi"))

    def test_unimplemented_general_chat_is_not_sent_to_device(self):
        self.assertFalse(is_supported_cloud_command("What is the meaning of life?"))


class DevicePairingTests(unittest.TestCase):
    def test_pairing_code_is_compared(self):
        original = hub_module.GURI_PAIRING_CODE
        try:
            hub_module.GURI_PAIRING_CODE = "correct-pairing-code"
            hub = DeviceHub()
            self.assertTrue(hub.verify_code("correct-pairing-code"))
            self.assertFalse(hub.verify_code("wrong-code"))
            self.assertFalse(hub.verify_code(None))
        finally:
            hub_module.GURI_PAIRING_CODE = original

    def test_dispatch_fails_fast_when_device_is_offline(self):
        async def scenario():
            hub = DeviceHub()
            with self.assertRaises(HTTPException) as caught:
                await hub.dispatch("windows", "Open ChatGPT")
            self.assertEqual(caught.exception.status_code, 409)

        import asyncio
        asyncio.run(scenario())


if __name__ == "__main__":
    unittest.main()
