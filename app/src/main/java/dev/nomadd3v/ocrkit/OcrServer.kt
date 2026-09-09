package dev.nomadd3v.ocrkit

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HTTP front for ML Kit text recognition.
 *
 * Speaks the EXACT contract the reference box/phone services already expose
 * (betterpostools/ocr-service) — external callers (an MCP client,
 * `lib/ocrMenu.ts`-style consumers) read `text` / `mean_confidence` /
 * `results[]` off it by name, snake_case, not camel. A field renamed here
 * does not raise an error over there, it silently reads undefined and looks
 * like "OCR found nothing".
 *
 * Every route requires `X-Api-Key` — see AuthGate. Unlike the reference,
 * there is no unauthenticated endpoint at all, including /health, so a
 * network scan reveals nothing.
 *
 * Bytes only: this never fetches a URL, same rule as the reference service.
 */
class OcrServer(private val context: Context, port: Int) : NanoHTTPD("0.0.0.0", port) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun serve(session: IHTTPSession): Response {
        return try {
            val apiKey = session.headers["x-api-key"]
            if (!AuthGate.isAuthorized(context, apiKey)) {
                return unauthorized()
            }
            when {
                session.uri == "/health" -> json(JSONObject().put("ok", true).put("engine", "mlkit"))
                session.uri == "/ocr" && session.method == Method.POST -> handleOcr(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}"""
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "serve failed", t)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", t.message ?: "unknown").toString()
            )
        }
    }

    private fun handleOcr(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"] ?: return bad("Empty body.")
        val images = JSONObject(raw).optJSONArray("images") ?: return bad("No images provided.")
        if (images.length() == 0) return bad("No images provided.")
        if (images.length() > MAX_IMAGES) return bad("Too many images (max $MAX_IMAGES).")

        val results = JSONArray()
        val allConfs = ArrayList<Double>()
        val allText = StringBuilder()

        for (i in 0 until images.length()) {
            val started = System.currentTimeMillis()
            val entry = JSONObject().put("index", i)
            try {
                val payload = images.getString(i).substringAfter(",", images.getString(i))
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalArgumentException("Not a decodable image.")

                val text = recognizeBlocking(InputImage.fromBitmap(bitmap, 0))
                val lines = JSONArray()
                val confs = ArrayList<Double>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        // ML Kit's per-line confidence is nullable; treat an
                        // absent value as 1.0 rather than 0.0 so a null does
                        // not drag mean_confidence to zero and look like a bad
                        // read. It is a log-only field on the caller's side.
                        val conf = (line.confidence ?: 1.0f).toDouble()
                        lines.put(JSONObject().put("text", line.text).put("conf", conf))
                        confs.add(conf)
                    }
                }
                val joined = text.textBlocks
                    .flatMap { it.lines }
                    .joinToString("\n") { it.text }
                entry.put("lines", lines)
                entry.put("text", joined)
                entry.put("mean_confidence", confs.orZero())
                entry.put("ms", System.currentTimeMillis() - started)
                allConfs.addAll(confs)
                if (joined.isNotEmpty()) {
                    if (allText.isNotEmpty()) allText.append("\n\n")
                    allText.append(joined)
                }
            } catch (t: Throwable) {
                // Per-image failure must not fail the batch — same policy as
                // the reference service.
                Log.w(TAG, "image $i failed", t)
                entry.put("lines", JSONArray())
                entry.put("text", "")
                entry.put("mean_confidence", 0.0)
                entry.put("ms", System.currentTimeMillis() - started)
                entry.put("error", t.message ?: "failed")
            }
            results.put(entry)
        }

        return json(
            JSONObject()
                .put("results", results)
                .put("text", allText.toString())
                .put("mean_confidence", allConfs.orZero())
        )
    }

    /** ML Kit is callback-based; the HTTP thread waits. */
    private fun recognizeBlocking(image: InputImage): com.google.mlkit.vision.text.Text {
        var out: com.google.mlkit.vision.text.Text? = null
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        recognizer.process(image)
            .addOnSuccessListener { out = it; latch.countDown() }
            .addOnFailureListener { err = it; latch.countDown() }
        if (!latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("OCR timed out.")
        err?.let { throw it }
        return out ?: throw IllegalStateException("OCR returned nothing.")
    }

    private fun json(obj: JSONObject) =
        newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())

    private fun bad(msg: String) = newFixedLengthResponse(
        Response.Status.BAD_REQUEST, "application/json", JSONObject().put("error", msg).toString()
    )

    // Same shape for missing vs. wrong key — distinguishing the two itself
    // leaks information to an unauthenticated caller.
    private fun unauthorized() = newFixedLengthResponse(
        Response.Status.UNAUTHORIZED, "application/json", """{"error":"unauthorized"}"""
    )

    companion object {
        const val TAG = "OcrKit"
        const val MAX_IMAGES = 12
    }
}

private fun List<Double>.orZero(): Double = if (isEmpty()) 0.0 else average()
