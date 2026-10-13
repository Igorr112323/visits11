#include <Arduino.h>

static const int PAIRS[14][2] = {
    {27, 14}, {14, 27},
    {16, 17}, {17, 16},
    {26, 25}, {25, 26},
    {21, 22}, {22, 21},
    {32, 33}, {33, 32},
    {18, 19}, {19, 18},
    {5, 4}, {4, 5}};

static const long BAUDS[2] = {115200, 9600};

static HardwareSerial dev(2);

static void testPair(int rxPin, int txPin, long baud)
{
    uint8_t buf[64];
    uint16_t got = 0;
    uint32_t start;
    size_t n;

    Serial.printf("RX=GPIO%d TX=GPIO%d скорость=%ld ... ", rxPin, txPin, baud);

    dev.begin(baud, SERIAL_8N1, rxPin, txPin);
    while (dev.available() > 0)
    {
        dev.read();
    }

    for (uint8_t i = 0; i < 3; i++)
    {
        static const uint8_t wake[7] = {0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00};
        dev.write(wake, sizeof(wake));
        dev.flush();
        delay(20);
    }

    for (uint8_t attempt = 0; attempt < 2; attempt++)
    {
        static const uint8_t ping[7] = {0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02};
        dev.write(ping, sizeof(ping));
        dev.flush();
        start = millis();
        while ((uint32_t)(millis() - start) < 250)
        {
            if (dev.available() > 0)
            {
                if (got < sizeof(buf))
                {
                    buf[got] = (uint8_t)dev.read();
                }
                else
                {
                    dev.read();
                }
                got++;
            }
        }
        if (got >= 6)
        {
            break;
        }
    }

    if (got == 0)
    {
        Serial.println("тишина");
    }
    else
    {
        n = got < sizeof(buf) ? got : sizeof(buf);
        Serial.printf("получено %u байт: ", (unsigned)got);
        for (size_t i = 0; i < n; i++)
        {
            Serial.printf("%02X ", (unsigned)buf[i]);
        }
        if (got >= 6 && buf[0] == 0x00 && buf[1] == 0x00 && buf[2] == 0xFF)
        {
            Serial.print("   <== ЭТО ОТВЕТ PN532, эта пара пинов и скорость подходят");
        }
        else
        {
            Serial.print("   (ответом не является: настоящий ответ начинается с 00 00 FF)");
        }
        Serial.println();
    }

    dev.end();
}

void setup()
{
    Serial.begin(115200);
    delay(300);
    Serial.println();
    Serial.println("KubGAU-Reader: поиск PN532 — перебор пар пинов и скоростей");
    Serial.println("строка с пометкой «ЭТО ОТВЕТ PN532» = модуль найден, скажите мне такую строку");
    Serial.println("проверка ESP32 без модуля: перемычка GPIO27 <-> GPIO14 даёт 28 байт в обеих строках с GPIO27 и GPIO14");
}

void loop()
{
    Serial.println();
    Serial.println("--- начало перебора ---");
    for (uint8_t b = 0; b < sizeof(BAUDS) / sizeof(BAUDS[0]); b++)
    {
        for (uint8_t p = 0; p < sizeof(PAIRS) / sizeof(PAIRS[0]); p++)
        {
            testPair(PAIRS[p][0], PAIRS[p][1], BAUDS[b]);
        }
    }
    Serial.println("--- перебор закончен, повтор через 5 секунд ---");
    delay(5000);
}
