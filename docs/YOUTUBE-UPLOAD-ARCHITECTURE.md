# YouTube Upload on Android — Technical Architecture

Status: design baseline; no upload implementation is included in this document.

## Product requirement

A user speaks a Hindi/Hinglish command such as: "Guri, 9 minute 3 second wali video YouTube par upload karo, Unlisted rakhna." The Android app running on the Motorola phone must locate the intended local video, prepare editable metadata, obtain explicit user approval, upload through the official YouTube Data API, and report the returned video URL and final status.

The first delivery is phone-only. It must not depend on a Windows PC, Termux, Render, or a paid hosting service.

## Repository findings used by this design

- Existing Android application: `android/`, application ID `com.orbit.agent`, min SDK 26, target SDK 35, Kotlin/JVM 17.
- Existing voice and command routing: `GuriBackgroundVoiceService.kt` and `MainActivity.kt`.
- Existing accessibility interaction: `AgentAccessibilityService.kt`.
- Existing Android network dependency: OkHttp 4.12.0.
- Existing Android APK GitHub Actions workflow: `.github/workflows/android-apk.yml`.
- The current Android manifest has microphone, foreground microphone service, notification, Internet, and package-query declarations. It does not declare video-media read permission.
- The Android app currently has YouTube app-launch handling, but repository code search did not find a YouTube Data API upload implementation, OAuth flow, MediaStore duration finder, or SEO metadata generator.
- The Python FastAPI backend and React frontend are separate existing PC/cloud workflows. The current Android README describes a standalone phone companion and says it does not require a backend URL or pairing code for local phone commands. The YouTube MVP should therefore execute natively in Android instead of depending on a paid or always-on server.
- Existing APK workflow uses Java 17 and Gradle 8.9 and builds `assembleDebug`. There is no Gradle wrapper checked into the Android project; CI installs Gradle explicitly.

## Architecture decision

Implement the YouTube feature as native Kotlin modules inside the existing Android application. Keep the existing Python backend unchanged for this feature. This avoids adding a server dependency, avoids embedding a Python runtime in the APK, and lets Android use its native media, authorization, lifecycle, and background-work APIs.

### Runtime flow

1. Voice parser converts the spoken command into a typed `UploadVideoCommand` containing a requested duration (for example, 543 seconds), privacy status (default must be explicitly Unlisted for this workflow), and any user-provided topic/context.
2. Video repository queries Android MediaStore for video rows, including content URI, display name, duration, size, and date. Match the requested duration with a small configurable tolerance and return candidates rather than silently selecting when there are multiple plausible matches.
3. If broad media access is not granted or the result is ambiguous, ask the user to grant the Android video permission or choose the file through the system Photo Picker / Storage Access Framework. Keep the selected content URI; do not assume a raw filesystem path.
4. Metadata builder creates a draft title, description, and tags from the filename plus explicit user context and (where available) a transcript. Show the draft for review and editing. Do not claim visual/content understanding if no transcript or on-device video model has actually analysed the file.
5. User reviews the selected file, metadata, and privacy setting. Require a clear confirmation before upload.
6. YouTube authorization module requests only `https://www.googleapis.com/auth/youtube.upload` using Google's Android authorization APIs. No Google account password or client secret is stored in the app.
7. Upload worker uses YouTube Data API v3 `videos.insert` with resumable media upload. Set `snippet.title`, `snippet.description`, `snippet.tags`, `snippet.categoryId` where known, and `status.privacyStatus = unlisted` explicitly. Never rely on the API default privacy status.
8. Persist upload progress and resumable session state using WorkManager and local storage. Retry transient network/server failures with bounded exponential backoff. Do not retry permanent authorization, quota, or validation errors blindly.
9. On a successful API response, persist video ID and show `https://www.youtube.com/watch?v={videoId}`. Only report success after the API returns a video resource with an ID.

## Proposed Android package layout

Keep the current package `com.orbit.agent`; add a focused subpackage:

```
android/app/src/main/java/com/orbit/agent/youtube/
  YouTubeCommandParser.kt
  VideoCandidate.kt
  LocalVideoRepository.kt
  VideoMatchPolicy.kt
  VideoMetadata.kt
  SeoMetadataBuilder.kt
  YouTubeAuthorizationManager.kt
  YouTubeApiClient.kt
  ResumableUploadClient.kt
  YouTubeUploadWorker.kt
  UploadRepository.kt
  UploadStatus.kt
  YouTubeUploadCoordinator.kt
```

Add tests under `android/app/src/test/java/com/orbit/agent/youtube/` for duration parsing/matching, metadata validation, privacy-status serialization, resumable-upload state transitions, and error classification.

## Android permissions and media selection

- API 33+: declare and request `READ_MEDIA_VIDEO` only when the feature needs to scan the library.
- API 26–32: use the legacy `READ_EXTERNAL_STORAGE` permission where required by the platform version.
- API 34+: account for selected-photo/video access (`READ_MEDIA_VISUAL_USER_SELECTED`) and partial library grants.
- Provide a system picker fallback. A picker URI is sufficient for uploading and avoids requiring broad library access when the user chooses a single file.
- Use `ContentResolver` streams / file descriptors for upload; do not convert a `content://` URI into a guessed filesystem path.

## OAuth and YouTube API

- Enable YouTube Data API v3 in the user's Google Cloud project.
- Register an Android OAuth client matching application ID `com.orbit.agent` and the signing certificate SHA-1 used for the installed build.
- Use Google's Android authorization APIs and request the narrow upload scope `https://www.googleapis.com/auth/youtube.upload`.
- Keep the user in control of account selection and consent. Handle Play Services unavailable, consent denied, revoked access, and expired token states.
- Use `videos.insert` with resumable upload and explicitly set privacy to `unlisted`.
- Current YouTube documentation states a default 100 `videos.insert` calls/day in the separate video-upload quota bucket (1 quota unit per call). Quotas can change and must be checked in the Cloud Console.
- Current YouTube revision history (2026-11-02) says uploads from unverified API projects are no longer restricted to private-only viewing. Still, the app must set the requested privacy status explicitly and comply with API policies.

## SEO metadata scope for a zero-cost MVP

The MVP must not depend on a paid generative-AI API or a cloud server. First version:
- Use the filename, spoken topic/context, and user-provided keywords to generate a structured editable draft.
- If a reliable transcript is available, extract relevant phrases and use them as additional signals.
- Add a small deterministic title/description/tag template and validation (length limits, duplicate tags, empty content).
- Clearly label generated metadata as a draft. User can edit it before upload.

True semantic video understanding (visual scenes, objects, context) is a separate capability requiring an on-device model or an external inference service. It is not present in the current repository and is not promised by the MVP.

## Error handling and safety

- Never upload until the user confirms the exact file, account, title, and Unlisted privacy setting.
- Do not log OAuth tokens, authorization headers, private file contents, or raw media.
- Store credentials/tokens only through platform-supported secure storage; never commit credentials or put them in source control.
- Handle network loss, HTTP 401/403, quota exhaustion, invalid metadata, upload session expiry, insufficient storage, and user cancellation as distinct states.
- A partial upload is not a successful upload. The app must verify the final response/video ID before showing a share URL.

## Acceptance criteria

1. A Hindi/Hinglish command containing a duration such as 9:03 is parsed as 543 seconds.
2. The app finds all matching local videos and handles zero, one, and multiple candidates safely.
3. The selected file can be streamed from a MediaStore or picker content URI.
4. The user can connect the intended Google account through Android authorization and grant the upload scope.
5. The metadata preview is editable and the user can confirm or cancel.
6. A confirmed upload uses resumable `videos.insert`, explicitly sends `privacyStatus=unlisted`, and survives recoverable network interruptions.
7. The app reports success only after receiving the YouTube video ID and displays the resulting URL.
8. Unit tests pass, CI produces a debug APK, and the APK is installed and manually tested on the target Motorola phone.

## Official references

- YouTube `videos.insert`: https://developers.google.com/youtube/data/reference/rest/v3/videos/insert
- YouTube resumable upload guide: https://developers.google.com/youtube/data/guides/uploading_a_video
- YouTube authorization and scopes: https://developers.google.com/youtube/data/guides/authentication
- YouTube quota calculator: https://developers.google.com/youtube/data/determine_quota_cost
- Android YouTube API quickstart: https://developers.google.com/youtube/data/quickstart/android
- Android media permissions: https://developer.android.com/reference/android/Manifest.permission
- Google Android authorization API: https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient


## Implementation status — 2026-11-15

The first native Android implementation has now been added on branch `feature/youtube-upload-foundation`:

- Hindi/Hinglish duration parser supports `09:03`, `9 minute 3 second`, and Hindi number words.
- Local MediaStore duration search returns candidate videos and asks the user to choose if there are multiple matches.
- A system document picker is available as a fallback.
- A deterministic, editable title/description/tag draft is generated before upload.
- Google Android AuthorizationClient requests only the YouTube upload scope.
- Access tokens are stored using AndroidX EncryptedSharedPreferences before the background worker uses them.
- WorkManager runs the upload with a foreground notification.
- The upload client starts a YouTube resumable session and sends media in 8 MiB chunks, explicitly setting privacy to Unlisted.
- Voice commands containing YouTube + upload are routed to the upload screen.
- Unit tests cover duration parsing and metadata validation.
- The APK workflow now runs unit tests before assembling the debug APK.

### Remaining validation gates

1. CI must pass on the latest branch commit; earlier intermediate commits failed while source wiring was being corrected.
2. The owner must create an Android OAuth client in Google Cloud for package `com.orbit.agent` and the SHA-1 of the signing certificate used by the installed APK, and enable YouTube Data API v3.
3. The owner must complete Google account consent on the phone.
4. Install the resulting APK on the target Motorola device and test media permission, 9:03 duration search, metadata editing, OAuth consent, upload, network interruption, and the final YouTube watch URL.
5. Only after these gates pass should the draft PR be merged and the APK called release-ready.

### Known limitations before device validation

- The current MVP uses filename and user-provided context for SEO drafts; it does not perform visual scene understanding or automatic speech transcription.
- Upload is chunked and uses YouTube's resumable protocol, but the session URI/byte offset is not yet durably checkpointed across Android process death. A process kill may require starting a new upload.
- Long uploads may need renewed authorization if the short-lived access token expires.
- CI can compile and run JVM unit tests, but it cannot validate Google consent, YouTube account/channel eligibility, real video-library access, or Motorola OEM background behavior.


## Latest CI result

- Latest verified Android build commit: `cc5fe01fa4090c8d869501d193043520ffb5fff9`.
- GitHub Actions run: https://github.com/iGurpeetSingh/Python/actions/runs/43693399426
- Result: `success`.
- `:app:testDebugUnitTest`: passed.
- `:app:assembleDebug`: passed.
- APK artifact: `Guri-Android-debug` (4,760,289-byte ZIP containing `app-debug.apk`).
- The artifact was downloaded and the APK file was verified as an Android package.
- This CI success does not replace OAuth consent or the required Motorola end-to-end acceptance test.
