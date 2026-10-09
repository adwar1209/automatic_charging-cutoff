package com.adwar.chargingcutoff

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.*

/** A user explicitly starts every charging session. No automatic reconnect. */
@SuppressLint("MissingPermission") // Runtime permission gates precede Bluetooth operations.
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var selected: BluetoothDevice? = null
    private var scanning = false
    private lateinit var battery: TextView
    private lateinit var status: TextView
    private lateinit var relay: TextView
    private lateinit var deviceName: TextView
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var deviceList: LinearLayout
    private val listener: (UiState) -> Unit = { render(it) }
    private val adapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter
    private val stopScanTask = Runnable { stopScan() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread {
                if (!scanning) return@runOnUiThread
                val device = result.device
                if (devices.put(device.address, device) == null) {
                    deviceList.addView(Button(this@MainActivity).apply {
                        isAllCaps = false
                        text = "${result.scanRecord?.deviceName ?: "ChargeCutoff"}\n${device.address}"
                        setOnClickListener {
                            selected = device
                            deviceName.text = "Selected: ${device.address}"
                            stopScan()
                            render(AppState.current)
                        }
                    })
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { stopScan(); status.text = "Scan failed ($errorCode). Try again." }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        scroll.addView(body)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            body.setPadding(dp(24) + insets.systemWindowInsetLeft,
                dp(28) + insets.systemWindowInsetTop,
                dp(24) + insets.systemWindowInsetRight,
                dp(28) + insets.systemWindowInsetBottom)
            insets
        }
        fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.rgb(25, 48, 40))
            setPadding(0, dp(8), 0, dp(8)); body.addView(this)
        }
        label("CHARGE CUTOFF", 14f)
        label("Charge with a clear stopping point.", 28f)
        battery = label("—%", 56f)
        label("Phone-reported battery · cutoff at 100%", 15f)
        relay = label("Not connected", 20f)
        status = label("Choose your controller to begin.")
        deviceName = label("No controller selected", 14f)
        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text; isAllCaps = false
            setOnClickListener { action() }; body.addView(this)
        }
        scanButton = button("Find controller") { beginScanWithPermissions() }
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(deviceList)
        startButton = button("Connect & start charging") { startSession() }
        stopButton = button("Stop charging") {
            startService(Intent(this, ChargingService::class.java).setAction(ChargingService.STOP))
        }
        label("First connection: enter the pairing PIN shown in the ESP32 serial monitor. " +
            "Keep Bluetooth enabled. Reporting stops when the session ends.", 14f)
        label("The relay display shows the controller's command, not a measurement of its contacts.", 13f)
        setContentView(scroll)
    }

    override fun onStart() { super.onStart(); AppState.add(listener) }
    override fun onStop() { stopScan(); AppState.remove(listener); super.onStop() }

    private fun render(state: UiState) {
        battery.text = "${state.percent ?: BatteryReader.read(this) ?: "—"}%"
        status.text = state.message
        relay.text = state.relay
        if (state.running) deviceName.text = state.device
        scanButton.isEnabled = !state.running && !scanning
        startButton.isEnabled = !state.running && selected != null && !scanning
        stopButton.isEnabled = state.running
        deviceList.visibility = if (state.running) View.GONE else View.VISIBLE
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBluetoothPermissions() = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun beginScanWithPermissions() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toMutableList()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 10); return }
        beginScan()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10) {
            if (hasBluetoothPermissions()) beginScan()
            else status.text = "Bluetooth permissions are needed to find and connect to the controller."
        }
    }

    private fun beginScan() {
        if (adapter == null) { status.text = "This phone has no Bluetooth adapter."; return }
        if (adapter?.isEnabled != true) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            status.text = "Enable Bluetooth, then tap Find controller again."
            return
        }
        devices.clear(); deviceList.removeAllViews(); selected = null
        try {
            val scanner = adapter?.bluetoothLeScanner ?: error("Bluetooth scanner unavailable")
            scanning = true
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Wire.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            render(AppState.current)
            status.text = "Searching for 10 seconds… On Android 8–11, also enable Location services."
            handler.postDelayed(stopScanTask, 10_000)
        } catch (e: Exception) { stopScan(); status.text = "Cannot scan: ${e.message}" }
    }

    private fun stopScan() {
        handler.removeCallbacks(stopScanTask)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        render(AppState.current)
        status.text = if (devices.isEmpty()) "No controller found. Check its power and firmware."
            else "Select a controller, then start a session."
    }

    private fun startSession() {
        if (!hasBluetoothPermissions()) { beginScanWithPermissions(); return }
        val device = selected ?: return
        stopScan()
        try {
            startForegroundService(Intent(this, ChargingService::class.java)
                .setAction(ChargingService.START).putExtra(ChargingService.ADDRESS, device.address))
        } catch (e: Exception) { status.text = "Cannot start reporting: ${e.message}" }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
