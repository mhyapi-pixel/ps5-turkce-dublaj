// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

/** Altyazıda adı geçen karakterin kadın mı erkek mi olduğunu tahmin eder. */
object Karakterler
{
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

	fun kadinMi(adBuyuk: String, ayarlar: CeviriAyarlari): Boolean =
		adBuyuk in ayarlar.kadinKarakterler || adBuyuk in BILINEN_KADINLAR ||
			adBuyuk.split(' ').any { it in BILINEN_KADINLAR }
}
