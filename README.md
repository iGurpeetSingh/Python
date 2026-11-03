# Guri Voice Assistant

Voice-first assistant with a React desktop voice screen, Python local automation service, and native Android companion.

## Important: use the correct Guri app for the device

- **Windows desktop commands** (open a browser tab, search ChatGPT, send a message): use the Guri webpage started on that same Windows PC. It needs the local Python backend.
- **Android phone commands** (open Instagram, find Reels, swipe/scroll): use the installed native **Guri Voice Assistant APK**. Do not use the React webpage in mobile Chrome for controlling other apps. Android does not let an ordinary webpage tap or scroll other installed apps.

## Windows desktop — first-time setup

Install these two runtimes once:
- Python 3.11 or newer
- Node.js LTS

Then download the repository ZIP, extract the entire folder to a normal folder (not the ZIP preview), and double-click **Start-Guri.bat**.

The launcher:
1. Creates the backend Python virtual environment.
2. Installs backend packages and Playwright Chromium.
3. Installs React packages if needed.
4. Starts the Python API on port 8000.
5. Starts the voice webpage on port 5173.
6. Verifies both services before opening Guri.

No terminal commands need to be typed. The first setup needs an internet connection to download packages and Chromium. Later starts reuse the installed packages.

Expected local addresses:
- Guri voice page: http://127.0.0.1:5173
- Guri backend health: http://127.0.0.1:8000/api/health

The health address must return JSON with \`"ok": true\` and \`"agent": "Guri"\`.

## Make Windows start Guri automatically at sign-in

After **Start-Guri.bat** has completed the first setup successfully, double-click **Install-Guri-Autostart.bat** once. It creates a shortcut in the current Windows user's Startup folder. On future Windows sign-ins, Guri starts its backend and opens the voice page automatically.

To undo this, double-click **Remove-Guri-Autostart.bat**. This only removes the Guri startup shortcut; it does not uninstall Python, Node.js or the repository.

If auto-start runs but something is missing, see:
- \`%TEMP%\guri-startup.log\`
- \`%TEMP%\guri-backend-error.log\`
- \`%TEMP%\guri-frontend-error.log\`

The launcher checks whether ports 8000 and 5173 are already occupied. It will report if another program owns one of those ports instead of silently treating it as Guri.

## Desktop voice commands

Open the page using **Start-Guri.bat**, press the microphone once, grant microphone permission, then speak. Saying "Guri" is optional during the explicit microphone session.

Examples:
- "Amazon खोलो"
- "New Tab खोलो, ChatGPT search करो और उसको message Hi"
- "ChatGPT खोलकर message करो कि Hello"

For ChatGPT automation, Guri opens a separate visible browser profile in a new tab. Sign in manually to ChatGPT in that Guri-controlled browser the first time. Guri stores that browser profile locally; it does not store your password or session token in source code.

The browser voice screen speaks the task result and re-arms listening while the page remains visible.

## Android phone — Python backend not required

Install the native Android APK built by the GitHub Actions workflow named **Build Guri Android APK**. Open the artifact named **Guri-Android-debug**, extract the ZIP, and install \`app-debug.apk\` on your phone.

1. Allow Microphone permission.
2. Tap **फोन Control चालू करें** once and enable **Guri Voice Assistant** in Android Accessibility settings.
3. Return to Guri. It listens while its app is open.

Example commands:
- "Guri, Instagram open karo."
- "Guri, Instagram open karo aur Reels scroll karo."
- "Guri, Reels scroll karo."
- "Guri, five baar scroll karo."

For the combined Instagram command, Guri launches Instagram, tries to find/tap the visible Reels tab through Android Accessibility (with a screen-position fallback), then dispatches swipe gestures. Instagram must be installed and signed in.

**Android listening limitation:** This version listens while the Guri app is open. When it launches Instagram, it pauses listening but continues the one-shot Reels/scroll action. Return to Guri before speaking another command. It is not an always-on, screen-off wake-word assistant.

## Why "Python backend is not running" appears

The React webpage calls \`http://127.0.0.1:8000\`. That address means "this same device", not "the other device on my Wi-Fi".

- If the webpage is open on Windows, the Python API must be running on that same Windows PC. Start it with **Start-Guri.bat**.
- If the webpage is open in Android Chrome, \`127.0.0.1\` points to the phone. It cannot find the Windows PC's Python server. Use the native Android Guri app for Instagram controls.
- If the webpage is hosted on a remote HTTPS website, it cannot use the local PC backend through that default HTTP address. Use the locally started page at \`http://127.0.0.1:5173\`.

## Local AI

Install Ollama and run \`ollama pull qwen2.5:3b\` if you want local conversational answers. The explicitly implemented browser and Android actions do not depend on Ollama.

## Safety and limitations

- Guri only runs supported commands and reports when an action is unavailable.
- Instagram UI changes can affect Reels tab detection; the agent does not bypass login, CAPTCHA, or app protections.
- Instagram likes, follows, comments, DMs and purchases are not automated.
- Never expose the local Python backend to the public internet without authentication and a security review.
- CI verifies the Python command parser, React production build and Android APK compilation. A real-device Instagram UI test still has to be performed on the user's phone.

## Backend command tests

From the backend folder:

~~~powershell
python -m unittest discover -s tests -v
~~~
