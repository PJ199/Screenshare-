package com.princedeveloper.screenshare

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class ScreenStreamService : Service() {

    companion object {
        private var staticResultCode: Int = 0
        private var staticResultData: Intent? = null

        fun setData(code: Int, data: Intent) {
            staticResultCode = code
            staticResultData = data
        }
    }

    private var fakeOverlayView: View? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private var webServer: WebServer? = null
    private var serverPort = 8080

    private var orientationListener: OrientationEventListener? = null
    private var lastRotation = -1

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val TARGET_HEIGHT = 1080
    @Volatile private var jpegQuality = 60
    private val SAMPLE_RATE = 44100
    private var audioRecord: AudioRecord? = null
    private var isAudioRunning = false
    private val audioPipeOut = PipedOutputStream()
    private val audioPipeIn = PipedInputStream(audioPipeOut, 8192)
    private var isStableMode = false
    
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        try {
            acquireLocks()
            setupGhostOverlay()

            if (staticResultData != null) {
                val code = staticResultCode
                val data = staticResultData!!
                staticResultData = null
                staticResultCode = 0
                
                cleanupProjection()
                setupMediaProjection(code, data)
            } else if (mediaProjection == null) {
                Log.e("ScreenShare", "Service started without Permission Data")
                showError("Restart App") 
            }
        } catch (e: Exception) {
            Log.e("ScreenShare", "Service Crash: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // --- UPDATED: RESIZE INSTEAD OF RECREATE ---
    private fun createVirtualDisplay() {
        try {
            if (mediaProjection == null) return
            
            // 1. Calculate new dimensions based on rotation
            val metrics = getDeviceMetrics()
            val rawWidth = metrics.widthPixels
            val rawHeight = metrics.heightPixels
            val scale = TARGET_HEIGHT.toFloat() / kotlin.math.min(rawWidth, rawHeight)
            val width = (rawWidth * scale).roundToInt()
            val height = (rawHeight * scale).roundToInt()
            val alignWidth = if (width % 2 == 0) width else width - 1
            val alignHeight = if (height % 2 == 0) height else height - 1

            // 2. We MUST perform a resize logic.
            // Close the old ImageReader because its size is fixed.
            val oldReader = imageReader
            imageReader = ImageReader.newInstance(alignWidth, alignHeight, PixelFormat.RGBA_8888, 2)
            oldReader?.close()

            if (virtualDisplay == null) {
                // FIRST TIME: Create the display
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenShare", alignWidth, alignHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )
            } else {
                // SUBSEQUENT TIMES: RESIZE IT (Fixes the "Multiple Captures" crash)
                virtualDisplay?.resize(alignWidth, alignHeight, metrics.densityDpi)
                virtualDisplay?.setSurface(imageReader?.surface)
                Log.d("ScreenShare", "VirtualDisplay Resized (Rotated)")
            }
            
        } catch (e: Throwable) {
            Log.e("ScreenShare", "VirtualDisplay Failed: ${e.message}")
            // If resize fails, we might need to stopSelf, but let's try to survive
        }
    }

    private fun setupOrientationListener() {
        try {
            // Fix: Initialize lastRotation so it doesn't trigger immediately on start
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            lastRotation = display?.rotation ?: 0

            orientationListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(orientation: Int) {
                    val d = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    val rotation = d?.rotation ?: 0
                    if (rotation != lastRotation) {
                        lastRotation = rotation
                        // 600ms delay to let the screen rotation animation finish
                        handler.postDelayed({ createVirtualDisplay() }, 600)
                    }
                }
            }
            orientationListener?.enable()
        } catch (e: Exception) {}
    }

    private fun setupMediaProjection(code: Int, data: Intent) {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                mediaProjection = mpManager.getMediaProjection(code, data)
            } catch (e: Exception) {
                Log.e("ScreenShare", "Permission Error: ${e.message}")
                showError("Restart App")
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.e("ScreenShare", "System stopped MediaProjection")
                    cleanupProjection()
                    stopSelf()
                }
            }, handler)

            createVirtualDisplay()
            setupOrientationListener()
            startServer()

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startAudioCapture()
            }

        } catch (e: Exception) {
            showError("Setup Failed: ${e.message}")
            stopSelf()
        }
    }

    private fun setupGhostOverlay() {
        if (Settings.canDrawOverlays(this)) {
            try {
                if (fakeOverlayView != null) return
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                val params = WindowManager.LayoutParams(
                    1, 1,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                fakeOverlayView = View(this)
                windowManager.addView(fakeOverlayView, params)
            } catch (e: Exception) {
                Log.e("ScreenShare", "Overlay Error: ${e.message}")
            }
        }
    }

    private fun removeGhostOverlay() {
        try {
            if (fakeOverlayView != null) {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.removeView(fakeOverlayView)
                fakeOverlayView = null
            }
        } catch (e: Exception) {}
    }

    // --- AUDIO & SERVER HELPERS ---

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT >= 29 && mediaProjection != null) {
            thread(priority = Thread.MAX_PRIORITY) {
                try {
                    if (mediaProjection == null) return@thread
                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                    val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    
                    audioRecord = AudioRecord.Builder().setAudioFormat(format)
                        .setBufferSizeInBytes(minBuffer * 2).setAudioPlaybackCaptureConfig(config).build()

                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord?.startRecording()
                        isAudioRunning = true
                        try { audioPipeOut.write(getWavHeader(SAMPLE_RATE, 2, 16)) } catch (_: Exception) {}
                        val buffer = ByteArray(4096)
                        while (isAudioRunning && mediaProjection != null) {
                            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (read > 0) try { audioPipeOut.write(buffer, 0, read) } catch (e: Exception) { break }
                        }
                    }
                } catch (e: Exception) { Log.e("ScreenShare", "Audio Error: ${e.message}") } 
                finally { try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {} }
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "screen_share_channel"
        val chan = NotificationChannel(channelId, "Screen Share", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Screen Share Active").setContentText("Streaming...").setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
        
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(1, notif, type)
        } else {
            startForeground(1, notif)
        }
    }

    private fun cleanupProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        audioRecord = null
        mediaProjection = null
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenShare::WakeLock")
                wakeLock?.acquire(4*60*60*1000L)
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wm.isWifiEnabled) {
                     val lockType = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wm.createWifiLock(lockType, "ScreenShare::WifiLock")
                    wifiLock?.acquire()
                }
            }
        } catch (e: Exception) {}
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun showIpAddress() {
        thread {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                var bestIp = ""
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168")) { bestIp = ip; break }
                            if (bestIp.isEmpty()) bestIp = ip
                        }
                    }
                }
                if (bestIp.isNotEmpty()) {
                    val fullAddress = "http://$bestIp:$serverPort"
                    val intent = Intent("com.princedeveloper.screenshare.IP_UPDATE")
                    intent.putExtra("IP_ADDRESS", fullAddress)
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                    handler.post { Toast.makeText(this, "Active: $fullAddress", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {}
        }
    }

    private fun getDeviceMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) display.getRealMetrics(metrics) else { metrics.widthPixels=1080; metrics.heightPixels=1920; metrics.densityDpi=320 }
        return metrics
    }

    private fun getWavHeader(sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
         val header = ByteArray(44)
         val totalDataLen = Int.MAX_VALUE - 44
         val byteRate = sampleRate * channels * bitDepth / 8
         header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.toByte()
         header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte()
         header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
         header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
         header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
         header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; header[20] = 1; header[21] = 0
         header[22] = channels.toByte(); header[23] = 0; header[24] = (sampleRate and 0xff).toByte()
         header[25] = ((sampleRate shr 8) and 0xff).toByte(); header[26] = ((sampleRate shr 16) and 0xff).toByte()
         header[27] = ((sampleRate shr 24) and 0xff).toByte(); header[28] = (byteRate and 0xff).toByte()
         header[29] = ((byteRate shr 8) and 0xff).toByte(); header[30] = ((byteRate shr 16) and 0xff).toByte()
         header[31] = ((byteRate shr 24) and 0xff).toByte(); header[32] = (channels * bitDepth / 8).toByte(); header[33] = 0
         header[34] = bitDepth.toByte(); header[35] = 0; header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
         header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte(); header[40] = (totalDataLen and 0xff).toByte()
         header[41] = ((totalDataLen shr 8) and 0xff).toByte(); header[42] = ((totalDataLen shr 16) and 0xff).toByte()
         header[43] = ((totalDataLen shr 24) and 0xff).toByte()
         return header
    }

    private fun startServer() {
        if (webServer != null) return
        thread(start = true) {
            for (port in 8080..8090) {
                try {
                    val server = WebServer(port)
                    server.start()
                    webServer = server
                    serverPort = port
                    showIpAddress()
                    return@thread
                } catch (_: Exception) {}
            }
            handler.post { showError("Error: No Port") }
        }
    }

    private fun showError(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        removeGhostOverlay()
        super.onDestroy()
        isAudioRunning = false
        orientationListener?.disable()
        releaseLocks()
        cleanupProjection()
        try { webServer?.stop() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class WebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val params = session.parms 
            return when {
                uri == "/" -> newFixedLengthResponse(Response.Status.OK, MIME_HTML, getWebPageHtml())
                uri.startsWith("/cmd") -> {
                    handleCommand(params)
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
                }
                uri == "/stream" -> MjpegResponse()
                uri == "/audio.wav" -> {
                    val resp = newChunkedResponse(Response.Status.OK, "audio/wav", audioPipeIn)
                    resp.addHeader("Cache-Control", "no-cache")
                    return resp
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }
    }

    private inner class MjpegResponse : NanoHTTPD.Response(
        Status.OK, "multipart/x-mixed-replace; boundary=--boundary", null, -1
    ) {
        init { try { setChunkedTransfer(true) } catch(_: Throwable){} }
        override fun send(outputStream: java.io.OutputStream) {
            val pw = java.io.PrintWriter(outputStream)
            pw.print("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=--boundary\r\n\r\n")
            pw.flush()
            val jpgStream = ByteArrayOutputStream(32768)
            try {
                while (true) {
                    val reader = imageReader ?: break
                    var image: android.media.Image? = null
                    var bitmap: Bitmap? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            if (planes.isNotEmpty()) {
                                val buffer = planes[0].buffer
                                val pixelStride = planes[0].pixelStride
                                val rowStride = planes[0].rowStride
                                val rowPadding = rowStride - pixelStride * image.width
                                val w = image.width + rowPadding / pixelStride
                                val h = image.height
                                if (w > 0 && h > 0) {
                                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    bitmap.copyPixelsFromBuffer(buffer)
                                }
                            }
                        }
                    } catch (_: Exception) {} finally { image?.close() }

                    if (bitmap != null) {
                        jpgStream.reset()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpgStream)
                        val jpgData = jpgStream.toByteArray()
                        bitmap.recycle()
                        sendFrame(outputStream, jpgData)
                    }
                    Thread.sleep(if (isStableMode) 60 else 20)
                }
            } catch (_: Throwable) {}
        }
        
        private fun sendFrame(out: java.io.OutputStream, data: ByteArray) {
            out.write("--boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    private fun handleCommand(params: Map<String, String>) {
         val action = params["action"]
         if (action == "toggle_stable") {
            isStableMode = params["val"] == "true"
            return
        }
        if (action == "set_quality") {
            val q = params["val"]?.toIntOrNull() ?: 60
            jpegQuality = q.coerceIn(10, 100)
            return
        }
        val service = ControlAccessibilityService.instance ?: return
        val metrics = getDeviceMetrics()
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        try {
            when (action) {
                "tap" -> {
                    val xPct = params["x"]?.toFloatOrNull() ?: 0.5f
                    val yPct = params["y"]?.toFloatOrNull() ?: 0.5f
                    service.performTap(xPct * w, yPct * h)
                }
                "swipe_left"  -> service.swipeLeft()
                "swipe_right" -> service.swipeRight()
                "swipe_up"    -> service.performSwipe(w/2, h*0.7f, w/2, h*0.3f) 
                "swipe_down"  -> service.performSwipe(w/2, h*0.3f, w/2, h*0.7f) 
                "scroll_up"   -> service.performScroll(w/2, h*0.4f, w/2, h*0.6f) 
                "scroll_down" -> service.performScroll(w/2, h*0.6f, w/2, h*0.4f) 
                "back"        -> service.performBack()
                "home"        -> service.performHome()
                "text"        -> params["val"]?.let { service.injectText(it) }
            }
        } catch (_: Throwable) {}
    }

    private fun getWebPageHtml(): String {
        return """<!DOCTYPE html><html>
            <head>
                <meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>
                <title>ScreenShare Stream</title>
                <style>
                    :root { --accent: #00E676; --glass: rgba(20, 20, 20, 0.95); --text: #eee; }
                    body { background: #000; margin: 0; overflow: hidden; font-family: 'Segoe UI', sans-serif; padding-top: 50px; }
                    .navbar { position: fixed; top: 0; left: 0; width: 100%; height: 50px; background: linear-gradient(90deg, #111, #000); display: flex; align-items: center; justify-content: space-between; padding: 0 15px; box-shadow: 0 2px 10px rgba(0,0,0,0.8); z-index: 9999; box-sizing: border-box; }
                    .brand { color: white; font-weight: bold; font-size: 16px; display: flex; align-items: center; gap: 8px; }
                    .live-dot { width: 8px; height: 8px; background: red; border-radius: 50%; box-shadow: 0 0 10px red; animation: pulse 1.5s infinite; }
                    .menu-icon { font-size: 24px; color: white; cursor: pointer; user-select: none; }
                    .dropdown { position: fixed; top: 55px; right: 10px; background: var(--glass); backdrop-filter: blur(10px); border: 1px solid #444; border-radius: 8px; width: 220px; padding: 12px; display: none; flex-direction: column; gap: 10px; z-index: 1001; }
                    .dropdown.show { display: flex; }
                    #stream-container { width: 100%; height: calc(100vh - 50px); overflow: auto; -webkit-overflow-scrolling: touch; display: flex; align-items: center; justify-content: center; background: #000; }
                    #stream { max-width: 100%; max-height: 100%; width: auto; height: auto; object-fit: contain; cursor: crosshair; display: block; }
                    #panel { position: fixed; top: 70px; left: 20px; background: var(--glass); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); padding: 15px; border-radius: 12px; width: 180px; min-width: 160px; min-height: 250px; max-height: 70vh; overflow: auto; resize: both; box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.7); z-index: 1000; }
                    .handle { width: 100%; height: 5px; background: #444; border-radius: 10px; margin-bottom: 15px; cursor: move; }
                    .section-title { font-size: 10px; text-transform: uppercase; color: #aaa; letter-spacing: 1px; margin: 8px 0 4px 0; }
                    button { width: 100%; padding: 12px; margin: 4px 0; background: rgba(255,255,255,0.08); color: white; border: none; border-radius: 6px; font-weight: 600; font-size: 12px; cursor: pointer; transition: 0.2s; }
                    button:active { background: var(--accent); color: black; }
                    .btn-toggle { background: #E65100; border-left: 4px solid #BF360C; }
                    .btn-toggle.on { background: #2E7D32; border-left: 4px solid #1B5E20; }
                    .zoom-controls { display: flex; gap: 5px; margin-top: 10px; }
                    .btn-zoom { background: #424242; font-size: 16px; }
                    .row { display: flex; gap: 8px; }
                    .btn-nav { background: #263238; }
                    input[type=range] { width: 100%; margin: 10px 0; }
                    input[type=text] { width: 100%; box-sizing: border-box; margin-top: 10px; padding: 10px; border: 1px solid #444; border-radius: 6px; background: #222; color: white; }
                    @keyframes pulse { 0% { opacity: 0.5; } 50% { opacity: 1; } 100% { opacity: 0.5; } }
                </style>
            </head>
            <body>
                <div class="navbar">
                    <div class="brand"><div class="live-dot" id="liveDot"></div>ScreenShare</div>
                    <div class="menu-icon" onclick="toggleMenu()">&#9776;</div>
                </div>

                <div id="dropdown" class="dropdown">
                    <div class="section-title">Quality (Re-buffer on change)</div>
                    <input type="range" min="10" max="100" value="60" onchange="setQuality(this.value)">
                    <div class="section-title">Settings</div>
                    <button id="btnAudio" class="btn-toggle" onclick="toggleAudio()">🔇 Start Audio</button>
                    <button id="btnStable" class="btn-toggle" onclick="toggleStable()">⚡ Realtime Mode</button>
                    <button onclick="resetZoom()" style="background:#444">🔍 Reset Zoom</button>
                    <div style="font-size:10px; color:#666; text-align:center; margin-top:5px;">Made by Prince N Jose (Developer,kochi,kerala)</div>
                </div>

                <div id="stream-container">
                    <img id='stream' src='/stream' onerror="this.src='/stream?'+new Date().getTime()" />
                </div>
                
                <audio id="audio" style="display:none"></audio>

                <div id="panel">
                    <div class="handle"></div>
                    <div class="section-title">View Controls</div>
                    <div class="zoom-controls">
                        <button class="btn-zoom" onclick="zoom(5)">+</button>
                        <button class="btn-zoom" onclick="zoom(-5)">-</button>
                        <button class="btn-zoom" onclick="resetZoom()">1x</button>
                    </div>
                    <div class="section-title">Scroll</div>
                    <div class="row">
                        <button onmousedown="startScroll('scroll_up')" onmouseup="stopScroll()" ontouchstart="startScroll('scroll_up')" ontouchend="stopScroll()">⬆ UP</button>
                        <button onmousedown="startScroll('scroll_down')" onmouseup="stopScroll()" ontouchstart="startScroll('scroll_down')" ontouchend="stopScroll()">⬇ DOWN</button>
                    </div>
                    <div class="section-title">Gestures</div>
                    <button onclick="f('swipe_up')">SWIPE UP</button>
                    <button onclick="f('swipe_down')">SWIPE DOWN</button>
                    <div class="row">
                        <button onclick="f('swipe_left')">LEFT</button>
                        <button onclick="f('swipe_right')">RIGHT</button>
                    </div>
                    <div class="section-title">Navigation</div>
                    <div class="row">
                        <button class="btn-nav" onclick="f('back')">BACK</button>
                        <button class="btn-nav" onclick="f('home')">HOME</button>
                    </div>
                    <input type="text" id='t' placeholder='Type & Send...' onkeydown="if(event.key==='Enter') ft()">
                    <button onclick="ft()" style="background:#1565C0; margin-top:5px;">SEND TEXT</button>
                </div>

                <script>
                    function f(a){ fetch('/cmd?action='+a); }
                    function ft(){ fetch('/cmd?action=text&val='+document.getElementById('t').value); document.getElementById('t').value = ''; }
                    function setQuality(v){ fetch('/cmd?action=set_quality&val='+v); }
                    function toggleMenu() { document.getElementById("dropdown").classList.toggle("show"); }

                    const img = document.getElementById('stream');
                    const container = document.getElementById('stream-container');
                    let currentScale = 0; 
                    function zoom(delta) {
                        if (currentScale === 0) {
                            let imgW = img.getBoundingClientRect().width;
                            let conW = container.getBoundingClientRect().width;
                            currentScale = (imgW / conW) * 100;
                        }
                        currentScale += delta;
                        if(currentScale < 10) currentScale = 10;
                        if(currentScale > 500) currentScale = 500;
                        applyZoom();
                    }
                    function resetZoom() { currentScale = 0; img.style.width = "auto"; img.style.height = "auto"; img.style.maxWidth = "100%"; img.style.maxHeight = "100%"; }
                    function applyZoom() { img.style.maxWidth = "none"; img.style.maxHeight = "none"; img.style.width = currentScale + "%"; img.style.height = "auto"; }

                    let initialDist = 0;
                    img.addEventListener('touchstart', function(e) {
                        if(e.touches.length === 2) initialDist = Math.hypot(e.touches[0].pageX - e.touches[1].pageX, e.touches[0].pageY - e.touches[1].pageY);
                    });
                    img.addEventListener('touchmove', function(e) {
                        if(e.touches.length === 2) {
                            e.preventDefault();
                            let dist = Math.hypot(e.touches[0].pageX - e.touches[1].pageX, e.touches[0].pageY - e.touches[1].pageY);
                            if (Math.abs(dist - initialDist) > 10) { zoom((dist - initialDist) > 0 ? 5 : -5); initialDist = dist; }
                        }
                    });

                    let isStable = false;
                    const btnStable = document.getElementById('btnStable');
                    const liveDot = document.getElementById('liveDot');
                    function toggleStable() {
                        isStable = !isStable; fetch('/cmd?action=toggle_stable&val=' + isStable);
                        if (isStable) { btnStable.innerText = "🐢 Stable Mode (5s)"; btnStable.classList.add("on"); liveDot.style.background = "orange"; liveDot.style.boxShadow = "0 0 10px orange"; }
                        else { btnStable.innerText = "⚡ Realtime Mode"; btnStable.classList.remove("on"); liveDot.style.background = "red"; liveDot.style.boxShadow = "0 0 10px red"; }
                    }

                    const audio = document.getElementById('audio');
                    const btnAudio = document.getElementById('btnAudio');
                    let isAudioPlaying = false;
                    function toggleAudio() {
                        if (!isAudioPlaying) {
                            audio.src = "/audio.wav?" + Date.now();
                            audio.play().then(() => { isAudioPlaying = true; btnAudio.innerText = "🔊 Stop Audio"; btnAudio.classList.add("on"); startSyncLoop(); }).catch(e => alert("Audio Error: " + e));
                        } else {
                            audio.pause(); audio.src = ""; isAudioPlaying = false; btnAudio.innerText = "🔇 Start Audio"; btnAudio.classList.remove("on");
                        }
                    }
                    function startSyncLoop() {
                        if(!isAudioPlaying) return;
                        if (!isStable && audio.buffered.length > 0) {
                            const end = audio.buffered.end(audio.buffered.length - 1);
                            if (end - audio.currentTime > 1.0) audio.currentTime = end - 0.1;
                        }
                        requestAnimationFrame(startSyncLoop);
                    }
                    
                    dragElement(document.getElementById("panel"));
                    function dragElement(elmnt) {
                        var pos1=0, pos2=0, pos3=0, pos4=0; var handle = elmnt.querySelector('.handle');
                        handle.onmousedown = dragMouseDown; handle.ontouchstart = dragMouseDown;
                        function dragMouseDown(e) { e = e || window.event; var rect = elmnt.getBoundingClientRect(); if(e.clientX > rect.right - 20 && e.clientY > rect.bottom - 20) return; pos3 = e.clientX || e.touches[0].clientX; pos4 = e.clientY || e.touches[0].clientY; document.onmouseup = closeDragElement; document.ontouchend = closeDragElement; document.onmousemove = elementDrag; document.ontouchmove = elementDrag; }
                        function elementDrag(e) { e = e || window.event; var cx = e.clientX || e.touches[0].clientX; var cy = e.clientY || e.touches[0].clientY; pos1 = pos3 - cx; pos2 = pos4 - cy; pos3 = cx; pos4 = cy; elmnt.style.top = (elmnt.offsetTop - pos2) + "px"; elmnt.style.left = (elmnt.offsetLeft - pos1) + "px"; }
                        function closeDragElement() { document.onmouseup = null; document.onmousemove = null; document.ontouchend = null; document.ontouchmove = null; }
                    }
                    
                    img.addEventListener('click', function(event) {
                        var rect = img.getBoundingClientRect(); var clickX = event.clientX - rect.left; var clickY = event.clientY - rect.top;
                        var xPct = clickX / rect.width; var yPct = clickY / rect.height;
                        if(xPct >= 0 && xPct <= 1 && yPct >= 0 && yPct <= 1) fetch('/cmd?action=tap&x=' + xPct + '&y=' + yPct);
                    });
                    
                    let scrollInterval;
                    function startScroll(action) { f(action); scrollInterval = setInterval(() => f(action), 800); }
                    function stopScroll() { clearInterval(scrollInterval); }
                </script>
            </body>
            </html> ...""".trimIndent()
    }
}
