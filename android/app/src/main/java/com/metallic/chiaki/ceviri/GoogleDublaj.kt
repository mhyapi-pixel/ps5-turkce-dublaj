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
import android.util.Base64
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Türkçe dublaj.
 * Ana ses: Google Chirp 3 HD (duygulu, gerçekçi; ayda 1 milyon karakter ücretsiz).
 * Yedek ses: TV'nin kendi ücretsiz Türkçe sesi — internet yoksa, anahtar girilmemişse
 * veya bu ayki ücretsiz sınıra yaklaşıldıysa otomatik devreye girer. Böylece asla ücret çıkmaz.
 */
class GoogleDublaj(private val context: Context, private val ayarlar: CeviriAyarlari)
{
	companion object
	{
		private const val TAG = "GoogleDublaj"
		private const val ORNEKLEME = 24000
		private const val WAV_BASLIK = 44
		private const val ONBELLEK_SINIRI = 24 * 1024 * 1024
	}

	private val anaHandler = Handler(Looper.getMainLooper())
	private val isci = Executors.newSingleThreadExecutor()
	private val nesil = AtomicInteger(0)
	@Volatile private var baglanti: HttpURLConnection? = null
	private var sonUyari = 0L
	private val sayac = context.getSharedPreferences(CeviriAyarlari.DOSYA, Context.MODE_PRIVATE)

	/** Tekrar eden replikler kota harcamadan tekrar çalınır. */
	private var onbellekBoyutu = 0
	private val onbellek = object: LinkedHashMap<String, ByteArray>(64, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean
		{
			if(onbellekBoyutu > ONBELLEK_SINIRI)
			{
				onbellekBoyutu -= eldest.value.size
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
			.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
			.setSampleRate(ORNEKLEME)
			.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
			.build())
		.setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(ORNEKLEME, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192))
		.setTransferMode(AudioTrack.MODE_STREAM)
		.build()

	// ---- Yedek: TV'nin ücretsiz Türkçe sesi ----
	@Volatile private var yedekHazir = false
	private lateinit var yedek: TextToSpeech

	init
	{
		yedek = TextToSpeech(context.applicationContext) { durum ->
			if(durum == TextToSpeech.SUCCESS)
			{
				val s = yedek.setLanguage(Locale("tr", "TR"))
				yedekHazir = s != TextToSpeech.LANG_MISSING_DATA && s != TextToSpeech.LANG_NOT_SUPPORTED
			}
		}
	}

	/** Türkçe cümleyi seslendirir. Önceki cümle hâlâ okunuyorsa onu keser. */
	fun oku(metin: String, konusmaci: String?)
	{
		if(!ayarlar.dublaj)
			return
		val benim = nesil.incrementAndGet()
		kes()
		val sesAdi = GoogleSesler.sec(konusmaci, ayarlar)
		val anahtar = ayarlar.googleAnahtari
		if(anahtar.isEmpty())
		{
			uyar("Google ses anahtarı girilmedi; TV'nin kendi sesi kullanılıyor.")
			yedekOku(metin)
			return
		}
		if(buAyKullanilan() + metin.length > ayarlar.aylikSinir)
		{
			uyar("Bu ayın ücretsiz Google ses kotası doldu; ay sonuna kadar TV'nin kendi sesi kullanılıyor.")
			yedekOku(metin)
			return
		}
		isci.execute { seslendir(benim, anahtar, sesAdi, metin) }
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
		try { yedek.shutdown() } catch(e: Exception) {}
	}

	/** Bu ay Google'a gönderilen karakter sayısı (ücretsiz sınırı aşmamak için). */
	fun buAyKullanilan(): Long
	{
		val ay = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
		return if(sayac.getString("kota_ay", "") == ay) sayac.getLong("kota_karakter", 0L) else 0L
	}

	private fun kullanimEkle(n: Int)
	{
		val ay = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
		val mevcut = buAyKullanilan()
		sayac.edit().putString("kota_ay", ay).putLong("kota_karakter", mevcut + n).apply()
	}

	private fun kes()
	{
		baglanti?.let { b -> Thread { try { b.disconnect() } catch(e: Exception) {} }.start() }
		baglanti = null
		try
		{
			ses.pause()
			ses.flush()
		}
		catch(e: Exception) {}
		if(yedekHazir)
			yedek.stop()
	}

	private fun guncelMi(n: Int) = n == nesil.get()

	private fun yedekOku(metin: String)
	{
		if(!yedekHazir)
			return
		yedek.setSpeechRate(ayarlar.konusmaHizi)
		yedek.speak(metin, TextToSpeech.QUEUE_FLUSH, Bundle(), "yedek")
	}

	private fun seslendir(n: Int, anahtar: String, sesAdi: String, metin: String)
	{
		if(!guncelMi(n))
			return
		val kayit = "$sesAdi|$metin"
		synchronized(onbellek) { onbellek[kayit] }?.let { cal(n, it); return }

		val govde = JSONObject().apply {
			put("input", JSONObject().put("text", metin))
			put("voice", JSONObject()
				.put("languageCode", "tr-TR")
				.put("name", GoogleSesler.tamAd(sesAdi)))
			put("audioConfig", JSONObject()
				.put("audioEncoding", "LINEAR16")
				.put("sampleRateHertz", ORNEKLEME)
				.apply { if(ayarlar.konusmaHizi != 1.0f) put("speakingRate", ayarlar.konusmaHizi.toDouble()) })
		}

		var b: HttpURLConnection? = null
		try
		{
			b = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=$anahtar").openConnection() as HttpURLConnection
			baglanti = b
			b.requestMethod = "POST"
			b.connectTimeout = 4000
			b.readTimeout = 8000
			b.doOutput = true
			b.setRequestProperty("Content-Type", "application/json; charset=utf-8")
			b.setRequestProperty("X-Android-Package", context.packageName)
			b.outputStream.use { it.write(govde.toString().toByteArray(Charsets.UTF_8)) }

			val kod = b.responseCode
			if(kod != 200)
			{
				val hata = try { b.errorStream?.bufferedReader()?.readText() ?: "" } catch(e: Exception) { "" }
				Log.w(TAG, "Google TTS $kod: $hata")
				when
				{
					kod == 400 && hata.contains("API key", true) -> uyar("Google ses anahtarı geçersiz.")
					kod == 403 && hata.contains("billing", true) -> uyar("Google Cloud'da faturalandırma açılmamış.")
					kod == 403 && hata.contains("SERVICE_DISABLED") -> uyar("Google Cloud'da Text-to-Speech API açılmamış.")
					kod == 403 -> uyar("Google ses anahtarının bu servise izni yok.")
					kod == 429 -> uyar("Google ses kotası doldu.")
					else -> uyar("Google ses hatası ($kod).")
				}
				if(guncelMi(n))
					anaHandler.post { if(guncelMi(n)) yedekOku(metin) }
				return
			}

			val yanit = b.inputStream.bufferedReader().use { it.readText() }
			if(!guncelMi(n))
				return
			val wav = Base64.decode(JSONObject(yanit).getString("audioContent"), Base64.DEFAULT)
			val pcm = if(wav.size > WAV_BASLIK && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
				wav.copyOfRange(WAV_BASLIK, wav.size) else wav
			kullanimEkle(metin.length)
			synchronized(onbellek) {
				onbellek[kayit] = pcm
				onbellekBoyutu += pcm.size
			}
			cal(n, pcm)
		}
		catch(e: Exception)
		{
			if(guncelMi(n))
			{
				Log.w(TAG, "Google TTS bağlantı hatası", e)
				uyar("İnternet yok; TV'nin kendi sesi kullanılıyor.")
				anaHandler.post { if(guncelMi(n)) yedekOku(metin) }
			}
		}
		finally
		{
			try { b?.disconnect() } catch(e: Exception) {}
			if(baglanti === b)
				baglanti = null
		}
	}

	private fun cal(n: Int, veri: ByteArray)
	{
		if(!guncelMi(n))
			return
		ses.play()
		var i = 0
		val uzunluk = veri.size - (veri.size % 2)
		while(i < uzunluk && guncelMi(n))
		{
			val adim = minOf(8192, uzunluk - i)
			ses.write(veri, i, adim)
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
