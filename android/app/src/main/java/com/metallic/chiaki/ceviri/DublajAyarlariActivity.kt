// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/** Türkçe dublaj ayarları: ses paketleri, anlatıcı, hız, kadın karakterler. */
class DublajAyarlariActivity: AppCompatActivity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "Türkçe Dublaj"
		if(savedInstanceState == null)
			supportFragmentManager.beginTransaction()
				.replace(android.R.id.content, DublajAyarlariFragment())
				.commit()
	}
}

class DublajAyarlariFragment: PreferenceFragmentCompat()
{
	private val ana = Handler(Looper.getMainLooper())
	@Volatile private var indiriliyor = false

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		preferenceManager.sharedPreferencesName = CeviriAyarlari.DOSYA
		setPreferencesFromResource(com.metallic.chiaki.R.xml.dublaj_preferences, rootKey)

		findPreference<Preference>("ses_paketleri")?.setOnPreferenceClickListener {
			if(!indiriliyor)
				hepsiniIndir()
			true
		}
	}

	override fun onResume()
	{
		super.onResume()
		durumuGoster()
	}

	private fun durumuGoster()
	{
		val p = findPreference<Preference>("ses_paketleri") ?: return
		val ctx = context ?: return
		val inen = PiperSesler.indirilenler(ctx)
		p.title = if(inen.size == PiperSesler.SESLER.size) "Ses paketleri hazır" else "Ses paketlerini indir"
		p.summary = if(inen.isEmpty())
			"3 erkek sesi, toplam ~190 MB. Bir kez indirilir, sonra internet gerekmez. İndirilene kadar TV'nin kendi sesi kullanılır."
		else
			"İndirilen: " + inen.joinToString(", ") { it.ad } +
				if(inen.size < PiperSesler.SESLER.size) " — eksikleri indirmek için dokunun" else ""
	}

	private fun hepsiniIndir()
	{
		val ctx = requireContext().applicationContext
		val p = findPreference<Preference>("ses_paketleri") ?: return
		indiriliyor = true
		Thread {
			var hata = false
			val eksik = PiperSesler.SESLER.filter { !PiperSesler.indirildiMi(ctx, it.kod) }
			eksik.forEachIndexed { i, s ->
				val tamam = PiperSesler.indir(ctx, s.kod) { yuzde ->
					ana.post { p.summary = "${s.ad} indiriliyor (${i + 1}/${eksik.size}): %$yuzde" }
				}
				if(!tamam) hata = true
			}
			try { PiperSesler.hazirla(ctx) } catch(e: Exception) { hata = true }
			ana.post {
				indiriliyor = false
				durumuGoster()
				Toast.makeText(ctx,
					if(hata) "Bazı ses paketleri indirilemedi. İnternet bağlantısını kontrol edip tekrar deneyin."
					else "Ses paketleri hazır.",
					Toast.LENGTH_LONG).show()
			}
		}.start()
	}
}
