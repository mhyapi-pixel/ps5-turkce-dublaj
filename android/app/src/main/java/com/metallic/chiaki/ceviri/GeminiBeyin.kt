// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone

/** Bir altyazı satırının Gemini ile çözümlenmiş hali. */
data class Analiz(
	val turkce: String,
	/** true: kadın, false: erkek, null: bilinmiyor */
	val kadin: Boolean?,
	val duygu: String
)

/**
 * Google AI Studio'nun ücretsiz Gemini API'si:
 *  - analiz(): altyazıyı doğal Türkçeye çevirir, konuşanın kadın/erkek olduğunu ve duyguyu bulur
 *  - seslendir(): Türkçe cümleyi duygulu, gerçekçi sesle okur (ücretsiz günlük sınırı düşüktür)
 * Sınır dolunca (HTTP 429) o model bir süre kapatılır, dublaj TV içindeki seslerle devam eder.
 */
object GeminiBeyin
{
	private const val TAG = "GeminiBeyin"
	private const val ADRES = "https://generativelanguage.googleapis.com/v1beta/models/"

	/** Sırayla denenir; ilki kaldırılmışsa (404) sonrakine geçilir. */
	private val METIN_MODELLERI = listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash-lite")
	private val SES_MODELLERI = listOf("gemini-2.5-flash-preview-tts", "gemini-3.1-flash-tts-preview")

	val DUYGULAR = listOf("sakin", "ofkeli", "uzgun", "korkmus", "neseli", "heyecanli", "fisilti", "alayci", "yorgun")

	private val KADIN_SESLERI = listOf("Kore", "Aoede", "Leda", "Despina", "Callirrhoe", "Zephyr")
	private val ERKEK_SESLERI = listOf("Orus", "Puck", "Fenrir", "Enceladus", "Iapetus", "Algenib")
	private const val ANLATICI_SESI = "Charon"

	const val SES_ORNEKLEME = 24000

	class KotaDoldu: Exception("Gemini kotası doldu")

	// model adı -> bu zamana kadar kullanma
	private val kapaliKadar = HashMap<String, Long>()
	private val bulunamayan = HashSet<String>()

	@Synchronized fun metinKullanilabilir() = model(METIN_MODELLERI) != null
	@Synchronized fun sesKullanilabilir() = model(SES_MODELLERI) != null

	@Synchronized
	private fun model(liste: List<String>): String?
	{
		val simdi = System.currentTimeMillis()
		return liste.firstOrNull { it !in bulunamayan && (kapaliKadar[it] ?: 0L) <= simdi }
	}

	/** Karakter için hep aynı Gemini sesi: Ellie her sahnede aynı sesle konuşur. */
	fun sesSec(konusmaci: String?, kadin: Boolean?): String
	{
		if(kadin == null && konusmaci == null)
			return ANLATICI_SESI
		val havuz = if(kadin == true) KADIN_SESLERI else ERKEK_SESLERI
		val tohum = konusmaci ?: return havuz[0]
		return havuz[(tohum.hashCode() and 0x7fffffff) % havuz.size]
	}

	/**
	 * Altyazıyı çevirir, cinsiyeti ve duyguyu bulur.
	 * @param onceki bağlam için son birkaç satır (konuşanı tahmin etmeye yardım eder)
	 * @return null: hata (internet yok, anahtar geçersiz vb.)
	 * @throws KotaDoldu tüm metin modellerinin sınırı doldu
	 */
	fun analiz(anahtar: String, ingilizce: String, konusmaci: String?, onceki: List<String>): Analiz?
	{
		val talimat = """
			You translate English video game subtitles into natural, spoken Turkish for a voice-over (dubbing).
			Keep it short, conversational and faithful; keep names as they are; do not add explanations.
			Also decide the speaker's gender and the emotion of the line.
			Reply ONLY with JSON: {"tr": "...", "cinsiyet": "kadin|erkek|bilinmiyor", "duygu": "${DUYGULAR.joinToString("|")}"}
			Use the speaker name, the previous lines and grammatical/contextual clues for gender. If unsure, use "bilinmiyor".
		""".trimIndent()
		val istem = buildString {
			if(onceki.isNotEmpty())
				append("Previous lines:\n").append(onceki.joinToString("\n")).append("\n\n")
			append("Speaker: ").append(konusmaci ?: "(not shown)").append('\n')
			append("Line: ").append(ingilizce)
		}
		val govde = JSONObject()
			.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", talimat))))
			.put("contents", JSONArray().put(JSONObject().put("role", "user")
				.put("parts", JSONArray().put(JSONObject().put("text", istem)))))
			.put("generationConfig", JSONObject()
				.put("temperature", 0.3)
				.put("maxOutputTokens", 400)
				.put("responseMimeType", "application/json"))

		val cevap = istek(METIN_MODELLERI, anahtar, govde, zamanAsimi = 3500) ?: return null
		return try
		{
			val metin = ilkParca(cevap)?.optString("text") ?: return null
			val j = JSONObject(metin.substring(metin.indexOf('{'), metin.lastIndexOf('}') + 1))
			val tr = j.optString("tr").trim()
			if(tr.isEmpty())
				return null
			val kadin = when(j.optString("cinsiyet")) { "kadin" -> true; "erkek" -> false; else -> null }
			val duygu = j.optString("duygu").takeIf { it in DUYGULAR } ?: "sakin"
			Analiz(tr, kadin, duygu)
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Analiz okunamadı", e)
			null
		}
	}

	/**
	 * Türkçe cümleyi duygulu sesle okutur.
	 * @return 24 kHz mono ses örnekleri; null: hata
	 * @throws KotaDoldu ses modellerinin sınırı doldu
	 */
	fun seslendir(anahtar: String, turkce: String, sesAdi: String, duygu: String): FloatArray?
	{
		val ton = when(duygu)
		{
			"ofkeli" -> "angrily, with a raised, tense voice"
			"uzgun" -> "sadly, with a soft, heavy voice"
			"korkmus" -> "fearfully, with a shaky, breathless voice"
			"neseli" -> "cheerfully, with a warm smile"
			"heyecanli" -> "excitedly and urgently"
			"fisilti" -> "in a quiet whisper"
			"alayci" -> "sarcastically, with a mocking tone"
			"yorgun" -> "tiredly, with an exhausted voice"
			else -> "naturally and calmly"
		}
		val istem = "Read this Turkish line like a professional game voice actor, $ton:\n$turkce"
		val govde = JSONObject()
			.put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", istem)))))
			.put("generationConfig", JSONObject()
				.put("responseModalities", JSONArray().put("AUDIO"))
				.put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
					.put("prebuiltVoiceConfig", JSONObject().put("voiceName", sesAdi)))))

		val cevap = istek(SES_MODELLERI, anahtar, govde, zamanAsimi = 7000) ?: return null
		return try
		{
			val veri = ilkParca(cevap)?.optJSONObject("inlineData")?.optString("data") ?: return null
			val bayt = Base64.decode(veri, Base64.DEFAULT)
			// 16 bit, küçük uçlu PCM → -1..1 arası örnekler
			FloatArray(bayt.size / 2) { i ->
				val s = (bayt[2 * i].toInt() and 0xff) or (bayt[2 * i + 1].toInt() shl 8)
				s.toShort() / 32768f
			}
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Ses çözülemedi", e)
			null
		}
	}

	private fun ilkParca(cevap: JSONObject): JSONObject? =
		cevap.optJSONArray("candidates")?.optJSONObject(0)
			?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)

	/** Listedeki ilk kullanılabilir modele istek atar; 404'te sonrakine, 429'da kapatıp sonrakine geçer. */
	private fun istek(modeller: List<String>, anahtar: String, govde: JSONObject, zamanAsimi: Int): JSONObject?
	{
		while(true)
		{
			val m = model(modeller) ?: throw KotaDoldu()
			var baglanti: HttpURLConnection? = null
			try
			{
				baglanti = (URL(ADRES + m + ":generateContent").openConnection() as HttpURLConnection).apply {
					requestMethod = "POST"
					connectTimeout = 2500
					readTimeout = zamanAsimi
					doOutput = true
					setRequestProperty("Content-Type", "application/json; charset=utf-8")
					setRequestProperty("x-goog-api-key", anahtar)
				}
				baglanti.outputStream.use { it.write(govde.toString().toByteArray(Charsets.UTF_8)) }
				val kod = baglanti.responseCode
				if(kod == 200)
					return JSONObject(baglanti.inputStream.bufferedReader().use { it.readText() })

				val hata = baglanti.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
				when(kod)
				{
					404 -> synchronized(this) { bulunamayan.add(m) }
					429 -> synchronized(this) { kapaliKadar[m] = kotaBitisi(hata) }
					else ->
					{
						Log.w(TAG, "$m HTTP $kod: ${hata.take(300)}")
						return null
					}
				}
				Log.i(TAG, "$m kullanılamıyor (HTTP $kod), sıradaki deneniyor")
			}
			catch(e: Exception)
			{
				Log.w(TAG, "$m isteği başarısız: ${e.message}")
				return null
			}
			finally
			{
				baglanti?.disconnect()
			}
		}
	}

	/** Günlük sınır dolduysa Pasifik saatiyle gece yarısına, dakikalık sınırsa kısa süre kapat. */
	private fun kotaBitisi(hata: String): Long
	{
		val simdi = System.currentTimeMillis()
		if(hata.contains("PerDay", ignoreCase = true) || hata.contains("per day", ignoreCase = true))
		{
			val c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
			c.add(Calendar.DAY_OF_YEAR, 1)
			c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 1)
			c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
			return c.timeInMillis
		}
		val bekle = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(hata)?.groupValues?.get(1)?.toLongOrNull() ?: 30L
		return simdi + (bekle + 2) * 1000
	}
}
