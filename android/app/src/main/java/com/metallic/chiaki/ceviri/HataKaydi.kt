// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama açılır açılmaz (her şeyden önce) çalışır ve olası çökmeleri dosyaya yazar.
 * "Dublaj Hata Raporu" uygulaması bu kaydı ekranda gösterir.
 */
class HataKaydi: ContentProvider()
{
	companion object
	{
		fun dosya(context: Context) = File(context.filesDir, "son_hata.txt")
	}

	override fun onCreate(): Boolean
	{
		val ctx = context ?: return true
		val onceki = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, hata ->
			try
			{
				val sw = StringWriter()
				hata.printStackTrace(PrintWriter(sw))
				val zaman = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
				dosya(ctx).writeText("Zaman: $zaman\nİş parçacığı: ${thread.name}\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nCihaz: ${Build.MANUFACTURER} ${Build.MODEL}\nİşlemci: ${Build.SUPPORTED_ABIS.joinToString()}\n\n$sw")
			}
			catch(e: Throwable) {}
			onceki?.uncaughtException(thread, hata)
		}
		return true
	}

	override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
	override fun getType(uri: Uri): String? = null
	override fun insert(uri: Uri, values: ContentValues?): Uri? = null
	override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
	override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** Son çökme kaydını büyük yazıyla gösterir (fotoğrafı çekilip gönderilsin diye). */
class HataRaporuActivity: Activity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val metin = StringBuilder()
		metin.append("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
		metin.append("İşlemci: ${Build.SUPPORTED_ABIS.joinToString()}\n\n")

		val f = HataKaydi.dosya(this)
		metin.append(if(f.exists()) "=== SON ÇÖKME (Java) ===\n" + f.readText().take(6000) + "\n\n" else "Java çökme kaydı yok.\n\n")

		if(Build.VERSION.SDK_INT >= 30)
		{
			try
			{
				val am = getSystemService(ActivityManager::class.java)
				val cikislar = am.getHistoricalProcessExitReasons(packageName, 0, 5)
				metin.append("=== SON KAPANMALAR ===\n")
				for(c in cikislar)
				{
					metin.append("${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(c.timestamp))}  ${c.processName}  sebep=${c.reason}  ${c.description ?: ""}\n")
					if(c.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE)
					{
						try
						{
							c.traceInputStream?.use { s ->
								val ham = s.readBytes()
								metin.append("  (yerel çökme kaydı ${ham.size} bayt)\n")
							}
						}
						catch(e: Throwable) {}
					}
				}
			}
			catch(e: Throwable)
			{
				metin.append("Kapanma bilgisi okunamadı: $e\n")
			}
		}

		val tv = TextView(this).apply {
			text = metin
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setPadding(48, 32, 48, 32)
			setTextIsSelectable(true)
		}
		setContentView(ScrollView(this).apply { addView(tv); isFocusable = true })
		title = "Dublaj Hata Raporu"
	}
}
