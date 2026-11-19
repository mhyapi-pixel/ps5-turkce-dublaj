// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Türkçe Dublaj eklentisi: ekrandaki İngilizce yazıları okur, Türkçeye çevirir,
// altyazıları Türkçe seslendirir. Tamamen cihaz içinde (ML Kit) çalışır.

package com.metallic.chiaki.ceviri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** Ekranda gösterilecek tek bir çeviri: yazının yeri (kare koordinatında) ve Türkçesi. */
data class CeviriOgesi(val kutu: RectF, val turkce: String, val altyaziMi: Boolean)

class CeviriMotoru(
	private val context: Context,
	private val surfaceView: SurfaceView,
	private val overlay: CeviriOverlayView
)
{
	companion object
	{
		/** Ekran tarama aralığı. Düşük = daha hızlı tepki, daha çok pil/işlemci. */
		private const val TARAMA_ARALIGI_MS = 250L
		private const val KARE_GENISLIK = 1280
		private const val KARE_YUKSEKLIK = 720
		private const val ONBELLEK_BOYUTU = 3000
	}

	val ayarlar = CeviriAyarlari(context)

	private val anaHandler = Handler(Looper.getMainLooper())
	private val isciThread = HandlerThread("ceviri-motoru").apply { start() }
	private val isciHandler = Handler(isciThread.looper)
	private val isciExecutor = java.util.concurrent.Executor { isciHandler.post(it) }

	private val tanima = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val cevirmen: Translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build())

	private val dublaj = Dublaj(context, ayarlar)

	/** Bir kez çevrilen yazı tekrar çevrilmez: menüler, eşya adları anında gelir. */
	private val onbellek = object: LinkedHashMap<String, String>(256, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > ONBELLEK_BOYUTU
	}
	private val bekleyenler = HashSet<String>()

	private val calisiyor = AtomicBoolean(false)
	private val mesgul = AtomicBoolean(false)
	@Volatile private var modelHazir = false
	private var kare: Bitmap? = null

	private var sonAltyazi = ""

	fun baslat()
	{
		if(calisiyor.getAndSet(true))
			return
		cevirmen.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener { modelHazir = true }
			.addOnFailureListener {
				Toast.makeText(context, "Türkçe dil paketi indirilemedi. İnternet bağlantısını kontrol edin.", Toast.LENGTH_LONG).show()
			}
		if(!modelHazir)
			Toast.makeText(context, "Türkçe dil paketi hazırlanıyor (ilk seferde ~30 MB)…", Toast.LENGTH_SHORT).show()
		isciHandler.post(taramaDongusu)
	}

	fun durdur()
	{
		calisiyor.set(false)
		isciHandler.removeCallbacksAndMessages(null)
		dublaj.sustur()
		anaHandler.post { overlay.ogeleriAyarla(emptyList(), 1, 1) }
	}

	fun kapat()
	{
		durdur()
		dublaj.kapat()
		tanima.close()
		cevirmen.close()
		isciThread.quitSafely()
	}

	private val taramaDongusu = object: Runnable
	{
		override fun run()
		{
			if(!calisiyor.get())
				return
			val basla = SystemClock.uptimeMillis()
			if(modelHazir && (ayarlar.ekranCevirisi || ayarlar.dublaj) && !mesgul.get())
				kareYakala()
			val gecen = SystemClock.uptimeMillis() - basla
			isciHandler.postDelayed(this, (TARAMA_ARALIGI_MS - gecen).coerceAtLeast(30L))
		}
	}

	private fun kareYakala()
	{
		val holder = surfaceView.holder
		val surface = holder.surface
		if(surface == null || !surface.isValid || surfaceView.width == 0)
			return
		val bmp = kare ?: Bitmap.createBitmap(KARE_GENISLIK, KARE_YUKSEKLIK, Bitmap.Config.ARGB_8888).also { kare = it }
		mesgul.set(true)
		try
		{
			PixelCopy.request(surfaceView, bmp, { sonuc ->
				if(sonuc == PixelCopy.SUCCESS && calisiyor.get())
					yaziOku(bmp)
				else
					mesgul.set(false)
			}, isciHandler)
		}
		catch(e: Exception)
		{
			mesgul.set(false)
		}
	}

	private fun yaziOku(bmp: Bitmap)
	{
		tanima.process(InputImage.fromBitmap(bmp, 0))
			.addOnSuccessListener(isciExecutor) { sonuc -> sonucuIsle(sonuc) }
			.addOnCompleteListener(isciExecutor) { mesgul.set(false) }
	}

	private fun sonucuIsle(sonuc: Text)
	{
		val ogeler = ArrayList<CeviriOgesi>()
		val altyaziParcalari = ArrayList<Text.TextBlock>()

		for(blok in sonuc.textBlocks)
		{
			val kutu = blok.boundingBox ?: continue
			val metin = temizle(blok.text)
			if(!cevrilebilirMi(metin))
				continue

			val altyaziMi = altyaziBolgesindeMi(kutu.exactCenterX(), kutu.exactCenterY(), kutu.width().toFloat())
			if(altyaziMi)
				altyaziParcalari.add(blok)

			if(ayarlar.ekranCevirisi)
			{
				val turkce = cevir(metin)
				if(turkce != null)
					ogeler.add(CeviriOgesi(RectF(kutu), turkce, altyaziMi))
			}
		}

		if(ayarlar.dublaj)
			altyaziyiSeslendir(altyaziParcalari)

		anaHandler.post { overlay.ogeleriAyarla(if(ayarlar.ekranCevirisi) ogeler else emptyList(), KARE_GENISLIK, KARE_YUKSEKLIK) }
	}

	/** Önbellekte varsa hemen döner; yoksa arka planda çevirir, bir sonraki taramada görünür. */
	private fun cevir(metin: String, bitince: ((String) -> Unit)? = null): String?
	{
		synchronized(onbellek) {
			onbellek[metin]?.let { return it }
			if(!bekleyenler.add(metin) && bitince == null)
				return null
		}
		cevirmen.translate(metin)
			.addOnSuccessListener(isciExecutor) { turkce ->
				synchronized(onbellek) {
					onbellek[metin] = turkce
					bekleyenler.remove(metin)
				}
				bitince?.invoke(turkce)
			}
			.addOnFailureListener(isciExecutor) {
				synchronized(onbellek) { bekleyenler.remove(metin) }
			}
		return null
	}

	private fun altyaziyiSeslendir(parcalar: List<Text.TextBlock>)
	{
		if(parcalar.isEmpty())
			return
		val sirali = parcalar.sortedBy { it.boundingBox?.top ?: 0 }
		val ham = temizle(sirali.joinToString(" ") { it.text })
		val metin = konusmaciAdiniAyikla(ham)
		if(metin.length < 2)
			return
		// OCR titremesinde aynı satırı iki kez okumamak için benzerlik kontrolü
		if(benzerlik(metin, sonAltyazi) > 0.8)
			return
		sonAltyazi = metin

		val hazir = synchronized(onbellek) { onbellek[metin] }
		if(hazir != null)
			dublaj.oku(hazir)
		else
			cevir(metin) { turkce -> if(sonAltyazi == metin) dublaj.oku(turkce) }
	}

	private fun altyaziBolgesindeMi(x: Float, y: Float, genislik: Float): Boolean
	{
		val ortadaMi = x > KARE_GENISLIK * 0.2f && x < KARE_GENISLIK * 0.8f
		val altta = y > KARE_YUKSEKLIK * (1f - ayarlar.altyaziBolgesi)
		return ortadaMi && altta && genislik > KARE_GENISLIK * 0.08f
	}

	private fun temizle(s: String) = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

	private fun cevrilebilirMi(s: String): Boolean
	{
		if(s.length < 2)
			return false
		val harf = s.count { it.isLetter() }
		return harf >= 2 && harf >= s.length * 0.5
	}

	/** "JOEL: Get down!" → "Get down!" */
	private fun konusmaciAdiniAyikla(s: String): String
	{
		val m = Regex("^([A-Z][A-Z .'\\-]{1,24}):\\s*(.+)$").find(s) ?: return s
		return m.groupValues[2]
	}

	private fun benzerlik(a: String, b: String): Double
	{
		if(a == b) return 1.0
		if(a.isEmpty() || b.isEmpty()) return 0.0
		val x = a.lowercase(); val y = b.lowercase()
		val onceki = IntArray(y.length + 1) { it }
		val simdiki = IntArray(y.length + 1)
		for(i in 1..x.length)
		{
			simdiki[0] = i
			for(j in 1..y.length)
			{
				val maliyet = if(x[i - 1] == y[j - 1]) 0 else 1
				simdiki[j] = minOf(simdiki[j - 1] + 1, onceki[j] + 1, onceki[j - 1] + maliyet)
			}
			System.arraycopy(simdiki, 0, onceki, 0, simdiki.size)
		}
		return 1.0 - onceki[y.length].toDouble() / maxOf(x.length, y.length)
	}
}
