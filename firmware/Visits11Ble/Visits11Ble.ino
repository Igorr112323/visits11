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
static SemaphoreHandle_t lock;
static String packet;
static bool connected = false, collecting = false, pending = false;
static uint32_t sequence = 0, activeId = 0, startedAt = 0, lastPcAt = 0;
static int powerLevel = -1;
struct Event { uint32_t id; char token[513]; char device[129]; };
static QueueHandle_t events;

static void answer(uint8_t code) {
  mark->setValue(&code, 1); // Polling avoids missed notifications and MTU-dependent name truncation.
  pending = false;
}
static bool safeField(const String& value, size_t maxLength) {
  if (value.length() == 0 || value.length() > maxLength) return false;
  for (size_t i=0; i<value.length(); i++) if (value[i] < 33 || value[i] > 126) return false;
  return true;
}
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    xSemaphoreTake(lock, portMAX_DELAY);
    connected = true; collecting = false; packet = ""; pending = false; activeId = ++sequence;
    uint8_t waiting=255; mark->setValue(&waiting,1);
    xSemaphoreGive(lock);
  }
  void onDisconnect(BLEServer*) override {
    xSemaphoreTake(lock, portMAX_DELAY);
    connected = false; collecting = false; packet = ""; pending = false; activeId = ++sequence;
    xSemaphoreGive(lock);
    BLEDevice::startAdvertising();
  }
};
class MarkCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    auto data = characteristic->getValue();
    xSemaphoreTake(lock, portMAX_DELAY);
    // A write replaces the characteristic value; never expose request bytes as a reply.
    uint8_t waiting = 255; mark->setValue(&waiting, 1);
    for (uint8_t c : data) {
      if (c == 1) { packet = ""; collecting = true; pending = false; activeId = ++sequence; continue; }
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
        if (xQueueSend(events,&event,0)!=pdTRUE) answer(4);
        packet=""; break;
      }
      if (packet.length() >= 650) { collecting=false; packet=""; answer(4); break; }
      packet += char(c);
    }
    xSemaphoreGive(lock);
  }
};
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
  auto server=BLEDevice::createServer(); server->setCallbacks(new ServerCallbacks());
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
  xSemaphoreTake(lock,portMAX_DELAY);
  if(pending && millis()-startedAt>4000) answer(4);
  xSemaphoreGive(lock);
  delay(5);
}
