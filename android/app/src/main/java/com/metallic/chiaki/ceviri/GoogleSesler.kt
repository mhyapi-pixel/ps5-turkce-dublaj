// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

/**
 * Google Chirp 3 HD Türkçe sesleri ve karakter → ses eşleştirmesi.
 * Aynı karakter oyun boyunca hep aynı sesle konuşur.
 */
object GoogleSesler
{
	private const val ON_EK = "tr-TR-Chirp3-HD-"

	/** Charon – derin, anlatıcı erkek sesi. */
	const val VARSAYILAN_ANLATICI = "Charon"

	val ERKEK = listOf("Charon", "Puck", "Fenrir", "Orus", "Enceladus", "Iapetus", "Algieba",
		"Alnilam", "Rasalgethi", "Sadaltager", "Schedar", "Umbriel", "Achird", "Zubenelgenubi")

	val KADIN = listOf("Kore", "Aoede", "Leda", "Zephyr", "Callirrhoe", "Despina", "Erinome",
		"Gacrux", "Laomedeia", "Pulcherrima", "Sulafat", "Vindemiatrix", "Autonoe", "Achernar")

	/** Oyunlarda sık geçen kadın karakter adları. Listede olmayanlar ayarlardan eklenebilir. */
	private val BILINEN_KADINLAR = setOf(
		"ELLIE", "ABBY", "DINA", "TESS", "MARLENE", "LEV", "YARA", "MEL", "NORA",
		"ALOY", "SYLENS", "VARL", "ZO", "SEYKA", "BETA", "TILDA", "ELISABET",
		"LARA", "KASSANDRA", "AYA", "NAOE", "CLAIRE", "JILL", "ADA", "ASHLEY", "SHERRY",
		"REBECCA", "TIFA", "AERITH", "YUFFIE", "JESSIE", "CIRI", "YENNEFER", "TRISS", "KEIRA",
		"JUDY", "PANAM", "ALT", "FREYA", "SIF", "ANGRBODA", "THRUD", "FAYE", "GNA", "CHLOE",
		"NADINE", "ELENA", "EVIE", "LAYLA", "MIRANDA", "TALI", "LIARA", "SAMARA", "ELIZABETH",
		"SAMUS", "PRINCESS", "QUEEN", "MOTHER", "MOM", "SISTER", "GRANDMA", "WOMAN", "GIRL",
		"LADY", "NURSE", "MAID", "WITCH", "ATSU", "YUNA", "LULU", "RIKKU", "QUIET", "EVA",
		"MERYL", "KAINE", "2B", "A2", "DEVOLA", "POPOLA", "LYDIA", "SERANA", "SAEKO", "MIKU"
	)

	fun tamAd(kisa: String) = ON_EK + kisa

	fun kadinMi(ad: String) = ad in KADIN

	/** Karaktere göre ses seçer. Ad yoksa anlatıcı sesi döner. */
	fun sec(konusmaci: String?, ayarlar: CeviriAyarlari): String
	{
		if(konusmaci.isNullOrBlank())
			return ayarlar.anlaticiSesi
		val ad = konusmaci.trim().uppercase()
		val kadin = ad in ayarlar.kadinKarakterler || ad in BILINEN_KADINLAR ||
			ad.split(' ').any { it in BILINEN_KADINLAR }
		val havuz = (if(kadin) KADIN else ERKEK).filter { it != ayarlar.anlaticiSesi }
		return havuz[(ad.hashCode() and 0x7fffffff) % havuz.size]
	}
}
