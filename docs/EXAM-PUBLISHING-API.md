# Exam Paper Publishing API (No Database)

The Guri FastAPI backend stores each paper as a JSON file in the private GitHub repository configured by the environment variables below. No SQL or NoSQL database is used for paper storage.

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
