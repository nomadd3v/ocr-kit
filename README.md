# ocr-kit

A free, on-device OCR server that turns an old Android phone into a private
text-recognition endpoint for AI agent tooling — screenshots, menus,
receipts, scanned documents — without paying for a cloud vision API call.

Point an AI agent, script, or MCP client at the phone's IP address, send it
images, and get back recognized text. The recognition itself runs entirely
on the phone using Google's on-device ML Kit; nothing is uploaded anywhere
except the response sent back to whoever asked.

## Why this exists

Cloud OCR / vision APIs charge per image and send your images to a third
party. If you have a spare Android phone lying around, it can do the same
job for free, on your own network, with the image data never leaving the
device.

## Requirements

- An Android phone running **Android 10 (API 29) or newer** — this is
  intentionally low, so an old phone in a drawer almost always qualifies.
- That phone and whatever will call the OCR server (your computer, your
  agent, an MCP client) need to be reachable over the same network — the
  phone does not need internet access at all once the app is installed.
- To build it yourself: Android Studio, or just a JDK + the Android SDK
  command-line tools if you're building from the CLI (see below).

## Install

1. Clone this repo and open it in Android Studio (or build from the CLI
   with `./gradlew :app:assembleDebug` — the resulting APK lands in
   `app/build/outputs/apk/debug/`).
2. Install the debug APK on the phone you want to use as the OCR server
   (`adb install app/build/outputs/apk/debug/app-debug.apk`, or copy it to
   the phone and open it there — you'll need "install from unknown
   sources" allowed for the latter).
3. Open the app once. On first launch it generates a random server key
   and stores it on the device — you never have to type it in yourself.

## Turning it on

Open the app and flip the **OCR server** toggle on. Once it's running, the
app prints the connection info as plain, selectable JSON:

```json
{"host":"<phone's LAN IP>","port":5210,"key":"<server key>"}
```

Copy that and hand it to whatever is going to send images — paste it into
an MCP server's config, a script's env vars, or tell your AI agent to set
up an OCR MCP server using it. There's nothing to scan; it's just text to
copy once.

- `host` — the phone's IP address on your local Wi-Fi network.
- `port` — `5210` by default, editable in the app (locked while the server
  is running — turn it off to change it).
- `key` — the random key that authenticates every request. Anyone who has
  this key can use the OCR server; anyone who doesn't gets rejected, even
  if they can reach the phone on the network.

Toggle it off and the phone stops accepting requests entirely, even from
someone who already has the key.

## Using it

Send a `POST` request to `/ocr` on the phone, with the pairing key in a
header, and a JSON body listing one or more base64-encoded images:

```
POST http://<host>:<port>/ocr
X-Api-Key: <key>
Content-Type: application/json

{"images": ["<base64-encoded image 1>", "<base64-encoded image 2>", ...]}
```

Response:

```json
{
  "text": "all recognized text from all images, joined together",
  "mean_confidence": 0.94,
  "results": [
    {
      "index": 0,
      "text": "recognized text for this image",
      "mean_confidence": 0.95,
      "lines": [{"text": "a line of text", "conf": 0.97}],
      "ms": 210
    }
  ]
}
```

Every route — including a plain `/health` check — requires the same
`X-Api-Key` header. There is no way to reach the phone without the key,
not even to check whether the server is running.

### Quick test

Once the app is running and you have its pairing JSON, confirm it's alive
with a single request (swap in your own host and key):

```bash
curl http://<host>:5210/health -H "X-Api-Key: <key>"
# {"ok":true,"engine":"mlkit"}
```

Then try it on a real image:

```bash
curl http://<host>:5210/ocr \
  -H "X-Api-Key: <key>" -H "Content-Type: application/json" \
  -d "{\"images\":[\"$(base64 -w0 some-photo.jpg)\"]}"
```

## Status

Fresh — the auth path has been adversarially reviewed and the app builds
clean (`./gradlew :app:assembleDebug`), but it has not yet been flashed
onto physical hardware and put through a real end-to-end pairing flow.
Treat it as a working v1, not a battle-tested one. Issues and PRs welcome.

## What this does NOT do

- **No telemetry.** Nothing about your usage — what you scanned, how
  often, from where — is collected, logged externally, or phoned home
  anywhere.
- **No account.** There's no sign-up, no login, no user database. The
  pairing key is the only credential, and it lives solely on the phone.
- **No cloud fallback.** If the phone is off, asleep, or unreachable, OCR
  requests simply fail — they never silently fall back to a cloud service.
- **Nothing leaves the device** except the OCR response itself, sent
  directly over your local network to whoever holds the pairing key. There
  is no third-party server in the loop at any point.
- **The server listens on every network interface, not only Wi-Fi** — if
  the phone also has mobile data, a hotspot, or a VPN connection active,
  the (still key-gated) port is reachable there too. The pairing key still
  guards it, but don't assume Wi-Fi is the only network this is exposed on.

## License

MIT — see [LICENSE](LICENSE).
