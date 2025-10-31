package com.example.smartparkingapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

class PaymentSummaryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_summary)

        val slotNum = intent.getIntExtra("slotNum", -1)
        val startMillis = intent.getLongExtra("startTime", 0L)
        val endMillis = intent.getLongExtra("endTime", 0L)

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startTime = sdf.format(Date(startMillis))
        val endTime = sdf.format(Date(endMillis))

        val diffMinutes = ((endMillis - startMillis) / (1000 * 60)).toInt().coerceAtLeast(1)
        val ratePerMinute = 2
        val total = diffMinutes * ratePerMinute

        findViewById<TextView>(R.id.slotInfo).text = "Slot: $slotNum"
        findViewById<TextView>(R.id.timeInfo).text = "From $startTime to $endTime"
        findViewById<TextView>(R.id.durationInfo).text = "Duration: $diffMinutes minutes"
        findViewById<TextView>(R.id.amountInfo).text = "Amount: ₹$total"

        val qrImage = findViewById<ImageView>(R.id.qrImage)
        qrImage.setImageResource(R.drawable.qr_placeholder) // your fake QR image

        val payBtn = findViewById<Button>(R.id.payBtn)
        val backBtn = findViewById<Button>(R.id.backBtn)

        payBtn.setOnClickListener {
            payBtn.text = "Payment Successful ✅"
            payBtn.isEnabled = false
            payBtn.setBackgroundColor(Color.GRAY)
        }

        backBtn.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }
}
