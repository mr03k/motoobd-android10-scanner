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
    private data class Parameter(val pid: Int, val ecu: String, val bytes: List<Int>, val at: Long, val error: String = "")
    private val parameters = ConcurrentHashMap<String, Parameter>()
    @Volatile private var capabilities = CapabilityDiscovery.Result(emptyMap(), emptyMap(), emptyList())
    @Volatile private var identifiedCan = false
    private var pollPids = emptyList<Int>()
    private var slowCursor = 0
    private var freezeCursor = 0
    private var infoCursor = 0

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
        parameters.clear()
        capabilities = CapabilityDiscovery.Result(emptyMap(), emptyMap(), emptyList())
        identifiedCan = false
        pollPids = emptyList()
        freezeCursor = 0
        infoCursor = 0
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
        var deadline = SystemClock.elapsedRealtime() + timeout
        var pendingExtended = false
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
                    if (!pendingExtended && Regex("7F\\s*[0-9A-F]{2}\\s*78", RegexOption.IGNORE_CASE).containsMatchIn(bleResponse)) {
                        deadline = SystemClock.elapsedRealtime() + 8000
                        pendingExtended = true
                    }
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
                    if (!pendingExtended && Regex("7F\\s*[0-9A-F]{2}\\s*78", RegexOption.IGNORE_CASE).containsMatchIn(response)) {
                        deadline = SystemClock.elapsedRealtime() + 8000
                        pendingExtended = true
                    }
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
        // CAN headers permit stable ECU attribution and safe ISO-TP reassembly.
        if (countedDtcs && command("ATH1").trim() == "OK") identifiedCan = true
        capabilities = discoverCapabilities(1)
        pollPids = CapabilityDiscovery.pollOrder(capabilities.supported)
        slowCursor = 0
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

    private fun ensureSession(token: Long) {
        if (generation.get() != token || state !in listOf("connecting", "connected")) throw IOException("Operation cancelled because the connection changed.")
    }
    private fun discoverCapabilities(mode: Int, deadline: Long = Long.MAX_VALUE): CapabilityDiscovery.Result {
        val token = generation.get()
        return CapabilityDiscovery.discover(mode) { request ->
            ensureSession(token)
            if (SystemClock.elapsedRealtime() >= deadline) throw DiagnosticBudgetException()
            command(request, 1800)
        }
    }
    private class DiagnosticBudgetException : IOException("Diagnostic read reached its time limit. Partial results are shown; retry to refresh.")
    private fun record(key: String, value: Double) { readings[key] = Reading(value) }
    private val legacyKeys = mapOf(0x0C to "rpm", 0x0D to "speed", 0x05 to "coolant", 0x0F to "intake", 0x0B to "map", 0x33 to "baro", 0x11 to "throttle", 0x04 to "load", 0x10 to "maf", 0x06 to "stft")
    private fun readParameter(pid: Int) {
        val raw = command("01%02X".format(pid), 1800)
        parameters.entries.removeIf { it.value.pid == pid }
        val replies = ObdParser.frames(raw).filter { it.bytes.size > 2 && it.bytes[0] == 0x41 && it.bytes[1] == pid }
        val now = System.currentTimeMillis()
        val bySource = replies.groupBy { it.ecu }
        for ((ecu, supported) in capabilities.supported) {
            if (pid !in supported) continue
            val frames = bySource[ecu].orEmpty()
            if (frames.isEmpty()) {
                parameters["$ecu:%02X".format(pid)] = Parameter(pid, ecu, emptyList(), now, raw.take(160).ifEmpty { "No parameter data returned." })
            }
        }
        bySource.forEach { (ecu, frames) -> frames.forEachIndexed { index, frame ->
            val bytes = frame.bytes.drop(2)
            val definition = PidCatalogue.definition(pid)
            val error = if (bytes.size < definition.size) "Truncated PID reply." else ""
            val source = if (frames.size > 1) "$ecu response ${index + 1}" else ecu
            parameters["$source:%02X".format(pid)] = Parameter(pid, source, bytes, now, error)
        } }
        // Compatibility dashboard only uses an unambiguous single response or stable primary CAN ECU.
        val primary = capabilities.supported.keys.filter { it != "unidentified" }.sorted().firstOrNull()
        legacyKeys[pid]?.let { readings.remove(it) }
        if (pid in listOf(0x14, 0x15)) readings.remove(if (pid == 0x14) "o2B1S1" else "o2B1S2")
        val chosen = if (identifiedCan) replies.singleOrNull { it.ecu == primary } else replies.singleOrNull()
        if (chosen != null) {
            val bytes = chosen.bytes.drop(2)
            val value = PidCatalogue.decode(pid, bytes)
            if (value is Number) legacyKeys[pid]?.let { record(it, value.toDouble()) }
            if (pid in listOf(0x14, 0x15) && bytes.size >= 2) record(if (pid == 0x14) "o2B1S1" else "o2B1S2", bytes[0] / 200.0)
        }
    }
    private fun pollTelemetry() {
        for (pid in listOf(0x0C, 0x0D)) if (pid in pollPids) readParameter(pid)
        val slow = pollPids.filter { it !in listOf(0x0C, 0x0D) }
        if (slow.isNotEmpty()) {
            readParameter(slow[slowCursor % slow.size])
            slowCursor = (slowCursor + 1) % slow.size
        }
        if (tick++ % 4 == 0) ObdParser.voltage(command("ATRV"))?.let {
            record("voltage", it)
            record("voltMin", minOf(readings["voltMin"]?.value ?: it, it))
        }
    }
    private fun parameterJson(pid: Int, ecu: String, bytes: List<Int>, at: Long, status: String, error: String = "", supported: Boolean = true): JSONObject {
        val definition = PidCatalogue.definition(pid)
        val value = if (status in listOf("fresh", "stale")) PidCatalogue.decode(pid, bytes) else null
        return JSONObject().put("id", "$ecu:%02X".format(pid)).put("ecu", ecu).put("pid", "%02X".format(pid))
            .put("name", definition.name).put("unit", definition.unit).put("value", value ?: JSONObject.NULL)
            .put("raw", PidCatalogue.rawHex(bytes)).put("status", status).put("updatedAt", at)
            .put("supported", supported).put("decoded", value != null).put("error", error)
            .put("staleAfterMs", maxOf(10000L, pollPids.size * 7000L))
    }
    private fun parameterSnapshot(): JSONArray {
        val now = System.currentTimeMillis()
        val staleAfter = maxOf(10000L, pollPids.size * 7000L)
        val rows = JSONArray()
        val snapshot = capabilities
        for ((ecu, supported) in snapshot.supported.toSortedMap()) {
            val known = PidCatalogue.knownPids().filter { (it - 1) / 32 * 32 in snapshot.ranges[ecu].orEmpty() }
            for (pid in (known + supported).distinct().filter { it % 32 != 0 }.sorted()) {
                val samples = parameters.values.filter { it.pid == pid && (it.ecu == ecu || it.ecu.startsWith("$ecu response ")) }.sortedBy { it.ecu }
                if (samples.isEmpty()) rows.put(parameterJson(pid, ecu, emptyList(), 0,
                    if (pid in supported) "read_failed" else "unsupported", if (pid in supported) "Waiting for first read." else "Not advertised by ECU.", pid in supported))
                else samples.forEach { sample -> rows.put(parameterJson(pid, sample.ecu, sample.bytes, sample.at,
                    if (sample.error.isNotEmpty()) "read_failed" else if (state != "connected" || now - sample.at > staleAfter) "stale" else "fresh", sample.error)) }
            }
        }
        return rows
    }
    private fun unavailableStatus(raw: String, mode: Int): String = if (ObdParser.frames(raw).any {
        it.bytes.size >= 3 && it.bytes[0] == 0x7F && it.bytes[1] == mode && it.bytes[2] in listOf(0x11, 0x12)
    }) "unsupported" else "read_failed"

    private fun readDiagnostics(): JSONObject {
        if (state != "connected") throw IOException("Connect to an ECU before reading diagnostics.")
        val token = generation.get()
        val deadline = SystemClock.elapsedRealtime() + 120000
        val readiness = JSONArray()
        val freezeFrames = JSONArray()
        val vehicleInfo = JSONArray()
        val errors = JSONArray()
        val rawResponses = JSONArray()
        fun query(request: String): String {
            ensureSession(token)
            if (SystemClock.elapsedRealtime() >= deadline) throw DiagnosticBudgetException()
            val raw = command(request, 2500)
            rawResponses.put(JSONObject().put("request", request).put("response", raw))
            val mode = request.take(2).toInt(16)
            if (ObdParser.hasNegativeReply(raw, mode)) errors.put("Mode %02X request %s included a negative ECU reply: %s".format(mode, request, raw.take(160)))
            return raw
        }
        fun issue(mode: String, raw: String): String {
            val message = "$mode unavailable: ${raw.take(160).ifEmpty { "No reply" }}"
            errors.put(message)
            return message
        }
        try {
            val rawReady = query("0101")
            val readyFrames = ObdParser.frames(rawReady).filter { it.bytes.size >= 6 && it.bytes.take(2) == listOf(0x41, 1) }
            if (readyFrames.isEmpty()) readiness.put(JSONObject().put("ecu", "unidentified").put("status", "read_failed").put("raw", rawReady).put("error", issue("Mode 01 readiness", rawReady)))
            readyFrames.forEach { frame ->
                val bytes = frame.bytes.drop(2)
                val decoded = DiagnosticDecoder.readiness(bytes)!!
                val monitors = JSONArray()
                decoded.monitors.forEach { monitor -> monitors.put(JSONObject().put("name", monitor.name).put("supported", monitor.supported).put("complete", monitor.complete ?: JSONObject.NULL)) }
                readiness.put(JSONObject().put("ecu", frame.ecu).put("mil", decoded.mil).put("dtcCount", decoded.dtcCount)
                    .put("ignitionType", decoded.ignitionType).put("monitors", monitors).put("status", "fresh")
                    .put("raw", PidCatalogue.rawHex(bytes)).put("error", ""))
            }
            val rawTrigger = query("020200")
            val triggerFrames = ObdParser.frames(rawTrigger).filter { it.bytes.size >= 5 && it.bytes.take(3) == listOf(0x42, 2, 0) }
            val freezeRows = linkedMapOf<String, JSONObject>()
            if (triggerFrames.isEmpty()) freezeFrames.put(JSONObject().put("ecu", "unidentified").put("frame", 0).put("dtc", JSONObject.NULL)
                .put("parameters", JSONArray()).put("status", unavailableStatus(rawTrigger, 2)).put("error", issue("Mode 02 frame 00", rawTrigger)).put("raw", rawTrigger))
            triggerFrames.forEachIndexed { index, frame ->
                val bytes = frame.bytes.drop(3).take(2)
                val noFrame = bytes.all { it == 0 }
                val ecu = if (triggerFrames.count { it.ecu == frame.ecu } > 1) "${frame.ecu} response ${index + 1}" else frame.ecu
                val row = JSONObject().put("ecu", ecu).put("frame", 0).put("dtc", if (noFrame) JSONObject.NULL else PidCatalogue.decode(2, bytes))
                    .put("parameters", JSONArray()).put("status", if (noFrame) "no_frame" else "fresh").put("error", "").put("raw", PidCatalogue.rawHex(bytes))
                freezeRows[ecu] = row
                freezeFrames.put(row)
            }
            if (triggerFrames.count { it.ecu == "unidentified" } > 1) {
                val error = "Mode 02 has multiple unidentified responders. Snapshot fields cannot be associated safely; raw responses retained."
                errors.put(error)
                freezeRows.values.forEach { row -> if (row.optString("status") != "no_frame") row.put("status", "read_failed").put("error", error) }
            }
            if (freezeRows.values.any { it.optString("status") == "fresh" }) {
                val support = CapabilityDiscovery.discover(2, ::query)
                support.errors.forEach { errors.put(it) }
                (support.supported.keys - freezeRows.keys).forEach { errors.put("Mode 02 source $it did not provide an unambiguous trigger code. Its parameter replies cannot be attached to a snapshot; raw replies retained.") }
                val allPids = CapabilityDiscovery.pollOrder(support.supported).filter { it != 2 }
                val frameDeadline = minOf(deadline - 45000, SystemClock.elapsedRealtime() + 45000)
                val rotation = if (allPids.isEmpty()) emptyList() else allPids.drop(freezeCursor % allPids.size) + allPids.take(freezeCursor % allPids.size)
                for (pid in rotation) {
                    if (SystemClock.elapsedRealtime() >= frameDeadline) {
                        errors.put("Mode 02 frame read is partial to reserve time for vehicle information. Refresh continues with the next fields.")
                        break
                    }
                    val raw = query("02%02X00".format(pid))
                    if (allPids.isNotEmpty()) freezeCursor = (allPids.indexOf(pid) + 1) % allPids.size
                    val frames = ObdParser.frames(raw).filter { it.bytes.size > 3 && it.bytes.take(3) == listOf(0x42, pid, 0) }
                    for ((ecu, advertised) in support.supported) {
                        if (pid !in advertised) continue
                        val matches = frames.filter { it.ecu == ecu }
                        val row = freezeRows[ecu]
                        // Without headers, associating multiple snapshot responders would fabricate a frame.
                        if (row == null || row.optString("status") == "no_frame") continue
                        if (matches.size != 1) {
                            val error = if (matches.isEmpty()) "No frame parameter reply: ${raw.take(100)}" else "Multiple unidentified frame responders; raw replies retained."
                            row.getJSONArray("parameters").put(parameterJson(pid, ecu, emptyList(), System.currentTimeMillis(), "read_failed", error))
                            errors.put("Mode 02 PID %02X: %s".format(pid, error))
                        } else {
                            val bytes = matches.single().bytes.drop(3)
                            val short = bytes.size < PidCatalogue.definition(pid).size
                            if (short) errors.put("Mode 02 PID %02X from %s: truncated reply".format(pid, ecu))
                            row.getJSONArray("parameters").put(parameterJson(pid, ecu, bytes, System.currentTimeMillis(), if (short) "read_failed" else "fresh", if (short) "Truncated frame parameter." else ""))
                        }
                    }
                }
            }
            var infoSupportRaw = ""
            val infoSupport = CapabilityDiscovery.discover(9) { request -> query(request).also { if (request == "0900") infoSupportRaw = it } }
            infoSupport.errors.forEach { errors.put(it) }
            if (infoSupport.supported.isEmpty()) vehicleInfo.put(JSONObject().put("ecu", "unidentified").put("pid", "00").put("name", "Vehicle information support")
                .put("value", JSONObject.NULL).put("raw", infoSupportRaw).put("status", unavailableStatus(infoSupportRaw, 9)).put("error", "Mode 09 supported information unavailable; see raw responses."))
            val allInfoPids = CapabilityDiscovery.pollOrder(infoSupport.supported)
            val infoRotation = if (allInfoPids.isEmpty()) emptyList() else allInfoPids.drop(infoCursor % allInfoPids.size) + allInfoPids.take(infoCursor % allInfoPids.size)
            for (pid in infoRotation) {
                val raw = query("09%02X".format(pid))
                if (allInfoPids.isNotEmpty()) infoCursor = (allInfoPids.indexOf(pid) + 1) % allInfoPids.size
                val frames = ObdParser.frames(raw).filter { it.bytes.size > 2 && it.bytes.take(2) == listOf(0x49, pid) }
                for ((ecu, advertised) in infoSupport.supported) {
                    if (pid !in advertised) continue
                    val matches = frames.filter { it.ecu == ecu }
                    if (matches.isEmpty()) {
                        val error = issue("Mode 09 PID %02X from %s".format(pid, ecu), raw)
                        vehicleInfo.put(JSONObject().put("ecu", ecu).put("pid", "%02X".format(pid)).put("name", DiagnosticDecoder.vehicleInfoName(pid))
                            .put("value", JSONObject.NULL).put("raw", raw).put("status", "read_failed").put("error", error))
                    }
                    matches.forEachIndexed { index, frame ->
                        val bytes = frame.bytes.drop(2)
                        val value = DiagnosticDecoder.vehicleInfo(pid, bytes, identifiedCan && ecu != "unidentified" && matches.size == 1)
                        vehicleInfo.put(JSONObject().put("ecu", if (matches.size > 1) "$ecu response ${index + 1}" else ecu).put("pid", "%02X".format(pid))
                            .put("name", DiagnosticDecoder.vehicleInfoName(pid)).put("value", value ?: JSONObject.NULL).put("raw", PidCatalogue.rawHex(bytes))
                            .put("status", "fresh").put("error", if (value == null) "Raw information; no verified decoder or unambiguous source." else ""))
                    }
                }
            }
        } catch (e: DiagnosticBudgetException) {
            errors.put(e.message)
        }
        ensureSession(token)
        return JSONObject().put("readiness", readiness).put("freezeFrames", freezeFrames).put("vehicleInfo", vehicleInfo)
            .put("errors", errors).put("complete", errors.length() == 0).put("updatedAt", System.currentTimeMillis()).put("rawResponses", rawResponses)
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
                val unsupported = ObdParser.frames(raw).any { it.bytes.size >= 3 && it.bytes[0] == 0x7F && it.bytes[1] == request.toInt(16) && it.bytes[2] in listOf(0x11, 0x12) }
                val reason = if (unsupported) "${if (request == "0A") "Permanent codes" else "$mode codes"}: unsupported by ECU" else "Read failed or incomplete"
                errors.put("Mode $request: $reason (${raw.take(120).ifEmpty { "No response" }})")
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
                val fresh = state == "connected" && reading != null && now - reading.at <= if (key in listOf("rpm", "speed", "voltage", "voltMin")) 10000 else maxOf(10000L, pollPids.size * 7000L)
                result.put(key, if (fresh) reading!!.value else JSONObject.NULL)
                if (fresh) newest = maxOf(newest, reading!!.at)
            }
            val capabilitySnapshot = capabilities
            val capabilityErrors = JSONArray()
            capabilitySnapshot.errors.forEach { capabilityErrors.put(it) }
            return result.put("updatedAt", newest).put("parameters", parameterSnapshot()).put("capabilities", JSONObject()
                .put("complete", capabilitySnapshot.errors.isEmpty() && capabilitySnapshot.supported.isNotEmpty())
                .put("supportedCount", CapabilityDiscovery.pollOrder(capabilitySnapshot.supported).size)
                .put("ecuIdentification", identifiedCan).put("errors", capabilityErrors)).toString()
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
                    if (operation in listOf("scanDtc", "clearDtc", "readDiagnostics") && generation.get() != requestGeneration) throw IOException("Operation cancelled because the connection changed.")
                    val data: Any = when (operation) {
                        "listDevices" -> JSONArray(listPairedDevices())
                        "connect" -> connect(argument.trim().uppercase(), requestGeneration)
                        "disconnect" -> "DISCONNECTED"
                        "scanDtc" -> scanDtcs()
                        "readDiagnostics" -> readDiagnostics()
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
