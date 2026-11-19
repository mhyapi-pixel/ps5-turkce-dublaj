// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.ceviri

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * Oyun görüntüsünün üstünde duran şeffaf katman. Oyun görüntüsünü hiç bekletmez;
 * sadece çeviriler hazır oldukça Türkçe yazıları çizer.
 */
class CeviriOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	/** Oyun görüntüsünün (SurfaceView) ekrandaki yeri; StreamActivity günceller. */
	var hedef: View? = null

	var ayarlar: CeviriAyarlari? = null

	private var ogeler: List<CeviriOgesi> = emptyList()
	private var kareG = 1
	private var kareY = 1

	private val yogunluk = resources.displayMetrics.scaledDensity
	private val minYazi = 9f * yogunluk
	private val maxYazi = 30f * yogunluk

	private val zeminBoya = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 10, 10, 18) }
	private val altyaziZemin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
	private val vurguBoya = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(227, 10, 23) }
	private val yaziBoya = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		isFakeBoldText = true
		setShadowLayer(3f, 0f, 1f, Color.BLACK)
	}

	private val konum = IntArray(2)
	private val benimKonum = IntArray(2)
	private val r = RectF()

	init
	{
		setWillNotDraw(false)
		isClickable = false
		isFocusable = false
	}

	fun ogeleriAyarla(yeni: List<CeviriOgesi>, kareGenislik: Int, kareYukseklik: Int)
	{
		ogeler = yeni
		kareG = kareGenislik
		kareY = kareYukseklik
		invalidate()
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val h = hedef ?: return
		if(ogeler.isEmpty() || h.width == 0)
			return

		h.getLocationInWindow(konum)
		getLocationInWindow(benimKonum)
		val ox = (konum[0] - benimKonum[0]).toFloat()
		val oy = (konum[1] - benimKonum[1]).toFloat()
		val sx = h.width.toFloat() / kareG
		val sy = h.height.toFloat() / kareY
		val kapat = ayarlar?.ustuneKapat ?: false

		for(o in ogeler)
		{
			r.set(ox + o.kutu.left * sx, oy + o.kutu.top * sy, ox + o.kutu.right * sx, oy + o.kutu.bottom * sy)
			if(o.altyaziMi)
				altyaziCiz(canvas, o.turkce, r)
			else
				etiketCiz(canvas, o.turkce, r, kapat)
		}
	}

	/** Altyazı: İngilizcenin hemen üstünde, okunaklı büyük şerit. */
	private fun altyaziCiz(canvas: Canvas, metin: String, kutu: RectF)
	{
		val genislik = maxOf(kutu.width() * 1.15f, width * 0.5f).coerceAtMost(width * 0.95f)
		yaziBoya.textSize = (kutu.height() * 0.45f).coerceIn(14f * yogunluk, maxYazi)
		val dizgi = yerlesim(metin, genislik.toInt(), Layout.Alignment.ALIGN_CENTER)
		val sol = (kutu.centerX() - dizgi.width / 2f).coerceIn(0f, (width - dizgi.width).toFloat().coerceAtLeast(0f))
		val ust = (kutu.top - dizgi.height - 10f).coerceAtLeast(0f)
		canvas.drawRoundRect(sol - 12f, ust - 6f, sol + dizgi.width + 12f, ust + dizgi.height + 6f, 10f, 10f, altyaziZemin)
		canvas.drawRect(sol - 12f, ust - 6f, sol - 6f, ust + dizgi.height + 6f, vurguBoya)
		canvas.save()
		canvas.translate(sol, ust)
		dizgi.draw(canvas)
		canvas.restore()
	}

	/** Menü/arayüz yazısı: yazının üstüne küçük Türkçe etiket (veya üstünü kapatır). */
	private fun etiketCiz(canvas: Canvas, metin: String, kutu: RectF, kapat: Boolean)
	{
		val genislik = maxOf(kutu.width(), 60f * yogunluk).coerceAtMost(width * 0.6f)
		// Yazıyı kutuya sığana kadar küçült
		var boyut = (kutu.height() * (if(kapat) 0.8f else 0.6f)).coerceIn(minYazi, maxYazi)
		var dizgi: StaticLayout
		while(true)
		{
			yaziBoya.textSize = boyut
			dizgi = yerlesim(metin, genislik.toInt(), Layout.Alignment.ALIGN_NORMAL)
			val sinir = if(kapat) kutu.height() * 1.4f else kutu.height() * 1.6f + 8f * yogunluk
			if(dizgi.height <= sinir || boyut <= minYazi)
				break
			boyut -= 1f * yogunluk
		}
		val sol = kutu.left.coerceIn(0f, (width - dizgi.width).toFloat().coerceAtLeast(0f))
		val ust = if(kapat)
			kutu.centerY() - dizgi.height / 2f
		else
		{
			val yukari = kutu.top - dizgi.height - 3f
			if(yukari >= 0f) yukari else kutu.bottom + 3f
		}
		canvas.drawRoundRect(sol - 4f, ust - 2f, sol + dizgi.width + 4f, ust + dizgi.height + 2f, 6f, 6f, zeminBoya)
		canvas.save()
		canvas.translate(sol, ust)
		dizgi.draw(canvas)
		canvas.restore()
	}

	private fun yerlesim(metin: String, genislik: Int, hiza: Layout.Alignment): StaticLayout
	{
		val olcu = yaziBoya.measureText(metin).toInt() + 2
		val g = minOf(genislik, olcu).coerceAtLeast(1)
		return StaticLayout.Builder.obtain(metin, 0, metin.length, yaziBoya, g)
			.setAlignment(hiza)
			.setIncludePad(false)
			.setMaxLines(4)
			.build()
	}
}
