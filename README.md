# Orbit Personal AI Agent

React frontend + Python FastAPI backend + optional native Android companion.

## Desktop setup (Windows)
Backend terminal:
\`\`\`powershell
cd backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
playwright install chromium
copy .env.example .env
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
\`\`\`
Frontend terminal:
\`\`\`powershell
cd frontend
npm install
npm run dev
\`\`\`
Open http://localhost:5173. Chrome supports the browser microphone button; allow microphone permission. Browser speech recognition support varies.

## Local AI (no paid API)
Install Ollama, run \`ollama pull qwen2.5:3b\`, and keep Ollama running. Configure \`OLLAMA_MODEL\` in backend/.env. Without Ollama, only the explicitly implemented commands work; general questions will show a setup message.

## Android companion (native app)
Open the \`android\` folder in Android Studio, allow Gradle sync, then Run on your own Android phone. In Android Settings > Accessibility, enable **Orbit Agent Control**. Return to Orbit, tap Speak, and say commands such as:
- "Instagram kholo"
- "Reels kholo"
- "Agli reel"
- "Teen baar scroll karo"

The companion uses Android SpeechRecognizer + TextToSpeech and an AccessibilityService gesture. It can launch Instagram, attempt to tap a visible Reels label, and swipe only while Instagram is the foreground app. Android may require you to enable the accessibility service manually. Do not enable accessibility for apps you do not trust.

## Important limitations
- Instagram UI labels/layouts vary; opening Reels is best-effort and may require manual navigation.
- This does not bypass login, CAPTCHA, rate limits, or app protections. It does not like, follow, comment, message, or purchase.
- Android restricts background microphone access; use the app's visible Speak button.
- Never expose the local backend to the public internet without authentication and a security review.
- This repository has not been built or tested on your specific PC/phone yet. Verify in Android Studio and on a connected device before relying on it.
