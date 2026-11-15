package com.motoobd.scanner

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** All ELM commands run on one serial worker. The UI only reads caches or queues operations. */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicLong()
    private val ioLock = Any()
    private val sessionLock = Any()
    private val responseLock = Object()
    private val bleResponse = StringBuilder()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var setupLatch: CountDownLatch? = null
    @Volatile private var writeSucceeded = false
    @Volatile private var state = "disconnected"
    @Volatile private var deviceLabel = ""
    @Volatile private var lastError = ""
    @Volatile private var destroyed = false
    @Volatile private var foreground = false
    @Volatile private var countedDtcs = false
    private var tick = 0
    private data class Reading(val value: Double, val at: Long = System.currentTimeMillis())
    private val readings = ConcurrentHashMap<String, Reading>()

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val ASSET_URL = "file:///android_asset/index.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = routeUrl(request.url)
            @Deprecated("Compatibility with older WebViews")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = routeUrl(Uri.parse(url))
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(ObdBridge(), "AndroidObdBridge")
        webView.loadUrl(ASSET_URL)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"), 1001)
        }
        worker.scheduleWithFixedDelay({
            if (state == "connected") {
                val token = generation.get()
                try { pollTelemetry() } catch (e: Exception) { failConnection(e.message ?: "Adapter disconnected", token) }
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun routeUrl(uri: Uri): Boolean {
        if (uri.toString().substringBefore('#') == ASSET_URL) return false
        if (uri.scheme == "https" || uri.scheme == "http") {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        disconnect()
        worker.shutdownNow()
        watchdog.shutdownNow()
        webView.removeJavascriptInterface("AndroidObdBridge")
        webView.destroy()
        super.onDestroy()
    }

    private fun requireBluetooth() {
        if (adapter == null) throw IOException("This device has no Bluetooth adapter.")
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Allow Nearby devices permission in Android Settings, then try again.")
        }
        if (adapter?.isEnabled != true) throw IOException("Turn on Bluetooth in Android Settings.")
    }

    @SuppressLint("MissingPermission")
    private fun closeTransport() {
        val oldSocket = socket
        socket = null
        val oldGatt = gatt
        gatt = null
        writeCharacteristic = null
        try { oldSocket?.close() } catch (_: Exception) { }
        try { oldGatt?.disconnect(); oldGatt?.close() } catch (_: Exception) { }
        writeLatch?.countDown()
        setupLatch?.countDown()
        synchronized(responseLock) { responseLock.notifyAll() }
    }

    private fun disconnect() = synchronized(sessionLock) {
        generation.incrementAndGet()
        state = "disconnected"
        lastError = ""
        deviceLabel = ""
        closeTransport()
        readings.clear()
        countedDtcs = false
    }

    private fun failConnection(error: String, token: Long = generation.get()) = synchronized(sessionLock) {
        if (generation.get() != token || state == "disconnected") return@synchronized
        disconnect()
        lastError = error
        state = "error"
    }

    @SuppressLint("MissingPermission")
    private fun command(cmd: String, timeout: Long = 1200): String {
        val token = generation.get()
        try { return exchange(cmd, timeout) } catch (e: Exception) {
            failConnection(e.message ?: "Adapter communication failed.", token)
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun exchange(cmd: String, timeout: Long): String = synchronized(ioLock) {
        val request = (cmd.trim() + "\r").toByteArray(Charsets.US_ASCII)
        val activeGatt = gatt
        val characteristic = writeCharacteristic
        val deadline = SystemClock.elapsedRealtime() + timeout
        if (activeGatt != null && characteristic != null) {
            synchronized(responseLock) { bleResponse.setLength(0) }
            for (chunk in request.asList().chunked(20)) {
                val latch = CountDownLatch(1)
                writeLatch = latch
                writeSucceeded = false
                characteristic.writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = chunk.toByteArray()
                if (!activeGatt.writeCharacteristic(characteristic)) throw IOException("Bluetooth write was rejected.")
                if (!latch.await(timeout, TimeUnit.MILLISECONDS) || !writeSucceeded) throw IOException("Bluetooth write timed out.")
                writeLatch = null
            }
            synchronized(responseLock) {
                while (!bleResponse.contains(">")) {
                    if (gatt !== activeGatt) throw IOException("Adapter disconnected.")
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw IOException("Adapter did not finish its reply. Reconnect and retry.")
                    responseLock.wait(remaining)
                }
                bleResponse.toString().substringBefore('>').trim()
            }
        } else {
            val activeSocket = socket ?: throw IOException("Connect an adapter first.")
            val input = activeSocket.inputStream
            val output = activeSocket.outputStream
            output.write(request)
            output.flush()
            val response = StringBuilder()
            val bytes = ByteArray(256)
            var complete = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (socket !== activeSocket) throw IOException("Adapter disconnected.")
                val available = input.available()
                if (available > 0) {
                    val size = input.read(bytes, 0, minOf(available, bytes.size))
                    if (size < 0) throw IOException("Adapter disconnected.")
                    response.append(String(bytes, 0, size, Charsets.US_ASCII))
                    if (response.length > 32768) throw IOException("Adapter reply is too large.")
                    if (response.contains(">")) { complete = true; break }
                } else Thread.sleep(10)
            }
            if (!complete) throw IOException("Adapter did not finish its reply. Reconnect and retry.")
            response.toString().substringBefore('>').trim()
        }
    }

    private fun initializeElm() {
        command("ATZ", 2500)
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATSP0")) {
            if (!Regex("(?m)^\\s*OK\\s*$").containsMatchIn(command(cmd).replace('\r', '\n'))) {
                throw IOException("Adapter rejected $cmd. Use a compatible ELM327 adapter.")
            }
        }
        if (ObdParser.pid(command("0100", 10000), 0, 4) == null) {
            throw IOException("Adapter responded, but no ECU replied. Check ignition, cable, and vehicle OBD-II compatibility.")
        }
        val protocolReply = command("ATDPN").trim().uppercase()
        val protocol = Regex("^(?:A)?([1-9])$").find(protocolReply)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Could not identify the ECU protocol. Reconnect with a compatible ELM327 adapter.")
        countedDtcs = protocol in 6..9
    }

    @SuppressLint("MissingPermission")
    private fun connect(mac: String, expectedGeneration: Long): String {
        requireBluetooth()
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) throw IOException("Choose a valid paired adapter.")
        val token = synchronized(sessionLock) {
            if (!foreground || destroyed || generation.get() != expectedGeneration) throw IOException("Connection cancelled.")
            disconnect()
            state = "connecting"
            generation.get()
        }
        val device = adapter!!.bondedDevices.firstOrNull { it.address.equals(mac, true) }
            ?: throw IOException("Pair this adapter in Android Settings first.")
        deviceLabel = device.name ?: device.address
        try {
            if (Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_SCAN") == PackageManager.PERMISSION_GRANTED) adapter?.cancelDiscovery()
            if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                connectBle(device, token)
            } else {
                var failure: Exception? = null
                for (secure in listOf(true, false)) {
                    if (generation.get() != token) throw IOException("Connection cancelled.")
                    val candidate = if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID) else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    synchronized(sessionLock) {
                        if (generation.get() != token) { candidate.close(); throw IOException("Connection cancelled.") }
                        socket = candidate
                    }
                    val timeout = watchdog.schedule({ try { candidate.close() } catch (_: Exception) { } }, 8, TimeUnit.SECONDS)
                    try {
                        candidate.connect()
                        timeout.cancel(false)
                        failure = null
                        break
                    } catch (e: Exception) {
                        timeout.cancel(false)
                        try { candidate.close() } catch (_: Exception) { }
                        socket = null
                        failure = e
                    }
                }
                if (failure != null) {
                    if (device.type == BluetoothDevice.DEVICE_TYPE_DUAL) connectBle(device, token)
                    else throw IOException("Could not connect. Check adapter power and disconnect other scanner apps.", failure)
                }
            }
            initializeElm()
            synchronized(sessionLock) {
                if (generation.get() != token || !foreground) throw IOException("Connection cancelled.")
                state = "connected"
            }
            tick = 0
            return "CONNECTED:$deviceLabel"
        } catch (e: Exception) {
            if (generation.get() == token) failConnection(e.message ?: "Connection failed.")
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectBle(device: BluetoothDevice, token: Long) {
        val ready = CountDownLatch(1)
        setupLatch = ready
        var setupError = "BLE setup timed out."
        var subscribed = false
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
                if (generation.get() != token) { connection.close(); return }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!connection.discoverServices()) ready.countDown()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    setupError = "BLE adapter disconnected ($status)."
                    ready.countDown()
                    if (state == "connected") failConnection(setupError, token)
                }
            }
            override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
                if (generation.get() != token) return
                if (status != BluetoothGatt.GATT_SUCCESS) { ready.countDown(); return }
                for (service in connection.services) {
                    val id = service.uuid.toString()
                    if (!listOf("0000fff0-", "0000ffe0-", "000018f0-", "6e400001-").any { id.startsWith(it) }) continue
                    val writer = service.characteristics.firstOrNull {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    } ?: continue
                    val notifier = service.characteristics.firstOrNull {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    } ?: continue
                    val descriptor = notifier.getDescriptor(CCCD_UUID) ?: continue
                    writeCharacteristic = writer
                    if (!connection.setCharacteristicNotification(notifier, true)) continue
                    descriptor.value = if (notifier.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    if (connection.writeDescriptor(descriptor)) return
                }
                setupError = "No supported BLE serial service with notifications was found."
                ready.countDown()
            }
            override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (generation.get() != token) return
                subscribed = status == BluetoothGatt.GATT_SUCCESS
                if (!subscribed) setupError = "BLE notification subscription failed ($status)."
                ready.countDown()
            }
            override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (generation.get() != token) return
                writeSucceeded = status == BluetoothGatt.GATT_SUCCESS
                writeLatch?.countDown()
            }
            override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (generation.get() != token) return
                synchronized(responseLock) {
                    if (bleResponse.length < 32768) bleResponse.append(String(characteristic.value ?: byteArrayOf(), Charsets.US_ASCII))
                    responseLock.notifyAll()
                }
            }
        }
        synchronized(sessionLock) {
            if (generation.get() != token) throw IOException("Connection cancelled.")
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        val complete = ready.await(8, TimeUnit.SECONDS)
        setupLatch = null
        if (!complete || !subscribed || generation.get() != token) throw IOException(setupError)
    }

    private fun record(key: String, value: Double) { readings[key] = Reading(value) }
    private fun readPid(pid: Int, size: Int, key: String, decode: (List<Int>) -> Double) {
        ObdParser.pid(command("01%02X".format(pid)), pid, size)?.let { record(key, decode(it)) }
    }
    private fun pollTelemetry() {
        readPid(0x0C, 2, "rpm") { (it[0] * 256 + it[1]) / 4.0 }
        readPid(0x0D, 1, "speed") { it[0].toDouble() }
        when (tick++ % 6) {
            0 -> {
                ObdParser.voltage(command("ATRV"))?.let {
                    record("voltage", it)
                    record("voltMin", minOf(readings["voltMin"]?.value ?: it, it))
                }
                readPid(0x0B, 1, "map") { it[0].toDouble() }
            }
            1 -> {
                readPid(0x05, 1, "coolant") { it[0] - 40.0 }
                readPid(0x0F, 1, "intake") { it[0] - 40.0 }
            }
            2 -> {
                readPid(0x11, 1, "throttle") { it[0] * 100.0 / 255 }
                readPid(0x04, 1, "load") { it[0] * 100.0 / 255 }
            }
            3 -> {
                readPid(0x06, 1, "stft") { it[0] * 100.0 / 128 - 100 }
                readPid(0x10, 2, "maf") { (it[0] * 256 + it[1]) / 100.0 }
            }
            4 -> readPid(0x33, 1, "baro") { it[0].toDouble() }
            5 -> {
                readPid(0x14, 2, "o2B1S1") { it[0] / 200.0 }
                readPid(0x15, 2, "o2B1S2") { it[0] / 200.0 }
            }
        }
    }

    private fun scanDtcs(): JSONObject {
        if (state != "connected") throw IOException("Connect to an ECU before scanning.")
        val codes = JSONArray()
        val result = JSONObject()
        val errors = JSONArray()
        for ((request, reply, mode) in listOf(Triple("03", 0x43, "STORED"), Triple("07", 0x47, "PENDING"), Triple("0A", 0x4A, "PERMANENT"))) {
            val raw = command(request, 4000)
            result.put("mode$request", raw)
            if (!ObdParser.validDtcReply(raw, reply, countedDtcs)) {
                errors.put("Mode $request: ${raw.take(120).ifEmpty { "No response" }}")
                continue
            }
            for (code in ObdParser.dtcs(raw, reply, countedDtcs)) codes.put(JSONObject().put("code", code).put("mode", mode).put("modeLabel", "Mode $request ${mode.lowercase()}"))
        }
        return result.put("codes", codes).put("errors", errors).put("complete", errors.length() == 0)
    }

    inner class ObdBridge {
        @JavascriptInterface fun getConnectionStateJson(): String = JSONObject()
            .put("connected", state == "connected").put("state", state).put("device", deviceLabel).put("error", lastError).toString()
        @JavascriptInterface fun getLiveTelemetryJson(): String {
            val now = System.currentTimeMillis()
            val result = JSONObject().put("connected", state == "connected").put("state", state)
            var newest = 0L
            for (key in listOf("speed", "rpm", "voltage", "voltMin", "coolant", "intake", "map", "baro", "throttle", "load", "maf", "stft", "o2B1S1", "o2B1S2")) {
                val reading = readings[key]
                val fresh = state == "connected" && reading != null && now - reading.at <= 10000
                result.put(key, if (fresh) reading!!.value else JSONObject.NULL)
                if (fresh) newest = maxOf(newest, reading!!.at)
            }
            return result.put("updatedAt", newest).toString()
        }
        @SuppressLint("MissingPermission")
        @JavascriptInterface fun listPairedDevices(): String {
            requireBluetooth()
            val devices = JSONArray()
            adapter!!.bondedDevices.sortedBy { it.name ?: it.address }.forEach {
                devices.put(JSONObject().put("name", it.name ?: "Unnamed adapter").put("address", it.address)
                    .put("type", if (it.type == BluetoothDevice.DEVICE_TYPE_LE) "BLE" else "Classic / dual"))
            }
            return devices.toString()
        }
        @JavascriptInterface fun requestOperation(requestId: String, operation: String, argument: String) {
            if (destroyed || requestId.length > 100 || argument.length > 200) return
            if (operation == "disconnect") disconnect()
            val requestGeneration = generation.get()
            worker.execute {
                val result = JSONObject()
                try {
                    if (operation in listOf("scanDtc", "clearDtc") && generation.get() != requestGeneration) throw IOException("Operation cancelled because the connection changed.")
                    val data: Any = when (operation) {
                        "listDevices" -> JSONArray(listPairedDevices())
                        "connect" -> connect(argument.trim().uppercase(), requestGeneration)
                        "disconnect" -> "DISCONNECTED"
                        "scanDtc" -> scanDtcs()
                        "clearDtc" -> {
                            if (state != "connected") throw IOException("Connect to an ECU before clearing codes.")
                            val response = command("04", 6000)
                            val success = ObdParser.hasReply(response, 0x44) && !ObdParser.hasNegativeReply(response, 0x04)
                            JSONObject().put("success", success).put("response", response)
                                .put("error", if (success) "" else "ECU did not confirm clearing codes. Permanent codes cannot be cleared by this command.")
                        }
                        "compression" -> JSONObject().put("supported", false).put("error", "OBD-II polling cannot measure cylinder compression. Use a dedicated compression tester.")
                        else -> throw IOException("Unknown operation.")
                    }
                    result.put("ok", true).put("data", data)
                } catch (e: Exception) {
                    if (operation == "connect" && state == "connecting") failConnection(e.message ?: "Connection failed.")
                    result.put("ok", false).put("error", e.message ?: "Operation failed.")
                }
                runOnUiThread {
                    if (!destroyed) webView.evaluateJavascript("window.onObdResult && window.onObdResult(${JSONObject.quote(requestId)},$result)", null)
                }
            }
        }
        @JavascriptInterface fun connectByMacAddress(mac: String): String = "ERROR:Use requestOperation to connect asynchronously."
        @JavascriptInterface fun connectPairedObdDevice(hint: String): String = "ERROR:Choose a paired adapter explicitly."
        @JavascriptInterface fun connectBleObdNative(): String = "ERROR:Choose a paired BLE adapter explicitly."
        @JavascriptInterface fun disconnectObd(): String { disconnect(); return "DISCONNECTED" }
        @JavascriptInterface fun scanAllDtcModesJson(): String = JSONObject().put("error", "Use requestOperation to scan asynchronously.").put("codes", JSONArray()).toString()
        @JavascriptInterface fun runLiveCompressionSampleJson(): String = JSONObject().put("supported", false).put("error", "A dedicated compression tester is required.").toString()
        @JavascriptInterface fun sendElmCommand(cmd: String): String = "ERROR:Use the validated operation interface."
    }
}
