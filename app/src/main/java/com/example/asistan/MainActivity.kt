package com.example.asistan

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

// E-posta adresinden ismi çıkarır: "yusuf.tekin42@gmail.com" -> "Yusuf"
fun adDan(mail: String): String {
    val tr = Locale("tr", "TR")
    val yerel = mail.substringBefore("@")
    val ilk = yerel.split('.', '_', '-', '+').firstOrNull { p -> p.any { it.isLetter() } } ?: return ""
    val harfler = ilk.filter { it.isLetter() }
    if (harfler.isEmpty()) return ""
    return harfler.lowercase(tr).replaceFirstChar { it.titlecase(tr) }
}

class MainActivity : Activity() {

    private lateinit var adAlani: EditText
    private lateinit var durum: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ayar", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        root.addView(TextView(this).apply { text = "Badi kurulumu"; textSize = 24f })

        root.addView(TextView(this).apply {
            text = "Claude API anahtarı (sohbet için gerekli):"
            setPadding(0, 32, 0, 0)
        })
        val anahtar = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("api_key", ""))
        }
        root.addView(anahtar)

        durum = TextView(this).apply {
            val mail = prefs.getString("mail", "") ?: ""
            text = if (mail.isBlank()) "E-posta hesabı seçilmedi" else "Hesap: $mail"
            setPadding(0, 32, 0, 0)
        }
        root.addView(durum)

        adAlani = EditText(this).apply {
            hint = "Bulunan isim (yanlışsa düzeltebilirsin)"
            setText(prefs.getString("ad", ""))
        }
        root.addView(adAlani)

        root.addView(btn("1) Mikrofon ve bildirim izni ver") {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1
            )
        })

        root.addView(btn("2) Diğer uygulamaların üzerinde göster izni") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        root.addView(btn("3) E-posta hesabını seç (ismimi buradan bul)") {
            val i = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"), null, null, null, null
            )
            startActivityForResult(i, 2)
        })

        root.addView(btn("4) Pil kısıtlamasını kaldır (arka planda çalışsın)") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Zaten kaldırılmış", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        })

        root.addView(btn("5) Varsayılan asistan yap (isteğe bağlı)") {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        })

        root.addView(btn("BAŞLAT: Badi'yi dinlemeye al") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Önce 1. adımdaki izni ver", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit()
                    .putString("api_key", anahtar.text.toString().trim())
                    .putString("ad", adAlani.text.toString().trim())
                    .apply()
                startForegroundService(Intent(this, AsistanService::class.java))
                Toast.makeText(this, "Badi dinlemede. \"Badi, bakar mısın\" de.", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(btn("DURDUR: Badi'yi tamamen kapat") {
            stopService(Intent(this, AsistanService::class.java))
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK) {
            val mail = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return
            getSharedPreferences("ayar", MODE_PRIVATE).edit().putString("mail", mail).apply()
            durum.text = "Hesap: $mail"
            adAlani.setText(adDan(mail))
        }
    }
}
