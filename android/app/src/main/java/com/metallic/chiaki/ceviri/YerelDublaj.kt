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
 * Türkçe dublaj.
 * Gemini anahtarı girildiyse: altyazı Gemini ile doğal Türkçeye çevrilir, konuşanın kadın/erkek
 * olduğu ve duygusu bulunur; ücretsiz ses kotası yettikçe Gemini'nin duygulu sesiyle okunur.
 * Kota dolunca ya da internet yoksa TV içindeki seslerle devam eder (duyguya göre hız/perde ayarlı):
 * Erkek karakterler ve anlatıcı: Piper sesleri. Kadın karakterler: TV'nin kendi Türkçe sesi.
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

	/** Gemini sesleri 24 kHz gelir; ayrı bir çıkış kullanılır. */
	private val geminiSes: AudioTrack = AudioTrack.Builder()
		.setAudioAttributes(AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_GAME)
			.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
			.build())
		.setAudioFormat(AudioFormat.Builder()
			.setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
			.setSampleRate(GeminiBeyin.SES_ORNEKLEME)
			.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
			.build())
		.setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(GeminiBeyin.SES_ORNEKLEME, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), 16384))
		.setTransferMode(AudioTrack.MODE_STREAM)
		.build()

	/** İnternet istekleri ayrı iş parçacığında: Piper seslendirmesini bekletmez. */
	private val ag = Executors.newSingleThreadExecutor()

	/** Aynı satır tekrar gelirse Gemini'ye yeniden sorulmaz (kota korunur). */
	private val analizler = object: LinkedHashMap<String, Analiz>(256, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Analiz>?) = size > 2000
	}
	private val sonSatirlar = ArrayDeque<String>()

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

	/**
	 * İngilizce altyazı satırını Türkçe seslendirir. Önceki satır hâlâ okunuyorsa keser.
	 * @param yedekCeviri Gemini kullanılamazsa cihaz içi çeviri (ML Kit)
	 */
	fun seslendir(ingilizce: String, konusmaci: String?, yedekCeviri: (String, (String) -> Unit) -> Unit)
	{
		if(!ayarlar.dublaj)
			return
		val benim = nesil.incrementAndGet()
		kes()
		val ad = konusmaci?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
		val anahtar = ayarlar.geminiAnahtari

		if(anahtar.isEmpty() || !GeminiBeyin.metinKullanilabilir())
		{
			yedekIleOku(benim, ingilizce, ad, yedekCeviri)
			return
		}
		ag.execute {
			if(!guncelMi(benim))
				return@execute
			val onbellekAnahtari = "${ad ?: ""}|$ingilizce"
			val analiz: Analiz? = synchronized(analizler) { analizler[onbellekAnahtari] }
				?: analizIste(anahtar, ingilizce, ad)?.also { yeni -> synchronized(analizler) { analizler[onbellekAnahtari] = yeni } }
			synchronized(sonSatirlar) {
				sonSatirlar.addLast((if(ad != null) "$ad: " else "") + ingilizce)
				while(sonSatirlar.size > 4) sonSatirlar.removeFirst()
			}
			if(!guncelMi(benim))
				return@execute
			if(analiz == null)
			{
				yedekIleOku(benim, ingilizce, ad, yedekCeviri)
				return@execute
			}

			// Adı geçen karakterin cinsiyeti öğrenilir: sonraki sahnelerde de aynı ses
			val bilinen = ad?.let { Karakterler.cinsiyet(it, ayarlar) }
			if(ad != null && bilinen == null && analiz.kadin != null)
				Karakterler.ogren(ad, analiz.kadin, ayarlar)
			val kadin = bilinen ?: analiz.kadin

			if(ayarlar.geminiSesi && GeminiBeyin.sesKullanilabilir() && geminiIleOku(benim, anahtar, analiz, ad, kadin))
				return@execute
			if(guncelMi(benim))
				yerelOku(benim, analiz.turkce, ad, kadin, analiz.duygu)
		}
	}

	private fun analizIste(anahtar: String, ingilizce: String, ad: String?): Analiz?
	{
		val baglam = synchronized(sonSatirlar) { sonSatirlar.toList() }
		return try
		{
			GeminiBeyin.analiz(anahtar, ingilizce, ad, baglam)
		}
		catch(e: GeminiBeyin.KotaDoldu)
		{
			uyar("Gemini günlük sınırı doldu; cihaz içi çeviri ve seslerle devam ediliyor.")
			null
		}
	}

	private fun sesIste(anahtar: String, analiz: Analiz, sesAdi: String): FloatArray?
	{
		return try
		{
			GeminiBeyin.seslendir(anahtar, analiz.turkce, sesAdi, analiz.duygu)
		}
		catch(e: GeminiBeyin.KotaDoldu)
		{
			uyar("Gemini ses sınırı doldu; TV içindeki seslerle devam ediliyor.")
			null
		}
	}

	private fun yedekIleOku(n: Int, ingilizce: String, ad: String?, yedekCeviri: (String, (String) -> Unit) -> Unit)
	{
		yedekCeviri(ingilizce) { turkce ->
			if(guncelMi(n))
				yerelOku(n, turkce, ad, ad?.let { Karakterler.cinsiyet(it, ayarlar) }, "sakin")
		}
	}

	/** @return true: Gemini sesiyle okundu (ya da bu arada yeni satır geldi) */
	private fun geminiIleOku(n: Int, anahtar: String, analiz: Analiz, ad: String?, kadin: Boolean?): Boolean
	{
		val sesAdi = GeminiBeyin.sesSec(ad, kadin)
		val onbellekAnahtari = "gemini|$sesAdi|${analiz.duygu}|${analiz.turkce}"
		val hazir: FloatArray? = synchronized(onbellek) { onbellek[onbellekAnahtari] }
		val veri: FloatArray = hazir ?: (sesIste(anahtar, analiz, sesAdi) ?: return false).also { yeni ->
			synchronized(onbellek) {
				onbellek[onbellekAnahtari] = yeni
				onbellekBoyutu += yeni.size * 4
			}
		}
		if(!guncelMi(n))
			return true
		isci.execute { cal(n, veri, geminiSes, ayarlar.konusmaHizi) }
		return true
	}

	/** Duyguya göre konuşma hızı ve ses perdesi çarpanları (TV içi sesler için). */
	private fun duyguAyari(duygu: String): Pair<Float, Float> = when(duygu)
	{
		"ofkeli" -> Pair(1.12f, 0.94f)
		"uzgun" -> Pair(0.86f, 0.95f)
		"korkmus" -> Pair(1.14f, 1.08f)
		"neseli" -> Pair(1.06f, 1.06f)
		"heyecanli" -> Pair(1.16f, 1.05f)
		"fisilti" -> Pair(0.88f, 0.92f)
		"alayci" -> Pair(0.96f, 1.02f)
		"yorgun" -> Pair(0.84f, 0.93f)
		else -> Pair(1.0f, 1.0f)
	}

	private fun yerelOku(benim: Int, metin: String, ad: String?, kadinIpucu: Boolean?, duygu: String)
	{
		val (duyguHiz, duyguPerde) = duyguAyari(duygu)
		val kadin = kadinIpucu == true
		val ozelHiz = if(ad == null) 1.0f else 0.94f + ((ad.hashCode() and 0xff) / 255f) * 0.12f
		val hiz = ayarlar.konusmaHizi * ozelHiz * duyguHiz

		if(kadin)
		{
			val perde = 1.0f + (((ad?.hashCode() ?: 0) and 0x7) / 7f) * 0.15f
			anaHandler.post { if(guncelMi(benim)) tvOku(metin, perde * duyguPerde, hiz) }
			return
		}

		val konusan = ad != null || kadinIpucu == false
		val indirilen = PiperSesler.indirilenler(context).map { it.kod }
		if(indirilen.isEmpty())
		{
			// Ses paketi yok: erkek karakterleri TV sesi biraz kalın okur
			anaHandler.post { if(guncelMi(benim)) tvOku(metin, (if(konusan) 0.82f else 1.0f) * duyguPerde, hiz) }
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
		ag.shutdownNow()
		try { ses.release() } catch(e: Exception) {}
		try { geminiSes.release() } catch(e: Exception) {}
		try { tv.shutdown() } catch(e: Exception) {}
		PiperSesler.hepsiniBirak()
	}

	private fun kes()
	{
		for(t in arrayOf(ses, geminiSes))
		{
			try
			{
				t.pause()
				t.flush()
			}
			catch(e: Exception) {}
		}
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
		synchronized(onbellek) { onbellek[anahtar] }?.let { cal(n, it, ses, 1.0f); return }

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

	private fun cal(n: Int, veri: FloatArray, cikis: AudioTrack, hiz: Float)
	{
		if(!guncelMi(n))
			return
		try
		{
			// Gemini sesinde konuşma hızı ayarı oynatma hızıyla uygulanır
			if(cikis === geminiSes)
				cikis.playbackParams = cikis.playbackParams.setSpeed(hiz.coerceIn(0.5f, 2.0f))
		}
		catch(e: Exception) {}
		cikis.play()
		var i = 0
		while(i < veri.size && guncelMi(n))
		{
			val adim = minOf(4096, veri.size - i)
			cikis.write(veri, i, adim, AudioTrack.WRITE_BLOCKING)
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
