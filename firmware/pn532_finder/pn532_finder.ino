#include <Arduino.h>

static const int PIN_A = 27;
static const int PIN_B = 14;

static const long BAUDS[6] = {115200, 9600, 19200, 38400, 57600, 230400};

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

    {
        static const uint8_t ping[7] = {0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02};
        dev.write(ping, sizeof(ping));
        dev.flush();
    }

    start = millis();
    while ((uint32_t)(millis() - start) < 300)
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

    if (got == 0)
    {
        Serial.println("тишина");
    }
    else
    {
        Serial.printf("получено %u байт: ", (unsigned)got);
        n = got < sizeof(buf) ? got : sizeof(buf);
        for (size_t i = 0; i < n; i++)
        {
            Serial.printf("%02X ", (unsigned)buf[i]);
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
    Serial.println("KubGAU-Reader: поиск PN532 — перебор проводов и скоростей");
    Serial.println("одна строка = один вариант: «тишина» или принятые байты");
    Serial.println("проверка ESP32 без модуля: перемычка GPIO27 <-> GPIO14 должна дать 28 байт");
}

void loop()
{
    Serial.println();
    Serial.println("--- начало перебора ---");
    for (uint8_t i = 0; i < sizeof(BAUDS) / sizeof(BAUDS[0]); i++)
    {
        testPair(PIN_A, PIN_B, BAUDS[i]);
        testPair(PIN_B, PIN_A, BAUDS[i]);
    }
    Serial.println("--- перебор закончен, повтор через 5 секунд ---");
    delay(5000);
}
