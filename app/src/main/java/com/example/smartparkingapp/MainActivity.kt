package com.example.smartparkingapp

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var slot1Btn: Button
    private lateinit var slot2Btn: Button
    private lateinit var slot3Btn: Button
    private lateinit var statusText: TextView

    private val slotState = mutableMapOf(1 to "free", 2 to "free", 3 to "free")

    private data class Booking(var startMillis: Long = 0L, var endMillis: Long = 0L)
    private val bookings = mutableMapOf<Int, Booking>()

    private val handler = Handler(Looper.getMainLooper())
    private val startRunnables = mutableMapOf<Int, Runnable>()
    private val endRunnables = mutableMapOf<Int, Runnable>()
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
        setAllButtonsToFree()
    }

    private fun onSlotClicked(slot: Int, button: Button) {
        when (slotState[slot]) {
            "free" -> startBookingFlow(slot, button)
            "reserved" -> Toast.makeText(this, "Slot reserved — wait for start time", Toast.LENGTH_SHORT).show()
            "occupied" -> Toast.makeText(this, "Slot currently occupied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBookingFlow(slot: Int, button: Button) {
        val now = Calendar.getInstance()

        TimePickerDialog(this, { _, startHour, startMinute ->
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

                if (endCal.timeInMillis <= startCal.timeInMillis) {
                    Toast.makeText(this, "End time must be after start time", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }

                bookings[slot] = Booking(startCal.timeInMillis, endCal.timeInMillis)
                markReserved(slot, button, startCal.timeInMillis, endCal.timeInMillis)
                scheduleStartCheck(slot, button)
                scheduleEndRelease(slot, button)

            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()

        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }

    private fun markReserved(slot: Int, button: Button, startMs: Long, endMs: Long) {
        slotState[slot] = "reserved"
        runOnUiThread {
            button.setBackgroundColor(Color.BLUE)
            button.text = "Slot $slot\nReserved\n${timeString(startMs)} - ${timeString(endMs)}"
            updateStatusText()
            Toast.makeText(this, "Slot $slot reserved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleStartCheck(slot: Int, button: Button) {
        startRunnables[slot]?.let { handler.removeCallbacks(it) }
        val booking = bookings[slot] ?: return
        val delay = booking.startMillis - System.currentTimeMillis()

        val startRunnable = Runnable {
            if (slotState[slot] == "reserved") beginArrivalWindow(slot, button, booking.endMillis)
        }

        startRunnables[slot] = startRunnable
        if (delay <= 0) handler.post(startRunnable) else handler.postDelayed(startRunnable, delay)
    }

    private fun beginArrivalWindow(slot: Int, button: Button, endMillis: Long) {
        statusText.text = "Start time reached for Slot $slot! Confirm within 10s."
        val arrivalClickListener = { _: Any ->
            if (slotState[slot] == "reserved") {
                confirmTimers[slot]?.cancel()
                occupySlot(slot, button, endMillis)
            }
        }
        button.setOnClickListener { arrivalClickListener(it) }

        val timer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                statusText.text = "Confirm arrival for Slot $slot: ${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                if (slotState[slot] == "reserved") {
                    Toast.makeText(applicationContext, "No arrival — releasing Slot $slot", Toast.LENGTH_SHORT).show()
                    releaseSlot(slot, button)
                }
            }
        }
        timer.start()
        confirmTimers[slot] = timer
    }

    private fun occupySlot(slot: Int, button: Button, endMillis: Long) {
        slotState[slot] = "occupied"
        runOnUiThread {
            button.setBackgroundColor(Color.RED)
            button.text = "Slot $slot\nOccupied\nUntil ${timeString(endMillis)}"
            updateStatusText()
        }
        scheduleEndRelease(slot, button)
    }

    private fun scheduleEndRelease(slot: Int, button: Button) {
        endRunnables[slot]?.let { handler.removeCallbacks(it) }
        val booking = bookings[slot] ?: return
        val delay = booking.endMillis - System.currentTimeMillis()

        val endRunnable = Runnable {
            if (slotState[slot] == "occupied") {
                runOnUiThread { promptPaymentOrExtension(slot, button) }
            } else releaseSlot(slot, button)
        }
        endRunnables[slot] = endRunnable
        if (delay <= 0) handler.post(endRunnable) else handler.postDelayed(endRunnable, delay)
    }

    private fun promptPaymentOrExtension(slot: Int, button: Button) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Parking Time Ended")
        builder.setMessage("Extend parking or proceed to payment?")
        var userResponded = false

        builder.setPositiveButton("Extend Time") { dialog, _ ->
            userResponded = true
            dialog.dismiss()
            extendParkingTime(slot, button)
        }

        builder.setNegativeButton("Pay Bill") { dialog, _ ->
            userResponded = true
            dialog.dismiss()
            showPaymentScreen(slot)
            releaseSlot(slot, button)
        }

        val dialog = builder.create()
        dialog.show()

        object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                statusText.text = "Choose within ${millisUntilFinished / 1000}s for Slot $slot"
            }

            override fun onFinish() {
                if (!userResponded) {
                    dialog.dismiss()
                    Toast.makeText(applicationContext, "No response — going to payment", Toast.LENGTH_SHORT).show()
                    showPaymentScreen(slot)
                    releaseSlot(slot, button)
                }
            }
        }.start()
    }

    private fun extendParkingTime(slot: Int, button: Button) {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, newHour, newMinute ->
            val newEnd = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, newHour)
                set(Calendar.MINUTE, newMinute)
            }
            bookings[slot]?.endMillis = newEnd.timeInMillis
            Toast.makeText(this, "Extended until ${timeString(newEnd.timeInMillis)}", Toast.LENGTH_SHORT).show()
            button.text = "Slot $slot\nOccupied\nUntil ${timeString(newEnd.timeInMillis)}"
            scheduleEndRelease(slot, button)
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }

    private fun showPaymentScreen(slot: Int) {
        val booking = bookings[slot]
        if (booking != null) {
            val intent = Intent(this, PaymentSummaryActivity::class.java)
            intent.putExtra("slotNum", slot)
            intent.putExtra("startTime", booking.startMillis)
            intent.putExtra("endTime", booking.endMillis)
            startActivity(intent)
        } else Toast.makeText(this, "No booking info found", Toast.LENGTH_SHORT).show()
    }

    private fun releaseSlot(slot: Int, button: Button) {
        slotState[slot] = "free"
        bookings.remove(slot)
        startRunnables[slot]?.let { handler.removeCallbacks(it) }
        endRunnables[slot]?.let { handler.removeCallbacks(it) }
        confirmTimers[slot]?.cancel()
        runOnUiThread {
            button.setBackgroundColor(Color.GREEN)
            button.text = "Slot $slot - Available"
            button.setOnClickListener { onSlotClicked(slot, button) }
            updateStatusText()
        }
    }

    private fun setAllButtonsToFree() {
        slot1Btn.setBackgroundColor(Color.GREEN)
        slot1Btn.text = "Slot 1 - Available"
        slot2Btn.setBackgroundColor(Color.GREEN)
        slot2Btn.text = "Slot 2 - Available"
        slot3Btn.setBackgroundColor(Color.GREEN)
        slot3Btn.text = "Slot 3 - Available"
    }

    private fun timeString(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        return String.format("%02d:%02d", hh, mm)
    }

    private fun updateStatusText() {
        val free = slotState.values.count { it == "free" }
        val reserved = slotState.values.count { it == "reserved" }
        val occupied = slotState.values.count { it == "occupied" }
        statusText.text = "Free: $free | Reserved: $reserved | Occupied: $occupied"
    }

    override fun onDestroy() {
        super.onDestroy()
        startRunnables.values.forEach { handler.removeCallbacks(it) }
        endRunnables.values.forEach { handler.removeCallbacks(it) }
        confirmTimers.values.forEach { it.cancel() }
    }
}
