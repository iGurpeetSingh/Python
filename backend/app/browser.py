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
                        "with playwright install chromium, close any Orbit browser "
                        "window using this profile, and retry. "
                        f"Technical detail: {type(exc).__name__}: {exc}"
                    ),
                ) from exc
        return _context


async def open_chatgpt(message: str | None = None) -> dict:
    """Open ChatGPT in a visible, persistent browser profile and optionally send a message."""
    context = await _get_context()
    page = await context.new_page()
    try:
        await page.goto("https://chatgpt.com/", wait_until="domcontentloaded", timeout=45000)
    except PlaywrightTimeoutError:
        # A slow page can still finish loading; continue to check for the composer.
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

    composer_selector = "[data-testid='prompt-textarea'], #prompt-textarea"
    try:
        await page.wait_for_selector(composer_selector, state="visible", timeout=45000)
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

    composer = page.locator(composer_selector).first
    try:
        await composer.fill(message, timeout=10000)
        send_button = page.locator("button[data-testid='send-button']").first
        if await send_button.count() and await send_button.is_visible() and await send_button.is_enabled():
            await send_button.click(timeout=10000)
        else:
            await composer.press("Enter", timeout=10000)

        # Do not report success until ChatGPT has cleared the submitted composer.
        cleared = False
        for _ in range(20):
            try:
                remaining = await composer.evaluate(
                    "(el) => ('value' in el ? el.value : (el.innerText || el.textContent || '')).trim()"
                )
                if not remaining:
                    cleared = True
                    break
            except Exception:
                # React may replace the composer node immediately after submission.
                cleared = True
                break
            await page.wait_for_timeout(250)

        if not cleared:
            raise HTTPException(
                status_code=502,
                detail="ChatGPT का Send action हुआ, लेकिन message submit होने की पुष्टि नहीं हो सकी। खुले tab को जाँचें।",
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
