## Android ChatGPT Image Batch

- Android app mein 1–50 image prompts paste karke sequentially ChatGPT Android app mein bhejne ka on-device workflow add kiya gaya hai.
- Setup, permissions, usage, limitations aur APK build instructions: [docs/ANDROID-CHATGPT-IMAGE-BATCH.md](docs/ANDROID-CHATGPT-IMAGE-BATCH.md).
- APK build ke liye GitHub Actions mein **Build Guri Android APK** workflow run/artifact dekhein.

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

## Bulk ChatGPT Image Automation

The Guri dashboard includes a sequential image-prompt queue for up to 50 prompts per batch.

### Local Windows mode

1. Run `Start-Guri.bat` and open the local Guri dashboard.
2. Paste numbered prompts into **Bulk Image Automation** (one prompt per numbered item).
3. Sign in to ChatGPT manually in the Chrome window opened by Playwright.
4. Press **Start batch**. The worker sends one prompt, waits until a generated image is visible and the response has stopped, then sends the next prompt.
5. Use **Pause** to stop after the current image, **Resume** to continue, or **Retry failed prompt** to retry the prompt that did not finish.

The automation uses the existing Playwright browser profile under `backend/browser_profile`. It does not use an OpenAI API key or a paid automation API. ChatGPT account limits, login checks, CAPTCHA, and usage verification remain under the user's control. The current implementation verifies image completion in the page; it does not automatically download generated images.

### Remote dashboard mode

The Vercel frontend can call the existing FastAPI cloud service by setting `VITE_API_URL` to the HTTPS URL of the deployed Guri API. Keep the FastAPI/WebSocket broker on a WebSocket-capable host (the existing Render service), because Vercel Functions do not host the long-lived Windows-agent WebSocket connection.

- Configure the Render service with `GURI_CLOUD_MODE=true` and a private `GURI_PAIRING_CODE`.
- Start the Windows agent with `GURI_CLOUD_URL` and `GURI_DEVICE_TOKEN` set to that Render URL and the same pairing code.
- Open the Vercel dashboard and enter the pairing code in the image automation panel.
- The dashboard submits the prompt queue to the Windows agent and polls progress from the cloud API.

Do not expose the pairing code in public source code or commit it to GitHub.

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

## Coaching & Exam Control (optional cloud feature)

The Android app now has a **Coaching & Exam Control** screen for creating and publishing Objective and Written exam papers. This feature is separate from Guri's local voice commands and requires the FastAPI service to be deployed over HTTPS.

- Set `PAPER_GITHUB_TOKEN` and `PAPER_ADMIN_TOKEN` in the Render service Environment.
- The GitHub token needs Contents: Read and write access to the private storage repository.
- The Paper API stores `data/exams/objective.json` and `data/exams/written.json` in GitHub. It does not use a database.
- Enter the deployed Render API base URL and the private admin token in the Android Coaching & Exam Control screen.
- Set `VITE_EXAMS_API_URL` in the Coaching website's Vercel project to the same Render base URL.

See [Exam Publishing API setup](docs/EXAM-PUBLISHING-API.md) for API paths, paper format, security, and deployment configuration. Until the Render secrets are configured and the backend is deployed, the publish/website connection is not live. The ordinary local Guri voice workflows remain independent.

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
