import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()
ROOT = Path(__file__).resolve().parent.parent
DB_PATH = ROOT / "history.db"
OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5:3b")
FRONTEND_ORIGIN = os.getenv("FRONTEND_ORIGIN", "http://localhost:5173")
ADB_PATH = os.getenv("ADB_PATH", "adb")
BROWSER_PROFILE_DIR = Path(
    os.getenv("BROWSER_PROFILE_DIR", str(ROOT / "browser_profile"))
)

GURI_PAIRING_CODE = os.getenv("GURI_PAIRING_CODE", "")
GURI_CLOUD_MODE = os.getenv("GURI_CLOUD_MODE", "0").strip().lower() in {"1", "true", "yes"}
