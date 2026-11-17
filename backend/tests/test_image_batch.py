import asyncio
import unittest

from app.image_batch import ImageBatchManager


class ImageBatchManagerTests(unittest.IsolatedAsyncioTestCase):
    async def test_runs_prompts_sequentially(self):
        calls = []

        async def runner(prompt):
            calls.append(prompt)
            await asyncio.sleep(0.01)
            return {"status": "completed", "image_generated": True, "reply": "generated"}

        manager = ImageBatchManager(runner=runner)
        await manager.start(["one", "two", "three"])
        await manager._task

        status = manager.status()
        self.assertEqual(calls, ["one", "two", "three"])
        self.assertEqual(status["state"], "completed")
        self.assertEqual(status["completed"], 3)
        self.assertEqual(status["total"], 3)

    async def test_failed_prompt_can_be_retried_without_skipping(self):
        calls = []
        fail_once = True

        async def runner(prompt):
            nonlocal fail_once
            calls.append(prompt)
            if fail_once:
                fail_once = False
                return {"status": "failed", "image_generated": False, "error": "temporary"}
            return {"status": "completed", "image_generated": True, "reply": "generated"}

        manager = ImageBatchManager(runner=runner)
        await manager.start(["first", "second"])
        await manager._task
        self.assertEqual(manager.status()["state"], "failed")
        self.assertEqual(manager.status()["completed"], 0)

        await manager.resume()
        await manager._task
        self.assertEqual(calls, ["first", "first", "second"])
        self.assertEqual(manager.status()["state"], "completed")
        self.assertEqual(manager.status()["completed"], 2)

    async def test_rejects_more_than_fifty_prompts(self):
        manager = ImageBatchManager(runner=lambda prompt: asyncio.sleep(0))
        with self.assertRaises(ValueError):
            await manager.start([f"prompt {i}" for i in range(51)])


if __name__ == "__main__":
    unittest.main()
