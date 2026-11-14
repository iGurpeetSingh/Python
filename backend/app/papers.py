"""Paper publishing API backed by private GitHub JSON files (no database)."""
from __future__ import annotations

import asyncio
import base64
import copy
import hashlib
import json
import os
import re
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any
import math

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


async def _read_json_path(path: str) -> tuple[dict[str, Any] | None, str | None]:
    token, repository, branch, _ = _settings()
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
        raise HTTPException(502, "Stored JSON file is invalid.") from exc
    if not isinstance(document, dict):
        raise HTTPException(502, "Stored JSON file must contain an object.")
    return document, result.get("sha")


async def _write_json_path(
    path: str,
    document: dict[str, Any],
    message: str,
    sha: str | None = None,
) -> None:
    token, repository, branch, _ = _settings()
    encoded = base64.b64encode(
        json.dumps(document, ensure_ascii=False, indent=2).encode("utf-8")
    ).decode("ascii")
    body: dict[str, Any] = {
        "message": message,
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


async def _read_document(paper_type: str) -> tuple[dict[str, Any] | None, str | None]:
    return await _read_json_path(_file_path(paper_type))


async def _write_document(paper_type: str, document: dict[str, Any]) -> None:
    _, sha = await _read_document(paper_type)
    state = "Publish" if document.get("published") else "Unpublish"
    await _write_json_path(
        _file_path(paper_type),
        document,
        f"{state} {paper_type} exam paper",
        sha,
    )


def _attempt_path(paper_type: str, attempt_id: str) -> str:
    _file_path(paper_type)
    if not re.fullmatch(r"[a-f0-9]{32}", attempt_id):
        raise HTTPException(404, "Attempt not found.")
    return f"data/exams/attempts/{paper_type}/{attempt_id}.json"


def _is_whole_number(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return True
    return isinstance(value, float) and math.isfinite(value) and value.is_integer()


def _parse_utc(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError) as exc:
        raise HTTPException(502, "The stored exam schedule is invalid.") from exc
    if parsed.tzinfo is None:
        raise HTTPException(502, "The stored exam schedule must include a timezone.")
    return parsed.astimezone(timezone.utc)


def _attempt_result(document: dict[str, Any]) -> dict[str, Any]:
    # Never return the stored receipt hash, answer key snapshot, or submitted answers.
    return {
        "attemptId": document["id"],
        "paperType": document["paperType"],
        "title": document["title"],
        "studentName": document["studentName"],
        "rollNumber": document["rollNumber"],
        "startedAt": document["startedAt"],
        "submittedAt": document.get("submittedAt"),
        "status": document["status"],
        "score": document.get("score"),
        "totalMarks": document["totalMarks"],
        "correctCount": document.get("correctCount"),
        "incorrectCount": document.get("incorrectCount"),
        "unansweredCount": document.get("unansweredCount"),
    }

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

    raw_duration = cleaned.get("durationMinutes", 60)
    if not _is_whole_number(raw_duration):
        raise HTTPException(422, "durationMinutes must be a whole number.")
    duration = int(raw_duration)
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
            raw_answer = question.get("correctIndex")
            raw_marks = question.get("marks", 2)
            if (
                not _is_whole_number(raw_answer)
                or not _is_whole_number(raw_marks)
            ):
                raise HTTPException(422, f"Objective question {index} needs whole-number correctIndex and marks.")
            answer = int(raw_answer)
            marks = int(raw_marks)
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
            raw_marks = question.get("marks")
            if (
                not _is_whole_number(raw_marks)
            ):
                raise HTTPException(422, f"Written question {index} needs whole-number marks.")
            marks = int(raw_marks)
            if marks not in (2, 4, 6):
                raise HTTPException(422, f"Written question {index} must carry 2, 4, or 6 marks.")
            question["marks"] = marks
            total += marks
        if total != 100:
            raise HTTPException(422, "Written Exam total must be exactly 100 marks.")
    return cleaned



@router.post("/api/exams/{paper_type}/attempts/start")
async def start_exam_attempt(
    paper_type: str,
    payload: dict[str, Any] = Body(...),
):
    _settings()
    _file_path(paper_type)
    paper, paper_sha = await _read_document(paper_type)
    if not paper or paper.get("published") is not True:
        raise HTTPException(404, "This exam is not published.")

    now = datetime.now(timezone.utc)
    start_at = _parse_utc(paper["startAt"])
    deadline = start_at + timedelta(minutes=int(paper["durationMinutes"]))
    if now < start_at:
        raise HTTPException(409, "This exam has not started yet.")
    if now >= deadline:
        raise HTTPException(410, "The scheduled exam time has ended.")

    student_name = str(payload.get("studentName", "")).strip()
    roll_number = str(payload.get("rollNumber", "")).strip()
    if not student_name or len(student_name) > 120:
        raise HTTPException(422, "Student name is required (maximum 120 characters).")
    if not roll_number or len(roll_number) > 40:
        raise HTTPException(422, "Roll number is required (maximum 40 characters).")

    attempt_id = secrets.token_hex(16)
    receipt_token = secrets.token_urlsafe(32)
    total_marks = 100
    attempt = {
        "id": attempt_id,
        "paperType": paper_type,
        "paperTitle": paper["title"],
        "title": paper["title"],
        "studentName": student_name,
        "rollNumber": roll_number,
        "startedAt": now.isoformat(),
        "expiresAt": deadline.isoformat(),
        "submittedAt": None,
        "status": "in_progress",
        "score": None,
        "totalMarks": total_marks,
        "receiptHash": hashlib.sha256(receipt_token.encode("utf-8")).hexdigest(),
        "answers": {},
        "paperSnapshot": copy.deepcopy(paper),
        "paperStorageSha": paper_sha,
    }
    await _write_json_path(
        _attempt_path(paper_type, attempt_id),
        attempt,
        f"Start {paper_type} exam attempt {attempt_id}",
    )
    return {
        "ok": True,
        "attemptId": attempt_id,
        "receiptToken": receipt_token,
        "title": paper["title"],
        "paperType": paper_type,
        "startedAt": attempt["startedAt"],
        "expiresAt": attempt["expiresAt"],
        "paper": _student_safe(paper),
    }


@router.post("/api/exams/{paper_type}/attempts/{attempt_id}/submit")
async def submit_exam_attempt(
    paper_type: str,
    attempt_id: str,
    payload: dict[str, Any] = Body(...),
    x_exam_receipt_token: str | None = Header(default=None),
):
    _settings()
    path = _attempt_path(paper_type, attempt_id)
    attempt, sha = await _read_json_path(path)
    if not attempt:
        raise HTTPException(404, "Exam attempt not found.")
    expected = attempt.get("receiptHash", "")
    if not x_exam_receipt_token or not secrets.compare_digest(
        hashlib.sha256(x_exam_receipt_token.encode("utf-8")).hexdigest(), expected
    ):
        raise HTTPException(401, "Exam receipt token is missing or invalid.")
    if attempt.get("submittedAt"):
        return {"ok": True, **_attempt_result(attempt)}
    if datetime.now(timezone.utc) >= _parse_utc(attempt["expiresAt"]):
        raise HTTPException(410, "The exam time has ended. The attempt can no longer be submitted.")

    answers = payload.get("answers", {})
    if not isinstance(answers, dict):
        raise HTTPException(422, "answers must be an object indexed by question number.")
    paper = attempt.get("paperSnapshot")
    if not isinstance(paper, dict):
        raise HTTPException(502, "The paper snapshot for this attempt is missing.")

    submitted: dict[str, Any] = {}
    if paper_type == "objective":
        questions = paper.get("questions") or []
        correct = 0
        incorrect = 0
        unanswered = 0
        for index, question in enumerate(questions):
            value = answers.get(str(index), answers.get(index))
            if value is None or value == "":
                unanswered += 1
                continue
            if isinstance(value, bool) or not isinstance(value, int) or value not in range(4):
                raise HTTPException(422, f"Answer for question {index + 1} must be an option index from 0 to 3.")
            submitted[str(index)] = value
            if value == int(question["correctIndex"]):
                correct += 1
            else:
                incorrect += 1
        score = correct * 2
        attempt.update({
            "answers": submitted,
            "score": score,
            "correctCount": correct,
            "incorrectCount": incorrect,
            "unansweredCount": unanswered,
            "status": "evaluated",
        })
    else:
        questions = paper.get("writtenQuestions") or []
        for index, _question in enumerate(questions):
            value = answers.get(str(index), answers.get(index, ""))
            if not isinstance(value, str):
                raise HTTPException(422, f"Answer for question {index + 1} must be text.")
            if len(value) > 20000:
                raise HTTPException(422, f"Answer for question {index + 1} is too long.")
            submitted[str(index)] = value.strip()
        attempt.update({
            "answers": submitted,
            "score": None,
            "status": "awaiting_marking",
        })

    now = datetime.now(timezone.utc)
    attempt["submittedAt"] = now.isoformat()
    attempt["paperSnapshot"] = paper
    await _write_json_path(
        path,
        attempt,
        f"Submit {paper_type} exam attempt {attempt_id}",
        sha,
    )
    return {"ok": True, **_attempt_result(attempt)}


@router.get("/api/exams/{paper_type}/attempts/{attempt_id}/result")
async def get_exam_attempt_result(
    paper_type: str,
    attempt_id: str,
    x_exam_receipt_token: str | None = Header(default=None),
):
    _settings()
    attempt, _ = await _read_json_path(_attempt_path(paper_type, attempt_id))
    if not attempt:
        raise HTTPException(404, "Exam attempt not found.")
    expected = attempt.get("receiptHash", "")
    if not x_exam_receipt_token or not secrets.compare_digest(
        hashlib.sha256(x_exam_receipt_token.encode("utf-8")).hexdigest(), expected
    ):
        raise HTTPException(401, "Exam receipt token is missing or invalid.")
    return {"ok": True, **_attempt_result(attempt)}


@router.get("/api/admin/exams/{paper_type}/attempts")
async def list_exam_attempts(
    paper_type: str,
    x_paper_admin_token: str | None = Header(default=None),
):
    token, repository, branch, _ = _require_admin(x_paper_admin_token)
    _file_path(paper_type)
    directory = f"data/exams/attempts/{paper_type}"
    url = f"{GITHUB_API}/repos/{repository}/contents/{directory}"
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.get(
            url, headers=_github_headers(token), params={"ref": branch}
        )
    if response.status_code == 404:
        return {"items": []}
    if response.status_code != 200:
        raise HTTPException(502, "Could not list exam attempts from GitHub storage.")
    entries = response.json()
    if not isinstance(entries, list):
        raise HTTPException(502, "Exam attempts storage path is not a directory.")
    paths = [
        item.get("path")
        for item in entries
        if isinstance(item, dict)
        and item.get("type") == "file"
        and str(item.get("name", "")).endswith(".json")
    ][:100]
    async def load_attempt(path: str):
        document, _ = await _read_json_path(path)
        if not document:
            return None
        snapshot = document.get("paperSnapshot") or {}
        questions = snapshot.get("questions") if paper_type == "objective" else snapshot.get("writtenQuestions")
        return {
            **_attempt_result(document),
            "answers": document.get("answers", {}),
            "questions": questions or [],
            "gradedAt": document.get("gradedAt"),
            "awardedMarks": document.get("awardedMarks", {}),
        }
    loaded = await asyncio.gather(*(load_attempt(path) for path in paths))
    items = [item for item in loaded if item is not None]
    items.sort(key=lambda item: item.get("startedAt", ""), reverse=True)
    return {"items": items}


@router.post("/api/admin/exams/written/attempts/{attempt_id}/grade")
async def grade_written_attempt(
    attempt_id: str,
    payload: dict[str, Any] = Body(...),
    x_paper_admin_token: str | None = Header(default=None),
):
    _require_admin(x_paper_admin_token)
    path = _attempt_path("written", attempt_id)
    attempt, sha = await _read_json_path(path)
    if not attempt:
        raise HTTPException(404, "Written exam attempt not found.")
    if attempt.get("status") not in {"awaiting_marking", "graded"}:
        raise HTTPException(409, "This written attempt is not awaiting evaluation.")
    paper = attempt.get("paperSnapshot") or {}
    questions = paper.get("writtenQuestions") or []
    awarded = payload.get("marks", {})
    if not isinstance(awarded, dict):
        raise HTTPException(422, "marks must be an object indexed by question number.")
    normalized: dict[str, int] = {}
    total = 0
    for index, question in enumerate(questions):
        raw = awarded.get(str(index), 0)
        if isinstance(raw, bool) or not isinstance(raw, (int, float)) or not math.isfinite(raw):
            raise HTTPException(422, f"Marks for question {index + 1} must be numeric.")
        mark = int(raw)
        if mark != raw or mark < 0 or mark > int(question["marks"]):
            raise HTTPException(422, f"Marks for question {index + 1} must be a whole number from 0 to {question['marks']}.")
        normalized[str(index)] = mark
        total += mark
    attempt["awardedMarks"] = normalized
    attempt["score"] = total
    attempt["status"] = "graded"
    attempt["gradedAt"] = datetime.now(timezone.utc).isoformat()
    await _write_json_path(path, attempt, f"Grade written exam attempt {attempt_id}", sha)
    return {"ok": True, **_attempt_result(attempt)}

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
