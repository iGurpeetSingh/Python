import os
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException

from app.papers import (
    _attempt_result, _file_path, _parse_utc, _require_admin, _student_safe,
    _validate_paper, start_exam_attempt, submit_exam_attempt, grade_written_attempt,
)


def objective_payload(count: int = 50) -> dict:
    return {
        "title": "Science Mock Exam",
        "subject": "Science",
        "className": "Class 10",
        "startAt": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
        "durationMinutes": 60,
        "instructions": "Read each question carefully.",
        "questions": [
            {
                "prompt": f"Question {index + 1}",
                "options": ["A", "B", "C", "D"],
                "correctIndex": index % 4,
                "marks": 2,
            }
            for index in range(count)
        ],
    }


class PaperValidationTests(unittest.TestCase):
    def test_objective_paper_requires_50_questions_and_100_marks(self):
        cleaned = _validate_paper("objective", objective_payload())
        self.assertEqual(len(cleaned["questions"]), 50)
        self.assertEqual(sum(q["marks"] for q in cleaned["questions"]), 100)

    def test_objective_paper_rejects_wrong_question_count(self):
        with self.assertRaises(HTTPException) as raised:
            _validate_paper("objective", objective_payload(49))
        self.assertEqual(raised.exception.status_code, 422)

    def test_objective_paper_rejects_missing_answer_or_option(self):
        payload = objective_payload()
        payload["questions"][0]["options"] = ["A", "B", "C"]
        with self.assertRaises(HTTPException):
            _validate_paper("objective", payload)

        payload = objective_payload()
        payload["questions"][0].pop("correctIndex")
        with self.assertRaises(HTTPException):
            _validate_paper("objective", payload)

    def test_written_paper_requires_100_marks_and_allowed_values(self):
        payload = {
            "title": "History Written Exam",
            "subject": "History",
            "className": "Class 10",
            "startAt": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
            "durationMinutes": 90,
            "writtenQuestions": [
                {"prompt": f"Question {index + 1}", "marks": 4}
                for index in range(25)
            ],
        }
        cleaned = _validate_paper("written", payload)
        self.assertEqual(sum(q["marks"] for q in cleaned["writtenQuestions"]), 100)

    def test_written_paper_rejects_invalid_total(self):
        payload = {
            "title": "History Written Exam",
            "subject": "History",
            "className": "Class 10",
            "startAt": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
            "durationMinutes": 90,
            "writtenQuestions": [
                {"prompt": "Question one", "marks": 6},
                {"prompt": "Question two", "marks": 6},
            ],
        }
        with self.assertRaises(HTTPException) as raised:
            _validate_paper("written", payload)
        self.assertEqual(raised.exception.status_code, 422)

    def test_student_response_never_contains_answer_key_or_marking_guide(self):
        source = {
            "paperType": "objective",
            "published": True,
            "questions": [
                {
                    "prompt": "2 + 2?",
                    "options": ["3", "4", "5", "6"],
                    "correctIndex": 1,
                    "answerGuide": "Teacher only",
                    "marks": 2,
                }
            ],
            "writtenQuestions": [
                {"prompt": "Explain", "marks": 4, "answerGuide": "Private rubric"}
            ],
        }
        safe = _student_safe(source)
        self.assertNotIn("published", safe)
        self.assertNotIn("correctIndex", safe["questions"][0])
        self.assertNotIn("answerGuide", safe["questions"][0])
        self.assertNotIn("answerGuide", safe["writtenQuestions"][0])
        self.assertEqual(source["questions"][0]["correctIndex"], 1)

    def test_attempt_result_does_not_leak_receipt_or_saved_answers(self):
        attempt = {
            "id": "a" * 32,
            "paperType": "objective",
            "title": "Science Mock Exam",
            "studentName": "Student",
            "rollNumber": "R-001",
            "startedAt": datetime.now(timezone.utc).isoformat(),
            "submittedAt": datetime.now(timezone.utc).isoformat(),
            "status": "evaluated",
            "score": 80,
            "totalMarks": 100,
            "correctCount": 40,
            "incorrectCount": 5,
            "unansweredCount": 5,
            "receiptHash": "private-hash",
            "answers": {"0": 1},
            "paperSnapshot": {"questions": [{"correctIndex": 1}]},
        }
        public = _attempt_result(attempt)
        self.assertEqual(public["score"], 80)
        self.assertNotIn("receiptHash", public)
        self.assertNotIn("answers", public)
        self.assertNotIn("paperSnapshot", public)

    def test_schedule_parser_requires_timezone(self):
        with self.assertRaises(HTTPException):
            _parse_utc("2026-11-14T10:00:00")

    def test_path_only_accepts_known_exam_types(self):
        self.assertEqual(_file_path("objective"), "data/exams/objective.json")
        with self.assertRaises(HTTPException):
            _file_path("unlisted")

    def test_admin_token_is_required_and_constant_time_compared(self):
        with patch.dict(
            os.environ,
            {"PAPER_GITHUB_TOKEN": "github-test", "PAPER_ADMIN_TOKEN": "admin-secret"},
        ):
            with self.assertRaises(HTTPException) as raised:
                _require_admin("wrong-token")
            self.assertEqual(raised.exception.status_code, 401)
            settings = _require_admin("admin-secret")
            self.assertEqual(settings[3], "admin-secret")


class PaperAttemptFlowTests(unittest.IsolatedAsyncioTestCase):
    async def test_objective_attempt_is_started_and_scored_by_server(self):
        paper = objective_payload()
        paper["startAt"] = (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat()
        paper["published"] = True
        paper["paperType"] = "objective"
        storage_write = AsyncMock()

        with (
            patch("app.papers._settings", return_value=("github", "owner/repo", "main", "admin")),
            patch("app.papers._read_document", new=AsyncMock(return_value=(paper, "paper-sha"))),
            patch("app.papers._write_json_path", new=storage_write),
        ):
            started = await start_exam_attempt(
                "objective",
                {"studentName": "Test Student", "rollNumber": "T-001"},
            )

        self.assertTrue(started["ok"])
        self.assertEqual(started["paper"]["paperType"], "objective")
        self.assertNotIn("correctIndex", started["paper"]["questions"][0])
        saved_attempt = storage_write.await_args.args[1]
        attempt_id = started["attemptId"]
        receipt = started["receiptToken"]

        submit_write = AsyncMock()
        with (
            patch("app.papers._settings", return_value=("github", "owner/repo", "main", "admin")),
            patch("app.papers._read_json_path", new=AsyncMock(return_value=(saved_attempt, "attempt-sha"))),
            patch("app.papers._write_json_path", new=submit_write),
        ):
            result = await submit_exam_attempt(
                "objective",
                attempt_id,
                {"answers": {"0": 0}},
                receipt,
            )

        self.assertEqual(result["status"], "evaluated")
        self.assertEqual(result["score"], 2)
        self.assertEqual(result["correctCount"], 1)
        self.assertEqual(result["unansweredCount"], 49)

    async def test_written_attempt_waits_for_teacher_marks(self):
        now = datetime.now(timezone.utc)
        paper = {
            "title": "Written Mock Exam",
            "subject": "History",
            "className": "Class 10",
            "startAt": (now - timedelta(minutes=5)).isoformat(),
            "durationMinutes": 60,
            "paperType": "written",
            "published": True,
            "writtenQuestions": [
                {"prompt": f"Question {index + 1}", "marks": 4}
                for index in range(25)
            ],
        }
        start_write = AsyncMock()
        with (
            patch("app.papers._settings", return_value=("github", "owner/repo", "main", "admin")),
            patch("app.papers._read_document", new=AsyncMock(return_value=(paper, "paper-sha"))),
            patch("app.papers._write_json_path", new=start_write),
        ):
            started = await start_exam_attempt(
                "written",
                {"studentName": "Test Student", "rollNumber": "T-002"},
            )

        attempt = start_write.await_args.args[1]
        submit_write = AsyncMock()
        with (
            patch("app.papers._settings", return_value=("github", "owner/repo", "main", "admin")),
            patch("app.papers._read_json_path", new=AsyncMock(return_value=(attempt, "attempt-sha"))),
            patch("app.papers._write_json_path", new=submit_write),
        ):
            submitted = await submit_exam_attempt(
                "written",
                started["attemptId"],
                {"answers": {"0": "A detailed written answer"}},
                started["receiptToken"],
            )
        self.assertEqual(submitted["status"], "awaiting_marking")
        self.assertIsNone(submitted["score"])

        submitted_attempt = submit_write.await_args.args[1]
        grade_write = AsyncMock()
        marks = {str(index): 2 for index in range(25)}
        with (
            patch("app.papers._require_admin", return_value=("github", "owner/repo", "main", "admin")),
            patch("app.papers._read_json_path", new=AsyncMock(return_value=(submitted_attempt, "attempt-sha"))),
            patch("app.papers._write_json_path", new=grade_write),
        ):
            graded = await grade_written_attempt(
                started["attemptId"],
                {"marks": marks},
                "admin",
            )
        self.assertEqual(graded["status"], "graded")
        self.assertEqual(graded["score"], 50)


if __name__ == "__main__":
    unittest.main()
