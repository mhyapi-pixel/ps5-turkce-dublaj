// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

/**
 * Türkçe ElevenLabs sesleri ve karakter → ses eşleştirmesi.
 * Aynı karakter oyun boyunca hep aynı sesle konuşur.
 */
object ElevenLabsSesler
{
	data class Ses(val id: String, val ad: String)

	/** Akay – sinematik anlatıcı, oyun karakterleri için uygun. */
	const val VARSAYILAN_ANLATICI = "XjeT2V2moeoWReDQKJU8"

	val ERKEK = listOf(
		Ses("gsJ9nrlXYbPztJxC4fwc", "Atakan"),
		Ses("HV5rAif1q1ITHdvxqyMw", "Alp"),
		Ses("ahoWe8ySe0QVVYwd2eaM", "Sami"),
		Ses("bbnzyQpbvnDaCct77O9l", "Kaan"),
		Ses("tOqnHSMt5y3uRBgH7e8J", "Yusuf"),
		Ses("RTq4D78xGS1cikM2yy6C", "Yavuz"),
		Ses("Zpbxhg6LG47cY8y5BJrf", "Celil"),
		Ses("hjttIVYHClF49Uu8APYC", "Davut")
	)

	val KADIN = listOf(
		Ses("J2f0yzAg5MILTXuHmQvk", "Mimoza"),
		Ses("SMRHdMmNcA5RcHlk7xCP", "Cansu"),
		Ses("uvU9jrgGLWNPeNA4NgNT", "İrem"),
		Ses("AT6MdagXHAuKZlvWWwtT", "Ümit")
	)

	/** Oyunlarda sık geçen kadın karakter adları. Listede olmayanlar ayarlardan eklenebilir. */
	private val BILINEN_KADINLAR = setOf(
		"ELLIE", "ABBY", "DINA", "TESS", "MARLENE", "LEV", "YARA", "MEL", "NORA",
		"ALOY", "SYLENS", "VARL", "ZO", "SEYKA", "BETA", "TILDA", "ELISABET", "SOBECK",
		"LARA", "KASSANDRA", "AYA", "NAOE", "CLAIRE", "JILL",
		"ADA", "ASHLEY", "SHERRY", "REBECCA", "TIFA", "AERITH", "YUFFIE", "JESSIE", "JILL",
		"CIRI", "YENNEFER", "TRISS", "KEIRA", "JUDY", "PANAM", "ALT", "SONG SO MI", "SONGBIRD",
		"FREYA", "SIF", "ANGRBODA", "THRUD", "FAYE", "GNA", "CHLOE", "NADINE", "ELENA",
		"EVIE", "LAYLA", "MIRANDA", "TALI", "LIARA", "SAMARA",
		"ELIZABETH", "SAMUS", "PRINCESS", "QUEEN", "MOTHER", "MOM", "SISTER", "GRANDMA",
		"WOMAN", "GIRL", "LADY", "NURSE", "MAID", "WITCH", "ATSU", "YUNA", "LULU", "RIKKU",
		"QUIET", "EVA", "THE BOSS", "MERYL", "SNIPER WOLF", "KAINE", "2B", "A2", "DEVOLA",
		"POPOLA", "LYDIA", "SERANA", "SAEKO", "SAYA", "YOSHIKO"
	)

	/** Karaktere göre ses seçer. Ad yoksa anlatıcı sesi döner. */
	fun sec(konusmaci: String?, ayarlar: CeviriAyarlari): String
	{
		if(konusmaci.isNullOrBlank())
			return ayarlar.anlaticiSesi
		val ad = konusmaci.trim().uppercase()
		val kadin = ad in ayarlar.kadinKarakterler || ad in BILINEN_KADINLAR ||
			ad.split(' ').any { it in BILINEN_KADINLAR }
		val havuz = if(kadin) KADIN else ERKEK
		// Anlatıcı sesini karakterlere vermeyelim ki karışmasın
		val uygun = havuz.filter { it.id != ayarlar.anlaticiSesi }
		val sira = (ad.hashCode() and 0x7fffffff) % uygun.size
		return uygun[sira].id
	}
}
