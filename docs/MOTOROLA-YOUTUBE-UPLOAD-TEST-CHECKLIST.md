# Motorola YouTube Upload — Manual Acceptance Checklist

Run this only after the Android CI build and unit tests pass and the matching Android OAuth client is configured.

## Device setup

- [ ] Install the candidate APK on the target Motorola phone.
- [ ] Confirm app launches and existing voice assistant screens still work.
- [ ] Grant microphone permission.
- [ ] Grant video-library permission only when using duration search; alternatively use the system video picker.
- [ ] Confirm Guri Accessibility settings still work for the existing phone-control features.

## Video finder

- [ ] Say: "Guri, YouTube par 9 minute 3 second wali video upload karo."
- [ ] Confirm the app opens the YouTube upload flow.
- [ ] Confirm a 9:03 duration search returns only videos within the configured ±2 second tolerance.
- [ ] Confirm zero matches produces a clear message.
- [ ] Confirm multiple matches require an explicit user selection.
- [ ] Confirm picker fallback can select a video that is not visible to MediaStore.

## Metadata and OAuth

- [ ] Confirm filename-derived title and description are editable.
- [ ] Confirm the app displays Unlisted privacy before upload.
- [ ] Cancel the metadata dialog and confirm no upload starts.
- [ ] Continue to Google, select the intended account, and grant YouTube upload scope.
- [ ] Confirm denial/cancellation does not enqueue an upload.

## Upload

- [ ] Confirm a foreground notification appears during upload.
- [ ] Confirm progress changes while media is sent.
- [ ] Test a small local MP4 first.
- [ ] Confirm the final API response contains a video ID and the app displays the matching watch URL.
- [ ] Open the returned URL and verify the video is Unlisted and playable by the owner.
- [ ] Repeat with a larger video and a temporary network interruption.
- [ ] Confirm failures are reported as failures, not as completed uploads.

## Existing feature regression

- [ ] Confirm WhatsApp app launch still works.
- [ ] Confirm WhatsApp search/contact selection and message draft behavior still work.
- [ ] Confirm Instagram open/Reels controls still work.

## Sign-off

Do not mark Phase 4 complete until the actual Motorola device tests above are run and results are recorded. Do not merge the draft PR or call the APK release-ready before this sign-off.
