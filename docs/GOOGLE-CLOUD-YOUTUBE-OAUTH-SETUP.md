# Google Cloud setup for Guri YouTube Upload

The Android implementation requests the narrow YouTube upload scope:
`https://www.googleapis.com/auth/youtube.upload`.

## 1. Create/select a Google Cloud project

1. Open https://console.cloud.google.com/
2. Create a project (or select the project intended for Guri).
3. Open APIs & Services → Library.
4. Find **YouTube Data API v3** and click Enable.

## 2. Configure OAuth consent

1. Open Google Auth Platform → Branding (or OAuth consent screen in the older console UI).
2. Enter the app name, user-support email, and developer contact email.
3. If the app is in Testing mode, add the Google account that will authorize the upload under Test users.
4. Save the configuration.
5. Do not request unrelated scopes. The app requests only YouTube upload access.

## 3. Get the Android signing SHA-1

The OAuth Android client is bound to both the package name and the SHA-1 certificate fingerprint.

From the repository root, run:

```powershell
gradle -p android :app:signingReport
```

If you use Android Studio, open the Gradle tool window and run `app > Tasks > android > signingReport`.

Copy the SHA-1 shown for the exact build variant you will install (normally `debug` for the first test). If the APK is later signed with a different keystore, create/update the Android OAuth client for that release certificate too.

## 4. Create the Android OAuth client

1. Open Google Auth Platform → Clients (or APIs & Services → Credentials).
2. Choose Create Client → Android.
3. Package name: `com.orbit.agent`.
4. SHA-1 certificate fingerprint: paste the value from the previous step.
5. Create the client.

This is an Android OAuth client. Do not create a Web client for this native flow and do not put a client secret in the APK.

## 5. First-device authorization test

1. Install the Guri APK on the Motorola phone.
2. Open Guri and grant Microphone permission if requested.
3. Use **Duration से वीडियो खोजें** or **YouTube Upload**.
4. Select the exact video and review the title/description.
5. Continue to Google and choose the account whose YouTube channel should receive the upload.
6. Approve the YouTube upload permission.
7. Confirm the upload is Unlisted.

## Important

- The app must never ask for or store the user's Google password.
- Do not paste OAuth tokens, passwords, client secrets, or signing keystore passwords into chat or commit them to GitHub.
- A debug APK built on a different machine/CI runner can have a different debug signing certificate. Register the SHA-1 for the exact APK being tested. For a stable production release, use the owner's release keystore and register its SHA-1.
- If the consent screen is in Testing mode, only accounts listed as test users can authorize it.
- Successful OAuth consent does not guarantee channel eligibility or successful upload. The app must receive a YouTube video ID from the API before reporting success.


## CI debug APK signing fingerprint (the APK attached to the 2026-11-15 run)

For the exact APK built by GitHub Actions run
https://github.com/iGurpeetSingh/Python/actions/runs/43693850049

use this SHA-1 when creating the Android OAuth client:

```
4F:E3:2A:EF:78:8A:97:E7:D8:4F:4A:A5:27:40:22:16:5E:EF:7C:03
```

This fingerprint matches the debug keystore generated within that CI run and used to sign that run's APK artifact. If you install an APK built on another machine or a later CI run with a different keystore, its fingerprint can differ; register the fingerprint for the exact APK you install.
