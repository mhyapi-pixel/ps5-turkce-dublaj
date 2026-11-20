// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.os.Bundle
import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
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

		findPreference<EditTextPreference>("gemini_anahtar")?.apply {
			setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
			summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
				val a = p.text?.trim().orEmpty()
				if(a.isEmpty()) "Girilmedi — Gemini kullanılmaz, cihaz içi çeviri ve sesler çalışır"
				else "Kayıtlı (…${a.takeLast(4)})"
			}
		}

		findPreference<Preference>("gemini_dene")?.setOnPreferenceClickListener {
			anahtariDene()
			true
		}

		findPreference<Preference>("ogrenilenleri_sil")?.setOnPreferenceClickListener {
			CeviriAyarlari(requireContext()).ogrenilenleriSil()
			Toast.makeText(requireContext(), "Karakter bilgileri sıfırlandı.", Toast.LENGTH_SHORT).show()
			true
		}

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

	private fun anahtariDene()
	{
		val ctx = requireContext().applicationContext
		val p = findPreference<Preference>("gemini_dene") ?: return
		val anahtar = CeviriAyarlari(ctx).geminiAnahtari
		if(anahtar.isEmpty())
		{
			Toast.makeText(ctx, "Önce Gemini API anahtarını girin.", Toast.LENGTH_LONG).show()
			return
		}
		p.summary = "Deneniyor…"
		Thread {
			val sonuc = try
			{
				val a = GeminiBeyin.analiz(anahtar, "Get down! They're right behind us!", "ELLIE", emptyList())
				if(a == null) "Olmadı: anahtar hatalı olabilir ya da internet yok."
				else "Çalışıyor ✓  \"${a.turkce}\" — " +
					(when(a.kadin) { true -> "kadın"; false -> "erkek"; else -> "cinsiyet belirsiz" }) + ", duygu: ${a.duygu}"
			}
			catch(e: GeminiBeyin.KotaDoldu) { "Anahtar doğru ama bugünkü ücretsiz sınır dolmuş. Yarın tekrar çalışır." }
			ana.post { p.summary = sonuc }
		}.start()
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
