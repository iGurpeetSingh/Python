from contextlib import asynccontextmanager
import re

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .config import FRONTEND_ORIGIN
from .core import (
    init_db, save, history, local_answer, open_site, search_web,
    instagram_open, reels_scroll, adb,
)
from .browser import open_chatgpt
from .intents import parse_chatgpt_command


class Chat(BaseModel):
    message: str = Field(min_length=1, max_length=2000)


class Scroll(BaseModel):
    swipes: int = Field(default=3, ge=1, le=30)


@asynccontextmanager
async def lifespan(app):
    init_db()
    yield


app = FastAPI(title="Orbit Personal Agent", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[FRONTEND_ORIGIN, "http://127.0.0.1:5173"],
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


@app.get("/api/health")
async def health():
    return {"ok": True, "agent": "Orbit"}


@app.get("/api/history")
async def get_history():
    return history()


@app.get("/api/mobile/devices")
async def mobile_devices():
    try:
        out = await adb("devices")
        return {
            "devices": [
                line.split()[0]
                for line in out.splitlines()[1:]
                if "\\tdevice" in line
            ]
        }
    except HTTPException:
        return {"devices": []}


@app.post("/api/mobile/instagram/open")
async def open_instagram():
    return {"reply": await instagram_open()}


@app.post("/api/mobile/instagram/reels/scroll")
async def scroll(body: Scroll):
    return {"reply": await reels_scroll(body.swipes)}


@app.post("/api/chat")
async def chat(body: Chat):
    msg = body.message.strip()
    save("user", msg)
    low = msg.casefold()

    try:
        chatgpt_command = parse_chatgpt_command(msg)
        if chatgpt_command is not None:
            result = await open_chatgpt(chatgpt_command.message)

        elif any(x in low for x in ["open instagram", "instagram kholo", "इंस्टाग्राम खोल", "इंस्टाग्राम ओपन"]):
            result = {"reply": await instagram_open(), "action": "instagram_open", "status": "completed"}

        elif any(x in low for x in ["scroll reels", "reels scroll", "रील स्क्रॉल", "रील्स स्क्रॉल"]):
            match = re.search(r"(\\d+)", low)
            count = max(1, min(30, int(match.group(1)))) if match else 3
            result = {"reply": await reels_scroll(count), "action": "reels_scroll", "status": "completed"}

        elif any(x in low for x in ["open amazon", "amazon kholo", "अमेज़न खोल"]):
            result = {"reply": await open_site("https://www.amazon.in"), "action": "open", "status": "completed"}

        elif any(x in low for x in ["open google", "google kholo", "गूगल खोल"]):
            result = {"reply": await open_site("https://www.google.com"), "action": "open", "status": "completed"}

        elif any(x in low for x in ["open youtube", "youtube kholo", "यूट्यूब खोल"]):
            result = {"reply": await open_site("https://www.youtube.com"), "action": "open", "status": "completed"}

        elif "amazon" in low and any(x in low for x in ["search", "find", "watch", "watches", "घड़ी", "खोज", "सर्च"]):
            match = re.search(r"(?:search|find|खोजो|सर्च करो)\\s*(?:for)?\\s*(.+)", msg, flags=re.I)
            query = match.group(1).strip() if match else "watches"
            rows = await search_web(query, True)
            result = {
                "reply": "Amazon results sorted by displayed price where available. Verify current price on each product page.",
                "action": "amazon_search",
                "results": rows,
                "status": "completed" if rows else "partial",
            }

        elif any(x in low for x in ["google पर सर्च", "search google for", "गूगल पर खोज"]):
            match = re.search(r"(?:search google for|google पर सर्च करो|गूगल पर खोजो)\\s*(.+)", msg, flags=re.I)
            query = match.group(1).strip() if match else msg
            rows = await search_web(query)
            result = {
                "reply": "\\n".join(
                    x["title"] + "\\n" + x["snippet"] + "\\n" + x["url"] for x in rows
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
