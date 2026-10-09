package com.adwar.chargingcutoff

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import java.security.SecureRandom

/** One GATT operation at a time; explicit status reads acknowledge each command. */
@SuppressLint("MissingPermission") // The activity gates runtime Bluetooth permission; failures close the session.
@Suppress("DEPRECATION")
class ChargingService : Service() {
    companion object {
        const val START = "com.adwar.chargingcutoff.START"
        const val STOP = "com.adwar.chargingcutoff.STOP"
        const val ADDRESS = "address"
        private const val CHANNEL = "charging_session"
        private const val NOTIFICATION = 1
    }
    private enum class Phase { IDLE, CONNECTING, PAIRING, DISCOVERING, SUBSCRIBING, INITIAL_READ, WRITING, ACK_READ, ACTIVE, CLOSED }
    private val handler = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var nonce = 0L
    private var session = 0L
    private var sequence = 0L
    private var lastPercent: Int? = null
    private var pendingPercent: Int? = null
    private var stopRequested = false
    private var expectedOperation = Wire.START
    private var receiversRegistered = false
    private var batteryRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var deadline: Runnable? = null
    private val heartbeat = Runnable { reportBattery(true) }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (phase == Phase.CLOSED) return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                fail("Bluetooth was turned off."); return
            }
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED || phase != Phase.PAIRING) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device.address != gatt?.device?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> discover()
                BluetoothDevice.BOND_NONE -> fail("Pairing failed or was cancelled. Start again to retry.")
            }
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { reportBattery(false) }
    }

    private fun callbackOnMain(source: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (source === gatt && phase != Phase.CLOSED) {
                try { action() } catch (e: Exception) { fail("Bluetooth operation failed: ${e.message}") }
            }
        }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = callbackOnMain(g) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("Connection lost (status $status). The controller ends the session on disconnect.")
            } else if (newState == BluetoothProfile.STATE_CONNECTED && phase == Phase.CONNECTING) {
                if (g.device.bondState == BluetoothDevice.BOND_BONDED) discover()
                else {
                    phase = Phase.PAIRING
                    update("Enter the pairing PIN from the ESP32 serial monitor.", "Charging off during pairing")
                    setDeadline(90_000, "Pairing timed out.")
                    if (g.device.bondState != BluetoothDevice.BOND_BONDING && !g.device.createBond())
                        fail("The phone could not begin pairing.")
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = callbackOnMain(g) {
            if (phase != Phase.DISCOVERING) return@callbackOnMain
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed ($status)."); return@callbackOnMain }
            val service = g.getService(Wire.SERVICE)
            commandChar = service?.getCharacteristic(Wire.COMMAND)
            statusChar = service?.getCharacteristic(Wire.STATUS)
            val characteristic = statusChar
            val cccd = characteristic?.getDescriptor(Wire.CCCD)
            if (commandChar == null || characteristic == null || cccd == null) {
                fail("This controller does not provide the expected charging service."); return@callbackOnMain
            }
            phase = Phase.SUBSCRIBING
            setDeadline(10_000, "Status subscription timed out.")
            if (!g.setCharacteristicNotification(characteristic, true)) {
                fail("Could not enable status updates."); return@callbackOnMain
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(cccd)) fail("Could not subscribe to controller status.")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = callbackOnMain(g) {
            if (phase != Phase.SUBSCRIBING || descriptor.uuid != Wire.CCCD) return@callbackOnMain
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Secure subscription failed ($status)."); return@callbackOnMain }
            phase = Phase.INITIAL_READ
            readStatus()
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = callbackOnMain(g) {
            if (phase != Phase.WRITING || characteristic.uuid != Wire.COMMAND) return@callbackOnMain
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Command write failed ($status)."); return@callbackOnMain }
            phase = Phase.ACK_READ
            readStatus()
        }
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handleRead(g, characteristic.uuid, characteristic.value?.clone(), status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, characteristic.uuid, value.clone(), status)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotification(g, characteristic.value?.clone())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == Wire.STATUS) handleNotification(g, value.clone())
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (phase == Phase.IDLE || phase == Phase.CLOSED) stopSelf()
            else if (session == 0L) finishSession("Stopped before charging started.", "Disconnected")
            else { stopRequested = true; if (phase == Phase.ACTIVE) send(Wire.STOP, 255) }
            return START_NOT_STICKY
        }
        if (intent?.action != START || phase != Phase.IDLE) return START_NOT_STICKY
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Charging session", NotificationManager.IMPORTANCE_LOW))
            val notification = notification("Connecting to controller…")
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
            val address = intent.getStringExtra(ADDRESS) ?: error("No controller selected")
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("Bluetooth unavailable")
            check(adapter.isEnabled) { "Bluetooth is disabled" }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ChargeCutoff:BatteryReports").apply {
                setReferenceCounted(false); acquire(120_000)
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
            else registerReceiver(bluetoothReceiver, filter)
            receiversRegistered = true
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
            else registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered = true
            phase = Phase.CONNECTING
            AppState.update(UiState(true, "Connecting…", BatteryReader.read(this), "Waiting for controller", address))
            setDeadline(30_000, "Connection timed out.")
            gatt = adapter.getRemoteDevice(address).connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: error("Could not open Bluetooth connection")
        } catch (e: Exception) { fail("Cannot start session: ${e.message}") }
        return START_NOT_STICKY
    }

    private fun discover() {
        phase = Phase.DISCOVERING
        update("Reading controller services…", "Waiting for controller")
        setDeadline(15_000, "Service discovery timed out.")
        if (gatt?.discoverServices() != true) fail("Could not discover services.")
    }
    private fun readStatus() {
        setDeadline(10_000, "Controller acknowledgement timed out.")
        if (gatt?.readCharacteristic(statusChar) != true) fail("Could not read controller status.")
    }
    private fun handleRead(g: BluetoothGatt, uuid: java.util.UUID, bytes: ByteArray?, status: Int) = callbackOnMain(g) {
        if (uuid != Wire.STATUS || (phase != Phase.INITIAL_READ && phase != Phase.ACK_READ)) return@callbackOnMain
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("Secure status read failed ($status)."); return@callbackOnMain }
        val response = Wire.decode(bytes)
        clearDeadline()
        if (phase == Phase.INITIAL_READ) {
            if (response.state != Wire.READY || response.nonce == 0L || response.session != 0L) {
                fail("Controller is not ready. Disconnect and start a new session."); return@callbackOnMain
            }
            nonce = response.nonce
            do { session = Integer.toUnsignedLong(SecureRandom().nextInt()) } while (session == 0L)
            val percent = BatteryReader.read(this)
            if (percent == null) fail("Android did not provide a valid battery percentage.")
            else send(Wire.START, percent)
            return@callbackOnMain
        }
        if (response.nonce != nonce || response.session != session) {
            fail("Controller replied for a different session."); return@callbackOnMain
        }
        if (response.state == Wire.FAULT) { finishSession(Wire.reasonText(response.reason), "Relay commanded OFF"); return@callbackOnMain }
        if (response.sequence != sequence) { fail("Controller did not acknowledge the latest report."); return@callbackOnMain }
        if (response.state == Wire.ENDED) {
            AppState.update(AppState.current.copy(percent = response.percent.takeIf { it <= 100 }))
            finishSession(Wire.reasonText(response.reason), "Relay commanded OFF"); return@callbackOnMain
        }
        if (response.state != Wire.CHARGING || expectedOperation == Wire.STOP) {
            fail("Unexpected controller response."); return@callbackOnMain
        }
        phase = Phase.ACTIVE
        lastPercent = response.percent
        wakeLock?.acquire(120_000)
        update("Reporting every 30 seconds. Cutoff at 100%.", "Relay commanded ON", response.percent)
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, Wire.HEARTBEAT_MS)
        if (stopRequested) send(Wire.STOP, 255)
        else {
            val pending = pendingPercent
            pendingPercent = null
            if (pending != null && pending != lastPercent) send(Wire.REPORT, pending)
        }
    }
    private fun handleNotification(g: BluetoothGatt, bytes: ByteArray?) = callbackOnMain(g) {
        // Reads acknowledge normal transactions. Fault notifications can end a session immediately.
        val response = Wire.decode(bytes)
        if (session != 0L && response.nonce == nonce && response.session == session && response.state == Wire.FAULT)
            finishSession(Wire.reasonText(response.reason), "Relay commanded OFF")
    }

    private fun reportBattery(force: Boolean) {
        if (session == 0L || phase == Phase.CLOSED || stopRequested) return
        val percent = BatteryReader.read(this)
        if (percent == null) { fail("Android battery data became unavailable."); return }
        if (phase == Phase.ACTIVE) {
            if (force || percent != lastPercent) send(Wire.REPORT, percent)
        } else pendingPercent = percent
    }
    private fun send(operation: Int, percent: Int) {
        try {
            if (phase == Phase.CLOSED) return
            if (sequence == 0xffffffffL) { fail("Session sequence exhausted. Start a new session."); return }
            sequence++
            expectedOperation = operation
            val characteristic = commandChar ?: error("Command characteristic missing")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = Wire.command(operation, percent, nonce, session, sequence)
            phase = Phase.WRITING
            handler.removeCallbacks(heartbeat)
            setDeadline(10_000, "Battery report timed out.")
            if (gatt?.writeCharacteristic(characteristic) != true) fail("Could not send battery report.")
        } catch (e: Exception) { fail("Could not send report: ${e.message}") }
    }

    private fun setDeadline(ms: Long, message: String) {
        clearDeadline()
        deadline = Runnable { fail(message) }.also { handler.postDelayed(it, ms) }
    }
    private fun clearDeadline() { deadline?.let { handler.removeCallbacks(it) }; deadline = null }
    private fun update(message: String, relay: String, percent: Int? = AppState.current.percent) {
        AppState.update(AppState.current.copy(running = true, message = message, relay = relay, percent = percent))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ChargingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_charge)
            .setContentTitle("Charge Cutoff").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private fun fail(message: String) = finishSession(message, "Disconnected · relay state unconfirmed")
    private fun finishSession(message: String, relay: String) {
        if (phase == Phase.CLOSED) return
        AppState.update(AppState.current.copy(running = false, message = message, relay = relay))
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun cleanup() {
        phase = Phase.CLOSED
        clearDeadline(); handler.removeCallbacks(heartbeat)
        val old = gatt; gatt = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        try { old?.close() } catch (_: SecurityException) { }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (receiversRegistered) { unregisterReceiver(bluetoothReceiver); receiversRegistered = false }
        if (batteryRegistered) { unregisterReceiver(batteryReceiver); batteryRegistered = false }
    }
    override fun onDestroy() {
        if (phase != Phase.CLOSED) {
            AppState.update(AppState.current.copy(running = false, message = "Reporting service stopped.", relay = "Disconnected · relay state unconfirmed"))
            cleanup()
        }
        super.onDestroy()
    }
}
