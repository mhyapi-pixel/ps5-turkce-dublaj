// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat

/** Türkçe dublaj ayarları: Google ses anahtarı, anlatıcı, hız, aylık sınır. */
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
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		preferenceManager.sharedPreferencesName = CeviriAyarlari.DOSYA
		setPreferencesFromResource(com.metallic.chiaki.R.xml.dublaj_preferences, rootKey)

		findPreference<EditTextPreference>("google_anahtar")?.apply {
			setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
			setSummaryProvider { p ->
				val deger = (p as EditTextPreference).text.orEmpty()
				if(deger.isBlank()) "Girilmedi — console.cloud.google.com → API'ler → Kimlik bilgileri"
				else "Kayıtlı: …" + deger.takeLast(4)
			}
		}
	}

	override fun onResume()
	{
		super.onResume()
		val sp = requireContext().getSharedPreferences(CeviriAyarlari.DOSYA, android.content.Context.MODE_PRIVATE)
		val ay = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
		val kullanilan = if(sp.getString("kota_ay", "") == ay) sp.getLong("kota_karakter", 0L) else 0L
		val sinir = CeviriAyarlari(requireContext()).aylikSinir
		findPreference<androidx.preference.Preference>("kota_durumu")?.summary =
			String.format(java.util.Locale("tr"), "%,d / %,d karakter", kullanilan, sinir)
	}
}
