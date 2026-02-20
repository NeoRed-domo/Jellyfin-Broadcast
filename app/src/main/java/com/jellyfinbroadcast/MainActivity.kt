package com.jellyfinbroadcast

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jellyfinbroadcast.core.DeviceMode
import com.jellyfinbroadcast.phone.PhoneActivity
import com.jellyfinbroadcast.tv.TvActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = DeviceMode.detect(this)
        val target = if (mode == DeviceMode.TV) TvActivity::class.java else PhoneActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
