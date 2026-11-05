import asyncio
import re
import shutil
import sqlite3
import webbrowser
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from urllib.parse import quote_plus

import httpx
from fastapi import HTTPException
from playwright.async_api import async_playwright

from .config import DB_PATH, OLLAMA_BASE_URL, OLLAMA_MODEL, ADB_PATH


def init_db():
    with sqlite3.connect(DB_PATH) as db:
        db.execute(
            "CREATE TABLE IF NOT EXISTS messages("
            "id INTEGER PRIMARY KEY, role TEXT, content TEXT, created_at TEXT)"
        )


def save(role, content):
    with sqlite3.connect(DB_PATH) as db:
        db.execute(
            "INSERT INTO messages(role,content,created_at) VALUES(?,?,?)",
            (role, content, datetime.now(timezone.utc).isoformat()),
        )


def history():
    with sqlite3.connect(DB_PATH) as db:
        rows = db.execute(
            "SELECT id,role,content,created_at FROM messages ORDER BY id DESC LIMIT 60"
        ).fetchall()
    return [
        dict(id=x[0], role=x[1], content=x[2], created_at=x[3])
        for x in reversed(rows)
    ]


async def local_answer(prompt):
    try:
        async with httpx.AsyncClient(timeout=100) as client:
            response = await client.post(
                OLLAMA_BASE_URL + "/api/chat",
                json={
                    "model": OLLAMA_MODEL,
                    "stream": False,
                    "messages": [
                        {
                            "role": "system",
                            "content": (
                                "You are a careful personal assistant. Reply in the user's "
                                "language. Never claim an action succeeded unless a tool confirmed it."
                            ),
                        },
                        {"role": "user", "content": prompt},
                    ],
                },
            )
            response.raise_for_status()
            return response.json().get("message", {}).get("content")
    except Exception:
        return None


async def open_site(url):
    opened = webbrowser.open_new_tab(url)
    if not opened:
        raise HTTPException(500, "Could not open the default browser on this computer.")
    return f"Sent open request for {url} to the computer's default browser."


async def search_web(query, amazon=False):
    url = (
        "https://www.amazon.in/s?k=" if amazon else "https://www.google.com/search?q="
    ) + quote_plus(query)
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True)
        page = await browser.new_page(locale="en-IN")
        try:
            await page.goto(url, wait_until="domcontentloaded", timeout=30000)
            await page.wait_for_timeout(1000)
            if amazon:
                rows = await page.locator(
                    '[data-component-type="s-search-result"]'
                ).evaluate_all(
                    """els => els.slice(0,25).map(el=>({
                        title:el.querySelector('h2 span')?.innerText?.trim()||'',
                        price:el.querySelector('.a-price .a-offscreen')?.innerText||'',
                        url:el.querySelector('h2 a')?.href||''
                    })).filter(x=>x.title)"""
                )

                def price(item):
                    match = re.search(
                        r"\d+(?:\.\d+)?",
                        (item.get("price") or "").replace(",", "").replace("₹", ""),
                    )
                    return float(match.group()) if match else float("inf")

                return sorted(rows, key=price)[:12]
            return await page.locator("div.MjjYud").evaluate_all(
                """els=>els.slice(0,8).map(el=>({
                    title:el.querySelector('h3')?.innerText||'',
                    url:el.querySelector('a')?.href||'',
                    snippet:(el.innerText||'').slice(0,300)
                })).filter(x=>x.title&&x.url.startsWith('http'))"""
            )
        finally:
            await browser.close()


def adb_path():
    path = shutil.which(ADB_PATH)
    if not path:
        raise HTTPException(
            503,
            "ADB not installed or not in PATH. Install Android Platform Tools.",
        )
    return path


async def adb(*args):
    process = await asyncio.create_subprocess_exec(
        adb_path(),
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    out, err = await process.communicate()
    if process.returncode:
        raise HTTPException(
            503,
            err.decode(errors="replace").strip() or "ADB command failed",
        )
    return out.decode(errors="replace").strip()


async def capture_phone_screen():
    """Capture one phone screenshot in memory; never persist or upload it."""
    process = await asyncio.create_subprocess_exec(
        adb_path(),
        "exec-out", "screencap", "-p",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    image, error = await process.communicate()
    if process.returncode != 0 or not image.startswith(b"\\x89PNG\\r\\n\\x1a\\n"):
        detail = error.decode(errors="replace").strip()
        raise HTTPException(503, detail or "Could not capture the Android screen.")
    return {"bytes": len(image), "png": True}


async def instagram_is_foreground():
    output = await adb("shell", "dumpsys", "activity", "activities")
    return "com.instagram.android" in output and (
        "mResumedActivity" in output or "topResumedActivity" in output
    )


async def phone():
    output = await adb("devices")
    devices = [
        line.split()[0]
        for line in output.splitlines()[1:]
        if "\tdevice" in line
    ]
    if not devices:
        raise HTTPException(
            409,
            "No authorized Android phone. Enable USB debugging and approve this computer.",
        )
    return devices[0]


async def instagram_open():
    await phone()
    await adb(
        "shell", "monkey", "-p", "com.instagram.android",
        "-c", "android.intent.category.LAUNCHER", "1",
    )
    for _ in range(12):
        await asyncio.sleep(0.5)
        if await instagram_is_foreground():
            await capture_phone_screen()
            return "Instagram opened on the connected phone; screen capture verified."
    raise HTTPException(504, "Instagram launch was sent, but Android did not report Instagram in the foreground.")


async def _tap_instagram_reels_tab():
    """Find the visible Reels tab by its accessibility text/description and tap it."""
    for _ in range(4):
        try:
            await adb("shell", "uiautomator", "dump", "/sdcard/guri-window.xml")
            xml_text = await adb("shell", "cat", "/sdcard/guri-window.xml")
            root = ET.fromstring(xml_text)
        except (HTTPException, ET.ParseError):
            await asyncio.sleep(0.7)
            continue

        candidates = []
        for node in root.iter("node"):
            label = " ".join(
                [
                    node.attrib.get("text", ""),
                    node.attrib.get("content-desc", ""),
                ]
            ).casefold()
            if "reel" not in label and "रील" not in label:
                continue
            bounds = re.search(
                r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                node.attrib.get("bounds", ""),
            )
            if not bounds:
                continue
            left, top, right, bottom = map(int, bounds.groups())
            if right <= left or bottom <= top:
                continue
            clickable = node.attrib.get("clickable") == "true"
            # Prefer a clickable node, then a precise short label over a long caption.
            candidates.append(
                (0 if clickable else 1, len(label), (left + right) // 2, (top + bottom) // 2)
            )

        if candidates:
            candidates.sort()
            _, _, x, y = candidates[0]
            await adb("shell", "input", "tap", str(x), str(y))
            await asyncio.sleep(1.0)
            return True

        await asyncio.sleep(0.7)

    return False


async def reels_scroll(count):
    await phone()
    size = await adb("shell", "wm", "size")
    match = re.search(r"(\d+)x(\d+)", size)
    width, height = map(int, match.groups()) if match else (1080, 1920)
    for _ in range(max(1, min(20, count))):
        await adb(
            "shell", "input", "swipe",
            str(width // 2), str(int(height * .80)),
            str(width // 2), str(int(height * .25)), "450",
        )
        await asyncio.sleep(.8)
    return f"Sent {count} upward swipe gestures to the connected Android phone."


async def instagram_reels_scroll(count):
    await instagram_open()
    await asyncio.sleep(1.0)
    # Capture the actual display and inspect Android's accessibility/UIAutomator tree.
    await capture_phone_screen()
    if not await _tap_instagram_reels_tab():
        return {
            "reply": (
                "Instagram खुल गया, लेकिन इस phone screen पर Reels tab का accessibility label "
                "नहीं मिला। Reels tab को manually खोलें; उसके बाद Guri को scroll बोलें।"
            ),
            "action": "instagram_reels_scroll",
            "status": "partial",
        }

    result = await reels_scroll(count)
    return {
        "reply": f"Instagram खोला, Reels tab दबाया और {count} बार scroll gesture भेजा।",
        "action": "instagram_reels_scroll",
        "status": "completed",
    }
