package dev.nomadd3v.ocrkit

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Launcher screen: an editable port, a toggle to start the OCR server, and
 * — once it's running — the connection info (host/port/key) to hand to
 * whatever agent or script is going to send it images.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This screen renders the server key as plaintext — block
        // screenshots/screen-recording and the recent-apps thumbnail so the
        // key's exposure is limited to someone looking at the device.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val apiKey = AuthGate.getOrCreateKey(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ServerScreen(
                        apiKey = apiKey,
                        initialPort = PortConfig.getPort(applicationContext),
                        onPortChange = { PortConfig.setPort(applicationContext, it) },
                        lanIpProvider = { getLanIpAddress(this) }
                    )
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

private fun buildServerInfoJson(host: String, port: Int, key: String): String {
    return JSONObject()
        .put("host", host)
        .put("port", port)
        .put("key", key)
        .toString()
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
private fun ServerScreen(
    apiKey: String,
    initialPort: Int,
    onPortChange: (Int) -> Boolean,
    lanIpProvider: () -> String?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var serviceRunning by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(initialPort.toString()) }
    var committedPort by remember { mutableStateOf(initialPort) }
    var portError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "ocr-kit", style = MaterialTheme.typography.headlineSmall)

        Row(
            modifier = Modifier.padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Port")
            OutlinedTextField(
                value = portText,
                onValueChange = { text ->
                    portText = text
                    val parsed = text.toIntOrNull()
                    portError = parsed == null || parsed !in PortConfig.VALID_RANGE
                    if (parsed != null && onPortChange(parsed)) {
                        committedPort = parsed
                    }
                },
                enabled = !serviceRunning,
                isError = portError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(120.dp)
            )
        }
        if (portError) {
            Text(
                text = "Enter a port between ${PortConfig.VALID_RANGE.first} and " +
                    "${PortConfig.VALID_RANGE.last}.",
                style = MaterialTheme.typography.labelMedium
            )
        } else if (serviceRunning) {
            Text(
                text = "Turn the server off to change the port.",
                style = MaterialTheme.typography.labelMedium
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
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

        if (serviceRunning) {
            val lanIp = remember(serviceRunning) { lanIpProvider() }

            if (lanIp == null) {
                Text(
                    modifier = Modifier.padding(vertical = 8.dp),
                    text = "Server is on, but no Wi-Fi IP address was found. Connect to Wi-Fi and " +
                        "toggle the server off and back on."
                )
            } else {
                val infoJson = remember(apiKey, lanIp, committedPort) {
                    buildServerInfoJson(lanIp, committedPort, apiKey)
                }
                Text(
                    modifier = Modifier.padding(bottom = 8.dp),
                    text = "Give this to whatever will send it images — paste it into your " +
                        "agent's OCR config (e.g. an MCP server) or a script:",
                    style = MaterialTheme.typography.labelMedium
                )
                SelectionContainer {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = infoJson
                    )
                }
            }
        } else {
            Text(
                modifier = Modifier.padding(vertical = 8.dp),
                text = "Turn the server on to see its connection info."
            )
        }
    }
}
