#!/usr/bin/env bash
# ==============================================================================
#  Самоподписанный HTTPS-сертификат для встроенного сервера «Журнала КубГАУ».
#
#  Нужен, чтобы iPhone разрешил Service Worker — а с ним приложение студента
#  открывается офлайн, без интернета и без включённого ПК.
#
#  Использование:   scripts/generate-cert.sh [доп. IP или имя ПК ...]
#  Пример:          scripts/generate-cert.sh 192.168.0.15 pc-kafedra.local
#
#  Результат: certs/cert.pem и certs/key.pem (срок действия 10 лет).
#  Положите папку certs рядом с KubGAU-Journal.exe и перезапустите программу.
#  Папка certs в Git не попадает (.gitignore) — ключ никому передавать не нужно.
#
#  ВАЖНО: iPhone открывает приложение по тому адресу, который записан в
#  сертификате. IP-адреса ПК определяются автоматически; если у ПК другой
#  адрес (например, он меняется) — передайте его аргументом или закрепите IP
#  за ПК в роутере. При смене адреса сертификат нужно выпустить заново.
# ==============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="certs"
DAYS=3650

if ! command -v openssl >/dev/null 2>&1; then
  echo "Не найден openssl. Установите его (macOS: brew install openssl, Debian/Ubuntu: apt install openssl)." >&2
  exit 1
fi

is_ipv4() { [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; }

DNS_NAMES=("visits11.local" "localhost")
IP_ADDRS=("127.0.0.1" "192.168.137.1")   # 192.168.137.1 — адрес ПК при раздаче Wi-Fi из Windows

add_dns() { local n; for n in "${DNS_NAMES[@]}"; do [ "$n" = "$1" ] && return 0; done; DNS_NAMES+=("$1"); }
add_ip()  { local a; for a in "${IP_ADDRS[@]}";  do [ "$a" = "$1" ] && return 0; done; IP_ADDRS+=("$1"); }

# имя этого ПК
HOST="$(hostname 2>/dev/null | cut -d. -f1 || true)"
if [[ "$HOST" =~ ^[A-Za-z0-9-]+$ ]]; then
  add_dns "$HOST"
  add_dns "$HOST.local"
fi

# IP-адреса этого ПК в локальных сетях
detect_ips() {
  if command -v hostname >/dev/null 2>&1 && hostname -I >/dev/null 2>&1; then
    hostname -I
  elif command -v ip >/dev/null 2>&1; then
    ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1
  elif command -v ipconfig >/dev/null 2>&1; then
    ipconfig getifaddr en0 2>/dev/null || true
    ipconfig getifaddr en1 2>/dev/null || true
  fi
}
for ip in $(detect_ips); do
  # 169.254.x.x — «адрес без сети», 127.x уже добавлен
  case "$ip" in 169.254.*|127.*) continue ;; esac
  is_ipv4 "$ip" && add_ip "$ip"
done

# адреса из аргументов
for extra in "$@"; do
  if is_ipv4 "$extra"; then add_ip "$extra"; else add_dns "$extra"; fi
done

mkdir -p "$OUT"
CONF="$(mktemp)"
trap 'rm -f "$CONF"' EXIT

{
  echo "[req]"
  echo "distinguished_name = dn"
  echo "x509_extensions    = ext"
  echo "prompt             = no"
  echo "[dn]"
  echo "CN = visits11.local"
  echo "O  = KubGAU Visits11"
  echo "[ext]"
  echo "basicConstraints     = critical, CA:TRUE"
  echo "keyUsage             = critical, digitalSignature, keyEncipherment, keyCertSign"
  echo "extendedKeyUsage     = serverAuth"
  echo "subjectAltName       = @san"
  echo "[san]"
  i=1; for n in "${DNS_NAMES[@]}"; do echo "DNS.$i = $n"; i=$((i+1)); done
  i=1; for a in "${IP_ADDRS[@]}";  do echo "IP.$i = $a";  i=$((i+1)); done
} > "$CONF"

openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days "$DAYS" \
  -keyout "$OUT/key.pem" -out "$OUT/cert.pem" -config "$CONF" 2>/dev/null
chmod 600 "$OUT/key.pem" 2>/dev/null || true

echo
echo "Готово: сертификат на $DAYS дней (10 лет)."
echo "  $OUT/cert.pem"
echo "  $OUT/key.pem"
echo
echo "Адреса в сертификате:"
for n in "${DNS_NAMES[@]}"; do echo "  имя  $n"; done
for a in "${IP_ADDRS[@]}";  do echo "  IP   $a"; done
echo
openssl x509 -in "$OUT/cert.pem" -noout -enddate -fingerprint -sha256
echo
echo "Дальше: положите папку certs рядом с KubGAU-Journal.exe и перезапустите программу."
echo "Для iPhone: откройте  http://<адрес ПК>:8090/setup  — там пошаговая инструкция."
