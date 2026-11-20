// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tamamen ücretsiz ve internetsiz Türkçe dublaj.
 * Erkek karakterler ve anlatıcı: Piper sesleri (TV'nin içinde üretilir).
 * Kadın karakterler: TV'nin kendi Türkçe sesi.
 * Piper sesi indirilmemişse her şeyi TV'nin kendi sesi okur.
 */
class YerelDublaj(private val context: Context, private val ayarlar: CeviriAyarlari)
{
	companion object
	{
		private const val TAG = "YerelDublaj"
		private const val ORNEKLEME = 22050
		private const val ONBELLEK_SINIRI = 32 * 1024 * 1024
	}

	private val anaHandler = Handler(Looper.getMainLooper())
	private val isci = Executors.newSingleThreadExecutor()
	private val nesil = AtomicInteger(0)
	private var sonUyari = 0L

	private var onbellekBoyutu = 0
	private val onbellek = object: LinkedHashMap<String, FloatArray>(64, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean
		{
			if(onbellekBoyutu > ONBELLEK_SINIRI)
			{
				onbellekBoyutu -= eldest.value.size * 4
				return true
			}
			return false
		}
	}

	private val ses: AudioTrack = AudioTrack.Builder()
		.setAudioAttributes(AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_GAME)
			.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
			.build())
		.setAudioFormat(AudioFormat.Builder()
			.setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
			.setSampleRate(ORNEKLEME)
			.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
			.build())
		.setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(ORNEKLEME, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), 16384))
		.setTransferMode(AudioTrack.MODE_STREAM)
		.build()

	// ---- TV'nin kendi Türkçe sesi ----
	@Volatile private var tvHazir = false
	private lateinit var tv: TextToSpeech

	init
	{
		tv = TextToSpeech(context.applicationContext) { durum ->
			if(durum == TextToSpeech.SUCCESS)
			{
				val s = tv.setLanguage(Locale("tr", "TR"))
				tvHazir = s != TextToSpeech.LANG_MISSING_DATA && s != TextToSpeech.LANG_NOT_SUPPORTED
				if(!tvHazir)
					uyar("TV'de Türkçe ses yok. Ayarlar → Metin okuma bölümünden Türkçe sesi indirin.")
			}
		}
		// Ses motorunu önceden ısıt: ilk replikte bekleme olmasın
		isci.execute {
			try
			{
				PiperSesler.hazirla(context)
				PiperSesler.motor(context, anlaticiKodu() ?: return@execute)
			}
			catch(e: Throwable) { Log.w(TAG, "Ön yükleme başarısız", e) }
		}
	}

	private fun anlaticiKodu(): String?
	{
		val indirilen = PiperSesler.indirilenler(context).map { it.kod }
		if(indirilen.isEmpty())
			return null
		return if(ayarlar.anlaticiSesi in indirilen) ayarlar.anlaticiSesi else indirilen.first()
	}

	/** Türkçe cümleyi seslendirir. Önceki cümle hâlâ okunuyorsa keser. */
	fun oku(metin: String, konusmaci: String?)
	{
		if(!ayarlar.dublaj)
			return
		val benim = nesil.incrementAndGet()
		kes()

		val ad = konusmaci?.trim()?.uppercase()
		val kadin = ad != null && Karakterler.kadinMi(ad, ayarlar)
		val ozelHiz = if(ad == null) 1.0f else 0.94f + ((ad.hashCode() and 0xff) / 255f) * 0.12f
		val hiz = ayarlar.konusmaHizi * ozelHiz

		if(kadin)
		{
			tvOku(metin, perde = 1.0f + ((ad!!.hashCode() and 0x7) / 7f) * 0.15f, hiz = hiz)
			return
		}

		val indirilen = PiperSesler.indirilenler(context).map { it.kod }
		if(indirilen.isEmpty())
		{
			// Ses paketi yok: erkek karakterleri TV sesi biraz kalın okur
			tvOku(metin, perde = if(ad == null) 1.0f else 0.82f, hiz = hiz)
			return
		}
		val kod = if(ad == null) anlaticiKodu()!!
		else
		{
			val havuz = indirilen.filter { it != anlaticiKodu() }.ifEmpty { indirilen }
			havuz[(ad.hashCode() and 0x7fffffff) % havuz.size]
		}
		isci.execute { piperOku(benim, kod, metin, hiz) }
	}

	fun sustur()
	{
		nesil.incrementAndGet()
		kes()
	}

	fun kapat()
	{
		sustur()
		isci.shutdownNow()
		try { ses.release() } catch(e: Exception) {}
		try { tv.shutdown() } catch(e: Exception) {}
		PiperSesler.hepsiniBirak()
	}

	private fun kes()
	{
		try
		{
			ses.pause()
			ses.flush()
		}
		catch(e: Exception) {}
		if(tvHazir)
			tv.stop()
	}

	private fun guncelMi(n: Int) = n == nesil.get()

	private fun tvOku(metin: String, perde: Float, hiz: Float)
	{
		if(!tvHazir)
			return
		tv.setPitch(perde)
		tv.setSpeechRate(hiz)
		tv.speak(metin, TextToSpeech.QUEUE_FLUSH, Bundle(), "tv")
	}

	private fun piperOku(n: Int, kod: String, metin: String, hiz: Float)
	{
		if(!guncelMi(n))
			return
		val anahtar = "$kod|$hiz|$metin"
		synchronized(onbellek) { onbellek[anahtar] }?.let { cal(n, it); return }

		val motor = PiperSesler.motor(context, kod)
		if(motor == null)
		{
			uyar("Ses paketi yüklenemedi; TV'nin kendi sesi kullanılıyor.")
			anaHandler.post { if(guncelMi(n)) tvOku(metin, 0.82f, hiz) }
			return
		}
		try
		{
			ses.play()
			val parcalar = ArrayList<FloatArray>()
			// Ses parça parça üretilir ve üretildikçe çalınır: cümlenin bitmesi beklenmez
			motor.generateWithCallback(text = metin, sid = 0, speed = hiz) { parca ->
				if(!guncelMi(n))
					return@generateWithCallback 0
				parcalar.add(parca.copyOf())
				ses.write(parca, 0, parca.size, AudioTrack.WRITE_BLOCKING)
				if(guncelMi(n)) 1 else 0
			}
			if(guncelMi(n))
			{
				val toplam = FloatArray(parcalar.sumOf { it.size })
				var i = 0
				for(p in parcalar) { System.arraycopy(p, 0, toplam, i, p.size); i += p.size }
				synchronized(onbellek) {
					onbellek[anahtar] = toplam
					onbellekBoyutu += toplam.size * 4
				}
			}
		}
		catch(e: Throwable)
		{
			Log.e(TAG, "Piper seslendirme hatası", e)
			anaHandler.post { if(guncelMi(n)) tvOku(metin, 0.82f, hiz) }
		}
	}

	private fun cal(n: Int, veri: FloatArray)
	{
		if(!guncelMi(n))
			return
		ses.play()
		var i = 0
		while(i < veri.size && guncelMi(n))
		{
			val adim = minOf(4096, veri.size - i)
			ses.write(veri, i, adim, AudioTrack.WRITE_BLOCKING)
			i += adim
		}
	}

	private fun uyar(mesaj: String)
	{
		val simdi = System.currentTimeMillis()
		if(simdi - sonUyari < 15000)
			return
		sonUyari = simdi
		anaHandler.post { Toast.makeText(context, mesaj, Toast.LENGTH_LONG).show() }
	}
}
