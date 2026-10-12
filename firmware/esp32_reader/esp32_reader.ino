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
static const int PIN_SDA = 21;
static const int PIN_SCL = 22;
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

static bool pnWaitReady(uint32_t ms) {
  uint32_t t0 = millis();
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
  Wire.beginTransmission(PN_ADDR);
  Wire.write((uint8_t)0x00);
  Wire.write((uint8_t)0x00);
  Wire.write((uint8_t)0xFF);
  Wire.write((uint8_t)0x00);
  Wire.write((uint8_t)0xFF);
  Wire.write((uint8_t)0x00);
  Wire.endTransmission();
  vTaskDelay(pdMS_TO_TICKS(20));
}

static bool pnReadAck() {
  if (!pnWaitReady(100)) return false;
  uint8_t a[7];
  int n = Wire.requestFrom((uint16_t)PN_ADDR, (size_t)7);
  if (n != 7) return false;
  for (int i = 0; i < 7; i++) a[i] = Wire.read();
  return a[1] == 0x00 && a[2] == 0x00 && a[3] == 0xFF && a[4] == 0x00 && a[5] == 0xFF && a[6] == 0x00;
}

static int pnReadResponse(uint8_t cmd, uint8_t *out, uint8_t cap, uint32_t ms) {
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

static bool pnBegin() {
  uint8_t r[32];
  for (int attempt = 0; attempt < 5; attempt++) {
    pnAbort();
    const uint8_t fw[] = {0x02};
    if (pnCommand(fw, sizeof(fw), r, sizeof(r), 500) >= 4) {
      const uint8_t sam[] = {0x14, 0x01, 0x14, 0x00};
      if (pnCommand(sam, sizeof(sam), r, sizeof(r), 500) >= 0) {
        // Включаем fAutomaticATR_RES (0x04) | fAutomaticRATS (0x10) | fISO14443-4_PICC (0x20)
        const uint8_t params[] = {0x12, 0x34};
        pnCommand(params, sizeof(params), r, sizeof(r), 300);

        // Максимальное усиление антенны и чувствительности PN532 (RFConfiguration 0x32, CfgItem 0x0A):
        // RFCfg = 0x79 (макс. RxGain 48 dB), GsNOn = 0xFF, CWGsP = 0x3F,
        // RxThreshold = 0x55 (высокая чувствительность), GsNOff = 0xFF (макс. отдача в режиме карты/Target)
        const uint8_t rfMax[] = {
          0x32, 0x0A,
          0x79, 0xFF, 0x3F, 0x11, 0x4D, 0x55, 0x61, 0xFF, 0x26, 0x62, 0x87
        };
        pnCommand(rfMax, sizeof(rfMax), r, sizeof(r), 300);
        return true;
      }
    }
    vTaskDelay(pdMS_TO_TICKS(200));
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
  Wire.setBufferSize(256);
  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(100000);
  Wire.setTimeOut(100);
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
