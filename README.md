# Orbit Personal AI Agent

React frontend + Python FastAPI backend. Supports browser automation, Google/Amazon search, optional local Ollama chat, and Android ADB controls for launching Instagram and swiping the currently visible screen.

## Run backend (Python 3.11+)
cd backend
py -m venv .venv
.venv\\Scripts\\activate
pip install -r requirements.txt
playwright install chromium
copy .env.example .env
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000

## Run frontend (Node 20+; second terminal)
cd frontend
npm install
npm run dev
Open http://localhost:5173.

## Local AI, no paid API
Install Ollama, run: ollama pull qwen2.5:3b
Set OLLAMA_MODEL=qwen2.5:3b in backend/.env. Without Ollama, explicit automation commands still work; open-ended answers need the local model.

## Android Instagram control
Install Android Platform Tools; enable USB debugging on your own Android phone; connect USB and approve the computer; verify with adb devices. In the dashboard press Open Instagram, manually navigate to Reels if required, then press Scroll Reels. The swipe action sends gestures to the current screen and cannot guarantee the app is on Reels.

## Scope and safety
This is a real starter implementation, not a universal agent. Website layouts, CAPTCHA, login, device settings, and network access affect results. It does not bypass CAPTCHA, access private data, like/follow/comment/message, or purchase. Keep backend bound to localhost. Never expose it publicly without authentication and a security review.
