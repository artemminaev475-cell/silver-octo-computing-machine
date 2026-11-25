package com.example.screenview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ScreenService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var server: Server? = null
    private var thread: HandlerThread? = null

    @Volatile
    private var lastJpeg: ByteArray = ByteArray(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "screenview"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "ScreenView", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("ScreenView")
            .setContentText("Экран транслируется")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels / 2
        val h = metrics.heightPixels / 2

        val t = HandlerThread("capture")
        t.start()
        thread = t

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val image = rd.acquireLatestImage()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * w
                    val bmp = Bitmap.createBitmap(
                        w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    lastJpeg = out.toByteArray()
                } finally {
                    image.close()
                }
            }
        }, Handler(t.looper))

        display = mp.createVirtualDisplay(
            "screenview", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )

        if (server == null) {
            val s = Server()
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }

    inner class Server : NanoHTTPD(8080) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/frame") {
                val bytes = lastJpeg
                val res = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, "image/jpeg",
                    ByteArrayInputStream(bytes), bytes.size.toLong()
                )
                res.addHeader("Cache-Control", "no-store")
                return res
            }
            val html = "<html><body style='margin:0;background:#000'>" +
                "<img id=i style='height:100vh;display:block;margin:auto'>" +
                "<script>function t(){var n=new Image();" +
                "n.onload=function(){document.getElementById('i').src=n.src;setTimeout(t,200)};" +
                "n.onerror=function(){setTimeout(t,1000)};" +
                "n.src='/frame?'+Date.now()}t()</script></body></html>"
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/html", html
            )
        }
    }
}
