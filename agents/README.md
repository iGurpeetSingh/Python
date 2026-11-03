# Guri device agents

The cloud website only coordinates commands. A paired device agent must remain connected for Guri to control that device.

## Windows PC

Run `Start-Guri-Desktop-Agent.bat` on the PC that should open browser tabs and send ChatGPT messages. It installs required Python packages on first use, asks for the public Guri Cloud URL and the pairing code, then keeps a secure outbound WebSocket connection open.

The agent uses a separate Playwright browser profile on that PC. Sign in to ChatGPT manually in that browser the first time. The agent never reads your normal Chrome passwords or exports session cookies.

## Android phone

Install the Guri Android APK, enter the cloud URL and the same Render pairing code, and connect. Keep Guri active so it can receive device commands. Android Accessibility must be enabled for tap/scroll actions.

## Pairing code

Render generates `GURI_PAIRING_CODE`. Copy its value from Render Dashboard > guri-cloud service > Environment. Use the same value in the web page, Windows agent, and Android app. Do not share it publicly.
