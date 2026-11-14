"""Paper publishing API backed by private GitHub JSON files (no database)."""
from __future__ import annotations

import base64
import copy
import json
import os
import re
import secrets
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import APIRouter, Header, HTTPException, Body

router = APIRouter(tags=["papers"])

GITHUB_API = "https://api.github.com"
PAPER_TYPES = {"objective", "written"}


def _settings() -> tuple[str, str, str, str]:
    token = os.getenv("PAPER_GITHUB_TOKEN", "").strip()
    repository = os.getenv("PAPER_STORAGE_REPO", "iGurpeetSingh/Python").strip()
    branch = os.getenv("PAPER_STORAGE_BRANCH", "main").strip()
    admin_token = os.getenv("PAPER_ADMIN_TOKEN", "").strip()
    if not token or not admin_token:
        raise HTTPException(
            status_code=503,
            detail=(
                "Paper API setup incomplete. Set PAPER_GITHUB_TOKEN and "
                "PAPER_ADMIN_TOKEN in the backend hosting environment."
            ),
        )
    if not re.fullmatch(r"[^/]+/[^/]+", repository):
        raise HTTPException(503, "PAPER_STORAGE_REPO must be owner/repository.")
    return token, repository, branch, admin_token


def _require_admin(supplied: str | None) -> tuple[str, str, str, str]:
    settings = _settings()
    expected = settings[3]
    if not supplied or not secrets.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="Admin token missing or invalid.")
    return settings


def _file_path(paper_type: str) -> str:
    if paper_type not in PAPER_TYPES:
        raise HTTPException(status_code=404, detail="Paper type must be objective or written.")
    return f"data/exams/{paper_type}.json"


def _github_headers(token: str) -> dict[str, str]:
    return {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
    }


async def _read_document(paper_type: str) -> tuple[dict[str, Any] | None, str | None]:
    token, repository, branch, _ = _settings()
    path = _file_path(paper_type)
    url = f"{GITHUB_API}/repos/{repository}/contents/{path}"
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.get(
            url, headers=_github_headers(token), params={"ref": branch}
        )
    if response.status_code == 404:
        return None, None
    if response.status_code != 200:
        raise HTTPException(
            status_code=502,
            detail=f"GitHub storage read failed ({response.status_code}). Check the GitHub token and repository permissions.",
        )
    result = response.json()
    try:
        raw = base64.b64decode(result["content"]).decode("utf-8")
        document = json.loads(raw)
    except (KeyError, ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HTTPException(502, "Stored paper JSON is invalid.") from exc
    if not isinstance(document, dict):
        raise HTTPException(502, "Stored paper JSON must be an object.")
    return document, result.get("sha")


async def _write_document(paper_type: str, document: dict[str, Any]) -> None:
    token, repository, branch, _ = _settings()
    path = _file_path(paper_type)
    _, sha = await _read_document(paper_type)
    encoded = base64.b64encode(
        json.dumps(document, ensure_ascii=False, indent=2).encode("utf-8")
    ).decode("ascii")
    body: dict[str, Any] = {
        "message": f"{'Publish' if document.get('published') else 'Unpublish'} {paper_type} exam paper",
        "content": encoded,
        "branch": branch,
    }
    if sha:
        body["sha"] = sha
    url = f"{GITHUB_API}/repos/{repository}/contents/{path}"
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.put(url, headers=_github_headers(token), json=body)
    if response.status_code not in (200, 201):
        detail = "GitHub storage write failed."
        try:
            detail = response.json().get("message", detail)
        except ValueError:
            pass
        raise HTTPException(
            status_code=502,
            detail=f"{detail} Confirm the token has Contents: Read and write permission.",
        )


def _validate_paper(paper_type: str, payload: dict[str, Any]) -> dict[str, Any]:
    if paper_type not in PAPER_TYPES:
        raise HTTPException(404, "Paper type must be objective or written.")
    cleaned = dict(payload)
    for field in ("title", "subject", "className", "startAt"):
        value = cleaned.get(field)
        if not isinstance(value, str) or not value.strip():
            raise HTTPException(422, f"{field} is required.")
        cleaned[field] = value.strip()

    try:
        start = datetime.fromisoformat(cleaned["startAt"].replace("Z", "+00:00"))
        if start.tzinfo is None:
            raise ValueError("timezone required")
    except ValueError as exc:
        raise HTTPException(422, "startAt must be an ISO date-time with timezone.") from exc

    try:
        duration = int(cleaned.get("durationMinutes", 60))
    except (TypeError, ValueError) as exc:
        raise HTTPException(422, "durationMinutes must be a number.") from exc
    if duration < 1 or duration > 300:
        raise HTTPException(422, "durationMinutes must be between 1 and 300.")
    cleaned["durationMinutes"] = duration
    cleaned["instructions"] = str(cleaned.get("instructions", ""))

    if paper_type == "objective":
        questions = cleaned.get("questions")
        if not isinstance(questions, list) or len(questions) != 50:
            raise HTTPException(422, "Objective Exam must contain exactly 50 questions.")
        total = 0
        for index, question in enumerate(questions, start=1):
            if not isinstance(question, dict) or not str(question.get("prompt", "")).strip():
                raise HTTPException(422, f"Objective question {index} is missing its prompt.")
            options = question.get("options")
            if not isinstance(options, list) or len(options) != 4:
                raise HTTPException(422, f"Objective question {index} must have exactly 4 options.")
            if not all(str(option).strip() for option in options):
                raise HTTPException(422, f"Objective question {index} has an empty option.")
            try:
                answer = int(question.get("correctIndex"))
                marks = int(question.get("marks", 2))
            except (TypeError, ValueError) as exc:
                raise HTTPException(422, f"Objective question {index} needs correctIndex and marks.") from exc
            if answer not in range(4) or marks != 2:
                raise HTTPException(422, f"Objective question {index} needs a valid answer and 2 marks.")
            question["correctIndex"] = answer
            question["marks"] = marks
            total += marks
        if total != 100:
            raise HTTPException(422, "Objective Exam total must be 100 marks.")
    else:
        questions = cleaned.get("writtenQuestions")
        if not isinstance(questions, list) or not questions:
            raise HTTPException(422, "Written Exam must contain writtenQuestions.")
        total = 0
        for index, question in enumerate(questions, start=1):
            if not isinstance(question, dict) or not str(question.get("prompt", "")).strip():
                raise HTTPException(422, f"Written question {index} is missing its prompt.")
            try:
                marks = int(question.get("marks"))
            except (TypeError, ValueError) as exc:
                raise HTTPException(422, f"Written question {index} needs marks.") from exc
            if marks not in (2, 4, 6):
                raise HTTPException(422, f"Written question {index} must carry 2, 4, or 6 marks.")
            question["marks"] = marks
            total += marks
        if total != 100:
            raise HTTPException(422, "Written Exam total must be exactly 100 marks.")
    return cleaned


def _student_safe(document: dict[str, Any]) -> dict[str, Any]:
    public = copy.deepcopy(document)
    public.pop("published", None)
    public.pop("publishedAt", None)
    public.pop("adminNotes", None)
    for question in public.get("questions", []) or []:
        if isinstance(question, dict):
            for key in ("correctIndex", "correctAnswer", "answer", "answerGuide", "explanation", "solution"):
                question.pop(key, None)
    for question in public.get("writtenQuestions", []) or []:
        if isinstance(question, dict):
            for key in ("answerGuide", "modelAnswer", "expectedAnswer", "markingScheme", "solution"):
                question.pop(key, None)
    return public


@router.get("/api/exams/published")
async def get_published_papers():
    # Public response contains only published papers and never includes answer keys.
    _settings()
    items = []
    for paper_type in ("objective", "written"):
        document, _ = await _read_document(paper_type)
        if document and document.get("published") is True:
            items.append(_student_safe(document))
    return {"items": items}


@router.get("/api/exams/published/{paper_type}")
async def get_published_paper(paper_type: str):
    _settings()
    _file_path(paper_type)
    document, _ = await _read_document(paper_type)
    if not document or document.get("published") is not True:
        raise HTTPException(404, "This exam is not published.")
    return _student_safe(document)


@router.post("/api/admin/exams/{paper_type}/publish")
async def publish_paper(
    paper_type: str,
    payload: dict[str, Any] = Body(...),
    x_paper_admin_token: str | None = Header(default=None),
):
    _require_admin(x_paper_admin_token)
    paper = _validate_paper(paper_type, payload)
    document = {
        **paper,
        "schemaVersion": 1,
        "paperType": paper_type,
        "published": True,
        "publishedAt": datetime.now(timezone.utc).isoformat(),
    }
    await _write_document(paper_type, document)
    return {
        "ok": True,
        "paperType": paper_type,
        "published": True,
        "title": document["title"],
        "message": f"{paper_type.title()} Exam published successfully.",
    }


@router.post("/api/admin/exams/{paper_type}/unpublish")
async def unpublish_paper(
    paper_type: str,
    x_paper_admin_token: str | None = Header(default=None),
):
    _require_admin(x_paper_admin_token)
    document, _ = await _read_document(paper_type)
    if not document:
        raise HTTPException(404, "No saved paper exists for this exam type.")
    document["published"] = False
    document["unpublishedAt"] = datetime.now(timezone.utc).isoformat()
    await _write_document(paper_type, document)
    return {"ok": True, "paperType": paper_type, "published": False}
