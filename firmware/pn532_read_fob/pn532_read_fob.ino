#include <Arduino.h>

static const int PIN_A = 27;
static const int PIN_B = 14;
static const long PN532_BAUD = 115200;

static const uint32_t ACK_TIMEOUT_MS = 400;
static const uint32_t FRAME_TIMEOUT_MS = 800;
static const uint32_t PING_TIMEOUT_MS = 500;

static HardwareSerial Pn(2);

static uint8_t frame[80];
static int pnRxPin = PIN_A;
static int pnTxPin = PIN_B;
static uint8_t pnIc = 0;
static uint8_t pnVersion = 0;
static uint8_t pnRevision = 0;
static uint8_t pnSupport = 0;
static bool pnReady = false;
static uint32_t cards = 0;
static uint32_t polls = 0;
static uint32_t dotsOnLine = 0;

static void pnFlush()
{
    while (Pn.available() > 0)
    {
        Pn.read();
    }
}

static void pnWakeup()
{
    static const uint8_t wake[7] = {0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00};

    Pn.write(wake, sizeof(wake));
    Pn.flush();
    delay(20);
}

static void pnWriteFrame(const uint8_t *data, uint16_t len)
{
    uint8_t head[6];
    uint8_t sum = 0xD4;
    uint8_t l = (uint8_t)(len + 1);

    head[0] = 0x00;
    head[1] = 0x00;
    head[2] = 0xFF;
    head[3] = l;
    head[4] = (uint8_t)(0x00 - l);
    head[5] = 0xD4;

    Pn.write(head, 6);
    for (uint16_t i = 0; i < len; i++)
    {
        Pn.write(data[i]);
        sum += data[i];
    }
    Pn.write((uint8_t)(0x00 - sum));
    Pn.write((uint8_t)0x00);
    Pn.flush();
}

static uint16_t pnRead(uint8_t *buf, uint16_t want, uint32_t timeoutMs)
{
    uint16_t got = 0;
    uint32_t start = millis();

    while (got < want)
    {
        if (Pn.available() > 0)
        {
            buf[got++] = (uint8_t)Pn.read();
            continue;
        }
        if ((uint32_t)(millis() - start) >= timeoutMs)
        {
            break;
        }
        delay(1);
    }
    return got;
}

static void printHex(const uint8_t *data, uint16_t len)
{
    for (uint16_t i = 0; i < len; i++)
    {
        Serial.printf("%02X ", (unsigned)data[i]);
    }
}

static int16_t pnCommand(const uint8_t *cmd, uint16_t len, uint32_t timeoutMs)
{
    uint8_t head[6];
    uint8_t len2[2];
    uint8_t sum = 0;
    uint16_t got;
    uint16_t l;

    pnFlush();
    pnWriteFrame(cmd, len);

    got = pnRead(head, 6, ACK_TIMEOUT_MS);
    if (!(got == 6 && head[0] == 0x00 && head[1] == 0x00 && head[2] == 0xFF &&
          head[3] == 0x00 && head[4] == 0xFF && head[5] == 0x00))
    {
        if (got > 0)
        {
            Serial.print("  плата ответила не подтверждением: ");
            printHex(head, got);
            Serial.println();
        }
        return -1;
    }
    if (pnRead(head, 3, timeoutMs) != 3)
    {
        return -2;
    }
    if (!(head[0] == 0x00 && head[1] == 0x00 && head[2] == 0xFF))
    {
        return -3;
    }
    if (pnRead(len2, 2, FRAME_TIMEOUT_MS) != 2)
    {
        return -4;
    }
    if ((uint8_t)(len2[0] + len2[1]) != 0)
    {
        return -5;
    }
    l = len2[0];
    if (l < 2 || l > sizeof(frame))
    {
        return -5;
    }
    if (pnRead(frame, (uint16_t)(l + 1), FRAME_TIMEOUT_MS) != (uint16_t)(l + 1))
    {
        return -4;
    }
    if (frame[0] != 0xD5)
    {
        return -6;
    }
    for (uint16_t i = 0; i < l; i++)
    {
        sum += frame[i];
    }
    if ((uint8_t)(sum + frame[l]) != 0)
    {
        return -7;
    }
    if (pnRead(head, 1, FRAME_TIMEOUT_MS) != 1 || head[0] != 0x00)
    {
        return -7;
    }
    return (int16_t)(l - 1);
}

static bool pnFirmware()
{
    uint8_t cmd[1] = {0x02};
    int16_t n = pnCommand(cmd, 1, PING_TIMEOUT_MS);

    if (n < 5 || frame[1] != 0x03)
    {
        return false;
    }
    pnIc = frame[2];
    pnVersion = frame[3];
    pnRevision = frame[4];
    pnSupport = frame[5];
    return true;
}

static bool pnTryPins(int rxPin, int txPin)
{
    Pn.end();
    Pn.begin(PN532_BAUD, SERIAL_8N1, rxPin, txPin);
    pnFlush();

    for (uint8_t i = 0; i < 3; i++)
    {
        pnWakeup();
        if (pnFirmware())
        {
            pnRxPin = rxPin;
            pnTxPin = txPin;
            return true;
        }
        delay(100);
    }
    return false;
}

static bool pnStart()
{
    bool swapped = false;
    uint8_t sam[4] = {0x14, 0x01, 0x14, 0x01};

    if (!pnTryPins(PIN_A, PIN_B))
    {
        swapped = true;
        if (!pnTryPins(PIN_B, PIN_A))
        {
            return false;
        }
    }

    Serial.printf("\nPN532 найден: ic=0x%02X, версия=%u.%u, поддержка=0x%02X\n",
                  pnIc, pnVersion, pnRevision, pnSupport);
    if (swapped)
    {
        Serial.printf("провода данных подключены наоборот: SDA/TX платы -> GPIO%d, SCL/RX платы -> GPIO%d\n",
                      pnRxPin, pnTxPin);
    }
    else
    {
        Serial.printf("связь: SDA/TX платы -> GPIO%d, SCL/RX платы -> GPIO%d\n", pnRxPin, pnTxPin);
    }
    if (pnCommand(sam, 4, FRAME_TIMEOUT_MS) <= 0)
    {
        Serial.println("не удалось перевести плату в рабочий режим");
        return false;
    }
    return true;
}

static void readCards()
{
    uint8_t cmd[3] = {0x4A, 0x01, 0x00};
    int16_t n = pnCommand(cmd, 3, PING_TIMEOUT_MS);

    polls++;
    if (n < 3 || frame[1] != 0x4B)
    {
        if (n < 0)
        {
            Serial.printf("\nNFC: ответа нет (код %d)\n", (int)n);
        }
        return;
    }
    if (frame[2] == 0)
    {
        Serial.print('.');
        if (++dotsOnLine >= 30)
        {
            Serial.println();
            dotsOnLine = 0;
        }
        return;
    }

    {
        uint8_t count = frame[2];
        uint8_t idLen = frame[7];
        uint8_t idFirst = 8;

        if ((uint16_t)(idFirst + idLen) > (uint16_t)(n + 1))
        {
            return;
        }
        cards++;
        Serial.println();
        Serial.printf("КАРТА НАЙДЕНА (целей %u): ATQA = %02X %02X, SAK = %02X, id карты = ",
                      count, frame[4], frame[5], frame[6]);
        printHex(frame + idFirst, idLen);
        Serial.println();
        Serial.println("МОДУЛЬ ЖИВ: связь с ESP32 работает, провода и режим верные");
        dotsOnLine = 0;
    }
}

void setup()
{
    Serial.begin(115200);
    delay(300);
    Serial.println();
    Serial.println("KubGAU-Reader: проверка модуля PN532 считыванием карты");
    Serial.println("провода: 5V -> 5V, GND -> GND, MO/SDA/TX -> D6 (GPIO27), NSS/SCL/RX -> D7 (GPIO14)");
    Serial.println("приложите к катушке PN532 синий брелок или карту из комплекта модуля");
}

void loop()
{
    static uint32_t nextTryAt = 0;
    static uint32_t searchDots = 0;

    if (!pnReady)
    {
        if ((uint32_t)(millis() - nextTryAt) < 1000)
        {
            delay(20);
            return;
        }
        nextTryAt = millis();
        if (pnStart())
        {
            pnReady = true;
            Serial.println("ждём карту или брелок у катушки PN532");
            return;
        }
        Serial.print('.');
        if (++searchDots >= 30)
        {
            Serial.println();
            searchDots = 0;
        }
        return;
    }

    readCards();
    delay(150);
}
