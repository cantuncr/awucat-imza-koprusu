# AwuCat İmza Köprüsü

Tarayıcılar akıllı kartlara erişemez. **AwuCat İmza Köprüsü**, bilgisayarınızda çalışan küçük bir
yerel servistir: AwuCat web uygulamasından **yalnızca imzalanacak özet baytlarını** (CAdES
`signedAttrs`, ~200 bayt) alır, kartınızdaki özel anahtarla imzalar ve imza baytlarını geri döndürür.
Belgenin kendisi köprüye veya herhangi bir sunucuya gitmez; e-imza paketi (`sign.sgn`) tarayıcıda
oluşturulur. Protokolün tamamı için bkz. [`docs/SIGNER_PROTOCOL.md`](../../docs/SIGNER_PROTOCOL.md).

Tek bağımlılık bir Java çalışma ortamıdır (Java 8 veya üstü); üçüncü taraf kütüphane kullanılmaz.
Kartlara JDK'nın yerleşik **SunPKCS11** sağlayıcısı üzerinden, kart üreticisinin PKCS#11 kütüphanesi
(`akisp11`, `eTPKCS11`, `opensc-pkcs11` …) ile erişilir.

## Güvenlik modeli

- Köprü yalnızca `127.0.0.1` üzerinde dinler (34791; meşgulse 34792, 34793). Ağdan erişilemez.
- Her istekte tarayıcının gönderdiği `Origin` başlığı izin listesiyle karşılaştırılır. `Origin`
  olmayan veya listede olmayan istekler `ORIGIN_DENIED` ile reddedilir. Varsayılan liste:
  `https://awucat.app`, `https://www.awucat.app`, `http://localhost:*`, `http://127.0.0.1:*`.
  Bilinmeyen bir site ilk isteğinde size sorulur ("bu sitenin imza köprüsünü kullanmasına izin
  ver?"); onaylarsanız yapılandırma dosyasına yazılır, reddederseniz 5 dakika boyunca tekrar sorulmaz.
- Her imza için **köprünün kendi penceresi** açılır: hangi site, hangi belge, hangi sertifika. PIN bu
  pencereye (maskeli alan) girilir; web sayfası PIN'i görmez. Kart yalnızca o işlem süresince açılır ve
  işlem biter bitmez kapatılır (`logout`); PIN hiçbir yerde saklanmaz, günlüğe yazılmaz.
- İmzalanacak veri (`data`) da günlüğe yazılmaz. İstek gövdesi en fazla 64 KB olabilir.
- Aynı anda tek bir kart işlemi yürütülür; eş zamanlı istekler sıraya alınır.
- Görüntü yokken (`--headless` ya da DISPLAY yok) hiçbir pencere açılmaz: PIN istekle gelmediyse
  `PIN_REQUIRED`, bilinmeyen origin'ler doğrudan `ORIGIN_DENIED` alır.

## Kurulum ve çalıştırma

### Hazır paket
`package.sh` ile üretilen kurulum paketini çalıştırın (macOS `.dmg`, Windows `.msi`, Linux `.deb`).
Paket kendi Java çalışma ortamını içerir; ayrıca Java kurmanız gerekmez. Uygulama açılınca sistem
tepsisinde / menü çubuğunda bir simge belirir; menüden durumu görebilir ve köprüyü kapatabilirsiniz.
İmzalamadan önce köprünün çalışıyor olması yeterlidir.

> macOS'ta imzalanmamış paket ilk açılışta Gatekeeper uyarısı verir: uygulamaya sağ tıklayıp **Aç**
> seçin. Windows'ta SmartScreen için "Yine de çalıştır" gerekebilir.

### Jar ile
```sh
java -jar awucat-signer.jar                 # normal mod
java -jar awucat-signer.jar --mock          # deneme modu (kart gerekmez)
java -jar awucat-signer.jar --headless      # pencere açma (sunucu / CI)
java -jar awucat-signer.jar --port 34792    # yalnızca bu porta bağlan
java -jar awucat-signer.jar --allow-origin https://ornek.com   # sormadan izin ver (tekrarlanabilir)
```
Kart sürücünüz 64-bit ise Java da 64-bit olmalıdır (32-bit sürücü + 64-bit Java çalışmaz).

Çalışıp çalışmadığını görmek için tarayıcı konsolundan:
```js
fetch("http://127.0.0.1:34791/status").then(r => r.json()).then(console.log)
```

## Deneme modu (`--mock`)

Kart yerine bellekte üretilen 2048 bit RSA anahtarı ve kendinden imzalı
`CN=TEST İMZACI, SERIALNUMBER=11111111110, C=TR` sertifikası kullanılır; onay penceresi açılmaz, PIN
istenmez. Uçtan uca testler bu modu kullanır. Bu imzalar gerçek değildir ve UYAP tarafından kabul
edilmez.

## Yapılandırma

`~/.awucat-signer/config.json` (ilk çalıştırmada oluşturulur; `AWUCAT_SIGNER_HOME` ortam değişkeni
ile klasör değiştirilebilir). Köprünün önceki sürümlerinden kalan `~/.udf-studio-signer/config.json`
ilk açılışta bir kez yeni klasöre kopyalanır; eski dosya `config.json.migrated` olarak bırakılır:
```json
{
  "allowedOrigins": ["https://ornek.com"],
  "modules": [ { "name": "AKİS (özel yol)", "path": "/opt/akis/libakisp11.dylib" } ],
  "port": 34791
}
```
- `allowedOrigins`: varsayılan listeye ek olarak izin verilen origin'ler (onayladığınız siteler
  buraya eklenir). `http://host:*` biçimi her portu kabul eder.
- `modules`: varsayılan yollarda bulunmayan PKCS#11 kütüphaneleri. Dosya yolu tam olmalıdır.
- `port`: ilk denenecek port.

Günlük: `~/.awucat-signer/signer.log` (1 MB'ta döndürülür).

## Desteklenen kartlar / modüller

Kartın PKCS#11 kütüphanesi kurulu olmalıdır; köprü şu yollara bakar ve bulduğu her modülün her
yuvasını tarar (`GET /status` → `modules[].present`):

| Kart / sürücü | Windows | macOS | Linux |
|---|---|---|---|
| AKİS (TÜBİTAK) | `C:\Windows\System32\akisp11.dll`, `SysWOW64\akisp11.dll` | `/usr/local/lib/libakisp11.dylib` | `/usr/lib/libakisp11.so`, `/usr/lib64/libakisp11.so` |
| Gemalto IDPrime | `C:\Program Files\Gemalto\IDGo 800 PKCS#11\IDPrimePKCS1164.dll`, `Classic Client\BIN\gclib.dll` | — | — |
| SafeNet eToken | `System32\eTPKCS11.dll`, `SysWOW64\eTPKCS11.dll` | `/usr/local/lib/libeTPkcs11.dylib`, `/Library/Frameworks/eToken.framework/…/libeToken.dylib` | `/usr/lib/libeTPkcs11.so`, `/usr/lib/x86_64-linux-gnu/libeTPkcs11.so` |
| OpenSC | `C:\Program Files\OpenSC Project\OpenSC\pkcs11\opensc-pkcs11.dll`, `System32\opensc-pkcs11.dll` | `/Library/OpenSC/lib/opensc-pkcs11.so`, Homebrew yolları | `/usr/lib/opensc-pkcs11.so`, `/usr/lib/x86_64-linux-gnu/…`, `/usr/lib/aarch64-linux-gnu/…` |

Başka bir yol için `modules` yapılandırmasını kullanın. Sertifika kimlikleri `modül:yuva:özet`
biçimindedir (`akis:0:1a2b…`); aynı kart iki modül üzerinden görünürse tek kez listelenir. Yalnızca
karşılık gelen özel anahtarı olan (imza) sertifikalar döndürülür; `keyUsage` alanına bakarak
`nonRepudiation` içeren nitelikli imza sertifikasını seçin.

SunPKCS11 çoğu kartta sertifikaları PIN'siz okuyamaz (`CKF_LOGIN_REQUIRED`); bu durumda
`/certificates` için PIN istekle gelmemişse köprü kendi penceresinden sorar.

## Hata kodları

`MODULE_NOT_FOUND`, `NO_CARD`, `PIN_REQUIRED`, `PIN_INCORRECT` (`remaining`: kart bildiriyorsa kalan
deneme), `PIN_LOCKED`, `CANCELLED`, `ORIGIN_DENIED`, `CERT_NOT_FOUND`, `UNSUPPORTED_ALGORITHM`,
`INTERNAL`; ayrıca bozuk istekler için `BAD_REQUEST`, bilinmeyen yol için `NOT_FOUND`.

## Sorun giderme

| Belirti | Sebep / çözüm |
|---|---|
| Tarayıcı köprüye ulaşamıyor | Köprü çalışıyor mu? Tepsi simgesi yoksa başlatın. `signer.log` içinde "Dinleniyor: …" satırına ve porta bakın. Chrome'da yerel ağ erişimi (Private Network Access) için köprü gerekli `Access-Control-Allow-Private-Network` başlığını gönderir. |
| `ORIGIN_DENIED` | Site izin listesinde değil ve onay penceresi reddedildi ya da görüntü yok. `--allow-origin` verin veya `config.json` → `allowedOrigins` ekleyin. |
| `MODULE_NOT_FOUND` | Kart sürücüsü kurulu değil (`/status` → `present:false`) ya da yüklenemedi (günlükte "kütüphanesi yüklenemedi"; genellikle 32/64-bit uyuşmazlığı veya Apple Silicon'da Intel sürücü). |
| `NO_CARD` | Kart takılı değil, okuyucu bağlı değil ya da pcscd/Smart Card servisi çalışmıyor. Kart köprü açıkken takılırsa 1-2 saniye içinde tanınır. |
| `PIN_INCORRECT` / `PIN_LOCKED` | Kalan deneme sayısı yalnızca kart "son deneme" bayrağını veriyorsa bildirilir. Kilitli kart PUK ile açılır (üreticinin aracıyla). |
| Onay penceresi görünmüyor | Pencere her zaman üstte açılır ama başka bir masaüstündeyse gizlenebilir; tepsi simgesine tıklayın. Uzak masaüstü / SSH oturumunda görüntü yoksa `--headless` davranışı geçerlidir. |
| Port meşgul | Başka bir köprü örneği çalışıyordur; ikinci örnek 34792/34793'e geçer, üçü de doluysa çıkar. |

## Geliştirme

```sh
sh build.sh          # → dist/awucat-signer.jar (yaklaşık 50 KB)
sh test/smoke.sh     # derler, --mock --headless --port 34799 ile başlatır, curl + openssl ile doğrular
sh package.sh        # jpackage ile bulunulan platformun kurulum paketi (dist/installer/)
```

Kaynaklar `src/main/java/app/awucat/signer/` altındadır; derleme yalnızca `javac` + `jar` ister.
`--release 8` ile derlenir: jar, Java 8'de de çalışır. Java 9+ ile gelen `Provider.configure` ve
Java 8'deki `SunPKCS11(InputStream)` kurucusu yansıma (reflection) ile çağrılır; böylece hem
kaynak kodu Java 8 API'sine bağlı kalır hem de yeni JDK'larda dışa açılmayan iç sınıflara derleme
zamanında dokunulmaz. "Kalan PIN denemesi" bilgisi SunPKCS11'in iç alanlarından okunur; bu yalnızca
jar `java -jar` ile (manifestteki `Add-Opens`) ya da `package.sh`'nin verdiği `--add-opens`
seçenekleriyle çalışırken mümkündür, aksi halde `remaining` alanı boş bırakılır.

## Bilinen sınırlamalar

- `CKA_ALWAYS_AUTHENTICATE` isteyen (her imza için ayrı PIN doğrulaması) kartlar SunPKCS11
  tarafından desteklenmez; imza `INTERNAL` ile başarısız olur.
- Kart okuyucular köprü başladıktan sonra takılırsa görünür (yuva listesi her istekte yenilenir), ancak
  bazı sürücüler yeni okuyucuyu ancak yeniden başlatınca görür.
- Aynı anda birden fazla kart takılıysa listeleme için girilen PIN sırayla her karta denenir; ilk
  yanlış PIN'de işlem durdurulur.

## Lisans

MIT — bkz. [`LICENSE`](LICENSE).
