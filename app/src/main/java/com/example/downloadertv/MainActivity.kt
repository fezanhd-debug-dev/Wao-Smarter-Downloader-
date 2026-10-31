package com.example.downloadertv

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.downloadertv.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        generateRandomCode()
    }

    private fun setupUI() {
        // Clock updater simulation
        val currentDate = SimpleDateFormat("HH:mm EEEE, d MMM yyyy", Locale.getDefault()).format(Date())
        binding.txtClock.text = currentDate

        // Code box click listener for TV remote OK button
        binding.layoutCodeBox.setOnClickListener {
            Toast.makeText(this, "Connection code copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        // Sidebar Navigation focus & click handling
        binding.navLocalSend.setOnClickListener {
            Toast.makeText(this, "Local Send selected", Toast.LENGTH_SHORT).show()
            binding.txtStatus.text = "🔍 Searching for local network devices..."
        }

        binding.navSettings.setOnClickListener {
            Toast.makeText(this, "Settings opened", Toast.LENGTH_SHORT).show()
        }

        binding.navDownload.setOnClickListener {
            binding.txtStatus.text = "⏳ Waiting for a device to connect..."
        }
    }

    private fun generateRandomCode() {
        val part1 = Random.nextInt(1000, 9999)
        val part2 = Random.nextInt(1000, 9999)
        val part3 = Random.nextInt(1000, 9999)
        val part4 = Random.nextInt(1000, 9999)
        val code = "$part1-$part2-$part3-$part4"
        binding.txtConnectionCode.text = code
    }
}
