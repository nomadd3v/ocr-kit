package dev.nomadd3v.ocrkit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/**
 * Launcher / pairing screen.
 *
 * Displays the API key + LAN IP as a scannable QR (and selectable plain
 * text, for copy-paste pairing flows), plus an ON/OFF toggle for the OCR
 * foreground service. No camera permission is needed here — this screen
 * only ever displays a QR code, it never scans one.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This screen renders the pairing secret as plaintext and a QR code —
        // block screenshots/screen-recording and the recent-apps thumbnail so
        // the key's exposure is limited to someone looking at the device.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val apiKey = AuthGate.getOrCreateKey(this)
        val lanIp = getLanIpAddress(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PairingScreen(apiKey = apiKey, lanIp = lanIp)
                }
            }
        }
    }

    private fun getLanIpAddress(context: Context): String? {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return null
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return null
            // WifiManager reports the address little-endian; format as dotted-quad.
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Throwable) {
            null
        }
    }
}

private fun buildPairingJson(host: String, key: String): String {
    return JSONObject()
        .put("host", host)
        .put("port", 5210)
        .put("key", key)
        .toString()
}

private fun renderQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bitmap
    } catch (_: Throwable) {
        null
    }
}

private fun setOcrServiceRunning(context: Context, running: Boolean) {
    val intent = Intent(context, OcrService::class.java)
    if (running) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } else {
        context.stopService(intent)
    }
}

@Composable
private fun PairingScreen(apiKey: String, lanIp: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var serviceRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "ocr-kit pairing", style = MaterialTheme.typography.headlineSmall)

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Text(text = "Server: ")
            Text(text = if (serviceRunning) "ON" else "OFF")
        }

        if (lanIp == null) {
            Text(
                modifier = Modifier.padding(vertical = 16.dp),
                text = "Could not read a Wi-Fi IP address. Connect to Wi-Fi, then reopen this app " +
                    "to generate a QR code. You can still read the API key below."
            )
        } else {
            val pairingJson = remember(apiKey, lanIp) { buildPairingJson(lanIp, apiKey) }
            val qrBitmap = remember(pairingJson) { renderQrBitmap(pairingJson) }

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .size(256.dp)
                )
            } else {
                Text(
                    modifier = Modifier.padding(vertical = 16.dp),
                    text = "Failed to render QR code. Use the text below instead."
                )
            }

            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = "Scan with an MCP client, or copy this text:",
                style = MaterialTheme.typography.labelMedium
            )
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    text = pairingJson
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "OCR server")
            Switch(
                checked = serviceRunning,
                onCheckedChange = { checked ->
                    serviceRunning = checked
                    setOcrServiceRunning(context, checked)
                },
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}
