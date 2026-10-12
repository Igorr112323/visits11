#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLEAdvertising.h>
#include <esp_bt.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <freertos/queue.h>

// UUID из приложения студента для iPhone (ios/Sources/ContentView.swift) и KubGAU-Teacher
#define SERVICE_UUID        "f0391101-0203-0405-0607-08090a0b0c0d"
#define CHAR_UUID           "f0391102-0203-0405-0607-08090a0b0c0d"

// Дополнительный UUID (для совместимости с черновиком)
#define LEGACY_SERVICE_UUID "a9e81100-5631-3154-8000-00805f9b34fb"
#define LEGACY_CHAR_UUID    "a9e81101-5631-3154-8000-00805f9b34fb"

static const uint8_t PN_ADDR = 0x24;

// Пины на WEMOS D1 R32 (IO25/IO26) и ESP32 (D14/D27), плюс автоопределение порядка пинов и режима
static int activePinA = 25;
static int activePinB = 26;
static bool useHsuUart = false;

static const uint8_t NFC_AID[7] = {0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04};
static const size_t NAME_CAP = 96;
static const uint32_t PC_TIMEOUT_MS = 3000;

struct BleJob {
  uint16_t len;
  char data[300];
};

static SemaphoreHandle_t txMutex;
static SemaphoreHandle_t tapMutex;
static SemaphoreHandle_t replySem;
static QueueHandle_t bleQueue;

static volatile uint32_t waitId = 0;
static uint32_t tapSeq = 0;
static uint8_t replyCode = 4;
static char replyName[NAME_CAP + 1];

static BLECharacteristic *markChar = nullptr;
static BLECharacteristic *legacyMarkChar = nullptr;
static uint8_t bleBuf[300];
static size_t bleLen = 0;
static bool bleCollecting = false;

static bool pnOk = false;

static void serialSend(const char *s) {
  xSemaphoreTake(txMutex, portMAX_DELAY);
  Serial.write((const uint8_t *)s, strlen(s));
  Serial.flush();
  xSemaphoreGive(txMutex);
}

static void cleanField(char *s) {
  for (char *p = s; *p; p++) {
    if (*p == ':' || *p == '\r' || *p == '\n') *p = '_';
  }
}

static size_t utf8Cut(const char *s, size_t maxBytes) {
  size_t n = strlen(s);
  if (n <= maxBytes) return n;
  n = maxBytes;
  while (n > 0 && (((uint8_t)s[n]) & 0xC0) == 0x80) n--;
  return n;
}

static uint8_t exchangeWithPc(const char *token, const char *device, char *nameOut, size_t nameCap) {
  nameOut[0] = 0;
  if (!token || !token[0]) return 4;
  xSemaphoreTake(tapMutex, portMAX_DELAY);
  uint32_t id = ++tapSeq;
  if (id == 0) id = ++tapSeq;
  xSemaphoreTake(replySem, 0);
  waitId = id;
  char t[160];
  char d[160];
  strncpy(t, token, sizeof(t) - 1);
  t[sizeof(t) - 1] = 0;
  strncpy(d, device ? device : "", sizeof(d) - 1);
  d[sizeof(d) - 1] = 0;
  cleanField(t);
  cleanField(d);
  char line[360];
  snprintf(line, sizeof(line), "TAP:%lu:%s:%s\n", (unsigned long)id, t, d);
  serialSend(line);
  uint8_t code = 4;
  if (xSemaphoreTake(replySem, pdMS_TO_TICKS(PC_TIMEOUT_MS)) == pdTRUE) {
    code = replyCode;
    size_t n = utf8Cut(replyName, nameCap - 1);
    memcpy(nameOut, replyName, n);
    nameOut[n] = 0;
  }
  waitId = 0;
  xSemaphoreGive(tapMutex);
  if (code != 0 && code != 2 && code != 4 && code != 5) code = 4;
  return code;
}

static void handleLine(char *line) {
  size_t n = strlen(line);
  while (n > 0 && (line[n - 1] == '\r' || line[n - 1] == ' ')) line[--n] = 0;
  while (*line == '\r' || *line == ' ') line++;
  if (strcmp(line, "PING") == 0 || (strncmp(line, "REPLY:", 6) != 0 && strstr(line, "PING") != nullptr)) {
    serialSend("VISITS11-ESP32\n");
    return;
  }
  if (strncmp(line, "REPLY:", 6) != 0) return;
  char *p = line + 6;
  char *e = strchr(p, ':');
  if (!e) return;
  *e = 0;
  uint32_t id = strtoul(p, nullptr, 10);
  p = e + 1;
  e = strchr(p, ':');
  const char *name = "";
  if (e) {
    *e = 0;
    name = e + 1;
  }
  int code = atoi(p);
  if (code == 1) code = 0;
  if (id == 0 || id != waitId) return;
  replyCode = (uint8_t)code;
  size_t k = utf8Cut(name, NAME_CAP);
  memcpy(replyName, name, k);
  replyName[k] = 0;
  xSemaphoreGive(replySem);
}

static void serialTask(void *) {
  static char line[512];
  size_t len = 0;
  for (;;) {
    while (Serial.available() > 0) {
      int c = Serial.read();
      if (c < 0) break;
      if (c == '\n') {
        line[len] = 0;
        handleLine(line);
        len = 0;
      } else if (len < sizeof(line) - 1) {
        line[len++] = (char)c;
      } else {
        len = 0;
      }
    }
    vTaskDelay(pdMS_TO_TICKS(2));
  }
}

static void setCharByte(uint8_t b) {
  if (markChar) markChar->setValue(&b, 1);
  if (legacyMarkChar) legacyMarkChar->setValue(&b, 1);
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleLen = 0;
    bleCollecting = false;
    setCharByte(0xFF);
  }
  void onDisconnect(BLEServer *server) override {
    bleLen = 0;
    bleCollecting = false;
    BLEDevice::startAdvertising();
  }
};

class CharCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    size_t n = c->getLength();
    uint8_t tmp[300];
    if (n > sizeof(tmp)) n = sizeof(tmp);
    memcpy(tmp, c->getData(), n);
    setCharByte(0xFF);
    size_t start = 0;
    if (n > 0 && tmp[0] == 0x01) {
      bleLen = 0;
      bleCollecting = true;
      start = 1;
    }
    if (!bleCollecting) return;
    for (size_t i = start; i < n; i++) {
      if (tmp[i] == 0x00) {
        BleJob job;
        size_t m = bleLen < sizeof(job.data) - 1 ? bleLen : sizeof(job.data) - 1;
        memcpy(job.data, bleBuf, m);
        job.data[m] = 0;
        job.len = (uint16_t)m;
        bleLen = 0;
        bleCollecting = false;
        xQueueSend(bleQueue, &job, 0);
        return;
      }
      if (bleLen < sizeof(bleBuf)) {
        bleBuf[bleLen++] = tmp[i];
      } else {
        bleLen = 0;
        bleCollecting = false;
        return;
      }
    }
  }
};

static void bleTask(void *) {
  static BleJob job;
  for (;;) {
    if (xQueueReceive(bleQueue, &job, portMAX_DELAY) != pdTRUE) continue;
    char *token = job.data;
    char *device = (char *)"";
    char *nl = strchr(job.data, '\n');
    if (nl) {
      *nl = 0;
      device = nl + 1;
    }
    char name[NAME_CAP + 1];
    uint8_t code = exchangeWithPc(token, device, name, sizeof(name));
    uint8_t reply[NAME_CAP + 2];
    size_t nl2 = strlen(name);
    reply[0] = code;
    memcpy(reply + 1, name, nl2);
    if (markChar) {
      markChar->setValue(reply, 1 + nl2);
      markChar->notify();
    }
    if (legacyMarkChar) {
      legacyMarkChar->setValue(reply, 1 + nl2);
      legacyMarkChar->notify();
    }
  }
}

static void setupBle() {
  BLEDevice::init("KubGAU-Reader");
  BLEDevice::setMTU(185);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_N12);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_SCAN, ESP_PWR_LVL_N12);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_N12);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_CONN_HDL0, ESP_PWR_LVL_N12);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_CONN_HDL1, ESP_PWR_LVL_N12);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_CONN_HDL2, ESP_PWR_LVL_N12);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  uint32_t props = BLECharacteristic::PROPERTY_READ |
                   BLECharacteristic::PROPERTY_WRITE |
                   BLECharacteristic::PROPERTY_WRITE_NR |
                   BLECharacteristic::PROPERTY_NOTIFY;

  BLEService *service = server->createService(SERVICE_UUID);
  markChar = service->createCharacteristic(CHAR_UUID, props);
  markChar->addDescriptor(new BLE2902());
  markChar->setCallbacks(new CharCallbacks());

  BLEService *legacyService = server->createService(LEGACY_SERVICE_UUID);
  legacyMarkChar = legacyService->createCharacteristic(LEGACY_CHAR_UUID, props);
  legacyMarkChar->addDescriptor(new BLE2902());
  legacyMarkChar->setCallbacks(new CharCallbacks());

  setCharByte(0xFF);
  service->start();
  legacyService->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  BLEAdvertisementData advData;
  advData.setFlags(0x06);
  advData.setCompleteServices(BLEUUID(SERVICE_UUID));
  adv->setAdvertisementData(advData);
  BLEAdvertisementData scanData;
  scanData.setName("KubGAU-Reader");
  adv->setScanResponseData(scanData);
  adv->setMinInterval(0x20);
  adv->setMaxInterval(0x40);
  BLEDevice::startAdvertising();
}

static int hsuReadBytes(uint8_t *buf, int len, uint32_t ms) {
  int got = 0;
  uint32_t t0 = millis();
  while (got < len) {
    if (Serial2.available() > 0) {
      buf[got++] = (uint8_t)Serial2.read();
      t0 = millis();
    } else {
      if (ms && (millis() - t0) >= ms) break;
      vTaskDelay(pdMS_TO_TICKS(1));
    }
  }
  return got;
}

static bool pnWaitReady(uint32_t ms) {
  uint32_t t0 = millis();
  if (useHsuUart) {
    for (;;) {
      if (Serial2.available() > 0) return true;
      if (ms && (millis() - t0) >= ms) return false;
      vTaskDelay(pdMS_TO_TICKS(2));
    }
  }
  for (;;) {
    if (Wire.requestFrom((uint16_t)PN_ADDR, (size_t)1) == 1) {
      if (Wire.read() & 0x01) return true;
    }
    if (ms && (millis() - t0) >= ms) return false;
    vTaskDelay(pdMS_TO_TICKS(3));
  }
}

static bool pnWriteFrame(const uint8_t *d, uint8_t n) {
  uint8_t len = n + 1;
  if (useHsuUart) {
    while (Serial2.available() > 0) Serial2.read();
    uint8_t frame[64];
    size_t idx = 0;
    frame[idx++] = 0x00;
    frame[idx++] = 0x00;
    frame[idx++] = 0xFF;
    frame[idx++] = len;
    frame[idx++] = (uint8_t)(~len + 1);
    frame[idx++] = 0xD4;
    uint8_t sum = 0xD4;
    for (uint8_t i = 0; i < n; i++) {
      frame[idx++] = d[i];
      sum += d[i];
    }
    frame[idx++] = (uint8_t)(~sum + 1);
    frame[idx++] = 0x00;
    Serial2.write(frame, idx);
    Serial2.flush();
    return true;
  }
  Wire.beginTransmission(PN_ADDR);
  Wire.write((uint8_t)0x00);
  Wire.write((uint8_t)0x00);
  Wire.write((uint8_t)0xFF);
  Wire.write(len);
  Wire.write((uint8_t)(~len + 1));
  Wire.write((uint8_t)0xD4);
  uint8_t sum = 0xD4;
  for (uint8_t i = 0; i < n; i++) {
    Wire.write(d[i]);
    sum += d[i];
  }
  Wire.write((uint8_t)(~sum + 1));
  Wire.write((uint8_t)0x00);
  return Wire.endTransmission() == 0;
}

static void pnAbort() {
  const uint8_t ack[] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00};
  if (useHsuUart) {
    Serial2.write(ack, sizeof(ack));
    Serial2.flush();
    vTaskDelay(pdMS_TO_TICKS(20));
    while (Serial2.available() > 0) Serial2.read();
    return;
  }
  Wire.beginTransmission(PN_ADDR);
  Wire.write(ack, sizeof(ack));
  Wire.endTransmission();
  vTaskDelay(pdMS_TO_TICKS(20));
}

static bool pnReadAck() {
  if (useHsuUart) {
    uint8_t a[16];
    int n = hsuReadBytes(a, 6, 150);
    if (n < 6) return false;
    for (int i = 0; i + 5 < n; i++) {
      if (a[i] == 0x00 && a[i + 1] == 0x00 && a[i + 2] == 0xFF &&
          a[i + 3] == 0x00 && a[i + 4] == 0xFF && a[i + 5] == 0x00) {
        return true;
      }
    }
    return false;
  }
  if (!pnWaitReady(100)) return false;
  uint8_t a[7];
  int n = Wire.requestFrom((uint16_t)PN_ADDR, (size_t)7);
  if (n != 7) return false;
  for (int i = 0; i < 7; i++) a[i] = Wire.read();
  return a[1] == 0x00 && a[2] == 0x00 && a[3] == 0xFF && a[4] == 0x00 && a[5] == 0xFF && a[6] == 0x00;
}

static int pnReadResponse(uint8_t cmd, uint8_t *out, uint8_t cap, uint32_t ms) {
  if (useHsuUart) {
    if (!pnWaitReady(ms)) return -3;
    uint8_t hdr[6];
    uint8_t prev = 0xFF;
    uint32_t t0 = millis();
    bool foundStart = false;
    while ((millis() - t0) < 200) {
      if (Serial2.available() > 0) {
        uint8_t b = (uint8_t)Serial2.read();
        if (prev == 0x00 && b == 0xFF) {
          foundStart = true;
          break;
        }
        prev = b;
      } else {
        vTaskDelay(pdMS_TO_TICKS(1));
      }
    }
    if (!foundStart) return -5;
    if (hsuReadBytes(hdr, 2, 100) != 2) return -5;
    uint8_t len = hdr[0];
    uint8_t lcs = hdr[1];
    if ((uint8_t)(len + lcs) != 0 || len < 2) return -6;
    uint8_t body[128];
    if (len + 2 > (int)sizeof(body)) return -7;
    if (hsuReadBytes(body, len + 2, 150) < len + 2) return -7;
    if (body[0] != 0xD5 || body[1] != (uint8_t)(cmd + 1)) return -8;
    uint8_t sum = 0;
    for (int i = 0; i < len; i++) sum += body[i];
    if ((uint8_t)(sum + body[len]) != 0) return -9;
    int dlen = len - 2;
    if (dlen > cap) return -10;
    memcpy(out, body + 2, dlen);
    return dlen;
  }

  if (!pnWaitReady(ms)) return -3;
  static uint8_t raw[128];
  int n = Wire.requestFrom((uint16_t)PN_ADDR, (size_t)sizeof(raw));
  if (n <= 0) return -4;
  for (int i = 0; i < n; i++) raw[i] = Wire.read();
  int k = -1;
  for (int i = 1; i + 1 < n; i++) {
    if (raw[i] == 0x00 && raw[i + 1] == 0xFF) {
      k = i;
      break;
    }
  }
  if (k < 0 || k + 5 >= n) return -5;
  uint8_t len = raw[k + 2];
  uint8_t lcs = raw[k + 3];
  if ((uint8_t)(len + lcs) != 0) return -6;
  if (len < 2 || k + 4 + len >= n) return -7;
  if (raw[k + 4] != 0xD5 || raw[k + 5] != (uint8_t)(cmd + 1)) return -8;
  uint8_t sum = 0;
  for (int i = 0; i < len; i++) sum += raw[k + 4 + i];
  if ((uint8_t)(sum + raw[k + 4 + len]) != 0) return -9;
  int dlen = len - 2;
  if (dlen > cap) return -10;
  memcpy(out, raw + k + 6, dlen);
  return dlen;
}

static int pnCommand(const uint8_t *c, uint8_t n, uint8_t *out, uint8_t cap, uint32_t ms) {
  if (!pnWriteFrame(c, n)) return -1;
  if (!pnReadAck()) return -2;
  return pnReadResponse(c[0], out, cap, ms);
}

static void initWirePins(int sda, int scl, uint32_t clockHz = 10000) {
  Serial2.end();
  Wire.end();
  useHsuUart = false;
  pinMode(sda, INPUT_PULLUP);
  pinMode(scl, INPUT_PULLUP);
  Wire.setBufferSize(256);
  Wire.begin(sda, scl, clockHz);
  Wire.setTimeOut(100);
  // Пробуждение PN532 из режима LowVbat по I2C (NXP AN10609 п. 2.4.2)
  Wire.beginTransmission(PN_ADDR);
  Wire.endTransmission();
  vTaskDelay(pdMS_TO_TICKS(10));
}

static void initHsuPins(int rxPin, int txPin) {
  Wire.end();
  Serial2.end();
  useHsuUart = true;
  pinMode(rxPin, INPUT_PULLUP);
  Serial2.setRxBufferSize(512);
  Serial2.begin(115200, SERIAL_8N1, rxPin, txPin);
  while (Serial2.available() > 0) Serial2.read();
}

static bool pnTryInitOnCurrentPins() {
  uint8_t r[32];
  if (useHsuUart) {
    // Пробуждение PN532 из LowVbat по HSU UART (libnfc preamble + SAMConfiguration 0x14)
    const uint8_t wakeSam[] = {
      0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0xFF, 0x05, 0xFB, 0xD4, 0x14, 0x01, 0x14, 0x01, 0x02, 0x00
    };
    Serial2.write(wakeSam, sizeof(wakeSam));
    Serial2.flush();
    vTaskDelay(pdMS_TO_TICKS(30));
    while (Serial2.available() > 0) Serial2.read();
  } else {
    pnAbort();
  }
  // Первой командой обязательно идёт SAMConfiguration (0x14, 0x01, 0x14, 0x01)
  const uint8_t sam[] = {0x14, 0x01, 0x14, 0x01};
  if (pnCommand(sam, sizeof(sam), r, sizeof(r), 300) >= 0) {
    const uint8_t fw[] = {0x02};
    if (pnCommand(fw, sizeof(fw), r, sizeof(r), 300) >= 4) {
      // Включаем fAutomaticATR_RES (0x04) | fAutomaticRATS (0x10) | fISO14443-4_PICC (0x20)
      const uint8_t params[] = {0x12, 0x34};
      pnCommand(params, sizeof(params), r, sizeof(r), 300);

      // Максимальное усиление антенны и чувствительности PN532 (RFConfiguration 0x32, CfgItem 0x0A)
      const uint8_t rfMax[] = {
        0x32, 0x0A,
        0x79, 0xFF, 0x3F, 0x11, 0x4D, 0x55, 0x61, 0xFF, 0x26, 0x62, 0x87
      };
      pnCommand(rfMax, sizeof(rfMax), r, sizeof(r), 300);
      return true;
    }
  }
  return false;
}

static bool pnBegin() {
  // Автоматически проверяем пины IO25/IO26 (WEMOS D1 R32), D14/D27 и D21/D22
  static const int pairs[][2] = {
    {25, 26}, // WEMOS D1 R32: IO25 и IO26
    {26, 25}, // WEMOS D1 R32: IO26 и IO25
    {14, 27}, // 5-й пин = D14, 6-й пин = D27
    {27, 14}, // 6-й пин = D27, 5-й пин = D14
    {21, 22},
    {22, 21}
  };

  for (size_t i = 0; i < sizeof(pairs) / sizeof(pairs[0]); i++) {
    int a = pairs[i][0];
    int b = pairs[i][1];

    // 1. Проверяем HSU UART (переключатели SET0=L, SET1=L)
    initHsuPins(a, b);
    if (pnTryInitOnCurrentPins()) {
      activePinA = a;
      activePinB = b;
      return true;
    }

    // 2. Проверяем I2C на 10 кГц (работает с внутренней подтяжкой ESP32 на 8-пиновой гребёнке, SET0=H, SET1=L)
    initWirePins(a, b, 10000);
    if (pnTryInitOnCurrentPins()) {
      activePinA = a;
      activePinB = b;
      return true;
    }

    // 3. Проверяем I2C на 100 кГц
    initWirePins(a, b, 100000);
    if (pnTryInitOnCurrentPins()) {
      activePinA = a;
      activePinB = b;
      return true;
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  return false;
}

static uint8_t buildApduReply(const uint8_t *apdu, int al, uint8_t *resp) {
  if (al >= 4 && apdu[1] == 0xA4) {
    if (al >= 12 && apdu[2] == 0x04 && apdu[4] == 7 && memcmp(apdu + 5, NFC_AID, 7) == 0) {
      resp[0] = 0x90;
      resp[1] = 0x00;
      return 2;
    }
    resp[0] = 0x6A;
    resp[1] = 0x82;
    return 2;
  }
  if (al >= 5 && apdu[1] == 0x10) {
    int lc = apdu[4];
    if (lc < 1 || al < 5 + lc) {
      resp[0] = 0x90;
      resp[1] = 0x00;
      return 2;
    }
    char data[260];
    memcpy(data, apdu + 5, lc);
    data[lc] = 0;
    char *token = data;
    char *device = (char *)"";
    char *nl = strchr(data, '\n');
    if (nl) {
      *nl = 0;
      device = nl + 1;
    }
    if (!token[0]) {
      resp[0] = 0x90;
      resp[1] = 0x00;
      return 2;
    }
    char name[NAME_CAP + 1];
    uint8_t code = exchangeWithPc(token, device, name, sizeof(name));
    size_t nn = strlen(name);
    resp[0] = code;
    memcpy(resp + 1, name, nn);
    resp[1 + nn] = 0x90;
    resp[2 + nn] = 0x00;
    return (uint8_t)(3 + nn);
  }
  if (al < 4) {
    resp[0] = 0x90;
    resp[1] = 0x00;
    return 2;
  }
  resp[0] = 0x6D;
  resp[1] = 0x00;
  return 2;
}

static void nfcSession() {
  uint32_t rnd = esp_random();
  uint8_t init[] = {
    0x8C, 0x05,
    0x04, 0x00,
    (uint8_t)(rnd & 0xFF), (uint8_t)((rnd >> 8) & 0xFF), (uint8_t)((rnd >> 16) & 0xFF),
    0x20,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0,
    0
  };
  uint8_t r[120];
  int n = pnCommand(init, sizeof(init), r, sizeof(r), 15000);
  if (n == -3) {
    pnAbort();
    return;
  }
  if (n < 1) {
    pnAbort();
    pnOk = false;
    return;
  }

  // Если первый APDU пришёл сразу в ответе TgInitAsTarget (после автоматического RATS)
  if (n >= 5 && r[1] == 0x00 && (r[2] == 0xA4 || r[2] == 0x10)) {
    uint8_t resp[NAME_CAP + 8];
    uint8_t rl = buildApduReply(r + 1, n - 1, resp);
    uint8_t set[NAME_CAP + 10];
    set[0] = 0x8E;
    memcpy(set + 1, resp, rl);
    uint8_t sr[16];
    int s = pnCommand(set, (uint8_t)(1 + rl), sr, sizeof(sr), 1500);
    if (s < 1 || sr[0] != 0x00) {
      if (s == -3) pnAbort();
      return;
    }
  }

  for (;;) {
    const uint8_t get[] = {0x86};
    n = pnCommand(get, sizeof(get), r, sizeof(r), 2500);
    if (n == -3) {
      pnAbort();
      return;
    }
    if (n < 1 || r[0] != 0x00) return;
    uint8_t resp[NAME_CAP + 8];
    uint8_t rl = buildApduReply(r + 1, n - 1, resp);
    uint8_t set[NAME_CAP + 10];
    set[0] = 0x8E;
    memcpy(set + 1, resp, rl);
    uint8_t sr[16];
    int s = pnCommand(set, (uint8_t)(1 + rl), sr, sizeof(sr), 1500);
    if (s < 1 || sr[0] != 0x00) {
      if (s == -3) pnAbort();
      return;
    }
  }
}

void setup() {
  Serial.setRxBufferSize(2048);
  Serial.begin(115200);
  esp_log_level_set("*", ESP_LOG_NONE);
  txMutex = xSemaphoreCreateMutex();
  tapMutex = xSemaphoreCreateMutex();
  replySem = xSemaphoreCreateBinary();
  bleQueue = xQueueCreate(4, sizeof(BleJob));
  replyName[0] = 0;
  xTaskCreatePinnedToCore(serialTask, "serial", 4096, nullptr, 3, nullptr, 0);
  xTaskCreatePinnedToCore(bleTask, "blejob", 6144, nullptr, 2, nullptr, 0);
  setupBle();
  vTaskDelay(pdMS_TO_TICKS(300));
  pnOk = pnBegin();
}

void loop() {
  if (!pnOk) {
    vTaskDelay(pdMS_TO_TICKS(1000));
    pnOk = pnBegin();
    return;
  }
  nfcSession();
  vTaskDelay(pdMS_TO_TICKS(30));
}
