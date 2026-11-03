import asyncio
from pathlib import Path

from fastapi import HTTPException
from playwright.async_api import TimeoutError as PlaywrightTimeoutError, async_playwright

from .config import BROWSER_PROFILE_DIR

_playwright = None
_context = None
_browser_lock = asyncio.Lock()


async def _get_context():
    global _playwright, _context
    async with _browser_lock:
        if _context is not None:
            try:
                browser = _context.browser
                if browser is not None and browser.is_connected():
                    return _context
            except Exception:
                pass
            try:
                await _context.close()
            except Exception:
                pass
            _context = None

        if _playwright is None:
            _playwright = await async_playwright().start()

        profile = Path(BROWSER_PROFILE_DIR).expanduser()
        profile.mkdir(parents=True, exist_ok=True)
        try:
            _context = await _playwright.chromium.launch_persistent_context(
                user_data_dir=str(profile),
                channel="chrome",
                headless=False,
                no_viewport=True,
            )
        except Exception:
            try:
                _context = await _playwright.chromium.launch_persistent_context(
                    user_data_dir=str(profile),
                    headless=False,
                    no_viewport=True,
                )
            except Exception as exc:
                raise HTTPException(
                    status_code=503,
                    detail=(
                        "Visible browser launch failed. Install Playwright Chromium "
                        "with playwright install chromium, then retry. "
                        f"Technical detail: {type(exc).__name__}: {exc}"
                    ),
                ) from exc
        return _context


async def _first_visible(page, selector):
    matches = page.locator(selector)
    for index in range(await matches.count()):
        candidate = matches.nth(index)
        if await candidate.is_visible():
            return candidate
    return None


async def open_chatgpt(message: str | None = None) -> dict:
    """Open ChatGPT in a visible, persistent browser profile and optionally send a message."""
    context = await _get_context()
    page = await context.new_page()

    try:
        await page.goto("https://chatgpt.com/", wait_until="domcontentloaded", timeout=45000)
    except PlaywrightTimeoutError:
        # A slow page can still finish loading; check for the actual composer below.
        pass
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"ChatGPT page could not be opened: {type(exc).__name__}: {exc}",
        ) from exc

    if not message:
        return {
            "reply": "ChatGPT नए browser tab में खोल दिया है।",
            "action": "chatgpt_open",
            "status": "opened",
        }

    composer_selector = (
        "[data-testid='prompt-textarea'], #prompt-textarea, "
        "div[contenteditable='true'][role='textbox'], "
        "textarea[placeholder*='Message']"
    )
    try:
        await page.wait_for_selector(composer_selector, state="visible", timeout=30000)
        composer = await _first_visible(page, composer_selector)
        if composer is None:
            raise PlaywrightTimeoutError("ChatGPT message composer is not visible")
    except PlaywrightTimeoutError:
        return {
            "reply": (
                "ChatGPT नए tab में खुला है, लेकिन इस Orbit browser profile में login नहीं मिला "
                "या message box लोड नहीं हुआ। खुले हुए tab में ChatGPT पर manually login करें, "
                "फिर वही command दोबारा बोलें।"
            ),
            "action": "chatgpt_login_required",
            "status": "needs_login",
        }

    try:
        await composer.fill(message, timeout=10000)
        send_button_selector = (
            "button[data-testid='send-button'], "
            "button[aria-label='Send prompt'], "
            "button[aria-label*='Send']"
        )
        send_button = await _first_visible(page, send_button_selector)
        if send_button is not None and await send_button.is_enabled():
            await send_button.click(timeout=10000)
        else:
            await composer.press("Enter", timeout=10000)

        # Verify that the composer cleared and the submitted text is visible in the page.
        submitted = False
        for _ in range(40):
            current_composer = await _first_visible(page, composer_selector)
            remaining = ""
            if current_composer is not None:
                remaining = await current_composer.evaluate(
                    "(el) => ('value' in el ? el.value : (el.innerText || el.textContent || '')).trim()"
                )
            body_text = await page.locator("body").inner_text()
            if not remaining and message in body_text:
                submitted = True
                break
            await page.wait_for_timeout(250)

        if not submitted:
            raise HTTPException(
                status_code=502,
                detail=(
                    "ChatGPT का Send action हुआ, लेकिन message submit होने की पुष्टि नहीं हो सकी। "
                    "खुले tab को जाँचें; संदेश दोबारा बोलने से पहले देखें कि वह पहले से भेजा तो नहीं गया।"
                ),
            )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"ChatGPT में message भेजने में समस्या आई: {type(exc).__name__}: {exc}",
        ) from exc

    return {
        "reply": f"ChatGPT में message भेज दिया: “{message}”",
        "action": "chatgpt_message",
        "status": "submitted",
    }


async def shutdown_browser():
    global _playwright, _context
    async with _browser_lock:
        if _context is not None:
            try:
                await _context.close()
            except Exception:
                pass
            _context = None
        if _playwright is not None:
            try:
                await _playwright.stop()
            except Exception:
                pass
            _playwright = None
