# Guri Voice Assistant

Guri has two independent local runtimes. **No Render URL, cloud URL, pairing code, or backend link is required for the basic workflows.**

## Windows PC mode

1. Download the latest repository ZIP from GitHub and extract it.
2. Install Python 3.11+ and Node.js LTS once.
3. Double-click `Start-Guri.bat`.
4. On first launch, the script creates the Python virtual environment, installs backend/frontend dependencies, installs Playwright Chromium, starts the API and React voice UI, checks both health endpoints, and opens Guri in your browser.
5. Press the microphone button and speak. Commands run through the Python process on this same Windows PC.

Examples:
- "New Tab खोलो और ChatGPT खोलो"
- "ChatGPT को Hi message करो"
- "Google खोलो और मौसम search करो"
- "Amazon खोलो"

Keep the extracted Guri folder on the PC. Do not open the Vercel-hosted page for local PC automation: a remote website cannot access your PC's localhost, terminal, browser profile, or screen. Use the page that `Start-Guri.bat` opens at `http://127.0.0.1:5173`.

The launcher writes diagnostic logs under `%TEMP%\guri-*.log`. If startup fails, it displays the exact missing dependency or port error instead of silently claiming success.

## Android phone mode (standalone)

The Android companion can listen and run its supported phone commands locally. It does not need a cloud URL, Render URL, pairing code, Windows PC, or USB connection.

1. Install the latest **Guri Voice Assistant APK** from the GitHub Actions artifact named `Guri-Android-debug`.
2. Open Guri and allow Microphone permission.
3. Tap **Phone Control चालू करें** once. Android Settings → Accessibility → Guri Voice Assistant → ON.
4. Return to Guri. It listens in Hindi while its activity is visible.
5. Say: "गुरी, Instagram खोलो और Reels scroll करो".

For Instagram/Reels automation, Guri:
- launches the installed Instagram app on the phone;
- checks that Instagram is the foreground app;
- captures the current display in memory on Android 11+ (the screenshot is immediately released and is not uploaded or saved);
- inspects the active accessibility window for the Reels control and attempts to tap it;
- sends swipe gestures sequentially, waiting for Android's gesture callback before reporting completion.

Android Accessibility permission is required for tapping and scrolling. It is an explicit Android system permission and cannot be silently enabled by an app. Android may also restrict background microphone use; start the command while Guri is open and listening. Once Instagram is foreground, Guri does not secretly listen in the background.

## Important boundaries

- The phone app currently implements the Instagram/Reels voice flow. It is not yet a general-purpose visual AI agent for every Android app.
- Screen capture is a local verification input only; current code does not run general OCR/vision reasoning over arbitrary screens.
- Instagram UI labels and navigation can change. If Reels cannot be located, Guri reports failure rather than claiming it worked.
- The PC runtime controls the Windows PC only. The Android standalone app controls the phone only. A future PC-to-phone remote bridge would need an explicit local network/device pairing mechanism; the two local modes do not silently control each other.
- Do not use automation to bypass logins, CAPTCHA, or app/site access protections.

## Optional local development

Backend:
```powershell
cd backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
playwright install chromium
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

Frontend:
```powershell
cd frontend
npm install
npm run dev
```

Tests:
```powershell
cd backend
python -m unittest discover -s tests -v
```
