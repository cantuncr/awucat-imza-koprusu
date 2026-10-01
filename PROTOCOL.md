# AwuCat İmza Köprüsü — Protokol (v1)

Tarayıcılar akıllı kartlara erişemez. "AwuCat İmza Köprüsü" kullanıcının bilgisayarında çalışan
küçük bir yerel servistir: tarayıcıdan **yalnızca imzalanacak özet/öznitelik baytlarını** alır, kartla
imzalar, imza baytlarını döndürür. Belge hiçbir zaman köprüye veya bir sunucuya gitmez; e-imza paketi
(CAdES-BES `sign.sgn`) tarayıcıda oluşturulur. PIN, köprünün kendi penceresine girilir.

## Taşıma

- HTTP/1.1 JSON, `http://127.0.0.1:34791` (meşgulse sırayla 34792, 34793).
- Her yanıt `Content-Type: application/json; charset=utf-8`.
- CORS: `Origin` başlığı izin listesindeyse aynısı `Access-Control-Allow-Origin` olarak döner; ayrıca
  `Access-Control-Allow-Private-Network: true`, `Access-Control-Allow-Headers: Content-Type`,
  `Access-Control-Allow-Methods: GET, POST, OPTIONS`. `OPTIONS` isteklerine 204 dönülür.
- İzin listesi varsayılanı: `https://awucat.app`, `https://www.awucat.app`, `http://localhost:*`,
  `http://127.0.0.1:*`. Listede olmayan bir origin ilk istekte kullanıcıya sorulur; onaylanırsa
  yapılandırmaya eklenir. Reddedilirse `ORIGIN_DENIED`.
- Yapılandırma: `~/.awucat-signer/config.json` (önceki sürümlerin `~/.udf-studio-signer/config.json` dosyası ilk açılışta bir kez taşınır) → `{ "allowedOrigins": [], "modules": [{"name","path"}], "port": 34791 }`.

## Uç noktalar

### `GET /status`
```json
{ "app": "awucat-signer", "version": "1.0.0", "platform": "mac-aarch64", "java": "21.0.1",
  "mock": false, "modules": [ { "name": "AKİS", "path": "/usr/local/lib/libakisp11.dylib", "present": true } ],
  "cardPresent": true }
```
Web istemcisi `app` alanında `awucat-signer` değerini bekler; köprünün yeniden adlandırma öncesi
sürümlerinin döndürdüğü `udf-studio-signer` de geriye dönük uyumluluk için kabul edilir.

### `POST /certificates`
İstek: `{ "pin": "1234" }` (isteğe bağlı; sertifikalar PIN'siz okunamıyorsa köprü kendi penceresinden PIN ister).
Yanıt:
```json
{ "certificates": [ {
  "id": "akis:0:1a2b…",            // köprüye özgü kararlı kimlik (modül:slot:sertifika-özeti)
  "der": "MIIF…",                    // X.509 DER, base64
  "subject": "CN=AYŞE YILMAZ, SERIALNUMBER=12345678901, C=TR",
  "issuer": "CN=TÜRKTRUST Nitelikli Elektronik Sertifika Hizmet Sağlayıcısı H7, O=TÜRKTRUST…, C=TR",
  "serialHex": "0A1B2C…",
  "notBefore": "2025-01-01T00:00:00Z", "notAfter": "2028-01-01T00:00:00Z",
  "keyUsage": ["digitalSignature", "nonRepudiation"],
  "expired": false
} ] }
```

### `POST /sign`
İstek:
```json
{ "certificateId": "akis:0:1a2b…",
  "algorithm": "SHA256withRSA",       // veya SHA256withECDSA
  "data": "MYGk…",                     // base64: imzalanacak DER (CAdES signedAttrs SET OF), ~200 bayt
  "documentName": "dilekce.udf",
  "origin": "https://awucat.app",
  "pin": "1234" }                       // isteğe bağlı; yoksa köprü kendi penceresinde sorar
```
Köprü, kullanıcıya **hangi sitenin hangi belgeyi hangi sertifikayla** imzalatmak istediğini gösteren
yerel bir onay penceresi açar (PIN alanı ile). Onaylanınca karttaki özel anahtarla `data` imzalanır.
Yanıt: `{ "signature": "base64", "algorithm": "SHA256withRSA" }`.

### Hatalar
HTTP 4xx/5xx ve gövde: `{ "error": { "code": "…", "message": "insan okunur Türkçe açıklama" } }`.
Kodlar: `MODULE_NOT_FOUND` (PKCS#11 kütüphanesi yok), `NO_CARD` (kart takılı değil), `PIN_REQUIRED`,
`PIN_INCORRECT` (kalan deneme sayısı `remaining` alanında), `PIN_LOCKED`, `CANCELLED` (kullanıcı
vazgeçti), `ORIGIN_DENIED`, `CERT_NOT_FOUND`, `UNSUPPORTED_ALGORITHM`, `INTERNAL`.

## Deneme modu
`--mock` ile başlatılınca köprü kart yerine bellekte ürettiği bir RSA anahtarı ve kendinden imzalı
sertifika ("CN=TEST İMZACI, SERIALNUMBER=11111111110, C=TR") kullanır, onay penceresi açmaz. Uçtan
uca testler bu modu kullanır. Üretilen imzalar gerçek değildir ve UYAP tarafından kabul edilmez.

## PKCS#11 modül tespiti (varsayılan yollar)
- Windows: `C:\Windows\System32\akisp11.dll`, `C:\Windows\SysWOW64\akisp11.dll`,
  `C:\Program Files\Gemalto\...`, `eTPKCS11.dll`, `opensc-pkcs11.dll`
- macOS: `/usr/local/lib/libakisp11.dylib`, `/Library/OpenSC/lib/opensc-pkcs11.so`,
  `/usr/local/lib/libeTPkcs11.dylib`, `/Library/Frameworks/eToken.framework/Versions/A/libeToken.dylib`
- Linux: `/usr/lib/libakisp11.so`, `/usr/lib64/libakisp11.so`, `/usr/lib/opensc-pkcs11.so`,
  `/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so`, `/usr/lib/libeTPkcs11.so`
Kullanıcı yapılandırmadan ek modül ekleyebilir.
