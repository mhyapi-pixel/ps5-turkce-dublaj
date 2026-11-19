// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat

/** Türkçe dublaj ayarları: ElevenLabs anahtarı, ses kalitesi, anlatıcı, hız. */
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

		findPreference<EditTextPreference>("elevenlabs_anahtar")?.apply {
			setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
			setSummaryProvider { p ->
				val deger = (p as EditTextPreference).text.orEmpty()
				if(deger.isBlank()) "Girilmedi — elevenlabs.io → Developers → API Keys"
				else "Kayıtlı: …" + deger.takeLast(4)
			}
		}
	}
}
