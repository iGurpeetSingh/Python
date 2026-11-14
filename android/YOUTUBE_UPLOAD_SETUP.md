# Guri Android YouTube Upload — Google Cloud setup

This setup is required once before the Android YouTube upload flow can authenticate against a real channel.

## 1. Enable the API

1. Open Google Cloud Console and select/create the project used for Guri.
2. Enable **YouTube Data API v3**.
3. Configure the OAuth consent screen. For private testing, choose the appropriate external/internal audience and add the Google account that owns the test YouTube channel as a test user when required.

## 2. Register this APK as an Android OAuth client

Create an OAuth 2.0 Client ID with application type **Android**.

For the APK built by GitHub Actions run 43593509600:

- Package name: `com.orbit.agent`
- SHA-1 signing certificate: `B1:E7:FC:17:E5:DE:1E:39:C8:18:E6:AB:97:BB:77:85:C4:E4:BE:49`

This SHA-1 is for the debug APK artifact from that specific CI build. A later CI build can have a different generated debug signing certificate. For repeatable future builds, configure a stable private signing keystore in GitHub Actions secrets and register that keystore's SHA-1 instead. Never commit a private signing keystore or password into the repository.

## 3. Permissions and first upload

1. Install the APK and allow microphone and video-library access when Android asks.
2. Start Guri and enable its voice service.
3. Say: **"गुरी, नौ मिनट तीन सेकंड वाली वीडियो YouTube पर अपलोड कर दो।"**
4. Guri searches indexed phone videos for a duration within 2.5 seconds of the requested duration. If more than one matches, choose the correct file. If none matches, select a video through Android's file picker.
5. On the first upload, choose the Google account for the YouTube channel and grant the `https://www.googleapis.com/auth/youtube.upload` permission.
6. The current implementation uploads as **Unlisted**. It reports the YouTube video URL after the API returns a video ID.

## Important

- An API key alone cannot authorize uploads. Uploads use the user's OAuth access token.
- Do not put a Google OAuth client secret or access token in source code.
- A successful APK build does not prove that Google Cloud OAuth is configured or that a live channel upload has succeeded. Complete an actual upload test after the setup above.
- The current Android metadata uses the selected file's name as the video title and a basic description. AI-generated titles/descriptions/tags are not yet connected to a content-analysis model.
