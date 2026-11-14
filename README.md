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

## YouTube upload by voice (Windows PC)

Guri supports a command such as: "गोरी, मेरी 7 मिनट की video YouTube पर upload करो, title और SEO खुद बनाओ।" The Windows local runtime selects a matching video from Videos/Downloads/Desktop (or `GURI_VIDEO_DIR`), uses FFprobe (included with FFmpeg) to match the spoken duration and optionally transcribes audio when `faster-whisper` is installed, asks the configured local Ollama model for title/description/tags/category, then uploads it as **Unlisted** using the official YouTube Data API. The original video file is not modified.

One-time YouTube setup:
1. In Google Cloud Console, create/select a project and enable **YouTube Data API v3**.
2. Configure the OAuth consent screen and create an **OAuth Client ID → Desktop app**.
3. Download the client JSON and save it outside this repository as `%USERPROFILE%\\.guri\\youtube\\client_secret.json`. Never commit this file or `token.json`.
4. Install the updated backend requirements and restart `Start-Guri.bat`. On the first upload, a browser opens for Google sign-in and YouTube channel authorization.
5. Install FFmpeg (for FFprobe duration matching). Optional: `pip install faster-whisper`; the first local transcription downloads a Whisper model. Without it, SEO generation uses the spoken instruction and filename only, not the actual video dialogue.

Set `GURI_VIDEO_DIR` to a dedicated folder to make video selection deterministic. If multiple files match, Guri asks for the filename rather than guessing. The API OAuth project may need Google verification; unverified projects can be restricted to private uploads, so verify the channel visibility after setup. Guri reports success only after YouTube returns a video ID. This feature currently uploads videos and metadata; it does not generate/upload custom thumbnails.

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
