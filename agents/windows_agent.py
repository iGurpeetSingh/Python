import asyncio
import json
import os
import re
import sys
from pathlib import Path
from urllib.parse import quote_plus, urlparse

import websockets

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.app.browser import open_chatgpt, shutdown_browser
from backend.app.core import open_site
from backend.app.intents import parse_chatgpt_command
from backend.app.image_batch import image_batch_manager


def websocket_url(base_url: str) -> str:
    parsed = urlparse(base_url.strip())
    if not parsed.hostname:
        raise ValueError("Set GURI_CLOUD_URL to your deployed HTTPS address.")
    scheme = "wss" if parsed.scheme == "https" else "ws"
    return f"{scheme}://{parsed.netloc}/ws/device/windows"


async def execute_command(command: str) -> dict:
    value = (command or "").strip()
    low = value.casefold()

    chatgpt = parse_chatgpt_command(value)
    if chatgpt is not None:
        return await open_chatgpt(chatgpt.message, search_first=chatgpt.search_first)

    if "amazon" in low or "अमेज़न" in low:
        if any(word in low for word in ("search", "find", "सर्च", "खोज")):
            query = re.sub(r".*?(?:search|find|सर्च करो|खोजो)\s*(?:for)?\s*", "", value, flags=re.I)
            if not query.strip():
                query = "popular products"
            url = "https://www.amazon.in/s?k=" + quote_plus(query)
        else:
            url = "https://www.amazon.in"
        reply = await open_site(url)
        return {"reply": reply, "action": "amazon", "status": "completed"}

    if "google" in low or "गूगल" in low:
        query_match = re.search(r"(?:search google for|google पर सर्च करो|गूगल पर खोजो)\s+(.+)", value, flags=re.I)
        url = (
            "https://www.google.com/search?q=" + quote_plus(query_match.group(1))
            if query_match
            else "https://www.google.com"
        )
        reply = await open_site(url)
        return {"reply": reply, "action": "google", "status": "completed"}

    if "youtube" in low or "यूट्यूब" in low:
        reply = await open_site("https://www.youtube.com")
        return {"reply": reply, "action": "youtube", "status": "completed"}

    if any(word in low for word in ("new tab", "नया टैब", "न्यू टैब", "browser", "chrome")):
        reply = await open_site("https://www.google.com")
        return {"reply": reply, "action": "new_tab", "status": "completed"}

    return {
        "reply": "यह Windows command अभी supported नहीं है। ChatGPT, Amazon, Google और YouTube commands उपलब्ध हैं।",
        "action": "unsupported",
        "status": "unsupported",
    }


async def publish_batch_status(websocket):
    terminal = {"completed", "failed", "cancelled", "paused", "idle"}
    while True:
        status = image_batch_manager.status()
        try:
            await websocket.send(json.dumps({
                "type": "agent_status",
                "status": status,
            }, ensure_ascii=False))
        except Exception:
            return
        if status.get("state") in terminal:
            return
        await asyncio.sleep(1)


async def handle_image_batch_command(command: str, websocket):
    prefix = "__GURI_IMAGE_BATCH__:"
    try:
        payload = json.loads(command[len(prefix):])
        action = payload.get("action")
        if action == "start":
            status = await image_batch_manager.start(payload.get("prompts") or [])
            asyncio.create_task(publish_batch_status(websocket))
            return {
                "reply": f"Image batch started: {status['total']} prompts queued.",
                "action": "image_batch_start",
                "status": "started",
            }
        if action == "pause":
            status = await image_batch_manager.pause()
            return {"reply": "Image batch pause requested.", "action": "image_batch_pause", "status": status["state"]}
        if action == "resume":
            status = await image_batch_manager.resume()
            asyncio.create_task(publish_batch_status(websocket))
            return {"reply": "Image batch resumed.", "action": "image_batch_resume", "status": status["state"]}
        if action == "cancel":
            status = await image_batch_manager.cancel()
            return {"reply": "Image batch cancelled.", "action": "image_batch_cancel", "status": status["state"]}
        return {"reply": "Unknown image batch action.", "action": "image_batch", "status": "failed"}
    except Exception as exc:
        return {
            "reply": f"Image batch command failed: {type(exc).__name__}: {exc}",
            "action": "image_batch",
            "status": "failed",
        }


async def heartbeat(websocket):
    while True:
        await asyncio.sleep(20)
        await websocket.send(json.dumps({"type": "heartbeat"}))


async def run():
    cloud_url = os.getenv("GURI_CLOUD_URL", "").strip()
    token = os.getenv("GURI_DEVICE_TOKEN", "").strip()
    if not cloud_url or not token:
        raise SystemExit("Set GURI_CLOUD_URL and GURI_DEVICE_TOKEN before starting the Windows agent.")

    uri = websocket_url(cloud_url)
    headers = {"Authorization": f"Bearer {token}"}

    print("Guri Windows agent starting...")
    print(f"Cloud: {cloud_url}")
    print("Device ID: windows")

    delay = 2
    while True:
        try:
            async with websockets.connect(
                uri,
                additional_headers=headers,
                ping_interval=20,
                ping_timeout=25,
                close_timeout=5,
                max_size=1_000_000,
            ) as websocket:
                print("Connected to Guri Cloud. This computer can receive voice commands.")
                delay = 2
                keepalive = asyncio.create_task(heartbeat(websocket))
                try:
                    async for raw in websocket:
                        try:
                            packet = json.loads(raw)
                        except json.JSONDecodeError:
                            continue
                        if packet.get("type") != "command":
                            continue

                        request_id = str(packet.get("id") or "")
                        command = str(packet.get("command") or "")
                        print(f"Command: {command}")
                        try:
                            if command.startswith("__GURI_IMAGE_BATCH__:"):
                                result = await handle_image_batch_command(command, websocket)
                            else:
                                result = await execute_command(command)
                        except Exception as exc:
                            result = {
                                "reply": f"Windows action failed: {type(exc).__name__}: {exc}",
                                "action": "error",
                                "status": "failed",
                            }
                        await websocket.send(json.dumps({
                            "type": "result",
                            "id": request_id,
                            "result": result,
                        }, ensure_ascii=False))
                        print(result.get("reply", result.get("status", "Done")))
                finally:
                    keepalive.cancel()
        except KeyboardInterrupt:
            break
        except Exception as exc:
            print(f"Cloud connection lost: {type(exc).__name__}: {exc}")
            print(f"Retrying in {delay} seconds...")
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30)

    await shutdown_browser()


if __name__ == "__main__":
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass
