# Guri Voice Assistant

A voice-first assistant with a minimal React voice screen, Python desktop automation backend, and a native Android companion.

## Windows desktop — no manual terminal commands

Install **Python 3.11+** and **Node.js LTS** once. Download/clone this repository, then double-click:

`Start-Guri.bat`

The launcher creates the Python environment, installs backend packages, installs Playwright Chromium, installs React packages when needed, starts the Python API and React voice screen, checks the API health endpoint, and opens the Guri page.

If startup fails, the launcher shows the actual error. Diagnostic logs are saved under `%TEMP%\guri-backend-error.log` and `%TEMP%\guri-frontend-error.log`.

In the Guri page, press the microphone once and grant microphone permission. Speak directly; saying "Guri" is optional because the microphone button is already an explicit command trigger.

Examples:
- "Amazon खोलो"
- "New Tab खोलो, ChatGPT search करो और उसको message Hi"
- "ChatGPT को खोलकर message करो कि Hello"

The browser voice interface speaks its response and re-arms listening after each command while the page remains visible. Browser speech recognition is provided by the browser and may require an internet connection.

## Android phone — Python backend not required

The native Android app runs the voice command and Instagram controls directly on the phone.

1. Open the `android` folder in Android Studio and run the app on your phone, or download the debug APK from the GitHub Actions workflow artifact named **Guri-Android-debug** after the build succeeds.
2. Allow Microphone permission.
3. Tap **फोन Control चालू करें** once and enable **Guri Voice Assistant** under Android Accessibility settings.
4. Return to Guri. It starts listening while its app is open.

Say:

- "Guri, Instagram open karo."
- "Guri, Instagram open karo aur Reels scroll karo."
- "Guri, Reels scroll karo."
- "Guri, five baar scroll karo."

For the combined Instagram command, Guri launches Instagram, looks for the visible Reels tab through Android Accessibility, taps it, and sends the requested swipe gestures. Instagram must be installed and signed in.

**Android listening limitation:** Android does not allow an ordinary app to keep using the microphone invisibly at all times. This version listens while the Guri app is open. When Guri launches Instagram, it stops listening but continues the one-shot Reels tap/scroll action through Accessibility. Return to Guri before speaking another command. A system-wide, screen-off wake phrase requires a separate foreground microphone service and explicit Android notification/permission handling.

**Important:** Opening the Guri React webpage on a phone does not grant it permission to tap or scroll other installed apps. Use the native Android Guri APK for Instagram control. The desktop webpage uses the Python backend on the same computer.

## ChatGPT browser automation

Desktop ChatGPT automation uses a separate visible browser profile at `backend/browser_profile`. On first use, sign in to ChatGPT manually in that Guri-controlled browser window. The session is stored in that local profile; no password or session token is stored in source code.

## Local AI

Install Ollama and run `ollama pull qwen2.5:3b` if you want local conversational answers. Explicit supported voice actions such as opening ChatGPT and supported Instagram commands do not depend on Ollama.

## Safety and limitations

- Guri only performs commands explicitly implemented and reports when a control cannot be found.
- Instagram UI changes can affect Reels tab discovery; the agent does not bypass login, CAPTCHA, or app protections.
- Instagram likes, follows, comments, DMs, and purchases are not automated.
- Never expose the local Python backend to the public internet without authentication and a security review.
- The project has not been tested on your physical phone or PC in this session. The GitHub Actions Android build checks whether the app compiles, but real-device behavior still requires an on-device test.

## Backend command tests

From the backend directory:

~~~powershell
python -m unittest discover -s tests -v
~~~
