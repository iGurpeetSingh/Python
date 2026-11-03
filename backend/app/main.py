from contextlib import asynccontextmanager
import os
import re
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .config import FRONTEND_ORIGIN, GURI_CLOUD_MODE
from .core import (
    init_db, save, history, local_answer, open_site, search_web,
    instagram_open, reels_scroll, instagram_reels_scroll, adb,
)
from .browser import open_chatgpt, shutdown_browser
from .device_hub import device_hub
from .intents import parse_chatgpt_command


class Chat(BaseModel):
    message: str = Field(min_length=1, max_length=2000)


class Scroll(BaseModel):
    swipes: int = Field(default=3, ge=1, le=30)


class PairingRequest(BaseModel):
    code: str = Field(min_length=6, max_length=256)


def require_pairing_code(code: str | None) -> None:
    if not GURI_CLOUD_MODE:
        return
    if not device_hub.verify_code(code):
        raise HTTPException(
            status_code=401,
            detail="Guri pairing code गलत है या missing है। Render Environment में GURI_PAIRING_CODE से मिलाएँ।",
        )


def command_target(message: str) -> str:
    low = message.casefold()
    if "instagram" in low or "इंस्टाग्राम" in low or "reels" in low or "रील" in low:
        return "android"
    return "windows"


def is_supported_cloud_command(message: str) -> bool:
    low = message.casefold()
    return (
        parse_chatgpt_command(message) is not None
        or any(word in low for word in (
            "instagram", "इंस्टाग्राम", "reels", "रील",
            "amazon", "अमेज़न", "google", "गूगल",
            "youtube", "यूट्यूब", "browser", "new tab", "न्यू टैब", "नया टैब",
        ))
    )


@asynccontextmanager
async def lifespan(app):
    init_db()
    try:
        yield
    finally:
        await shutdown_browser()


app = FastAPI(title="Guri Voice Assistant Cloud", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    # The Vercel frontend and Render API use different hostnames.
    # Cloud commands still require the private GURI_PAIRING_CODE header.
    allow_origins=["*"],
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)


@app.get("/api/health")
async def health():
    devices = await device_hub.device_names() if GURI_CLOUD_MODE else []
    return {
        "ok": True,
        "agent": "Guri",
        "cloud_mode": GURI_CLOUD_MODE,
        "pairing_required": GURI_CLOUD_MODE,
        "connected_devices": devices,
    }


@app.post("/api/pairing/verify")
async def verify_pairing(body: PairingRequest):
    if not device_hub.verify_code(body.code):
        raise HTTPException(status_code=401, detail="Pairing code गलत है।")
    return {"ok": True, "message": "Guri cloud pairing verified."}


@app.get("/api/devices")
async def list_devices(x_guri_pairing_code: str | None = Header(default=None)):
    require_pairing_code(x_guri_pairing_code)
    names = await device_hub.device_names()
    return {
        "devices": {
            "windows": "online" if "windows" in names else "offline",
            "android": "online" if "android" in names else "offline",
        }
    }


@app.websocket("/ws/device/{device_id}")
async def device_socket(websocket: WebSocket, device_id: str):
    authorization = websocket.headers.get("authorization", "")
    code = authorization.removeprefix("Bearer ").strip()
    await device_hub.connect(device_id, websocket, code)


@app.get("/api/history")
async def get_history():
    return history()


@app.get("/api/mobile/devices")
async def mobile_devices(x_guri_pairing_code: str | None = Header(default=None)):
    if GURI_CLOUD_MODE:
        require_pairing_code(x_guri_pairing_code)
        return await list_devices(x_guri_pairing_code)
    try:
        out = await adb("devices")
        return {
            "devices": [
                line.split()[0]
                for line in out.splitlines()[1:]
                if "\tdevice" in line
            ]
        }
    except HTTPException:
        return {"devices": []}


@app.post("/api/mobile/instagram/open")
async def open_instagram(x_guri_pairing_code: str | None = Header(default=None)):
    if GURI_CLOUD_MODE:
        require_pairing_code(x_guri_pairing_code)
        result = await device_hub.dispatch("android", "Instagram open karo")
        return result
    return {"reply": await instagram_open()}


@app.post("/api/mobile/instagram/reels/scroll")
async def scroll(body: Scroll, x_guri_pairing_code: str | None = Header(default=None)):
    if GURI_CLOUD_MODE:
        require_pairing_code(x_guri_pairing_code)
        return await device_hub.dispatch("android", f"Instagram open karo aur Reels {body.swipes} baar scroll karo")
    return {"reply": await reels_scroll(body.swipes)}


@app.post("/api/chat")
async def chat(
    body: Chat,
    x_guri_pairing_code: str | None = Header(default=None),
):
    msg = body.message.strip()
    low = msg.casefold()

    if GURI_CLOUD_MODE:
        require_pairing_code(x_guri_pairing_code)
        if not is_supported_cloud_command(msg):
            raise HTTPException(
                status_code=422,
                detail="यह command अभी cloud agent में supported नहीं है। Instagram, Reels, browser, Amazon, Google, YouTube और ChatGPT commands उपलब्ध हैं।",
            )
        target = command_target(msg)
        result = await device_hub.dispatch(target, msg)
        return {
            "reply": result.get("reply", "Command device तक पहुँचाई गई।"),
            "action": result.get("action", "device_command"),
            "status": result.get("status", "completed"),
            "device": target,
            "device_result": result,
        }

    save("user", msg)
    try:
        chatgpt_command = parse_chatgpt_command(msg)
        if chatgpt_command is not None:
            result = await open_chatgpt(
                chatgpt_command.message,
                search_first=chatgpt_command.search_first,
            )

        elif (
            ("instagram" in low or "इंस्टाग्राम" in low)
            and any(word in low for word in ["open", "launch", "खोल", "ओपन", "start"])
        ):
            asks_reels = "reel" in low or "रील" in low
            asks_scroll = any(word in low for word in ["scroll", "स्क्रॉल", "स्क्रोल", "अगली"])
            if asks_reels and asks_scroll:
                match = re.search(r"(\d+)", low)
                count = max(1, min(20, int(match.group(1)))) if match else 3
                result = await instagram_reels_scroll(count)
            else:
                result = {
                    "reply": await instagram_open(),
                    "action": "instagram_open",
                    "status": "completed",
                }

        elif any(x in low for x in ["scroll reels", "reels scroll", "रील स्क्रोल", "रील्स स्क्रोल", "रील स्क्रॉल", "रील्स स्क्रॉल"]):
            match = re.search(r"(\d+)", low)
            count = max(1, min(20, int(match.group(1)))) if match else 3
            result = {
                "reply": await reels_scroll(count),
                "action": "reels_scroll",
                "status": "completed",
            }

        elif (
            "amazon" in low or "अमेज़न" in low or "अमेज़ॉन" in low
        ) and any(word in low for word in ["open", "खोल", "ओपन", "start"]):
            result = {
                "reply": await open_site("https://www.amazon.in"),
                "action": "open",
                "status": "completed",
            }

        elif ("google" in low or "गूगल" in low) and any(word in low for word in ["open", "खोल", "ओपन"]):
            result = {
                "reply": await open_site("https://www.google.com"),
                "action": "open",
                "status": "completed",
            }

        elif ("youtube" in low or "यूट्यूब" in low) and any(word in low for word in ["open", "खोल", "ओपन"]):
            result = {
                "reply": await open_site("https://www.youtube.com"),
                "action": "open",
                "status": "completed",
            }

        elif "amazon" in low and any(x in low for x in ["search", "find", "watch", "watches", "घड़ी", "खोज", "सर्च"]):
            match = re.search(r"(?:search|find|खोजो|सर्च करो)\s*(?:for)?\s*(.+)", msg, flags=re.I)
            query = match.group(1).strip() if match else "watches"
            rows = await search_web(query, True)
            result = {
                "reply": "Amazon results sorted by displayed price where available. Verify current price on each product page.",
                "action": "amazon_search",
                "results": rows,
                "status": "completed" if rows else "partial",
            }

        elif any(x in low for x in ["google पर सर्च", "search google for", "गूगल पर खोज"]):
            match = re.search(r"(?:search google for|google पर सर्च करो|गूगल पर खोजो)\s*(.+)", msg, flags=re.I)
            query = match.group(1).strip() if match else msg
            rows = await search_web(query)
            result = {
                "reply": "\n".join(
                    x["title"] + "\n" + x["snippet"] + "\n" + x["url"] for x in rows
                ) or "No readable results.",
                "action": "google_search",
                "results": rows,
                "status": "completed" if rows else "partial",
            }

        else:
            answer = await local_answer(msg)
            result = {
                "reply": answer or "Local AI model is not available. Install Ollama and run: ollama pull qwen2.5:3b",
                "action": "local_ai",
                "status": "completed" if answer else "needs_setup",
            }

    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(500, f"Task failed: {type(exc).__name__}: {exc}") from exc

    save("assistant", result["reply"])
    return result


web_dist = Path(os.getenv(
    "GURI_WEB_DIST",
    str(Path(__file__).resolve().parents[2] / "frontend" / "dist"),
))
if web_dist.exists():
    app.mount("/", StaticFiles(directory=str(web_dist), html=True), name="guri-web")
