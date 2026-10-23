package com.example.asistan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class AsistanService : Service() {

    companion object {
        // Hızlı ve ucuz model. Değiştirmek istersen buraya başka model adı yaz.
        const val MODEL = "claude-haiku-5-5"

        // Uyandırma kelimesi gibi anlaşılabilecek yazılışlar (küçük harf, Türkçe)
        val UYANMA_KELIMELERI = listOf("badi", "badı", "baddi", "baddy", "buddy", "bady")
    }

    private enum class Mod { UYKU, AKTIF }

    private lateinit var wm: WindowManager
    private var panel: TextView? = null
    private var bubble: TextView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var bekleyenKonusma: Pair<String, Boolean>? = null
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tr = Locale("tr", "TR")

    private var running = false
    private var mod = Mod.UYKU
    private var emptyCount = 0
    private var uykuyaGec = false
    private var dinlemeNesli = 0
    private val gecmis = ArrayList<Pair<String, String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "DURDUR") {
            stopAll()
            return START_NOT_STICKY
        }

        startAsForeground()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "badi:dinleme").apply { acquire() }
        }
        baslatTts()

        if (intent?.getBooleanExtra("cagir", false) == true) uyan(null) else uyku()
        return START_STICKY
    }

    // ---------- Modlar ----------

    /** Arka planda sadece "Badi" kelimesini bekler. */
    private fun uyku() {
        mod = Mod.UYKU
        uykuyaGec = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        panelKaldir()
        baloncukKaldir()
        startListening()
    }

    /** Uyandırma kelimesi duyuldu (ya da asistan düğmesiyle çağrıldı). */
    private fun uyan(duyulan: String?) {
        mod = Mod.AKTIF
        emptyCount = 0
        uykuyaGec = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        baloncukKaldir()

        val kalan = if (duyulan == null) "" else temizle(duyulan)
        if (kalan.length >= 3) {
            // "Badi not defterini aç" gibi tek cümlede gelen komut
            panelGoster(kalan)
            handle(kalan)
        } else {
            val ad = getSharedPreferences("ayar", MODE_PRIVATE).getString("ad", "") ?: ""
            say(if (ad.isBlank()) "Efendim" else "Efendim $ad")
        }
    }

    private fun uyanmaVarMi(t: String): Boolean {
        val n = t.lowercase(tr).filter { it.isLetter() }
        return UYANMA_KELIMELERI.any { n.contains(it) }
    }

    private fun temizle(t: String): String {
        var s = t.lowercase(tr)
        for (w in UYANMA_KELIMELERI) s = s.replace(w, " ")
        s = s.replace(Regex("bakar\\s*m[ıi]s[ıi]n([ıi]z)?"), " ")
        s = s.replace(Regex("[,.!?]"), " ")
        return s.trim().replace(Regex("\\s+"), " ")
    }

    // ---------- Konuşma (TTS) ----------

    private fun baslatTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = tr
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { konusmaBitti() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { handler.post { konusmaBitti() } }
                })
                ttsReady = true
                bekleyenKonusma?.let { say(it.first, it.second) }
                bekleyenKonusma = null
            }
        }
    }

    private fun say(text: String, panelde: Boolean = true) {
        recognizer?.cancel()
        dinlemeNesli++
        if (panelde) panelGoster(text)
        if (!ttsReady) {
            bekleyenKonusma = Pair(text, panelde)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun konusmaBitti() {
        if (!running) return
        if (uykuyaGec) {
            uyku()
        } else if (mod == Mod.AKTIF) {
            startListening()
        }
    }

    // ---------- Dinleme ----------

    private fun startListening() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (mod == Mod.AKTIF) panelGoster("Bu telefonda ses tanıma yok")
            return
        }
        recognizer?.destroy()
        val nesil = ++dinlemeNesli
        val buMod = mod

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (nesil == dinlemeNesli && buMod == Mod.AKTIF) setText("Dinliyorum...")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partial: Bundle?) {
                    if (nesil != dinlemeNesli || buMod != Mod.AKTIF) return
                    val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) setText(t)
                }

                override fun onResults(results: Bundle?) {
                    if (nesil != dinlemeNesli) return
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (buMod == Mod.UYKU) {
                        if (!t.isNullOrBlank() && uyanmaVarMi(t)) uyan(t) else uykuTekrar(300)
                    } else {
                        if (t.isNullOrBlank()) relisten() else handle(t)
                    }
                }

                override fun onError(error: Int) {
                    if (nesil != dinlemeNesli) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopAll()
                        return
                    }
                    if (buMod == Mod.UYKU) {
                        val bekle = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                            10 -> 3000L // ERROR_TOO_MANY_REQUESTS
                            else -> 300L
                        }
                        uykuTekrar(bekle)
                    } else {
                        relisten()
                    }
                }
            })
        }

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Arka plan beklemesinde çevrimdışı tanıma tercih edilir (pil ve gizlilik için)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, buMod == Mod.UYKU)
        }
        recognizer?.startListening(i)
    }

    private fun uykuTekrar(gecikme: Long) {
        handler.postDelayed({
            if (running && mod == Mod.UYKU) startListening()
        }, gecikme)
    }

    private fun relisten() {
        emptyCount++
        if (emptyCount >= 6) {
            // Uzun süre ses gelmedi: tekrar "Badi" kelimesini beklemeye dön
            uyku()
            return
        }
        handler.postDelayed({ if (running && mod == Mod.AKTIF) startListening() }, 600)
    }

    // ---------- Komutlar ve sohbet ----------

    private fun handle(spoken: String) {
        emptyCount = 0
        val t = spoken.lowercase(tr)
        val words = t.split(" ", "'", "’")

        when {
            words.size <= 4 &&
                listOf("kapat", "görüşürüz", "hoşça kal", "çıkış", "uyu").any { t.contains(it) } &&
                !words.contains("aç") -> {
                uykuyaGec = true
                say("Görüşürüz")
            }
            words.contains("aç") || t.contains("açar mısın") || t.contains("açar misin") -> {
                if (!openApp(t)) sohbet(spoken)
            }
            else -> sohbet(spoken)
        }
    }

    private fun norm(s: String): String = s.lowercase(tr).filter { it.isLetterOrDigit() }

    /** Uygulamayı bulup açarsa true, bulamazsa false döner. */
    private fun openApp(spoken: String): Boolean {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = norm(spoken)

        val best = pm.queryIntentActivities(launcher, 0).mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString()
            val n = norm(label)
            val key = if (n.length > 4) n.dropLast(1) else n
            if (key.length >= 3 && q.contains(key)) Triple(ri.activityInfo.packageName, label, key.length) else null
        }.maxByOrNull { it.third } ?: return false

        val launch = pm.getLaunchIntentForPackage(best.first) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)

        baloncukGoster()
        say("${best.second} açılıyor", panelde = false)
        return true
    }

    private fun sohbet(soru: String) {
        val key = getSharedPreferences("ayar", MODE_PRIVATE).getString("api_key", "") ?: ""
        if (key.isBlank()) {
            say("Sohbet edebilmem için uygulamadan API anahtarını girmen gerekiyor")
            return
        }
        recognizer?.cancel()
        dinlemeNesli++
        panelGoster("Düşünüyorum...")
        gecmis.add(Pair("user", soru))
        val kopya = gecmis.toList()

        thread {
            var cevap: String? = null
            var hata: String? = null
            try {
                cevap = claudeSor(key, kopya)
            } catch (e: Exception) {
                hata = e.message ?: "bilinmeyen hata"
            }
            handler.post {
                if (!running) return@post
                if (cevap.isNullOrBlank()) {
                    gecmis.removeLastOrNull()
                    val kisa = when {
                        hata?.contains("401") == true -> "API anahtarı geçersiz görünüyor"
                        hata?.contains("404") == true -> "Model bulunamadı"
                        hata?.contains("429") == true -> "Çok fazla istek gönderildi, biraz bekle"
                        else -> "İnternete bağlanamadım"
                    }
                    say(kisa)
                    panelGoster("$kisa (${hata ?: ""})")
                } else {
                    gecmis.add(Pair("assistant", cevap))
                    while (gecmis.size > 20) gecmis.removeAt(0)
                    say(cevap)
                }
            }
        }
    }

    private fun claudeSor(key: String, mesajlar: List<Pair<String, String>>): String {
        val ad = getSharedPreferences("ayar", MODE_PRIVATE).getString("ad", "") ?: ""
        val sistem = "Sen Badi'sin, kullanıcının telefonundaki sesli yardımcısın. " +
            (if (ad.isNotBlank()) "Kullanıcının adı $ad. " else "") +
            "Türkçe, samimi ve doğal konuş. Cevapların sesli okunacak, bu yüzden kısa tut " +
            "(en fazla 2-3 cümle) ve markdown, liste, emoji ya da özel karakter kullanma. " +
            "Telefonda şu an sadece uygulama açabiliyorsun (ör. \"not defterini aç\"). " +
            "Başka telefon işlemleri istenirse bunu henüz yapamadığını dürüstçe söyle."

        val msgs = JSONArray()
        for ((rol, metin) in mesajlar) {
            msgs.put(JSONObject().put("role", rol).put("content", metin))
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 400)
            .put("system", sistem)
            .put("messages", msgs)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val kod = conn.responseCode
            val akim = if (kod in 200..299) conn.inputStream else conn.errorStream
            val metin = akim?.bufferedReader()?.use { it.readText() } ?: ""
            if (kod !in 200..299) throw IllegalStateException("HTTP $kod")

            val icerik = JSONObject(metin).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until icerik.length()) {
                val o = icerik.getJSONObject(i)
                if (o.optString("type") == "text") sb.append(o.getString("text"))
            }
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Ekrandaki arayüz ----------

    private fun canOverlay() = Settings.canDrawOverlays(this)

    private fun overlayParams(gravity: Int, x: Int, y: Int, w: Int, h: Int) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun panelGoster(text: String) {
        if (!canOverlay()) return
        baloncukKaldir()
        if (panel == null) {
            panel = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = resources.displayMetrics.widthPixels - dp(32)
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6202030"))
                    cornerRadius = dp(24).toFloat()
                }
            }
            wm.addView(
                panel,
                overlayParams(
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(48),
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
                )
            )
        }
        panel?.text = text
    }

    private fun setText(text: String) { panel?.text = text }

    private fun panelKaldir() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun baloncukGoster() {
        panelKaldir()
        if (!canOverlay() || bubble != null) return
        bubble = TextView(this).apply {
            text = "AI"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC5B5BD6"))
            }
            setOnClickListener {
                emptyCount = 0
                baloncukKaldir()
                panelGoster("Dinliyorum...")
                startListening()
            }
        }
        wm.addView(bubble, overlayParams(Gravity.END or Gravity.CENTER_VERTICAL, 0, 0, dp(56), dp(56)))
    }

    private fun baloncukKaldir() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    // ---------- Servis yaşam döngüsü ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("asistan", "Badi", NotificationManager.IMPORTANCE_LOW)
        )
        val durdur = PendingIntent.getService(
            this, 0,
            Intent(this, AsistanService::class.java).setAction("DURDUR"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "asistan")
            .setContentTitle("Badi dinlemede")
            .setContentText("\"Badi, bakar mısın\" diyerek çağırabilirsin")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Durdur", durdur
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun stopAll() {
        running = false
        dinlemeNesli++
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        panelKaldir()
        baloncukKaldir()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopAll()
        super.onDestroy()
    }
}
