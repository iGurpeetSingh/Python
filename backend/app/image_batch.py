"""Sequential ChatGPT image-prompt queue for the local Windows browser runtime."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable

from .browser import send_image_prompt_and_wait


MAX_PROMPTS = 50


class ImageBatchManager:
    """Runs exactly one image-generation request at a time.

    A failed prompt remains selected so Resume retries that prompt instead of
    silently skipping it. Pause takes effect after the current generation ends.
    """

    def __init__(self, runner: Callable[[str], Awaitable[dict[str, Any]]] | None = None):
        self.runner = runner or send_image_prompt_and_wait
        self._task: asyncio.Task | None = None
        self._pause_event = asyncio.Event()
        self._pause_event.set()
        self._cancel_requested = False
        self._prompts: list[str] = []
        self._status: dict[str, Any] = self._empty_status()

    @staticmethod
    def _empty_status() -> dict[str, Any]:
        return {
            "state": "idle",
            "total": 0,
            "completed": 0,
            "current": 0,
            "current_prompt": "",
            "results": [],
            "error": None,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }

    def _touch(self) -> None:
        self._status["updated_at"] = datetime.now(timezone.utc).isoformat()

    def status(self) -> dict[str, Any]:
        return {**self._status, "results": list(self._status["results"])}

    async def start(self, prompts: list[str]) -> dict[str, Any]:
        cleaned = [str(item).strip() for item in prompts if str(item).strip()]
        if not cleaned:
            raise ValueError("कम से कम एक image prompt दें।")
        if len(cleaned) > MAX_PROMPTS:
            raise ValueError(f"एक batch में अधिकतम {MAX_PROMPTS} prompts भेज सकते हैं।")
        if self._task and not self._task.done():
            raise RuntimeError("एक image batch पहले से चल रहा है।")

        self._prompts = cleaned
        self._cancel_requested = False
        self._pause_event.set()
        self._status = {
            **self._empty_status(),
            "state": "running",
            "total": len(cleaned),
            "current": 1,
            "current_prompt": cleaned[0],
        }
        self._touch()
        self._task = asyncio.create_task(self._run())
        return self.status()

    async def _run(self) -> None:
        while self._status["completed"] < len(self._prompts):
            await self._pause_event.wait()
            if self._cancel_requested:
                self._status["state"] = "cancelled"
                self._touch()
                return

            index = self._status["completed"]
            prompt = self._prompts[index]
            self._status.update({
                "state": "running",
                "current": index + 1,
                "current_prompt": prompt,
                "error": None,
            })
            self._touch()
            try:
                result = await self.runner(prompt)
                if result.get("status") != "completed" or not result.get("image_generated", False):
                    self._status["state"] = "failed"
                    self._status["error"] = result.get(
                        "error", "Image generation पूरी होने की पुष्टि नहीं हो सकी।"
                    )
                    self._status["results"].append({
                        "index": index + 1,
                        "status": "failed",
                        "detail": self._status["error"],
                    })
                    self._touch()
                    return
            except asyncio.CancelledError:
                self._status["state"] = "cancelled"
                self._touch()
                raise
            except Exception as exc:
                self._status["state"] = "failed"
                self._status["error"] = f"{type(exc).__name__}: {exc}"
                self._touch()
                return

            self._status["completed"] = index + 1
            self._status["results"].append({
                "index": index + 1,
                "status": "completed",
                "detail": result.get("reply", "Image generated"),
            })
            self._touch()

            if self._cancel_requested:
                self._status["state"] = "cancelled"
                self._touch()
                return
            if not self._pause_event.is_set():
                self._status["state"] = "paused"
                self._touch()
                continue

        self._status["state"] = "completed"
        self._status["current"] = self._status["total"]
        self._status["current_prompt"] = ""
        self._touch()

    async def pause(self) -> dict[str, Any]:
        if self._status["state"] == "running":
            self._pause_event.clear()
            self._status["state"] = "pausing"
            self._touch()
        return self.status()

    async def resume(self) -> dict[str, Any]:
        if self._status["state"] in {"paused", "pausing"}:
            self._pause_event.set()
            if self._task is None or self._task.done():
                self._task = asyncio.create_task(self._run())
            else:
                self._status["state"] = "running"
            self._touch()
        elif self._status["state"] == "failed":
            self._status["error"] = None
            self._pause_event.set()
            self._task = asyncio.create_task(self._run())
            self._touch()
        return self.status()

    async def cancel(self) -> dict[str, Any]:
        if self._status["state"] in {"running", "pausing", "paused", "failed"}:
            self._cancel_requested = True
            self._pause_event.set()
            if self._task and not self._task.done():
                self._task.cancel()
            self._status["state"] = "cancelled"
            self._touch()
        return self.status()


image_batch_manager = ImageBatchManager()
