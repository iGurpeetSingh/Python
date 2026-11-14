"""Local YouTube upload workflow for Guri.

Credentials are deliberately stored outside the repository. See README for setup.
"""
from __future__ import annotations

import asyncio
import base64
import json
import tempfile
import os
import re
import subprocess
from pathlib import Path
from typing import Any

import httpx
from fastapi import HTTPException

from .config import OLLAMA_BASE_URL, OLLAMA_MODEL

VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v"}
MAX_UPLOAD_BYTES = 256 * 1024 * 1024 * 1024


def _video_roots() -> list[Path]:
    home = Path.home()
    roots = [home / "Videos", home / "Downloads", home / "Desktop"]
    configured = os.getenv("GURI_VIDEO_DIR", "").strip()
    if configured:
        roots.insert(0, Path(configured).expanduser())
    return [p for p in roots if p.exists() and p.is_dir()]


def _duration_seconds(path: Path) -> float | None:
    ffprobe = os.getenv("GURI_FFPROBE", "ffprobe")
    try:
        result = subprocess.run(
            [ffprobe, "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
            capture_output=True, text=True, timeout=20, check=True,
        )
        return float(result.stdout.strip())
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def _requested_duration(command: str) -> int | None:
    value = command.casefold()
    hindi_numbers = {
        "शून्य": 0, "एक": 1, "दो": 2, "तीन": 3, "चार": 4,
        "पांच": 5, "पाँच": 5, "छह": 6, "सात": 7, "आठ": 8, "नौ": 9, "दस": 10,
    }
    minute = re.search(r"(\d+)\s*(?:minutes?|mins?|मिनट)", value)
    second = re.search(r"(\d+)\s*(?:seconds?|secs?|सेकंड)", value)
    if not minute:
        word = re.search(r"(शून्य|एक|दो|तीन|चार|पांच|पाँच|छह|सात|आठ|नौ|दस)\s*मिनट", value)
        if word:
            minute_value = hindi_numbers[word.group(1)]
        else:
            minute_value = 0
    else:
        minute_value = int(minute.group(1))
    if not second:
        word = re.search(r"(शून्य|एक|दो|तीन|चार|पांच|पाँच|छह|सात|आठ|नौ|दस)\s*सेकंड", value)
        second_value = hindi_numbers[word.group(1)] if word else 0
    else:
        second_value = int(second.group(1))
    if not minute and not second and minute_value == 0 and second_value == 0:
        return None
    return minute_value * 60 + second_value


def find_video(command: str) -> Path:
    roots = _video_roots()
    if not roots:
        raise HTTPException(404, "Videos, Downloads या GURI_VIDEO_DIR में कोई video folder नहीं मिला।")

    # If the spoken command contains a filename, prefer an exact/partial filename match.
    filename_match = re.search(r"([\w .()-]+\.(?:mp4|mov|mkv|avi|webm|m4v))", command, re.I)
    candidates: list[Path] = []
    for root in roots:
        try:
            candidates.extend(p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in VIDEO_EXTENSIONS)
        except OSError:
            continue

    if filename_match:
        needle = filename_match.group(1).strip().casefold()
        matches = [p for p in candidates if p.name.casefold() == needle or needle in p.name.casefold()]
        if len(matches) == 1:
            return matches[0]
        if len(matches) > 1:
            candidates = matches

    wanted = _requested_duration(command)
    if wanted is not None:
        measured = [(p, _duration_seconds(p)) for p in candidates]
        measured = [(p, d) for p, d in measured if d is not None and abs(d - wanted) <= 120]
        if not measured:
            raise HTTPException(404, f"{wanted // 60} मिनट के आसपास की video नहीं मिली। GURI_VIDEO_DIR सेट करें या पूरा filename बोलें।")
        measured.sort(key=lambda item: item[0].stat().st_mtime, reverse=True)
        if len(measured) > 1 and measured[0][0].stat().st_mtime == measured[1][0].stat().st_mtime:
            raise HTTPException(409, "इस duration की एक से ज्यादा videos मिलीं। कृपया filename बताएं।")
        return measured[0][0]

    if not candidates:
        raise HTTPException(404, "कोई video file नहीं मिली। GURI_VIDEO_DIR में अपनी video रखें।")
    candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    if len(candidates) > 1 and candidates[0].stat().st_mtime - candidates[1].stat().st_mtime < 60:
        raise HTTPException(409, "कई recent videos मिलीं। गलती से गलत video upload न हो, इसलिए filename बोलें।")
    return candidates[0]


def _transcribe(path: Path) -> str:
    """Optional local transcription; requires ffmpeg and faster-whisper installed."""
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        return ""
    ffmpeg = os.getenv("GURI_FFMPEG", "ffmpeg")
    audio = path.with_name(path.stem + ".guri-temp.wav")
    try:
        subprocess.run(
            [ffmpeg, "-y", "-i", str(path), "-vn", "-ac", "1", "-ar", "16000", str(audio)],
            capture_output=True, timeout=900, check=True,
        )
        model = WhisperModel(os.getenv("GURI_WHISPER_MODEL", "base"), device="cpu", compute_type="int8")
        segments, _ = model.transcribe(str(audio), vad_filter=True)
        return " ".join(segment.text.strip() for segment in segments).strip()[:18000]
    except (OSError, subprocess.SubprocessError, Exception):
        return ""
    finally:
        try:
            audio.unlink(missing_ok=True)
        except OSError:
            pass


async def _describe_video_frames(path: Path) -> str:
    """Sample video frames and ask a local Ollama vision model for a factual summary."""
    ffmpeg = os.getenv("GURI_FFMPEG", "ffmpeg")
    model = os.getenv("GURI_YOUTUBE_VISION_MODEL", "llava:7b")
    try:
        with tempfile.TemporaryDirectory(prefix="guri-youtube-") as temp_dir:
            pattern = str(Path(temp_dir) / "frame-%02d.jpg")
            subprocess.run(
                [ffmpeg, "-y", "-i", str(path), "-vf",
                 "fps=1/45,scale=640:-1:force_original_aspect_ratio=decrease",
                 "-frames:v", "8", "-q:v", "5", pattern],
                capture_output=True, timeout=180, check=True,
            )
            frames = sorted(Path(temp_dir).glob("frame-*.jpg"))
            if not frames:
                return ""
            images = [base64.b64encode(frame.read_bytes()).decode("ascii") for frame in frames]
            async with httpx.AsyncClient(timeout=180) as client:
                response = await client.post(
                    OLLAMA_BASE_URL + "/api/chat",
                    json={
                        "model": model,
                        "stream": False,
                        "messages": [{
                            "role": "user",
                            "content": (
                                "Describe the visual subject, actions, setting, and on-screen text "
                                "visible across these sampled video frames. Be factual; mark uncertainty "
                                "and do not invent names or events."
                            ),
                            "images": images,
                        }],
                    },
                )
                response.raise_for_status()
                return str(response.json().get("message", {}).get("content", "")).strip()[:6000]
    except Exception:
        # A missing/unavailable vision model must not make the upload pretend it saw the video.
        return ""


async def _make_metadata(path: Path, command: str, transcript: str, visual_summary: str) -> dict[str, Any]:
    context = (
        f"Spoken upload instruction: {command}\n"
        f"Video filename: {path.name}\n"
        f"Video transcript (may be empty): {transcript or '[not available]'}\n"
        f"Visual frame analysis (may be empty): {visual_summary or '[not available]'}"
    )
    prompt = (
        "Create accurate YouTube metadata for this video. Do not invent facts not supported by "
        "the filename, instruction, transcript, or visual frame analysis. Return ONLY valid JSON with keys title, "
        "description, tags (array of 8-15 strings), categoryId (one of 22, 23, 24, 25, 26, 27, 28). "
        "Write in the language used in the transcript/instruction. Title should be compelling "
        "and concise, description useful and searchable, hashtags may be included in description. "
        "Do not claim guaranteed rankings.\n\n" + context
    )
    async with httpx.AsyncClient(timeout=120) as client:
        response = await client.post(
            OLLAMA_BASE_URL + "/api/chat",
            json={"model": OLLAMA_MODEL, "stream": False,
                  "format": "json",
                  "messages": [{"role": "user", "content": prompt}]},
        )
        response.raise_for_status()
        raw = response.json().get("message", {}).get("content", "")
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(503, "Local AI ने valid SEO metadata नहीं बनाया। Ollama model जाँचें।") from exc
    title = str(data.get("title", "")).strip()[:100]
    description = str(data.get("description", "")).strip()[:5000]
    tags = [str(x).strip()[:100] for x in data.get("tags", []) if str(x).strip()][:15]
    category = str(data.get("categoryId", "22"))
    if not title or not description:
        raise HTTPException(503, "AI से title/description खाली मिले; upload रोक दिया गया।")
    if category not in {"22", "23", "24", "25", "26", "27", "28"}:
        category = "22"
    return {"title": title, "description": description, "tags": tags, "categoryId": category}


def _youtube_client():
    try:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
        from google_auth_oauthlib.flow import InstalledAppFlow
        from googleapiclient.discovery import build
    except ImportError as exc:
        raise HTTPException(503, "YouTube API packages missing. Install backend/requirements.txt again.") from exc

    credentials_dir = Path(os.getenv("GURI_YOUTUBE_CREDENTIALS_DIR", str(Path.home() / ".guri" / "youtube")))
    client_secret = Path(os.getenv("GURI_YOUTUBE_CLIENT_SECRET", str(credentials_dir / "client_secret.json")))
    token_file = Path(os.getenv("GURI_YOUTUBE_TOKEN", str(credentials_dir / "token.json")))
    if not client_secret.is_file():
        raise HTTPException(503, f"YouTube OAuth client file नहीं मिला: {client_secret}. README में setup देखें।")
    credentials_dir.mkdir(parents=True, exist_ok=True)
    credentials = None
    if token_file.is_file():
        credentials = Credentials.from_authorized_user_file(str(token_file), ["https://www.googleapis.com/auth/youtube.upload"])
    if credentials and credentials.expired and credentials.refresh_token:
        credentials.refresh(Request())
    if not credentials or not credentials.valid:
        flow = InstalledAppFlow.from_client_secrets_file(
            str(client_secret), ["https://www.googleapis.com/auth/youtube.upload"]
        )
        credentials = flow.run_local_server(port=0, prompt="consent")
    token_file.write_text(credentials.to_json(), encoding="utf-8")
    return build("youtube", "v3", credentials=credentials, cache_discovery=False)


def _upload(path: Path, metadata: dict[str, Any]) -> dict[str, str]:
    if path.stat().st_size > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "Video YouTube API की supported maximum file size से बड़ी है।")
    try:
        from googleapiclient.http import MediaFileUpload
        youtube = _youtube_client()
        request = youtube.videos().insert(
            part="snippet,status",
            body={
                "snippet": {
                    "title": metadata["title"],
                    "description": metadata["description"],
                    "tags": metadata["tags"],
                    "categoryId": metadata["categoryId"],
                },
                "status": {"privacyStatus": "unlisted"},
            },
            media_body=MediaFileUpload(str(path), chunksize=8 * 1024 * 1024, resumable=True),
        )
        response = None
        while response is None:
            _, response = request.next_chunk()
        video_id = response["id"]
        return {"video_id": video_id, "url": f"https://youtu.be/{video_id}"}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"YouTube upload failed: {type(exc).__name__}: {exc}") from exc


async def upload_from_voice_command(command: str) -> dict[str, Any]:
    path = find_video(command)
    transcript = await asyncio.to_thread(_transcribe, path)
    visual_summary = await _describe_video_frames(path)
    if not visual_summary:
        raise HTTPException(503, "Video का visual analysis नहीं हो पाया। Ollama में llava:7b install/run करें और FFmpeg जाँचें; upload रोक दिया गया।")
    metadata = await _make_metadata(path, command, transcript, visual_summary)
    uploaded = await asyncio.to_thread(_upload, path, metadata)
    return {
        "reply": (
            f"वीडियो Unlisted अपलोड हो गई।\nTitle: {metadata['title']}\n"
            f"YouTube link: {uploaded['url']}"
        ),
        "action": "youtube_upload",
        "status": "completed",
        "video": path.name,
        "duration_seconds": _duration_seconds(path),
        "metadata": metadata,
        **uploaded,
        "transcript_used": bool(transcript),
        "visual_analysis_used": True,
    }
