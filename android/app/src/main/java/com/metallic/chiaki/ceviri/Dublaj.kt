// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/** Türkçe seslendirme: cihazın kendi Türkçe sesini kullanır, internet ve ücret gerekmez. */
class Dublaj(private val context: Context, private val ayarlar: CeviriAyarlari)
{
	@Volatile private var hazir = false
	private var sayac = 0

	private lateinit var tts: TextToSpeech

	init
	{
		tts = TextToSpeech(context.applicationContext) { durum -> hazirla(durum) }
	}

	private fun hazirla(durum: Int)
	{
		if(durum != TextToSpeech.SUCCESS)
			return
		val sonuc = tts.setLanguage(Locale("tr", "TR"))
		if(sonuc == TextToSpeech.LANG_MISSING_DATA || sonuc == TextToSpeech.LANG_NOT_SUPPORTED)
		{
			Toast.makeText(context, "Cihazda Türkçe ses bulunamadı. Ayarlar > Metin okuma bölümünden Türkçe sesi indirin.", Toast.LENGTH_LONG).show()
			return
		}
		tts.setAudioAttributes(AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_GAME)
			.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
			.build())
		hazir = true
	}

	/** Yeni altyazı geldiğinde eskisini keser, yenisini hemen okur (gecikme birikmez). */
	fun oku(metin: String)
	{
		if(!hazir || !ayarlar.dublaj)
			return
		tts.setSpeechRate(ayarlar.konusmaHizi)
		tts.speak(metin, TextToSpeech.QUEUE_FLUSH, Bundle(), "altyazi_${sayac++}")
	}

	fun sustur()
	{
		if(hazir)
			tts.stop()
	}

	fun kapat()
	{
		tts.stop()
		tts.shutdown()
	}
}
