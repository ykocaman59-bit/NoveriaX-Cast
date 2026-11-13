package com.noveria.cast.tv

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class ReceiverActivity : Activity(), ReceiverServer.Listener {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView

    companion object {
        // İşlem canlı olduğu sürece sunucu açık kalır (Yansıtmadan Çık sonrası ana ekrana dönülür).
        private var server: ReceiverServer? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        container = findViewById(R.id.container)
        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)

        if (server == null) {
            val name = try {
                Settings.Global.getString(contentResolver, "device_name")
            } catch (_: Exception) {
                null
            }?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"
            server = ReceiverServer(this, name).also { it.start() }
        }
        tvStatus.text = "NoveriaX Cast Alıcı\n${deviceName()}\nBağlantı bekleniyor..."

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                server?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                server?.setSurface(null)
            }
        })
    }

    private fun deviceName(): String =
        try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"

    override fun onStart() {
        super.onStart()
        server?.listener = this
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        if (server?.listener === this) server?.listener = null
        super.onDestroy()
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread {
            tvStatus.visibility = View.GONE
            // Uygulama arka plandaysa öne getirmeyi dene (Android 10+ için "üstte görüntüle" izni gerekir).
            try {
                startActivity(
                    Intent(this, ReceiverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (_: Exception) {
            }
            container.post {
                val cw = container.width
                val ch = container.height
                if (cw > 0 && ch > 0) {
                    val scale = minOf(cw.toFloat() / width, ch.toFloat() / height)
                    surfaceView.layoutParams = FrameLayout.LayoutParams(
                        (width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER
                    )
                }
            }
        }
    }

    /** Telefon "Yansıtmadan Çık" dediğinde: bekleme ekranı + TV kendi ana ekranına döner. */
    override fun onSessionEnded() {
        runOnUiThread {
            tvStatus.visibility = View.VISIBLE
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            moveTaskToBack(true)
        }
    }
}
