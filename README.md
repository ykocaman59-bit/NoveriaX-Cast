# NoveriaX Cast

Tamamen yerel ağ (Wi-Fi LAN) üzerinden çalışan ekran yansıtma ve cihaz yönetim uygulaması. İnternet gerekmez.

Proje iki modülden oluşur:

| Modül | Açıklama |
|-------|----------|
| `app` | **Telefon uygulaması** (MainActivity, DeviceAdapter, NetworkManager, ScreenCastService) |
| `tvreceiver` | **TV alıcı uygulaması** (Android TV / Google TV). Yayını alır, ekrana çizer |

> TV tarafında alıcı uygulama şarttır. Hazır bir TV (Samsung/LG/Android TV) ham TCP H.264 akışını
> kendiliğinden oynatmaz. `tvreceiver` APK'sını TV'ye kurun.

## Özellikler
1. **Wi-Fi kontrolü** – Açılışta ve "Cihaz Tara"da telefonun Wi-Fi'ı denetlenir. Kapalıysa `Telefon - Çevrimdışı`,
   kayıtlı TV ağda cevap vermiyorsa `[TV Adı] - Çevrimdışı` uyarısı çıkar.
2. **Cihaz keşfi** – NSD (mDNS) + UDP broadcast (port 53536) birlikte kullanılır.
3. **Kalıcılık** – "Ekle ve Yansıt" cihazı SharedPreferences'a (JSON) kaydeder; silinene kadar kalır.
4. **Yönetim** – Her cihazda `Yansıt` / `Yansıtmadan Çık` ve `Cihazı Sil`.

## Protokol (TCP 53535)
`[tip:1][uzunluk:4][veri]` — 1=CONFIG (w,h,SPS/PPS), 2=FRAME (pts+H.264), 3=STOP, 4=PING (cevap 0x01).

## Derleme
1. Android Studio (Hedgehog+) ile klasörü açın; JDK 17 gerekir.
2. `app` → telefona, `tvreceiver` → TV'ye kurun (`adb connect <tv-ip>` + `adb install`).
3. TV'de "NoveriaX Cast Alıcı" uygulamasını açın. Telefonda "Cihaz Tara" → TV'yi seç → "Ekle ve Yansıt".

GitHub Actions (`.github/workflows/android.yml`) her push'ta iki debug APK üretir.

## GitHub'a yükleme
```
git init
git add .
git commit -m "NoveriaX Cast ilk sürüm"
git branch -M main
git remote add origin https://github.com/<kullanici>/NoveriaXCast.git
git push -u origin main
```

## Bilinen sınırlar
- Ses aktarılmaz (yalnızca video). DRM/`FLAG_SECURE` içerikler (Netflix vb.) siyah görünür.
- TV'nin IP'si DHCP ile değişirse kayıtlı cihaz çevrimdışı görünür; yönlendiricide sabit IP önerilir.
- TV'de uygulama arka plandayken yayının otomatik öne gelmesi için alıcıya "Üstte görüntüle" izni verin;
  vermezseniz yansıtmadan önce alıcıyı TV'de bir kez açın.
- Yayın sırasında telefon dönerse çözünürlük sabit kalır.
- Gradle wrapper JAR'ı pakete dahil değil; Android Studio, `gradle-wrapper.properties` ile Gradle 8.5'i kendisi indirir.
