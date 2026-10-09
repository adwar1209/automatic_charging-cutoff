package com.adwar.chargingcutoff

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class UiState(
    val running: Boolean = false,
    val message: String = "Choose your controller to begin.",
    val percent: Int? = null,
    val relay: String = "Not connected",
    val device: String = "No controller selected"
)

/** All reads and writes occur on Android's main thread. No activity is retained. */
object AppState {
    var current = UiState()
        private set
    private val listeners = mutableSetOf<(UiState) -> Unit>()
    fun update(state: UiState) {
        current = state
        listeners.toList().forEach { it(state) }
    }
    fun add(listener: (UiState) -> Unit) { listeners.add(listener); listener(current) }
    fun remove(listener: (UiState) -> Unit) { listeners.remove(listener) }
}

object BatteryReader {
    fun read(context: Context): Int? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (scale <= 0 || level < 0 || level > scale) return null
        return ((level.toLong() * 100L) / scale).toInt()
    }
}
