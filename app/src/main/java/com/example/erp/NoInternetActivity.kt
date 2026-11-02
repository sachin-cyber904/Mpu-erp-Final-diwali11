package com.example.erp

import android.content.Intent
import android.os.Bundle
import android.view.animation.TranslateAnimation
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class NoInternetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_no_internet)

        // ✅ RETRY BUTTON
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isInternetAvailable()) {
                if (MainActivity.isAlive) {

                    finish()
                } else {

                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } else {
                val shake = TranslateAnimation(0f, 12f, 0f, 0f).apply {
                    duration = 80
                    repeatCount = 5
                    repeatMode = TranslateAnimation.REVERSE
                }
                findViewById<Button>(R.id.retryButton).startAnimation(shake)
            }
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}