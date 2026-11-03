import os
from pathlib import Path
from dotenv import load_dotenv
load_dotenv()
ROOT=Path(__file__).resolve().parent.parent
DB_PATH=ROOT/"history.db"
OLLAMA_BASE_URL=os.getenv("OLLAMA_BASE_URL","http://127.0.0.1:11434").rstrip("/")
OLLAMA_MODEL=os.getenv("OLLAMA_MODEL","qwen2.5:3b")
FRONTEND_ORIGIN=os.getenv("FRONTEND_ORIGIN","http://localhost:5173")
ADB_PATH=os.getenv("ADB_PATH","adb")
