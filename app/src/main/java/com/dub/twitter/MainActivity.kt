package com.dub.twitter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var keyInput: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        fun label(t: String, size: Float): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setPadding(0, pad / 2, 0, pad / 2)
            return v
        }

        root.addView(label("مترجم فيديو تويتر للعربي", 22f))
        root.addView(
            label(
                "1) الصق مفتاح Groq (مجاني من console.groq.com)\n" +
                    "2) اضغط تشغيل ووافق على الصلاحيات، واختر \"الشاشة بالكامل\"\n" +
                    "3) افتح تويتر وشغّل أي فيديو بصوت. الترجمة والصوت العربي هيظهروا فوق الشاشة",
                15f
            )
        )

        keyInput = EditText(this)
        keyInput.hint = "Groq API Key (gsk_...)"
        keyInput.setSingleLine(true)
        keyInput.setText(prefs.getString("key", ""))
        root.addView(keyInput)

        val start = Button(this)
        start.text = "تشغيل"
        start.setOnClickListener { begin() }
        root.addView(start)

        val stop = Button(this)
        stop.text = "إيقاف"
        stop.setOnClickListener {
            startService(Intent(this, DubService::class.java).setAction("STOP"))
            status.text = "تم الإيقاف"
        }
        root.addView(stop)

        status = label("", 15f)
        root.addView(status)

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    private fun begin() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            status.text = "اكتب مفتاح Groq الأول"
            return
        }
        prefs.edit().putString("key", key).apply()

        if (!Settings.canDrawOverlays(this)) {
            status.text = "فعّل \"العرض فوق التطبيقات\" ثم ارجع واضغط تشغيل"
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                102
            )
            return
        }

        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 101)
            return
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                begin()
            } else {
                status.text = "لازم توافق على صلاحية الصوت عشان الأداة تشتغل"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            if (Settings.canDrawOverlays(this)) begin()
            else status.text = "لازم تفعّل العرض فوق التطبيقات"
        }
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, DubService::class.java)
                i.putExtra("code", resultCode)
                i.putExtra("data", data)
                startForegroundService(i)
                status.text = "اشتغلت. افتح تويتر وشغّل فيديو بصوت"
            } else {
                status.text = "لازم توافق على تسجيل الشاشة عشان نلتقط الصوت"
            }
        }
    }
}
