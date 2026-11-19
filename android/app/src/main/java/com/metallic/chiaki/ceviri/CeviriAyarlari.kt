// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.content.Context

/** Türkçe çeviri ve dublaj ayarları (uygulama kapansa da hatırlanır). */
class CeviriAyarlari(context: Context)
{
	private val sp = context.getSharedPreferences("turkce_dublaj", Context.MODE_PRIVATE)

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

	/** Dublaj konuşma hızı (1.0 = normal). Altyazıya yetişmesi için varsayılan biraz hızlı. */
	var konusmaHizi: Float
		get() = sp.getFloat("konusma_hizi", 1.15f)
		set(v) = sp.edit().putFloat("konusma_hizi", v).apply()


	/** Ekranın alttan ne kadarı altyazı bölgesi sayılsın (0.30 = alt %30). */
	var altyaziBolgesi: Float
		get() = sp.getFloat("altyazi_bolgesi", 0.30f)
		set(v) = sp.edit().putFloat("altyazi_bolgesi", v).apply()
}
