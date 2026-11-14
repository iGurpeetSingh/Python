# Exam Paper Publishing API (No Database)

The Guri FastAPI backend stores each paper as a JSON file in the private GitHub repository configured by the environment variables below. No SQL or NoSQL database is used for paper storage.

## Deploy the API service

The repository already contains a Render Blueprint for the `guri-cloud` Docker service. It is configured to deploy after repository checks pass. To create or sync the service, open Render Dashboard → **New → Blueprint**, connect `iGurpeetSingh/Python`, choose the `main` branch, and use the root `render.yaml`.

After creating or syncing the Blueprint, open the `guri-cloud` service → **Environment** and add the two secret values below. Render does not automatically prompt for `sync: false` variables when syncing an already-existing service; add them manually in that case. See the [Render Blueprint specification](https://render.com/docs/blueprint-spec) and [deploy documentation](https://render.com/docs/deploys).

Once the service deploys, copy its exact `https://…onrender.com` URL. Check `GET /api/health` on that URL. Then set `VITE_EXAMS_API_URL` in the Coaching Website Vercel project's Production environment to that exact base URL and redeploy the site. The health response should include `paper_api_configured: true` after both secrets are saved.

## Required Render environment variables

- `PAPER_GITHUB_TOKEN`: GitHub fine-grained personal access token with **Contents: Read and write** permission for the storage repository.
- `PAPER_ADMIN_TOKEN`: A long, random secret used by the Android admin app when publishing or unpublishing.
- `PAPER_STORAGE_REPO`: Repository in `owner/name` format. The default is `iGurpeetSingh/Python`.
- `PAPER_STORAGE_BRANCH`: Branch to store files on. Default: `main`.

Do not put either secret in the Android APK source, website frontend, or a committed `.env` file. Enter the two secrets in Render's service Environment settings.

## Storage files

- `data/exams/objective.json`
- `data/exams/written.json`

The files are created on first publish. Unpublishing updates only the relevant file and keeps the paper for later republishing.

## Admin API

Send the `X-Paper-Admin-Token` header.

- `POST /api/admin/exams/objective/publish`
- `POST /api/admin/exams/written/publish`
- `POST /api/admin/exams/objective/unpublish`
- `POST /api/admin/exams/written/unpublish`

Objective papers are validated as 50 questions, four options each, two marks each, and 100 total marks. Written papers must total 100 marks and each question must carry 2, 4, or 6 marks.

Publish request JSON fields: `title`, `subject`, `className`, `startAt` (timezone-aware ISO date-time), `durationMinutes`, `instructions`, and either `questions` (objective) or `writtenQuestions` (written). Objective question objects need `prompt`, `options` (four strings), `correctIndex` (0-3), and `marks` (2). Written question objects need `prompt` and `marks`.

## Student API

- `GET /api/exams/published`
- `GET /api/exams/published/objective`
- `GET /api/exams/published/written`

Student responses contain only published papers. Objective answer keys and written marking guides are removed before returning data.

## Website configuration

Set `VITE_EXAMS_API_URL` to the deployed Render service base URL in the Coaching website's Vercel project environment, for example `https://your-guri-service.onrender.com`. Do not include a trailing path.

After setting or changing this variable, redeploy the Coaching website so Vite can bake the value into the frontend bundle.

## Android admin configuration

The Android Coaching & Exam Control screen must use the same Render base URL and the admin token from `PAPER_ADMIN_TOKEN`. Calls are sent over HTTPS. Never ship a fixed token in the APK; the admin must enter it in the app's protected settings.

## Android paste format

The Android admin screen accepts a plain-text question block (not a JSON paste).

Objective example format (repeat for all 50 questions):

```text
1. What is the chemical symbol for water?
A) CO2
B) H2O
C) O2
D) NaCl
Answer: B

2. ...
A) ...
B) ...
C) ...
D) ...
Answer: A
```

Each objective question must have four options and an explicit `Answer: A`, `Answer: B`, `Answer: C`, or `Answer: D` line. Every objective question is assigned 2 marks.

Written example format:

```text
1. Explain photosynthesis.
Marks: 6

2. Define evaporation.
Marks: 2
```

Continue until the written paper totals exactly 100 marks. Only the answer key and written marking guide are kept in the private GitHub JSON files; the student API strips those fields from its responses.

## Student exam lifecycle

1. The website calls `POST /api/exams/{objective|written}/attempts/start` with `studentName` and `rollNumber`. The service only starts an attempt during the scheduled exam window.
2. The response returns an `attemptId`, one-time `receiptToken`, the server deadline, and the safe student paper (without answer keys).
3. The website runs the countdown to the server deadline and posts answers to `POST /api/exams/{objective|written}/attempts/{attemptId}/submit` with the `X-Exam-Receipt-Token` header.
4. Objective answers are scored on the server. The response contains score and correct/incorrect/unanswered counts.
5. Written answers are stored with status `awaiting_marking`. A teacher can grade them through `POST /api/admin/exams/written/attempts/{attemptId}/grade` using the admin token and a `marks` object keyed by zero-based question index.
6. A private result receipt can be read from `GET /api/exams/{objective|written}/attempts/{attemptId}/result` with the same `X-Exam-Receipt-Token` header.
7. A teacher/admin can load submissions from `GET /api/admin/exams/{objective|written}/attempts` with `X-Paper-Admin-Token`. Written attempts can be graded using the grading endpoint above. The endpoint only returns data after admin-token verification.

Each attempt is stored as its own private JSON file under `data/exams/attempts/{objective|written}/`. No database is used. The student name and roll number are currently entered by the student in the exam page because this Coaching project has no existing authenticated student-session API. Do not treat self-entered details as verified identity.
