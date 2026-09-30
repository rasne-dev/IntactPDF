# 1.0.9 – İnceleme ve düzeltmeler

## Hatalar (düzeltildi)
- **Döndürülmüş / kırpılmış sayfalar:** Düzenleme, silme ve taşıma /Rotate ve CropBox'ı yok sayıyordu; kutular ve beyaz kapatma yanlış yere çiziliyordu. Artık görüntü uzayına göre çalışır (`PageGeometry.kt`, testli).
- **Geri alma çökmesi/bozulması:** Geri alma, render sürerken dosyayı ana iş parçacığında değiştiriyordu. Artık motorun mutex'i altında güvenli yapılır; başarısız olursa anlık görüntü kaybolmaz.
- **Başarısız işlemde yanlış anlık görüntü silme** ve düzenleme hatasında gizlenmiş metinlerin geri gelmesi giderildi.
- **ANR riski:** Dosya kopyalama (özellikle bulut sağlayıcıları) ana iş parçacığındaydı; artık IO'da.
- **Kayıt sırasında bozulma:** PDF'ler önce geçici dosyaya yazılıp taşınıyor (atomik).
- **Kaydetme / düzenleme yarışı:** Düzenleme sürerken kaydetme, paylaşma ve geri alma engellendi.
- **Önbellek sızıntısı:** `opened_doc_*`, `undo_*` vb. dosyalar birikiyordu; artık silinir, açılışta eskiler temizlenir. Geri alma diski 256 MB ile sınırlı.
- **`Float.MIN_VALUE` hatası** (`PdfTextLocator`): en küçük *pozitif* sayıdır; `-Float.MAX_VALUE` yapıldı.
- Satır sonu içeren metin PDF'e `?` olarak yazılıyordu; boşluğa çevriliyor.
- Yeni sayfa artık her zaman ilk sayfanın boyutunu değil, bulunulan sayfanın boyutunu/yönünü alıyor.
- Büyük sayfalarda bellek taşması (OOM) için piksel bütçesi ve yedek çözünürlük.
- Belge adı `content://` URI'sinin son parçasından değil, gerçek dosya adından alınıyor.
- Geri tuşu Yükleniyor/Hata ekranlarında da çalışıyor.
- `FileProvider` artık tüm önbelleği/`files` dizinini değil, yalnızca `cache/share/` klasörünü açıyor.

## UX/UI
- Kaydedilmemiş değişiklik uyarısı (Kaydet / Vazgeç / Kaydetmeden devam).
- Toast yerine Snackbar; düzenleme sonrası **"Geri Al"** eylemi.
- Sayfa numarasına dokununca **"Sayfaya Git"**.
- Parmak merkezli yakınlaştırma, kaydırma sınırı, çift dokunmayla 2.5x yakınlaştırma.
- Metin düzenleme penceresi: otomatik klavye, imleç sonda, değişiklik yoksa "Uygula" pasif, boş metin → silme onayı.
- Silme uyarısı artık dürüst: metin PDF'den kaldırılmaz, beyaz kutuyla kapatılır.
- Marka mavisiyle özel açık/koyu tema (önceden Material'ın mor varsayılanı), karanlık modda pencere teması.
- Kaydet/Paylaş dosya adı: `<belge adı>_duzenlendi.pdf`.

## Bilerek dokunulmayan / sizin karar vermeniz gerekenler
- `release` derlemesi **debug anahtarıyla** imzalanıyor ve `isMinifyEnabled=false`. Mağazaya çıkacaksanız kendi keystore'unuzu ekleyin.
- Gradle wrapper 9.2.1 ile AGP 8.7.3 uyumsuz olabilir (Gradle 9, AGP 8.13+ ister). Kendi makinenizde derlemeniz sorunsuzsa dokunmayın.
- Silinen metin PDF içeriğinde durur (whiteout). Gerçek redaksiyon ayrı bir özelliktir.
