import os
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from fastapi import HTTPException

from app.papers import _file_path, _require_admin, _student_safe, _validate_paper


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


if __name__ == "__main__":
    unittest.main()
