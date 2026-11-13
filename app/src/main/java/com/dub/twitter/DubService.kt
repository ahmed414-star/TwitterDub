package com.dub.twitter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DubService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null

    @Volatile private var running = false
    @Volatile private var paused = false

    private var overlay: View? = null
    private var wm: WindowManager? = null
    private var subtitle: TextView? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val pending = AtomicInteger(0)
    private var focusReq: AudioFocusRequest? = null

    private var apiKey = ""
    private val exec = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val ttsAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val rate = 16000
    private val chunkSeconds = 6

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running || intent == null) return START_NOT_STICKY

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        apiKey = getSharedPreferences("p", MODE_PRIVATE).getString("key", "") ?: ""

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        setupOverlay()
        initTts()

        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val p = mgr.getMediaProjection(code, data)
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ui.post { stopSelf() }
                }
            }, ui)
            startCapture(p)
        } catch (e: Exception) {
            show("تعذر بدء التقاط الصوت: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val ch = NotificationChannel("dub", "Dub", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, DubService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "إيقاف",
            stopPi
        ).build()
        return Notification.Builder(this, "dub")
            .setContentTitle("المترجم شغال")
            .setContentText("اضغط إيقاف لإنهاء الترجمة")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(action)
            .build()
    }

    // ---------- Overlay ----------
    private fun setupOverlay() {
        val d = resources.displayMetrics.density
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        val bg = GradientDrawable()
        bg.setColor(Color.argb(200, 0, 0, 0))
        bg.cornerRadius = 16 * d
        box.background = bg

        val sub = TextView(this)
        sub.setTextColor(Color.WHITE)
        sub.textSize = 17f
        sub.gravity = Gravity.CENTER
        sub.text = "جاري الاستماع..."
        subtitle = sub

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER

        val pause = TextView(this)
        pause.text = "⏸"
        pause.textSize = 22f
        pause.setTextColor(Color.WHITE)
        pause.setPadding((16 * d).toInt(), (4 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
        pause.setOnClickListener {
            paused = !paused
            pause.text = if (paused) "▶" else "⏸"
        }

        val close = TextView(this)
        close.text = "✕"
        close.textSize = 22f
        close.setTextColor(Color.WHITE)
        close.setPadding((16 * d).toInt(), (4 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
        close.setOnClickListener { stopSelf() }

        row.addView(pause)
        row.addView(close)
        box.addView(sub)
        box.addView(row)

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = (resources.displayMetrics.heightPixels * 0.3).toInt()

        var startY = 0
        var touchY = 0f
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                startY = lp.y
                touchY = e.rawY
            } else if (e.action == MotionEvent.ACTION_MOVE) {
                lp.y = startY + (e.rawY - touchY).toInt()
                w.updateViewLayout(box, lp)
            }
            true
        }

        w.addView(box, lp)
        overlay = box
    }

    private fun show(msg: String) {
        ui.post { subtitle?.text = msg }
    }

    // ---------- TTS ----------
    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            val t = tts
            if (st == TextToSpeech.SUCCESS && t != null) {
                val r = t.setLanguage(Locale("ar"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(ttsAttrs)
                t.setSpeechRate(1.1f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { finished() }
                    override fun onError(utteranceId: String?) { finished() }
                })
                if (!ttsReady) show("نزّل صوت عربي من إعدادات الجهاز (محرك تحويل النص إلى كلام)")
            } else {
                show("محرك الصوت مش شغال")
            }
        }
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            ui.post { unduck() }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        duck()
        pending.incrementAndGet()
        val r = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
        if (r != TextToSpeech.SUCCESS) finished()
    }

    private fun duck() {
        if (focusReq != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ttsAttrs)
            .build()
        getSystemService(AudioManager::class.java).requestAudioFocus(req)
        focusReq = req
    }

    private fun unduck() {
        val req = focusReq ?: return
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(req)
        focusReq = null
    }

    // ---------- Capture ----------
    private fun startCapture(p: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, 32000))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        rec.startRecording()
        record = rec
        running = true

        Thread {
            val chunk = rate * chunkSeconds
            val buf = ShortArray(chunk)
            while (running) {
                var read = 0
                while (running && read < chunk) {
                    val n = rec.read(buf, read, chunk - read)
                    if (n < 0) {
                        running = false
                        break
                    }
                    read += n
                }
                if (!running) break
                if (paused) continue
                if (rms(buf) < 200.0) continue
                val wav = toWav(buf, rate)
                exec.execute { process(wav) }
            }
        }.start()
    }

    private fun rms(a: ShortArray): Double {
        var s = 0.0
        for (v in a) s += v.toDouble() * v.toDouble()
        return Math.sqrt(s / a.size)
    }

    private fun toWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    // ---------- Speech -> text -> Arabic ----------
    private fun process(wav: ByteArray) {
        try {
            if (pending.get() >= 3) return // متأخرين، نتخطى الجزء ده

            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("file", "a.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            var text = ""
            var lang = ""
            http.newCall(req).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    show("خطأ ${r.code} - اتأكد من المفتاح")
                    return
                }
                val j = JSONObject(s)
                text = j.optString("text").trim()
                lang = j.optString("language")
                val segs = j.optJSONArray("segments")
                if (segs != null && segs.length() > 0) {
                    var allSilent = true
                    for (k in 0 until segs.length()) {
                        if (segs.getJSONObject(k).optDouble("no_speech_prob", 0.0) < 0.6) allSilent = false
                    }
                    if (allSilent) return
                }
            }
            if (text.length < 2) return
            if (lang.equals("arabic", ignoreCase = true)) return // الفيديو عربي أصلاً

            val payload = JSONObject()
                .put("model", "llama-3.3-70b-versatile")
                .put("temperature", 0.2)
                .put(
                    "messages", JSONArray()
                        .put(
                            JSONObject().put("role", "system").put(
                                "content",
                                "أنت مترجم فوري. ترجم النص التالي إلى العربية الفصحى المبسطة. أخرج الترجمة فقط بدون أي شرح أو علامات اقتباس."
                            )
                        )
                        .put(JSONObject().put("role", "user").put("content", text))
                )
            val req2 = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            var arabic = ""
            http.newCall(req2).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    show("خطأ ترجمة ${r.code}")
                    return
                }
                arabic = JSONObject(s).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            }
            if (arabic.isEmpty()) return

            ui.post {
                subtitle?.text = arabic
                speak(arabic)
            }
        } catch (e: Exception) {
            show("مشكلة اتصال: ${e.message}")
        }
    }

    override fun onDestroy() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        unduck()
        tts?.stop()
        tts?.shutdown()
        exec.shutdownNow()
        super.onDestroy()
    }
}
