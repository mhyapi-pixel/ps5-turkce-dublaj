// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * ElevenLabs ile gerçekçi Türkçe dublaj.
 * Ses akış (stream) olarak gelir ve ilk baytlar ulaşır ulaşmaz çalınmaya başlar;
 * cümlenin tamamının inmesi beklenmez. Yeni altyazı gelince eski cümle anında kesilir.
 */
class ElevenLabsDublaj(private val context: Context, private val ayarlar: CeviriAyarlari)
{
	companion object
	{
		private const val TAG = "ElevenLabsDublaj"
		private const val ORNEKLEME = 24000
		private const val ONBELLEK_SINIRI = 24 * 1024 * 1024
	}

	private val anaHandler = Handler(Looper.getMainLooper())
	private val isci = Executors.newSingleThreadExecutor()
	private val nesil = AtomicInteger(0)
	@Volatile private var baglanti: HttpURLConnection? = null
	private var sonUyari = 0L

	/** Her sesin son söylediği cümle: tonlamanın cümleden cümleye doğal akması için. */
	private val oncekiCumle = HashMap<String, String>()

	/** Tekrar eden replikler (menü sesleri, "Hadi!", "Dikkat!") kredi harcamadan tekrar çalınır. */
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
		.apply { if(android.os.Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
		.build()
		.apply { setVolume(1.0f) }

	/** Türkçe cümleyi seslendirir. Önceki cümle hâlâ okunuyorsa onu keser. */
	fun oku(metin: String, konusmaci: String?)
	{
		if(!ayarlar.dublaj)
			return
		val anahtar = ayarlar.elevenLabsAnahtari
		if(anahtar.isEmpty())
		{
			uyar("ElevenLabs API anahtarı girilmedi. Ayarlar → Türkçe Dublaj bölümünden ekleyin.")
			return
		}
		val benim = nesil.incrementAndGet()
		kes()
		val sesId = ElevenLabsSesler.sec(konusmaci, ayarlar)
		val model = ayarlar.sesModeli
		isci.execute { seslendir(benim, anahtar, sesId, model, metin) }
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
	}

	private fun guncelMi(n: Int) = n == nesil.get()

	private fun seslendir(n: Int, anahtar: String, sesId: String, model: String, metin: String)
	{
		if(!guncelMi(n))
			return
		val kayitAnahtari = "$sesId|$model|$metin"
		val hazir = synchronized(onbellek) { onbellek[kayitAnahtari] }
		if(hazir != null)
		{
			cal(n, hazir, 0, hazir.size)
			return
		}

		val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$sesId/stream?output_format=pcm_$ORNEKLEME&optimize_streaming_latency=2")
		val govde = JSONObject().apply {
			put("text", metin)
			put("model_id", model)
			if(model.contains("v2_5"))
				put("language_code", "tr")
			oncekiCumle[sesId]?.let { put("previous_text", it) }
			put("voice_settings", JSONObject().apply {
				put("stability", 0.42)
				put("similarity_boost", 0.8)
				put("style", 0.25)
				put("use_speaker_boost", true)
				put("speed", ayarlar.konusmaHizi.toDouble().coerceIn(0.7, 1.2))
			})
		}

		var b: HttpURLConnection? = null
		try
		{
			b = url.openConnection() as HttpURLConnection
			baglanti = b
			b.requestMethod = "POST"
			b.connectTimeout = 4000
			b.readTimeout = 8000
			b.doOutput = true
			b.setRequestProperty("xi-api-key", anahtar)
			b.setRequestProperty("Content-Type", "application/json")
			b.setRequestProperty("Accept", "audio/pcm")
			b.outputStream.use { it.write(govde.toString().toByteArray(Charsets.UTF_8)) }

			val kod = b.responseCode
			if(kod != 200)
			{
				val hata = try { b.errorStream?.bufferedReader()?.readText() ?: "" } catch(e: Exception) { "" }
				Log.w(TAG, "ElevenLabs $kod: $hata")
				when
				{
					kod == 401 && hata.contains("quota", true) -> uyar("ElevenLabs krediniz bitti.")
					kod == 401 -> uyar("ElevenLabs API anahtarı geçersiz veya yetkisi yok.")
					kod == 402 || hata.contains("quota", true) -> uyar("ElevenLabs krediniz bitti.")
					kod == 429 -> uyar("ElevenLabs aynı anda çok fazla istek aldı, biraz bekleyin.")
					hata.contains("voice", true) -> uyar("Bu ElevenLabs sesi hesabınızda kullanılamıyor. Ayarlardan başka anlatıcı sesi seçin.")
					else -> uyar("ElevenLabs hatası ($kod).")
				}
				return
			}

			val tamami = ByteArrayOutputStream()
			val tampon = ByteArray(4096)
			var tek: Byte? = null // 16 bit örnek ikiye bölünürse bir sonraki parçaya taşı
			b.inputStream.use { giris ->
				ses.play()
				while(true)
				{
					val okunan = giris.read(tampon)
					if(okunan < 0 || !guncelMi(n))
						break
					tamami.write(tampon, 0, okunan)
					var bas = 0
					var son = okunan
					val parca: ByteArray
					if(tek != null)
					{
						parca = ByteArray(okunan + 1)
						parca[0] = tek!!
						System.arraycopy(tampon, 0, parca, 1, okunan)
						son = okunan + 1
						tek = null
					}
					else
						parca = tampon
					if((son - bas) % 2 == 1)
					{
						tek = parca[son - 1]
						son -= 1
					}
					if(son > bas && guncelMi(n))
						ses.write(parca, bas, son - bas)
				}
			}

			if(guncelMi(n))
			{
				oncekiCumle[sesId] = metin
				val veri = tamami.toByteArray()
				synchronized(onbellek) {
					onbellek[kayitAnahtari] = veri
					onbellekBoyutu += veri.size
				}
			}
		}
		catch(e: Exception)
		{
			if(guncelMi(n))
			{
				Log.w(TAG, "ElevenLabs bağlantı hatası", e)
				uyar("ElevenLabs'e bağlanılamadı. İnternet bağlantısını kontrol edin.")
			}
		}
		finally
		{
			try { b?.disconnect() } catch(e: Exception) {}
			if(baglanti === b)
				baglanti = null
		}
	}

	private fun cal(n: Int, veri: ByteArray, bas: Int, uzunluk: Int)
	{
		if(!guncelMi(n))
			return
		ses.play()
		var i = bas
		while(i < bas + uzunluk && guncelMi(n))
		{
			val adim = minOf(8192, bas + uzunluk - i)
			ses.write(veri, i, adim)
			i += adim
		}
	}

	private fun uyar(mesaj: String)
	{
		val simdi = System.currentTimeMillis()
		if(simdi - sonUyari < 8000)
			return
		sonUyari = simdi
		anaHandler.post { Toast.makeText(context, mesaj, Toast.LENGTH_LONG).show() }
	}
}
