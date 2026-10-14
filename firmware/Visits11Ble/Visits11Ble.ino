// ESP32 Devkit V1 (ESP32-WROOM-32), Arduino-ESP32 2.0.17.
// No PN532, network or extra wiring. USB data cable -> Windows journal.
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_gap_ble_api.h>

static const char* SERVICE = "F0391101-0203-0405-0607-08090A0B0C0D";
static const char* CHARACTERISTIC = "F0391102-0203-0405-0607-08090A0B0C0D";
static BLECharacteristic* mark;
static BLEServer* terminal;
static SemaphoreHandle_t lock;
static String packet;
static bool connected = false, collecting = false, pending = false;
static uint32_t sequence = 0, activeId = 0, startedAt = 0, lastPcAt = 0;
static int powerLevel = -1;
struct Event { uint32_t id; char token[513]; char device[129]; };
static QueueHandle_t events;
// Proximity is enforced by the terminal, never trusted to the student's app.
// This is a signal-strength heuristic, NOT secure distance bounding.
static const int MIN_PHONE_RSSI = -46;
static const int REQUIRED_SAMPLES = 3;
static Event awaitingProximity{};
static esp_bd_addr_t peerAddress{};
static uint16_t connectionId = 0;
static bool checkingProximity = false, rssiInFlight = false, replyReady = false;
static bool disconnectRequested = false;
static uint32_t rssiRequestId = 0, sampleDue = 0, connectedAt = 0, releaseAt = 0;
static int goodSamples = 0;

static void gapEvent(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t* param);

static void answer(uint8_t code) {
  mark->setValue(&code, 1); // Polling avoids missed notifications and MTU-dependent name truncation.
  pending = false;
  checkingProximity = false;
  replyReady = true;
}
static bool safeField(const String& value, size_t maxLength) {
  if (value.length() == 0 || value.length() > maxLength) return false;
  for (size_t i=0; i<value.length(); i++) if (value[i] < 33 || value[i] > 126) return false;
  return true;
}
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*, esp_ble_gatts_cb_param_t* param) override {
    xSemaphoreTake(lock, portMAX_DELAY);
    connected = true; collecting = false; packet = ""; pending = false; activeId = ++sequence;
    memcpy(peerAddress, param->connect.remote_bda, sizeof(peerAddress));
    connectionId = param->connect.conn_id;
    connectedAt = millis(); releaseAt = 0; disconnectRequested = false;
    checkingProximity = false; replyReady = false; goodSamples = 0;
    uint8_t waiting=255; mark->setValue(&waiting,1);
    xSemaphoreGive(lock);
  }
  void onDisconnect(BLEServer*) override {
    xSemaphoreTake(lock, portMAX_DELAY);
    connected = false; collecting = false; packet = ""; pending = false; activeId = ++sequence;
    checkingProximity = false; replyReady = false; releaseAt = 0;
    // Keep rssiInFlight until its callback arrives: never mistake an old measurement for a new one.
    xSemaphoreGive(lock);
    BLEDevice::startAdvertising();
  }
};
class MarkCallbacks : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic*) override {
    xSemaphoreTake(lock, portMAX_DELAY);
    // Give the ATT response time to leave, then forcibly free even a modified client.
    if (replyReady && releaseAt == 0) releaseAt = millis() + 350;
    xSemaphoreGive(lock);
  }
  void onWrite(BLECharacteristic* characteristic) override {
    auto data = characteristic->getValue();
    xSemaphoreTake(lock, portMAX_DELAY);
    // A write replaces the characteristic value; never expose request bytes as a reply.
    uint8_t waiting = 255; mark->setValue(&waiting, 1);
    replyReady = false;
    for (uint8_t c : data) {
      if (c == 1) { packet = ""; collecting = true; pending = false; checkingProximity = false; activeId = ++sequence; continue; }
      if (!collecting) continue;
      if (c == 0) {
        collecting = false;
        int separator = packet.indexOf('\n');
        String token = separator < 0 ? "" : packet.substring(0, separator);
        String device = separator < 0 ? "" : packet.substring(separator+1);
        if (!safeField(token,512) || !safeField(device,128) || lastPcAt==0 || millis()-lastPcAt>4500) {
          answer(4); break;
        }
        Event event{}; event.id=activeId;
        token.toCharArray(event.token,sizeof(event.token)); device.toCharArray(event.device,sizeof(event.device));
        pending=true; startedAt=millis();
        awaitingProximity = event;
        checkingProximity = true; goodSamples = 0; sampleDue = millis();
        // No E frame reaches the PC until the board itself validates the phone's RSSI.
        packet=""; break;
      }
      if (packet.length() >= 650) { collecting=false; packet=""; answer(4); break; }
      packet += char(c);
    }
    xSemaphoreGive(lock);
  }
};
static void gapEvent(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t* param) {
  if (event != ESP_GAP_BLE_READ_RSSI_COMPLETE_EVT) return;
  xSemaphoreTake(lock, portMAX_DELAY);
  bool current = rssiInFlight && connected && pending && checkingProximity
    && rssiRequestId == activeId && memcmp(param->read_rssi_cmpl.remote_addr,peerAddress,6)==0;
  rssiInFlight = false;
  if (current) {
    int rssi = param->read_rssi_cmpl.rssi;
    if (param->read_rssi_cmpl.status != ESP_BT_STATUS_SUCCESS || rssi >= 0 || rssi < MIN_PHONE_RSSI) {
      answer(6); // Too far or measurement unavailable; NEVER delete the saved account.
    } else if (++goodSamples >= REQUIRED_SAMPLES) {
      checkingProximity = false;
      startedAt = millis(); // PC gets its full response budget after the measurements.
      if (xQueueSend(events,&awaitingProximity,0)!=pdTRUE) answer(4);
    } else {
      sampleDue = millis() + 100;
    }
  }
  xSemaphoreGive(lock);
}
static void power(int level) {
  static const esp_power_level_t levels[] = {ESP_PWR_LVL_N12, ESP_PWR_LVL_N6, ESP_PWR_LVL_N0, ESP_PWR_LVL_P9};
  if (level<0 || level>3) return;
  if (level != powerLevel) {
    BLEDevice::setPower(levels[level], ESP_BLE_PWR_TYPE_DEFAULT);
    BLEDevice::setPower(levels[level], ESP_BLE_PWR_TYPE_ADV);
    BLEDevice::setPower(levels[level], ESP_BLE_PWR_TYPE_SCAN);
    powerLevel=level;
  }
}
static void command(const String& line) {
  if (line=="V11?") { Serial.println("V11ESP32\t1"); return; }
  if (line.length()==3 && line.startsWith("P\t") && line[2]>='0' && line[2]<='3') {
    power(line[2]-'0');
    xSemaphoreTake(lock,portMAX_DELAY); lastPcAt=millis(); xSemaphoreGive(lock);
    Serial.printf("P\t%d\n",powerLevel); return;
  }
  unsigned long id; int code; char extra;
  if (sscanf(line.c_str(),"R\t%lu\t%d%c",&id,&code,&extra)==2 && (code==0 || code==2 || code==4 || code==5)) {
    xSemaphoreTake(lock,portMAX_DELAY);
    if (connected && pending && id==activeId) answer((uint8_t)code);
    xSemaphoreGive(lock);
  }
}
void setup() {
  Serial.begin(115200);
  lock=xSemaphoreCreateMutex(); events=xQueueCreate(4,sizeof(Event));
  BLEDevice::init("KubGAU BLE");
  BLEDevice::setCustomGapHandler(gapEvent);
  auto server=BLEDevice::createServer(); terminal=server; server->setCallbacks(new ServerCallbacks());
  auto service=server->createService(SERVICE);
  mark=service->createCharacteristic(CHARACTERISTIC,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY);
  mark->addDescriptor(new BLE2902()); mark->setCallbacks(new MarkCallbacks());
  uint8_t waiting=255; mark->setValue(&waiting,1);
  service->start();
  auto advertising=BLEDevice::getAdvertising(); advertising->addServiceUUID(SERVICE);
  advertising->setScanResponse(true); power(0); BLEDevice::startAdvertising();
  Serial.println("V11ESP32\t1");
}
void loop() {
  static String line;
  static bool overflow=false;
  while (Serial.available()) {
    char c=Serial.read();
    if (c=='\n') { if(!overflow) command(line); line=""; overflow=false; }
    else if(c!='\r') { if(line.length()<128 && !overflow) line+=c; else overflow=true; }
  }
  Event event;
  while (xQueueReceive(events,&event,0)==pdTRUE) {
    xSemaphoreTake(lock,portMAX_DELAY);
    bool valid=connected && pending && event.id==activeId;
    xSemaphoreGive(lock);
    if(valid) Serial.printf("E\t%lu\t%s\t%s\n",(unsigned long)event.id,event.token,event.device);
  }
  esp_bd_addr_t address{};
  bool requestRssi=false, dropConnection=false;
  uint16_t dropId=0;
  xSemaphoreTake(lock,portMAX_DELAY);
  if(pending && millis()-startedAt>4000) answer(4);
  if(connected && pending && checkingProximity && !rssiInFlight && (int32_t)(millis()-sampleDue)>=0) {
    rssiInFlight=true; rssiRequestId=activeId;
    memcpy(address,peerAddress,6); requestRssi=true;
  }
  // Bound even idle/uncooperative connections; a waiting student must not hold the terminal.
  if(connected && !disconnectRequested &&
      ((releaseAt!=0 && (int32_t)(millis()-releaseAt)>=0) || millis()-connectedAt>8000)) {
    disconnectRequested=true; dropConnection=true; dropId=connectionId;
  }
  xSemaphoreGive(lock);
  if(requestRssi && esp_ble_gap_read_rssi(address)!=ESP_OK) {
    xSemaphoreTake(lock,portMAX_DELAY);
    rssiInFlight=false;
    if(pending && checkingProximity && rssiRequestId==activeId) answer(6);
    xSemaphoreGive(lock);
  }
  if(dropConnection) terminal->disconnect(dropId);
  delay(5);
}
