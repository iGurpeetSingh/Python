# Guri Voice Assistant — Cloud + Device Agents

Guri is a live cloud command service with a minimal voice webpage and paired device agents.

- The **cloud website and command API** are hosted on Render.
- The **Windows agent** stays connected to Guri Cloud and carries out browser actions on that PC.
- The **Android Guri app** stays connected to Guri Cloud and carries out Instagram/Reels actions on that phone.
- The website does not pretend that a server-side browser can click on your personal computer or phone. The paired agent performs the action on the actual target device and reports the result to the cloud.

## 1. Deploy the live website and API

This repository includes \`Dockerfile\` and \`render.yaml\` for a single Render web service. The Docker image builds the React webpage and serves it from the FastAPI cloud API.

1. Open the repository's Render Blueprint deployment page:
   \`https://render.com/deploy?repo=https://github.com/iGurpeetSingh/Python\`
2. Sign in to Render and authorize access to this GitHub repository.
3. Review the service named **guri-cloud** and deploy the Blueprint.
4. Render generates a secret called \`GURI_PAIRING_CODE\`. After the service is created, open Render Dashboard → **guri-cloud** → **Environment** and copy this secret. Do not publish or send this value to anyone.
5. Wait for the service deploy to finish. Your live URL will look like \`https://guri-cloud.onrender.com\` (Render may add a suffix if that name is already taken).

The Blueprint uses a **Starter** web service so the cloud relay does not intentionally sleep when idle. This is a paid Render compute plan. A free Render web service can sleep after 15 minutes without inbound HTTP or WebSocket messages, and waking it can take about a minute; that is not suitable when you expect the control service to be instantly available.

Health check:
\`https://YOUR-RENDER-URL/api/health\`

Expected JSON includes:
~~~json
{
  "ok": true,
  "agent": "Guri",
  "cloud_mode": true,
  "pairing_required": true,
  "connected_devices": []
}
~~~

## 2. Open the live Guri webpage

Open the Render URL in Chrome. Press the microphone button once and grant microphone access.

Under **Secure Device Pairing**, paste the Render \`GURI_PAIRING_CODE\` and press **Connect**. The website should show Windows and Android device status. The code is stored only in the current browser tab's session storage.

## 3. Connect your Windows PC

On the PC that should open browser tabs and send ChatGPT messages:

1. Download and extract the latest repository ZIP.
2. Install Python 3.11+ once.
3. Double-click \`Start-Guri-Desktop-Agent.bat\`.
4. Enter the Render URL, then the same \`GURI_PAIRING_CODE\`.
5. Keep the agent window running. It reconnects automatically if the network or service drops.

The first run installs the Python requirements and Playwright Chromium into \`agents/.venv\`. The Windows agent opens a separate visible browser profile on your PC. Sign into ChatGPT manually in that profile once.

Desktop examples:
- "New Tab खोलो, ChatGPT search करो और उसको message Hi"
- "ChatGPT खोलो और message करो कि Hello"
- "Amazon खोलो"
- "Google खोलो"

The command runs on the paired Windows PC, not inside Render's server.

## 4. Connect your Android phone

Install the latest **Guri Voice Assistant APK** from the GitHub Actions artifact **Guri-Android-debug**. In the app:

1. Allow Microphone permission.
2. Enable **Guri Voice Assistant** under Android Settings → Accessibility.
3. Enter your Render URL in **Guri Cloud URL**.
4. Enter the same Render \`GURI_PAIRING_CODE\`.
5. Tap **Guri Cloud से Connect करें** and keep Guri active.

The app reconnects to the cloud WebSocket if the connection drops. Its Accessibility Service is used only for the user-requested tap and swipe actions.

Phone examples from the live webpage:
- "Instagram खोलो"
- "Instagram खोलो और Reels scroll करो"
- "Reels पाँच बार scroll करो"

The command is sent over HTTPS/WebSocket to the Android companion. The phone then opens Instagram, locates/taps the Reels tab (with a best-effort fallback), performs swipes, and sends its result back to the webpage.

Android still has OS-level restrictions: this app is not an invisible, always-on, screen-off wake-word service. Keep Guri active and exempt it from battery optimization if your Android manufacturer aggressively stops background apps. If the app is force-stopped or the phone is offline, the cloud will show Android as offline and commands will not be reported as successful.

## 5. Command routing

| Spoken request | Device that executes it |
|---|---|
| Open ChatGPT in a new tab and send Hi | Paired Windows PC |
| Open Amazon / Google / YouTube | Paired Windows PC |
| Open Instagram | Paired Android phone |
| Open Instagram and scroll Reels | Paired Android phone |

The cloud API returns an error instead of claiming success when the required device is offline or does not reply before the timeout.

## 6. Security and reliability

- Use the generated \`GURI_PAIRING_CODE\` as a secret. Anyone with it can issue commands to your paired devices.
- Do not commit the pairing code, browser profile, passwords, or session cookies to GitHub.
- WebSocket connections use WSS in production, and devices authenticate with an Authorization Bearer header.
- The current broker keeps connected-device state in the service process. Use one Render instance; horizontal scaling needs a shared broker such as Redis or a managed pub/sub service.
- Render deploys/restarts can temporarily disconnect agents; both clients reconnect automatically.
- The cloud service cannot control a device unless its own paired companion is running and connected.
- Never use automation to bypass logins, CAPTCHA, or a site's access protections.

## 7. Local development (optional)

Backend:
~~~powershell
cd backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
playwright install chromium
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
~~~

Frontend:
~~~powershell
cd frontend
npm install
npm run dev
~~~

The live Render service does not use this local backend; it is only for local development.

Tests:
~~~powershell
cd backend
python -m unittest discover -s tests -v
~~~
