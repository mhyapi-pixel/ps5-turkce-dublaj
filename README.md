# PS5 Türkçe Dublaj

PS5 oyunlarını Remote Play ile Android TV'de ya da telefonda açar ve:

- **Ekran çevirisi:** Ekrandaki her İngilizce yazının (menüler, görevler, eşya adları, altyazılar) üstüne Türkçesini yazar.
- **Türkçe dublaj:** Altyazıları Türkçe sesle okur.
- **Kadın/erkek ve duygulu dublaj (isteğe bağlı, ücretsiz):** Ayarlar → Türkçe Dublaj → Gemini API anahtarı.
  Anahtar [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey) adresinden ücretsiz alınır.
  Gemini altyazıyı doğal Türkçeye çevirir, konuşanın kadın mı erkek mi olduğunu ve duygusunu (öfkeli, üzgün,
  korkmuş, fısıltı…) bulur; karakterleri hatırlar. Ücretsiz ses kotası yettikçe Gemini'nin gerçekçi sesiyle okur,
  kota dolunca cihaz içi seslerle duyguya göre hız/perde ayarlayarak devam eder.
- Anahtar girilmezse her şey cihazın içinde çalışır; ücret gerekmez.
  İlk açılışta Türkçe dil paketi (~30 MB) bir kez indirilir.

## Kurulum
1. **Releases** bölümünden son `PS5-Turkce-Dublaj.apk` dosyasını indirip kurun.
2. PS5'te: Ayarlar → Sistem → Uzaktan Oynama → **Uzaktan Oynamayı Etkinleştir**.
3. Uygulamada konsolu bulun, PS5'in gösterdiği kodla eşleştirin ve bağlanın.
4. Oyunda **altyazıları İngilizce olarak açın**.
5. Oyunun ses ayarlarında **diyalog/konuşma sesini kısın** (müzik ve efektler açık kalsın); konuşmaları Türkçe dublaj okur.

## En düşük gecikme için
- PS5'i modeme kabloyla bağlayın, TV'yi de mümkünse kabloyla ya da 5 GHz Wi-Fi ile bağlayın.
- Ayarlarda görüntüyü 720p / 60 FPS seçin.

## Lisans
[chiaki-ng](https://github.com/streetpea/chiaki-ng) üzerine kuruludur; AGPL-3.0 lisansı geçerlidir.
