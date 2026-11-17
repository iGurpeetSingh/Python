import asyncio
from pathlib import Path

from fastapi import HTTPException
from playwright.async_api import TimeoutError as PlaywrightTimeoutError, async_playwright

from .config import BROWSER_PROFILE_DIR

_playwright = None
_context = None
_browser_lock = asyncio.Lock()\n_image_batch_page = None


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


async def open_chatgpt(message: str | None = None, search_first: bool = False) -> dict:
    """Open ChatGPT in a visible, persistent browser profile and optionally send a message."""
    context = await _get_context()
    page = await context.new_page()

    try:
        if search_first:
            # Make the requested search visible in the new tab, then open ChatGPT.
            try:
                await page.goto(
                    "https://www.google.com/search?q=ChatGPT",
                    wait_until="domcontentloaded",
                    timeout=30000,
                )
                await page.wait_for_timeout(900)
                official_link = page.locator("a[href*='chatgpt.com']").first
                if await official_link.count() and await official_link.is_visible():
                    await official_link.click(timeout=8000)
                else:
                    await page.goto(
                        "https://chatgpt.com/",
                        wait_until="domcontentloaded",
                        timeout=45000,
                    )
            except PlaywrightTimeoutError:
                await page.goto(
                    "https://chatgpt.com/",
                    wait_until="domcontentloaded",
                    timeout=45000,
                )
        else:
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
            "steps": ["new_tab", "search_chatgpt" if search_first else "open_chatgpt"],
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
        "steps": (["new_tab", "search_chatgpt", "open_chatgpt"] if search_first else ["new_tab", "open_chatgpt"]) + ["fill_message", "send_message"],
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


async def send_image_prompt_and_wait(prompt: str, timeout_seconds: int = 360) -> dict:
    """Send one image prompt and wait for a new assistant image to finish rendering."""
    global _image_batch_page
    context = await _get_context()

    if _image_batch_page is None or _image_batch_page.is_closed():
        _image_batch_page = next(
            (p for p in context.pages if "chatgpt.com" in (p.url or "")),
            None,
        )
    if _image_batch_page is None:
        _image_batch_page = await context.new_page()

    page = _image_batch_page
    if "chatgpt.com" not in (page.url or ""):
        await page.goto("https://chatgpt.com/", wait_until="domcontentloaded", timeout=45000)

    composer_selector = (
        "[data-testid='prompt-textarea'], #prompt-textarea, "
        "div[contenteditable='true'][role='textbox'], "
        "textarea[placeholder*='Message']"
    )
    try:
        await page.wait_for_selector(composer_selector, state="visible", timeout=45000)
        composer = await _first_visible(page, composer_selector)
        if composer is None:
            raise PlaywrightTimeoutError("ChatGPT composer is not visible")

        # A visible composer is not enough: the user must already be logged in.
        if await page.locator("input[type='password']").count():
            return {
                "status": "failed",
                "image_generated": False,
                "error": "ChatGPT login required. Log in manually in the opened Chrome tab.",
            }

        before = await page.locator("[data-message-author-role='assistant']").count()
        await composer.fill(prompt, timeout=15000)
        send_selector = (
            "button[data-testid='send-button'], "
            "button[aria-label='Send prompt'], "
            "button[aria-label*='Send']"
        )
        send_button = await _first_visible(page, send_selector)
        if send_button is not None and await send_button.is_enabled():
            await send_button.click(timeout=15000)
        else:
            await composer.press("Enter", timeout=15000)

        deadline = asyncio.get_running_loop().time() + timeout_seconds
        new_turn_seen = False
        stable_since = None
        image_ready = False
        while asyncio.get_running_loop().time() < deadline:
            assistants = page.locator("[data-message-author-role='assistant']")
            count = await assistants.count()
            if count > before:
                new_turn_seen = True
                last = assistants.nth(count - 1)
                stop = page.locator(
                    "button[data-testid='stop-button'], "
                    "button[aria-label*='Stop generating'], "
                    "button[aria-label*='Stop response']"
                )
                generating = False
                for i in range(await stop.count()):
                    if await stop.nth(i).is_visible():
                        generating = True
                        break

                images = last.locator("img")
                for i in range(await images.count()):
                    try:
                        natural = await images.nth(i).evaluate(
                            "(img) => ({width: img.naturalWidth, height: img.naturalHeight})"
                        )
                        if natural["width"] >= 256 and natural["height"] >= 256:
                            image_ready = True
                            break
                    except Exception:
                        continue

                if image_ready and not generating:
                    if stable_since is None:
                        stable_since = asyncio.get_running_loop().time()
                    elif asyncio.get_running_loop().time() - stable_since >= 4:
                        return {
                            "status": "completed",
                            "image_generated": True,
                            "reply": "Image generation complete.",
                        }
                else:
                    stable_since = None

            await page.wait_for_timeout(1000)

        if not new_turn_seen:
            reason = "ChatGPT ने नया response शुरू नहीं किया।"
        elif not image_ready:
            reason = "Response आया, लेकिन generated image की पुष्टि timeout के अंदर नहीं हुई।"
        else:
            reason = "Image दिखाई दी, लेकिन generation पूरी होने की पुष्टि timeout के अंदर नहीं हुई।"
        return {"status": "failed", "image_generated": False, "error": reason}
    except PlaywrightTimeoutError as exc:
        return {
            "status": "failed",
            "image_generated": False,
            "error": f"ChatGPT page/composer timeout: {exc}",
        }
    except Exception as exc:
        return {
            "status": "failed",
            "image_generated": False,
            "error": f"{type(exc).__name__}: {exc}",
        }


async def close_image_batch_page():
    global _image_batch_page
    if _image_batch_page is not None:
        try:
            if not _image_batch_page.is_closed():
                await _image_batch_page.close()
        except Exception:
            pass
        _image_batch_page = None
