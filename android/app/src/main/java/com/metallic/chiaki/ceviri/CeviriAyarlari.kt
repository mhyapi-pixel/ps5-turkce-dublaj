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

	/** ElevenLabs API anahtarı (sadece bu cihazda saklanır). */
	val elevenLabsAnahtari: String
		get() = sp.getString("elevenlabs_anahtar", "")?.trim() ?: ""

	/** ElevenLabs ses modeli. */
	val sesModeli: String
		get() = sp.getString("ses_modeli", "eleven_multilingual_v2") ?: "eleven_multilingual_v2"

	/** Konuşma hızı (ElevenLabs: 0.7 – 1.2). */
	val konusmaHizi: Float
		get() = sp.getString("konusma_hizi", "1.08")?.toFloatOrNull() ?: 1.08f

	/** Adı konuşmacı olarak geçmeyen satırları okuyan anlatıcı sesi. */
	val anlaticiSesi: String
		get() = sp.getString("anlatici_ses", ElevenLabsSesler.VARSAYILAN_ANLATICI) ?: ElevenLabsSesler.VARSAYILAN_ANLATICI

	/** Kullanıcının kadın olarak işaretlediği karakter adları (virgülle). */
	val kadinKarakterler: Set<String>
		get() = (sp.getString("kadin_karakterler", "") ?: "")
			.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

	/** Ekranın alttan ne kadarı altyazı bölgesi sayılsın (0.30 = alt %30). */
	val altyaziBolgesi: Float
		get() = sp.getFloat("altyazi_bolgesi", 0.30f)
}
