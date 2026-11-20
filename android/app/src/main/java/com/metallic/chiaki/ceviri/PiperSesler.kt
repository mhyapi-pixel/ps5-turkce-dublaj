// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ücretsiz, internetsiz Türkçe Piper sesleri.
 * Ses dosyaları bir kez indirilir, sonra tamamen TV'nin içinde çalışır.
 */
object PiperSesler
{
	private const val TAG = "PiperSesler"
	private const val INDIRME_ADRESI = "https://github.com/mhyapi-pixel/ps5-turkce-dublaj/releases/download/ses-paketi/"

	data class Ses(val kod: String, val ad: String, val aciklama: String)

	val SESLER = listOf(
		Ses("fahrettin", "Fahrettin", "olgun erkek sesi"),
		Ses("dfki", "Kerem", "net, anlatıcı erkek sesi"),
		Ses("fettah", "Fettah", "derin erkek sesi")
	)

	const val VARSAYILAN_ANLATICI = "fahrettin"

	private fun klasor(context: Context) = File(context.filesDir, "piper").apply { mkdirs() }
	private fun modelDosyasi(context: Context, kod: String) = File(klasor(context), "tr-$kod.onnx")
	private fun tokenDosyasi(context: Context, kod: String) = File(klasor(context), "tokens-$kod.txt")
	private fun espeakKlasoru(context: Context) = File(klasor(context), "espeak-ng-data")

	fun indirildiMi(context: Context, kod: String) = modelDosyasi(context, kod).let { it.exists() && it.length() > 1_000_000 }

	fun indirilenler(context: Context) = SESLER.filter { indirildiMi(context, it.kod) }

	/** espeak-ng sözlüğünü ve token dosyalarını uygulama içinden kopyalar (bir kez). */
	@Synchronized
	fun hazirla(context: Context)
	{
		val hedef = espeakKlasoru(context)
		val isaret = File(hedef, ".tamam")
		if(!isaret.exists())
		{
			hedef.deleteRecursively()
			varliklariKopyala(context, "piper/espeak-ng-data", hedef)
			isaret.createNewFile()
		}
		for(s in SESLER)
		{
			val t = tokenDosyasi(context, s.kod)
			if(!t.exists())
				context.assets.open("piper/tokens-${s.kod}.txt").use { g -> FileOutputStream(t).use { g.copyTo(it) } }
		}
	}

	private fun varliklariKopyala(context: Context, yol: String, hedef: File)
	{
		val icerik = context.assets.list(yol) ?: emptyArray()
		if(icerik.isEmpty())
		{
			hedef.parentFile?.mkdirs()
			context.assets.open(yol).use { g -> FileOutputStream(hedef).use { g.copyTo(it) } }
			return
		}
		hedef.mkdirs()
		for(ad in icerik)
			varliklariKopyala(context, "$yol/$ad", File(hedef, ad))
	}

	/**
	 * Bir ses paketini indirir (~63 MB). İlerleme 0..100 olarak bildirilir.
	 * Arka plan iş parçacığında çağrılmalıdır.
	 */
	fun indir(context: Context, kod: String, ilerleme: (Int) -> Unit): Boolean
	{
		val hedef = modelDosyasi(context, kod)
		val gecici = File(hedef.path + ".indiriliyor")
		try
		{
			var adres = URL("${INDIRME_ADRESI}tr-$kod.onnx")
			var b: HttpURLConnection
			var yonlendirme = 0
			while(true)
			{
				b = adres.openConnection() as HttpURLConnection
				b.instanceFollowRedirects = false
				b.connectTimeout = 10000
				b.readTimeout = 30000
				val kod2 = b.responseCode
				if(kod2 in 300..399 && yonlendirme < 5)
				{
					adres = URL(adres, b.getHeaderField("Location"))
					b.disconnect()
					yonlendirme++
					continue
				}
				if(kod2 != 200)
				{
					Log.w(TAG, "İndirme hatası $kod2")
					return false
				}
				break
			}
			val toplam = b.contentLengthLong
			var inen = 0L
			var sonYuzde = -1
			b.inputStream.use { giris ->
				FileOutputStream(gecici).use { cikis ->
					val tampon = ByteArray(64 * 1024)
					while(true)
					{
						val n = giris.read(tampon)
						if(n < 0) break
						cikis.write(tampon, 0, n)
						inen += n
						if(toplam > 0)
						{
							val yuzde = (inen * 100 / toplam).toInt()
							if(yuzde != sonYuzde) { sonYuzde = yuzde; ilerleme(yuzde) }
						}
					}
				}
			}
			b.disconnect()
			if(toplam > 0 && inen != toplam)
				return false
			return gecici.renameTo(hedef)
		}
		catch(e: Exception)
		{
			Log.w(TAG, "İndirme başarısız", e)
			gecici.delete()
			return false
		}
	}

	fun sil(context: Context, kod: String)
	{
		modelDosyasi(context, kod).delete()
		synchronized(yuklu) { yuklu.remove(kod)?.release() }
	}

	// TV belleğini korumak için aynı anda en fazla 2 ses yüklü tutulur.
	private val yuklu = object: LinkedHashMap<String, OfflineTts>(4, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OfflineTts>): Boolean
		{
			if(size > 2)
			{
				try { eldest.value.release() } catch(e: Exception) {}
				return true
			}
			return false
		}
	}

	/** Sesi yükler (ilk seferde 1-2 saniye sürer, sonra bellekte kalır). */
	fun motor(context: Context, kod: String): OfflineTts?
	{
		synchronized(yuklu) { yuklu[kod]?.let { return it } }
		if(!indirildiMi(context, kod))
			return null
		return try
		{
			hazirla(context)
			val ayar = OfflineTtsConfig(
				model = OfflineTtsModelConfig(
					vits = OfflineTtsVitsModelConfig(
						model = modelDosyasi(context, kod).absolutePath,
						tokens = tokenDosyasi(context, kod).absolutePath,
						dataDir = espeakKlasoru(context).absolutePath,
						noiseScale = 0.667f,
						noiseScaleW = 0.8f,
						lengthScale = 1.0f
					),
					numThreads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() - 1)),
					debug = false,
					provider = "cpu"
				)
			)
			val tts = OfflineTts(assetManager = null, config = ayar)
			synchronized(yuklu) { yuklu[kod] = tts }
			tts
		}
		catch(e: Throwable)
		{
			Log.e(TAG, "Piper sesi yüklenemedi: $kod", e)
			null
		}
	}

	fun hepsiniBirak()
	{
		synchronized(yuklu) {
			yuklu.values.forEach { try { it.release() } catch(e: Exception) {} }
			yuklu.clear()
		}
	}
}
