#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

static const int BLE_TX_POWER_DBM = -12;
static const char *DEVICE_NAME = "Visits11-Box";
static const char *SERVICE_UUID = "F0391101-0203-0405-0607-08090A0B0C0D";
static const char *CHAR_UUID = "F0391102-0203-0405-0607-08090A0B0C0D";
static const char *BOX_VERSION = "VISITS11-ESP32 v2";
static const long SERIAL_BAUD = 115200;
static const uint32_t REPLY_TIMEOUT_MS = 6000;
static const uint8_t CODE_MARKED = 0;
static const uint8_t CODE_UNAVAILABLE = 4;
static const size_t MAX_PAYLOAD = 512;

static BLEServer *bleServer = nullptr;
static BLECharacteristic *markChar = nullptr;
static std::string rxBuffer;
static std::string replyData;
static String serialLine;
static uint32_t requestCounter = 0;
static uint32_t pendingId = 0;
static uint32_t pendingSince = 0;
static bool waitingReply = false;
static int currentPower = BLE_TX_POWER_DBM;

static bool applyPower(int dbm)
{
    esp_power_level_t level;

    switch (dbm)
    {
        case -12: level = ESP_PWR_LVL_N12; break;
        case -9: level = ESP_PWR_LVL_N9; break;
        case -6: level = ESP_PWR_LVL_N6; break;
        case -3: level = ESP_PWR_LVL_N3; break;
        case 0: level = ESP_PWR_LVL_N0; break;
        case 3: level = ESP_PWR_LVL_P3; break;
        case 6: level = ESP_PWR_LVL_P6; break;
        case 9: level = ESP_PWR_LVL_P9; break;
        default: return false;
    }

    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, level);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, level);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_SCAN, level);
    currentPower = dbm;
    return true;
}

static void setReply(uint8_t code, const std::string &name)
{
    replyData.clear();
    replyData.push_back((char)code);
    replyData += name;
}

static void notifyReply()
{
    if (markChar == nullptr || replyData.empty())
    {
        return;
    }
    markChar->setValue((uint8_t *)replyData.data(), replyData.size());
    markChar->notify();
}

static void sendTap(const std::string &full)
{
    size_t nl = full.find('\n');
    std::string token = nl == std::string::npos ? full : full.substr(0, nl);
    std::string device = nl == std::string::npos ? std::string() : full.substr(nl + 1);

    if (token.empty())
    {
        return;
    }

    pendingId = ++requestCounter;
    pendingSince = millis();
    waitingReply = true;
    setReply(0xFF, std::string());
    notifyReply();
    Serial.printf("TAP:%lu:%s:%s\n", (unsigned long)pendingId, token.c_str(), device.c_str());
}

static void handleSerialLine(const String &line)
{
    if (line == "PING")
    {
        Serial.println(BOX_VERSION);
        return;
    }
    if (line == "POWER?")
    {
        Serial.printf("POWER:%d\n", currentPower);
        return;
    }
    if (line.startsWith("POWER:"))
    {
        int value = line.substring(6).toInt();
        if (applyPower(value))
        {
            Serial.printf("POWER:%d OK\n", currentPower);
        }
        else
        {
            Serial.printf("POWER:%d ERR\n", value);
        }
        return;
    }
    if (line.startsWith("REPLY:"))
    {
        int first = line.indexOf(':');
        int second = line.indexOf(':', first + 1);
        int third = line.indexOf(':', second + 1);
        if (first < 0 || second < 0)
        {
            return;
        }
        uint32_t id = (uint32_t)line.substring(first + 1, second).toInt();
        int code = third < 0 ? line.substring(second + 1).toInt()
                             : line.substring(second + 1, third).toInt();
        String name = third < 0 ? String() : line.substring(third + 1);
        if (!waitingReply || id != pendingId)
        {
            return;
        }
        waitingReply = false;
        setReply((uint8_t)code, std::string(name.c_str()));
        notifyReply();
        Serial.printf("REPLY:%lu:%d:%s\n", (unsigned long)id, code, name.c_str());
        return;
    }
}

class MarkCallbacks : public BLECharacteristicCallbacks
{
    void onWrite(BLECharacteristic *characteristic) override
    {
        uint8_t *data = characteristic->getData();
        size_t length = characteristic->getLength();
        size_t start = 0;
        size_t end = (size_t)-1;

        if (data == nullptr || length == 0)
        {
            return;
        }
        if (data[0] == 0x01)
        {
            rxBuffer.clear();
            start = 1;
        }
        for (size_t i = start; i < length; i++)
        {
            if (data[i] == 0x00)
            {
                end = i;
                break;
            }
        }
        if (end == (size_t)-1)
        {
            rxBuffer.append((const char *)data + start, length - start);
            if (rxBuffer.size() > MAX_PAYLOAD)
            {
                rxBuffer.clear();
            }
            return;
        }
        rxBuffer.append((const char *)data + start, end - start);
        std::string full = rxBuffer;
        rxBuffer.clear();
        sendTap(full);
    }
};

class ServerCallbacks : public BLEServerCallbacks
{
    void onConnect(BLEServer *server) override
    {
        rxBuffer.clear();
        setReply(0xFF, std::string());
    }

    void onDisconnect(BLEServer *server) override
    {
        waitingReply = false;
        rxBuffer.clear();
        BLEDevice::startAdvertising();
    }
};

void setup()
{
    Serial.begin(SERIAL_BAUD);
    delay(300);
    Serial.println();
    Serial.println(BOX_VERSION);
    Serial.printf("мощность Bluetooth: %d дБм (меняется строкой вверху скетча или командой POWER:-6)\n",
                  currentPower);

    BLEDevice::init(DEVICE_NAME);
    applyPower(BLE_TX_POWER_DBM);

    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new ServerCallbacks());

    BLEService *service = bleServer->createService(SERVICE_UUID);
    markChar = service->createCharacteristic(
        CHAR_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE |
            BLECharacteristic::PROPERTY_WRITE_NR | BLECharacteristic::PROPERTY_NOTIFY);
    markChar->addDescriptor(new BLE2902());
    markChar->setCallbacks(new MarkCallbacks());
    setReply(0xFF, std::string());
    service->start();

    BLEAdvertising *advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->setScanResponse(true);
    advertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.println("реклама запущена: ждём телефон студента");
}

void loop()
{
    while (Serial.available() > 0)
    {
        char c = (char)Serial.read();
        if (c == '\n' || c == '\r')
        {
            if (serialLine.length() > 0)
            {
                handleSerialLine(serialLine);
                serialLine = "";
            }
            continue;
        }
        if (serialLine.length() < 256)
        {
            serialLine += c;
        }
    }

    if (waitingReply && (uint32_t)(millis() - pendingSince) >= REPLY_TIMEOUT_MS)
    {
        waitingReply = false;
        setReply(CODE_UNAVAILABLE, std::string());
        notifyReply();
        Serial.printf("TIMEOUT:%lu\n", (unsigned long)pendingId);
    }

    delay(5);
}
