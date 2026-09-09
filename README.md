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

## Install

1. Clone this repo and open it in Android Studio (or build from the CLI
   with `./gradlew :app:assembleDebug`).
2. Install the debug APK on the phone you want to use as the OCR server.
3. Open the app once. On first launch it generates a random pairing key
   and stores it on the device — you never have to type it in yourself.

## Pairing

Open the app on the phone. It shows:

- A **QR code** — scan it with whatever tool is consuming the OCR service
  (if it supports camera-based pairing).
- The **same information as plain text** underneath the QR code, so you can
  copy-paste it instead — useful when you're pasting it straight into an
  MCP config or a script instead of scanning with a camera.

Both forms encode the same JSON:

```json
{"host":"<phone's LAN IP>","port":5210,"key":"<pairing key>"}
```

- `host` — the phone's IP address on your local Wi-Fi network.
- `port` — always `5210`.
- `key` — the random key that authenticates every request. Anyone who has
  this key can use the OCR server; anyone who doesn't gets rejected, even
  if they can reach the phone on the network.

There's also an **ON/OFF toggle** in the app to start or stop the OCR
server whenever you want — turn it off and the phone stops accepting
requests entirely, even from someone who already has the key.

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
