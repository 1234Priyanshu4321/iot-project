package com.example.smartparkingapp

import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.*

/**
 * MainActivity.kt
 *
 * Behavior:
 *  - Tap a FREE slot -> choose start & end time -> slot becomes RESERVED (Blue).
 *  - When start time arrives -> app gives a 10s window for user to confirm arrival by tapping the same slot.
 *      - If user confirms -> slot becomes OCCUPIED (Red) until end time.
 *      - If user doesn't confirm -> slot reverts to FREE (Green).
 *  - When end time passes for an OCCUPIED slot -> it becomes FREE automatically.
 *
 * Notes:
 *  - This implementation schedules checks using Handler + Runnables (main looper).
 *  - Keeps one booking record per slot.
 *  - For quick testing, you can choose start time = current time so the arrival flow triggers immediately.
 */

class MainActivity : AppCompatActivity() {

    private lateinit var slot1Btn: Button
    private lateinit var slot2Btn: Button
    private lateinit var slot3Btn: Button
    private lateinit var statusText: TextView

    // states: "free", "reserved", "occupied"
    private val slotState = mutableMapOf(1 to "free", 2 to "free", 3 to "free")

    // booking info per slot
    private data class Booking(var startMillis: Long = 0L, var endMillis: Long = 0L)

    private val bookings = mutableMapOf<Int, Booking>()

    // Runnables scheduled to trigger start check and end release
    private val startRunnables = mutableMapOf<Int, Runnable>()
    private val endRunnables = mutableMapOf<Int, Runnable>()

    private val handler = Handler(Looper.getMainLooper())

    // active 10-second countdown timers (for arrival confirmation)
    private val confirmTimers = mutableMapOf<Int, CountDownTimer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        slot1Btn = findViewById(R.id.slot1)
        slot2Btn = findViewById(R.id.slot2)
        slot3Btn = findViewById(R.id.slot3)
        statusText = findViewById(R.id.statusText)

        slot1Btn.setOnClickListener { onSlotClicked(1, slot1Btn) }
        slot2Btn.setOnClickListener { onSlotClicked(2, slot2Btn) }
        slot3Btn.setOnClickListener { onSlotClicked(3, slot3Btn) }

        updateStatusText()
        setAllButtonsToFree() // initialize UI
    }

    private fun onSlotClicked(slot: Int, button: Button) {
        when (slotState[slot]) {
            "free" -> startBookingFlow(slot, button)
            "reserved" -> {
                Toast.makeText(this, "Slot is reserved. Wait for start time to confirm arrival.", Toast.LENGTH_SHORT).show()
            }
            "occupied" -> {
                Toast.makeText(this, "Slot is currently occupied.", Toast.LENGTH_SHORT).show()
            }
            else -> { /* no-op */ }
        }
    }

    // START: Booking flow - pick start & end times
    private fun startBookingFlow(slot: Int, button: Button) {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, startHour, startMinute ->
            // Build start millis using today's date + chosen time
            val startCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            TimePickerDialog(this, { _, endHour, endMinute ->
                val endCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // Basic validation: end > start
                if (endCal.timeInMillis <= startCal.timeInMillis) {
                    Toast.makeText(this, "End time must be after start time", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }

                // Save booking
                bookings[slot] = Booking(startCal.timeInMillis, endCal.timeInMillis)
                markReserved(slot, button, startCal.timeInMillis, endCal.timeInMillis)

                // Schedule start check (at startMillis) and end release (at endMillis)
                scheduleStartCheck(slot, button)
                scheduleEndRelease(slot, button)

            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()

        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }
    // END: Booking flow

    // mark button UI reserved
    private fun markReserved(slot: Int, button: Button, startMs: Long, endMs: Long) {
        slotState[slot] = "reserved"
        runOnUiThread {
            button.setBackgroundColor(Color.BLUE)
            button.text = "Slot $slot\nReserved\n${timeString(startMs)} - ${timeString(endMs)}"
            updateStatusText()
            Toast.makeText(this, "Slot $slot reserved", Toast.LENGTH_SHORT).show()
        }
    }

    // schedule runnable to run at start time
    private fun scheduleStartCheck(slot: Int, button: Button) {
        // cancel previous
        startRunnables[slot]?.let { handler.removeCallbacks(it) }
        val booking = bookings[slot] ?: return
        val now = System.currentTimeMillis()
        val delay = booking.startMillis - now

        val startRunnable = Runnable {
            // Only act if slot still reserved
            if (slotState[slot] == "reserved") {
                runOnUiThread { beginArrivalWindow(slot, button, booking.endMillis) }
            }
        }
        startRunnables[slot] = startRunnable

        if (delay <= 0) {
            // start time already reached — immediately begin arrival window
            handler.post(startRunnable)
        } else {
            handler.postDelayed(startRunnable, delay)
        }
    }

    // at start time: give user 10s to confirm arrival, else release
    private fun beginArrivalWindow(slot: Int, button: Button, endMillis: Long) {
        // Change status prompt
        statusText.text = "Start time reached for Slot $slot. Confirm arrival within 10s."

        // Allow button tap to mark occupied during this window
        val arrivalClickListener = { _: Any ->
            // Only allow if still reserved
            if (slotState[slot] == "reserved") {
                // cancel confirm timer if any
                confirmTimers[slot]?.cancel()
                confirmTimers.remove(slot)
                occupySlot(slot, button, endMillis)
            }
        }

        // Set a temporary click handler for arrival confirmation
        button.setOnClickListener { arrivalClickListener(it) }

        // Start 10-second countdown
        val timer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val s = millisUntilFinished / 1000
                statusText.text = "Confirm arrival for Slot $slot: $s s"
            }

            override fun onFinish() {
                // If still reserved -> release
                if (slotState[slot] == "reserved") {
                    Toast.makeText(this@MainActivity, "No arrival — slot $slot released", Toast.LENGTH_SHORT).show()
                    releaseSlot(slot, button)
                }
            }
        }
        timer.start()
        confirmTimers[slot] = timer
    }

    // occupy slot until endMillis
    private fun occupySlot(slot: Int, button: Button, endMillis: Long) {
        // mark occupied
        slotState[slot] = "occupied"
        runOnUiThread {
            button.setBackgroundColor(Color.RED)
            button.text = "Slot $slot\nOccupied\nUntil ${timeString(endMillis)}"
            updateStatusText()
            Toast.makeText(this, "Slot $slot is now Occupied", Toast.LENGTH_SHORT).show()
        }

        // schedule automatic release at endMillis
        scheduleEndRelease(slot, button) // ensure any existing end runnable replaced
    }

    // schedule slot release at end time
    private fun scheduleEndRelease(slot: Int, button: Button) {
        // cancel existing end runnable
        endRunnables[slot]?.let { handler.removeCallbacks(it) }

        val booking = bookings[slot] ?: return
        val now = System.currentTimeMillis()
        val delay = booking.endMillis - now

        val endRunnable = Runnable {
            // release regardless of current state
            runOnUiThread {
                Toast.makeText(this, "End time reached — releasing slot $slot", Toast.LENGTH_SHORT).show()
                releaseSlot(slot, button)
            }
        }
        endRunnables[slot] = endRunnable

        if (delay <= 0) {
            // end time already passed -> immediate release
            handler.post(endRunnable)
        } else {
            handler.postDelayed(endRunnable, delay)
        }
    }

    // release to free state and clean up
    private fun releaseSlot(slot: Int, button: Button) {
        slotState[slot] = "free"
        bookings.remove(slot)

        // cancel scheduled runnables and timers
        startRunnables[slot]?.let { handler.removeCallbacks(it) }
        startRunnables.remove(slot)
        endRunnables[slot]?.let { handler.removeCallbacks(it) }
        endRunnables.remove(slot)
        confirmTimers[slot]?.cancel()
        confirmTimers.remove(slot)

        runOnUiThread {
            button.setBackgroundColor(Color.GREEN)
            button.text = "Slot $slot - Available"
            // restore default click
            button.setOnClickListener { onSlotClicked(slot, button) }
            updateStatusText()
        }
    }

    private fun setAllButtonsToFree() {
        slot1Btn.setBackgroundColor(Color.GREEN); slot1Btn.text = "Slot 1 - Available"
        slot2Btn.setBackgroundColor(Color.GREEN); slot2Btn.text = "Slot 2 - Available"
        slot3Btn.setBackgroundColor(Color.GREEN); slot3Btn.text = "Slot 3 - Available"
    }

    private fun timeString(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        return String.format(Locale.getDefault(), "%02d:%02d", hh, mm)
    }

    private fun updateStatusText() {
        val free = slotState.values.count { it == "free" }
        val reserved = slotState.values.count { it == "reserved" }
        val occupied = slotState.values.count { it == "occupied" }
        statusText.text = "Status -> Free: $free  Reserved: $reserved  Occupied: $occupied"
    }

    override fun onDestroy() {
        super.onDestroy()
        // cleanup handlers/timers
        for (r in startRunnables.values) handler.removeCallbacks(r)
        for (r in endRunnables.values) handler.removeCallbacks(r)
        confirmTimers.values.forEach { it.cancel() }
        startRunnables.clear(); endRunnables.clear(); confirmTimers.clear()
    }
}
