package com.princedeveloper.screenshare

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri // FIXED: Added missing import
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager // FIXED: Added missing import
import android.provider.Settings // FIXED: Added missing import
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_SCREEN_CAPTURE = 100
    private val REQUEST_CODE_PERMISSIONS = 101
    // New request codes for advanced settings
    private val REQUEST_CODE_OVERLAY = 200 
    private val REQUEST_CODE_BATTERY = 201
    
    private lateinit var tvIpAddress: TextView

    private val ipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ip = intent?.getStringExtra("IP_ADDRESS")
            if (ip != null) tvIpAddress.text = ip
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvIpAddress = findViewById(R.id.tvIpAddress)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        tvIpAddress.setOnClickListener {
            val text = tvIpAddress.text.toString()
            if (text != "Not Started") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Stream IP", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }

        btnStart.setOnClickListener {
            // STEP 1: Always stop the old service first to prevent crashes
            stopService(Intent(this, ScreenStreamService::class.java))
            
            // Wait 500ms for the service to die, then start the permission flow
            Handler(Looper.getMainLooper()).postDelayed({
                checkAdvancedPermissions() // Changed to check advanced permissions first
            }, 500)
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenStreamService::class.java))
            tvIpAddress.text = "Not Started"
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.princedeveloper.screenshare.IP_UPDATE")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ipReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(ipReceiver) } catch (e: Exception) {}
    }

    // --- NEW PERMISSION LOGIC ---

    private fun checkAdvancedPermissions() {
        // 1. Check Overlay Permission (Required for Ghost Overlay to keep screen ON)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                                Uri.parse("package:$packageName"))
            // We use startActivityForResult so we know when they come back
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            Toast.makeText(this, "Enable 'Appear on top' to keep connection alive", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Check Battery Optimization (Required for Samsung to not kill app)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivityForResult(intent, REQUEST_CODE_BATTERY)
            return
        }

        // If both are good, proceed to normal permissions (Audio/Notif)
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        // Android 13+ Notification Permission
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Audio Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            startProjection()
        }
    }

    private fun startProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // This generates the "Ticket" for screen sharing
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // Handle Overlay Permission Return
        if (requestCode == REQUEST_CODE_OVERLAY) {
            // Check again, then move to next step
            checkAdvancedPermissions() 
            return
        }
        
        // Handle Battery Permission Return
        if (requestCode == REQUEST_CODE_BATTERY) {
            checkAdvancedPermissions()
            return
        }

        // Handle Screen Share Permission Return
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // STEP 2: Pass the FRESH ticket to the service
                ScreenStreamService.setData(resultCode, data)
                
                // STEP 3: Start the service immediately
                val intent = Intent(this, ScreenStreamService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) startProjection()
    }
}
