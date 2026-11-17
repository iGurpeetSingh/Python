# Guri Android ChatGPT Image Batch

This Android module adds a local, on-device prompt queue for the installed ChatGPT Android app. It does not use the Windows Python server, Render, or a pairing code.

## Current workflow

1. Install and sign in to the official ChatGPT Android app.
2. Install Guri and enable **Guri Voice Assistant** under Android Accessibility settings.
3. Open Guri and tap **ChatGPT Image Batch**.
4. Paste up to 50 prompts. Separate prompts with a blank line.
5. Tap **Start Image Batch**. Guri launches ChatGPT and uses Accessibility actions to fill the composer and tap Send.
6. The queue advances only after the accessibility tree exposes a new, sufficiently large image node and no recognized image-generation indicator remains.
7. Use Pause, Resume, or Cancel from the batch screen.

## Important device requirements

- ChatGPT Android app must already be installed and signed in.
- Keep ChatGPT in the foreground while the batch is running; Android may pause accessibility observations when another app covers it.
- Keep the phone awake and connected to the internet.
- Enable Guri's Accessibility service. This is the permission that lets Guri inspect visible UI labels and operate the ChatGPT composer.
- Android, ChatGPT app versions, language, and UI experiments can change accessibility labels. If the composer, Send button, or generated image is not exposed to Accessibility, the queue pauses instead of blindly sending the next prompt.
- A 10-minute image confirmation timeout pauses the batch for manual inspection. Resume retries the current stage; Cancel clears the queue.
- Pause does not cancel an image already generating. Cancel also cannot retract a prompt already submitted to ChatGPT.
- This is UI automation of the consumer app, not an official ChatGPT API integration. Respect ChatGPT plan limits and applicable terms.

## Build APK

The GitHub Actions workflow **Build Guri Android APK** builds a debug APK for every push that changes `android/**`.

1. Open the repository's **Actions** tab.
2. Select **Build Guri Android APK**.
3. Open the latest successful run on the branch you want.
4. Download the **Guri-Android-debug** artifact.
5. Extract the artifact ZIP and install `app-debug.apk` on the Android phone. Allow installation from the browser/files app if Android asks.

For a local build, install JDK 17 and Gradle 8.9, then run:

```bash
gradle -p android :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Test checklist

- Confirm Guri can open the installed ChatGPT app.
- Confirm a single prompt is entered and submitted once.
- Confirm the accessibility tree exposes the generated image after completion.
- Test a two-prompt batch before attempting a larger queue.
- Test Pause during generation, Resume after pausing, and Cancel.
