// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.content.Context

/** Türkçe çeviri ve dublaj ayarları (uygulama kapansa da hatırlanır). */
class CeviriAyarlari(context: Context)
{
	companion object
	{
		const val DOSYA = "turkce_dublaj"
	}

	private val sp = context.getSharedPreferences(DOSYA, Context.MODE_PRIVATE)

	/** Ekrandaki her İngilizce yazının üstüne Türkçesini yaz. */
	var ekranCevirisi: Boolean
		get() = sp.getBoolean("ekran_cevirisi", true)
		set(v) = sp.edit().putBoolean("ekran_cevirisi", v).apply()

	/** Altyazıları Türkçe sesle oku. */
	var dublaj: Boolean
		get() = sp.getBoolean("dublaj", true)
		set(v) = sp.edit().putBoolean("dublaj", v).apply()

	/** Türkçe yazı İngilizcenin tam üstünü kapatsın mı (false: İngilizcenin hemen üstüne yazılır). */
	var ustuneKapat: Boolean
		get() = sp.getBoolean("ustune_kapat", false)
		set(v) = sp.edit().putBoolean("ustune_kapat", v).apply()

	/** Konuşma hızı (1.0 = normal). */
	val konusmaHizi: Float
		get() = sp.getString("konusma_hizi", "1.0")?.toFloatOrNull() ?: 1.0f

	/** Adı konuşmacı olarak geçmeyen satırları okuyan anlatıcı sesi. */
	val anlaticiSesi: String
		get() = sp.getString("anlatici_ses", PiperSesler.VARSAYILAN_ANLATICI) ?: PiperSesler.VARSAYILAN_ANLATICI

	/** Kullanıcının kadın olarak işaretlediği karakter adları (virgülle). */
	val kadinKarakterler: Set<String>
		get() = (sp.getString("kadin_karakterler", "") ?: "")
			.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

	/** Google AI Studio'dan alınan ücretsiz Gemini API anahtarı (boşsa Gemini kullanılmaz). */
	val geminiAnahtari: String
		get() = (sp.getString("gemini_anahtar", "") ?: "").trim()

	/** Gemini'nin duygulu sesini kullan (ücretsiz günlük sınırı düşüktür; dolunca TV sesleri devreye girer). */
	val geminiSesi: Boolean
		get() = sp.getBoolean("gemini_ses", true)

	/** Gemini'nin karakterler için bulduğu cinsiyet ("AD" -> kadın mı). */
	fun ogrenilenCinsiyet(adBuyuk: String): Boolean? =
		if(sp.contains("cinsiyet_$adBuyuk")) sp.getBoolean("cinsiyet_$adBuyuk", false) else null

	fun cinsiyetOgren(adBuyuk: String, kadin: Boolean) =
		sp.edit().putBoolean("cinsiyet_$adBuyuk", kadin).apply()

	/** Öğrenilen tüm karakter cinsiyetlerini sil. */
	fun ogrenilenleriSil()
	{
		val e = sp.edit()
		sp.all.keys.filter { it.startsWith("cinsiyet_") }.forEach { e.remove(it) }
		e.apply()
	}

	/** Ekranın alttan ne kadarı altyazı bölgesi sayılsın (0.30 = alt %30). */
	val altyaziBolgesi: Float
		get() = sp.getFloat("altyazi_bolgesi", 0.30f)
}
