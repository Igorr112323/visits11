#include <Arduino.h>
#include <Wire.h>

static const int PIN_SDA = 21;
static const int PIN_SCL = 22;
static const uint8_t PN532_ADDR = 0x24;

static uint8_t scanBus()
{
    uint8_t found = 0;

    for (uint8_t addr = 1; addr < 127; addr++)
    {
        Wire.beginTransmission(addr);
        if (Wire.endTransmission() == 0)
        {
            Serial.printf("  устройство на адресе 0x%02X\n", addr);
            found++;
        }
    }
    return found;
}

static bool readChunk(uint8_t *buf, uint8_t want)
{
    for (uint8_t attempt = 0; attempt < 10; attempt++)
    {
        uint8_t got = 0;
        uint8_t n;

        delay(50);
        n = (uint8_t)Wire.requestFrom((int)PN532_ADDR, (int)want);
        if (n == 0)
        {
            continue;
        }
        if ((uint8_t)Wire.read() == 0x01)
        {
            while (Wire.available() > 0 && got < want)
            {
                buf[got++] = (uint8_t)Wire.read();
            }
            return got > 0;
        }
        while (Wire.available() > 0)
        {
            Wire.read();
        }
    }
    return false;
}

static void tryFirmware()
{
    static const uint8_t cmd[7] = {0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02};
    uint8_t buf[32];

    Wire.beginTransmission(PN532_ADDR);
    Wire.write(cmd, sizeof(cmd));
    if (Wire.endTransmission() != 0)
    {
        Serial.println("  на адресе 0x24 никого нет: запрос передать некому");
        return;
    }

    if (readChunk(buf, 7))
    {
        Serial.printf("  первые байты: %02X %02X %02X %02X %02X %02X %02X\n",
                      buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6]);
    }
    else
    {
        Serial.println("  модуль не отдал первую часть ответа");
        return;
    }

    if (readChunk(buf, 16))
    {
        Serial.print("  ответ на запрос версии: ");
        for (uint8_t i = 0; i < 16; i++)
        {
            Serial.printf("%02X ", (unsigned)buf[i]);
        }
        Serial.println();
        if (buf[0] == 0x00 && buf[1] == 0x00 && buf[2] == 0xFF && buf[5] == 0xD5 && buf[6] == 0x03)
        {
            Serial.println("  вердикт: модуль ЖИВ, отвечает на запрос версии");
        }
    }
    else
    {
        Serial.println("  второй части ответа нет");
    }
}

void setup()
{
    Serial.begin(115200);
    delay(300);
    Serial.println();
    Serial.println("KubGAU-Reader: проба PN532 по шине I2C");
    Serial.printf("провода: SDA платы -> GPIO%d, SCL платы -> GPIO%d, GND -> GND, VCC5V -> 5V\n",
                  PIN_SDA, PIN_SCL);
    Serial.println("перемычки SET0/SET1 для I2C: SET0 = 1 (колпачок надет), SET1 = 0 (снят)");
    Serial.println("после перестановки перемычек обязательно снять и снова подать питание");
    Wire.begin(PIN_SDA, PIN_SCL, 100000);
}

void loop()
{
    Serial.println();
    Serial.println("--- поиск устройств на шине I2C ---");
    if (scanBus() == 0)
    {
        Serial.println("  на шине никого нет");
    }

    Serial.println("--- запрос версии PN532 (адрес 0x24) ---");
    tryFirmware();

    Serial.println("если выше есть 00 00 FF и байты 03 32 01 06 07 — модуль жив,");
    Serial.println("значит не отвечал только режим UART (перемычки), и надо вернуть HSU.");
    Serial.println("если на шине никого нет — модуль неисправен, нужен новый.");
    delay(5000);
}
