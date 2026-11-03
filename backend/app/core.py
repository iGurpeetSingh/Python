import asyncio, re, shutil, sqlite3
from datetime import datetime, timezone
from urllib.parse import quote_plus
import httpx
from fastapi import HTTPException
from playwright.async_api import async_playwright
from .config import DB_PATH, OLLAMA_BASE_URL, OLLAMA_MODEL, ADB_PATH

def init_db():
    with sqlite3.connect(DB_PATH) as db:
        db.execute("CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, role TEXT, content TEXT, created_at TEXT)")
def save(role, content):
    with sqlite3.connect(DB_PATH) as db:
        db.execute("INSERT INTO messages(role,content,created_at) VALUES(?,?,?)",(role,content,datetime.now(timezone.utc).isoformat()))
def history():
    with sqlite3.connect(DB_PATH) as db:
        rows=db.execute("SELECT id,role,content,created_at FROM messages ORDER BY id DESC LIMIT 60").fetchall()
    return [dict(id=x[0],role=x[1],content=x[2],created_at=x[3]) for x in reversed(rows)]

async def local_answer(prompt):
    try:
        async with httpx.AsyncClient(timeout=100) as c:
            r=await c.post(OLLAMA_BASE_URL+"/api/chat",json={"model":OLLAMA_MODEL,"stream":False,"messages":[{"role":"system","content":"You are a careful personal assistant. Reply in the user's language. Never claim an action succeeded unless a tool confirmed it."},{"role":"user","content":prompt}]})
            r.raise_for_status()
            return r.json().get("message",{}).get("content")
    except Exception:
        return None

async def open_site(url):
    async with async_playwright() as p:
        browser=await p.chromium.launch(headless=False)
        page=await browser.new_page()
        await page.goto(url,wait_until="domcontentloaded",timeout=30000)
        title=await page.title()
        # Browser context remains alive while this Playwright session is active.
        # Use a persistent browser profile for long-lived sessions in a later release.
        await page.wait_for_timeout(1500)
        return f"Opened {url} — {title or 'page loaded'}. Browser action completed."

async def search_web(query, amazon=False):
    url=("https://www.amazon.in/s?k=" if amazon else "https://www.google.com/search?q=")+quote_plus(query)
    async with async_playwright() as p:
        browser=await p.chromium.launch(headless=True)
        page=await browser.new_page(locale="en-IN")
        try:
            await page.goto(url,wait_until="domcontentloaded",timeout=30000)
            await page.wait_for_timeout(1000)
            if amazon:
                rows=await page.locator('[data-component-type="s-search-result"]').evaluate_all("""els => els.slice(0,25).map(el=>({title:el.querySelector('h2 span')?.innerText?.trim()||'',price:el.querySelector('.a-price .a-offscreen')?.innerText||'',url:el.querySelector('h2 a')?.href||''})).filter(x=>x.title)""")
                def price(x):
                    m=re.search(r"\d+(?:\.\d+)?",(x.get("price") or "").replace(",","").replace("₹",""))
                    return float(m.group()) if m else float("inf")
                return sorted(rows,key=price)[:12]
            return await page.locator("div.MjjYud").evaluate_all("""els=>els.slice(0,8).map(el=>({title:el.querySelector('h3')?.innerText||'',url:el.querySelector('a')?.href||'',snippet:(el.innerText||'').slice(0,300)})).filter(x=>x.title&&x.url.startsWith('http'))""")
        finally:
            await browser.close()

def adb_path():
    path=shutil.which(ADB_PATH)
    if not path:
        raise HTTPException(503,"ADB not installed or not in PATH. Install Android Platform Tools.")
    return path
async def adb(*args):
    proc=await asyncio.create_subprocess_exec(adb_path(),*args,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
    out,err=await proc.communicate()
    if proc.returncode:
        raise HTTPException(503,err.decode(errors="replace").strip() or "ADB command failed")
    return out.decode(errors="replace").strip()
async def phone():
    out=await adb("devices")
    devices=[line.split()[0] for line in out.splitlines()[1:] if "\tdevice" in line]
    if not devices:
        raise HTTPException(409,"No authorized Android phone. Enable USB debugging and approve this computer.")
    return devices[0]
async def instagram_open():
    await phone()
    await adb("shell","monkey","-p","com.instagram.android","-c","android.intent.category.LAUNCHER","1")
    return "Instagram launch requested on your Android phone. Sign in manually and open Reels if needed."
async def reels_scroll(count):
    await phone()
    size=await adb("shell","wm","size")
    match=re.search(r"(\d+)x(\d+)",size)
    width,height=map(int,match.groups()) if match else (1080,1920)
    for _ in range(count):
        await adb("shell","input","swipe",str(width//2),str(int(height*.80)),str(width//2),str(int(height*.25)),"450")
        await asyncio.sleep(.8)
    return f"Sent {count} upward swipe gestures to the current phone screen. Confirm Instagram Reels is foreground."
