#!/bin/sh
# Smoke test: builds the jar, starts it with --mock --headless --port 34799 and exercises the
# protocol with curl; the RSA signature is verified with openssl against the certificate the
# bridge returned. Needs: a JDK, curl, openssl. Exit code 0 = all checks passed.
set -eu
cd "$(dirname "$0")/.."

PORT=34799
ORIGIN="http://localhost:3000"
BASE="http://127.0.0.1:$PORT"
TMP=$(mktemp -d)
# Keep the test away from the real ~/.awucat-signer (config + log).
export AWUCAT_SIGNER_HOME="$TMP/home"
PID=""

cleanup() {
  if [ -n "$PID" ]; then kill "$PID" 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

fail() {
  echo "FAIL: $*" >&2
  echo "--- signer output:" >&2
  cat "$TMP/signer.out" >&2 || true
  exit 1
}

# First string field named $1 from the JSON on stdin (our JSON is compact and single-line).
json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

sh build.sh

"${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar dist/awucat-signer.jar --mock --headless --port "$PORT" \
  > "$TMP/signer.out" 2>&1 &
PID=$!

i=0
until curl -s -o /dev/null -H "Origin: $ORIGIN" "$BASE/status"; do
  i=$((i + 1))
  [ "$i" -lt 50 ] || fail "köprü $PORT portunda başlamadı"
  sleep 0.2
done

echo "== GET /status"
STATUS=$(curl -s -D "$TMP/h" -H "Origin: $ORIGIN" "$BASE/status")
echo "$STATUS"
echo "$STATUS" | grep -q '"app":"awucat-signer"' || fail "status: app"
echo "$STATUS" | grep -q '"version":"1.0.0"' || fail "status: version"
echo "$STATUS" | grep -q '"mock":true' || fail "status: mock"
echo "$STATUS" | grep -q '"cardPresent":true' || fail "status: cardPresent"
grep -qi "^Access-Control-Allow-Origin: $ORIGIN" "$TMP/h" || fail "status: Access-Control-Allow-Origin"
grep -qi "^Content-Type: application/json; charset=utf-8" "$TMP/h" || fail "status: Content-Type"

echo "== OPTIONS preflight"
curl -s -o /dev/null -D "$TMP/h" -X OPTIONS -H "Origin: $ORIGIN" \
  -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: content-type" \
  -H "Access-Control-Request-Private-Network: true" "$BASE/sign"
grep -q "^HTTP/1.1 204" "$TMP/h" || fail "preflight: expected 204"
grep -qi "^Access-Control-Allow-Origin: $ORIGIN" "$TMP/h" || fail "preflight: Allow-Origin"
grep -qi "^Access-Control-Allow-Private-Network: true" "$TMP/h" || fail "preflight: Allow-Private-Network"
grep -qi "^Access-Control-Allow-Headers: Content-Type" "$TMP/h" || fail "preflight: Allow-Headers"
grep -qi "^Access-Control-Allow-Methods: GET, POST, OPTIONS" "$TMP/h" || fail "preflight: Allow-Methods"
echo "204 + CORS başlıkları tamam"

echo "== disallowed origin"
DENIED=$(curl -s -D "$TMP/h" -H "Origin: https://evil.example" "$BASE/status")
echo "$DENIED"
grep -q "^HTTP/1.1 403" "$TMP/h" || fail "origin: expected 403"
echo "$DENIED" | grep -q '"code":"ORIGIN_DENIED"' || fail "origin: expected ORIGIN_DENIED"
if grep -qi "^Access-Control-Allow-Origin" "$TMP/h"; then fail "origin: CORS header leaked to a denied origin"; fi
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/status")" = "403" ] || fail "origin: request without Origin must be denied"
[ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Origin: http://127.0.0.1:5173' "$BASE/status")" = "200" ] || fail "origin: http://127.0.0.1:* must be allowed"

echo "== POST /certificates"
CERTS=$(curl -s -H "Origin: $ORIGIN" -X POST -H "Content-Type: application/json" -d '{}' "$BASE/certificates")
echo "$CERTS" | sed 's/"der":"[^"]*"/"der":"…"/'
ID=$(echo "$CERTS" | json_field id)
[ -n "$ID" ] || fail "certificates: id"
echo "$CERTS" | grep -q '"subject":"CN=TEST İMZACI, SERIALNUMBER=11111111110, C=TR"' || fail "certificates: subject"
echo "$CERTS" | grep -q '"keyUsage":\["digitalSignature","nonRepudiation"\]' || fail "certificates: keyUsage"
echo "$CERTS" | grep -q '"expired":false' || fail "certificates: expired"
echo "$CERTS" | json_field der | openssl base64 -d -A > "$TMP/cert.der"
openssl x509 -inform DER -in "$TMP/cert.der" -noout -subject -dates || fail "certificates: openssl cannot parse DER"
openssl x509 -inform DER -in "$TMP/cert.der" -noout -pubkey > "$TMP/pub.pem"

echo "== POST /sign (100 random bytes, SHA256withRSA)"
openssl rand 100 > "$TMP/data.bin"
DATA=$(openssl base64 -A -in "$TMP/data.bin")
SIGN=$(curl -s -H "Origin: $ORIGIN" -X POST -H "Content-Type: application/json" \
  -d "{\"certificateId\":\"$ID\",\"algorithm\":\"SHA256withRSA\",\"data\":\"$DATA\",\"documentName\":\"dilekce.udf\",\"origin\":\"$ORIGIN\",\"pin\":\"1234\"}" \
  "$BASE/sign")
echo "$SIGN" | cut -c1-100
echo "$SIGN" | grep -q '"algorithm":"SHA256withRSA"' || fail "sign: algorithm"
echo "$SIGN" | json_field signature | openssl base64 -d -A > "$TMP/sig.bin"
openssl dgst -sha256 -verify "$TMP/pub.pem" -signature "$TMP/sig.bin" "$TMP/data.bin" || fail "sign: openssl verification failed"

echo "== error codes"
check_code() { # $1 expected code, $2 endpoint, $3 body
  OUT=$(curl -s -H "Origin: $ORIGIN" -X POST -d "$3" "$BASE/$2")
  echo "$OUT" | grep -q "\"code\":\"$1\"" || fail "$2 → expected $1, got: $OUT"
  echo "$1 tamam"
}
check_code CERT_NOT_FOUND sign "{\"certificateId\":\"akis:0:0000\",\"algorithm\":\"SHA256withRSA\",\"data\":\"$DATA\"}"
check_code UNSUPPORTED_ALGORITHM sign "{\"certificateId\":\"$ID\",\"algorithm\":\"SHA1withRSA\",\"data\":\"$DATA\"}"
check_code UNSUPPORTED_ALGORITHM sign "{\"certificateId\":\"$ID\",\"algorithm\":\"SHA256withECDSA\",\"data\":\"$DATA\"}"
check_code BAD_REQUEST sign "{\"certificateId\":\"$ID\",\"algorithm\":\"SHA256withRSA\"}"
check_code BAD_REQUEST sign "{not json"
head -c 70000 /dev/zero | tr '\0' 'a' > "$TMP/big.txt"
[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Origin: $ORIGIN" -X POST --data-binary "@$TMP/big.txt" "$BASE/sign")" = "400" ] \
  || fail "64 KB body limit not enforced"
echo "gövde sınırı tamam"

echo "== log hygiene"
if grep -q "$DATA" "$TMP/home/signer.log"; then fail "data-to-be-signed leaked into the log"; fi
if grep -q '1234' "$TMP/home/signer.log"; then fail "PIN leaked into the log"; fi
grep -q "İmzalandı" "$TMP/home/signer.log" || fail "sign operation not logged"

echo
echo "SMOKE OK"
