#include <Arduino.h>

static const int PIN_PN532_RX = 27;
static const int PIN_PN532_TX = 14;
static const long PN532_BAUD = 115200;

static const uint32_t ACK_TIMEOUT_MS = 400;
static const uint32_t FRAME_TIMEOUT_MS = 800;
static const uint32_t ARM_TIMEOUT_MS = 2000;
static const uint32_t DATA_TIMEOUT_MS = 10000;
static const uint32_t PING_TIMEOUT_MS = 500;
static const uint32_t SUMMARY_PERIOD_MS = 10000;
static const uint8_t TAP_REPLY_CODE = 0x00;

static const int16_t RES_NO_ANSWER = -1;
static const int16_t RES_BAD_ACK = -2;
static const int16_t RES_BAD_FRAME = -3;
static const int16_t RES_RELEASED = -4;
static const int16_t RES_TARGET_ERROR = -5;
static const int16_t RES_DATA = 1;

static HardwareSerial PnSerial(2);

static uint8_t pnFrame[300];
static uint8_t pnRaw[300];
static uint8_t apduBuf[300];
static uint16_t apduLen = 0;
static uint8_t pnStatus = 0;
static uint8_t targetMode = 0;
static uint8_t targetFirstBytes[8];
static uint8_t targetFirstLen = 0;
static uint32_t tapCounter = 0;
static uint32_t cycles = 0;
static uint32_t ackOk = 0;
static uint32_t ackBad = 0;
static uint32_t arms = 0;
static uint32_t frameBad = 0;
static uint32_t lastSummaryAt = 0;
static bool dotLine = false;
static uint32_t dotsOnLine = 0;

static void stamp()
{
    if (dotLine)
    {
        Serial.println();
        dotLine = false;
    }
    Serial.printf("[%lu.%03lu] ", (unsigned long)(millis() / 1000), (unsigned long)(millis() % 1000));
}

static void logText(const char *text)
{
    stamp();
    Serial.println(text);
}

static void logHex(const uint8_t *data, uint16_t len)
{
    for (uint16_t i = 0; i < len; i++)
    {
        Serial.printf("%02X ", data[i]);
    }
}

static void pnFlush()
{
    while (PnSerial.available() > 0)
    {
        PnSerial.read();
    }
}

static void pnWakeup()
{
    static const uint8_t wake[7] = {0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00};

    PnSerial.write(wake, sizeof(wake));
    PnSerial.flush();
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

    PnSerial.write(head, 6);
    for (uint16_t i = 0; i < len; i++)
    {
        PnSerial.write(data[i]);
        sum += data[i];
    }
    PnSerial.write((uint8_t)(0x00 - sum));
    PnSerial.write((uint8_t)0x00);
    PnSerial.flush();
}

static uint16_t pnRead(uint8_t *buf, uint16_t want, uint32_t timeoutMs)
{
    uint16_t got = 0;
    uint32_t start = millis();

    while (got < want)
    {
        if (PnSerial.available() > 0)
        {
            buf[got++] = (uint8_t)PnSerial.read();
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

static int16_t pnSaveFrame(uint16_t payload)
{
    uint8_t sum = 0;
    uint16_t i;

    if (payload < 1 || payload > sizeof(pnFrame))
    {
        return RES_BAD_FRAME;
    }
    if (pnRead(pnRaw, (uint16_t)(payload + 3), FRAME_TIMEOUT_MS) != (uint16_t)(payload + 3))
    {
        return RES_BAD_FRAME;
    }
    if (pnRaw[0] != 0xD5)
    {
        return RES_BAD_FRAME;
    }
    for (i = 0; i <= payload; i++)
    {
        sum += pnRaw[i];
    }
    if ((uint8_t)(sum + pnRaw[payload + 1]) != 0)
    {
        return RES_BAD_FRAME;
    }
    if (pnRaw[payload + 2] != 0x00)
    {
        return RES_BAD_FRAME;
    }
    memcpy(pnFrame, pnRaw + 1, payload);
    return (int16_t)payload;
}

static int16_t pnReadFrame(uint16_t maxLen, uint32_t timeoutMs)
{
    uint8_t head[3];
    uint8_t len[2];
    uint16_t payload;

    if (pnRead(head, 3, timeoutMs) != 3)
    {
        return RES_NO_ANSWER;
    }
    if (head[0] != 0x00 || head[1] != 0x00 || head[2] != 0xFF)
    {
        stamp();
        Serial.print("мусор из PN532: ");
        logHex(head, 3);
        Serial.println();
        return RES_BAD_FRAME;
    }
    if (pnRead(len, 2, FRAME_TIMEOUT_MS) != 2)
    {
        return RES_BAD_FRAME;
    }
    if ((uint8_t)(len[0] + len[1]) != 0)
    {
        return RES_BAD_FRAME;
    }
    payload = (uint16_t)(len[0] - 1);
    if (payload > maxLen)
    {
        return RES_BAD_FRAME;
    }
    return pnSaveFrame(payload);
}

static int16_t pnFinishFrame(const uint8_t *head)
{
    uint8_t tail[300];
    uint16_t payload = (uint16_t)(head[3] - 1);
    uint8_t sum = 0xD5;
    uint16_t i;

    if (payload < 1 || payload > sizeof(pnFrame))
    {
        return RES_BAD_FRAME;
    }
    if (pnRead(tail, (uint16_t)(payload + 2), FRAME_TIMEOUT_MS) != (uint16_t)(payload + 2))
    {
        return RES_BAD_FRAME;
    }
    for (i = 0; i < payload; i++)
    {
        sum += tail[i];
    }
    if ((uint8_t)(sum + tail[payload]) != 0)
    {
        return RES_BAD_FRAME;
    }
    if (tail[payload + 1] != 0x00)
    {
        return RES_BAD_FRAME;
    }
    memcpy(pnFrame, tail, payload);
    return (int16_t)payload;
}

static int16_t pnCommand(const uint8_t *cmd, uint16_t len, uint32_t timeoutMs)
{
    uint8_t head[6];
    uint16_t got;

    pnFlush();
    pnWriteFrame(cmd, len);

    got = pnRead(head, 6, ACK_TIMEOUT_MS);
    if (got == 6 && head[0] == 0x00 && head[1] == 0x00 && head[2] == 0xFF &&
        head[3] == 0x00 && head[4] == 0xFF && head[5] == 0x00)
    {
        ackOk++;
        return pnReadFrame(sizeof(pnFrame), timeoutMs);
    }

    ackBad++;
    if (got == 6 && head[0] == 0x00 && head[1] == 0x00 && head[2] == 0xFF)
    {
        logText("вместо ACK пришёл кадр PN532, разбираю его");
        return pnFinishFrame(head);
    }
    if (got > 0)
    {
        stamp();
        Serial.print("PN532 ответил не ACK: ");
        logHex(head, got);
        Serial.println();
    }
    return RES_BAD_ACK;
}

static bool pnFirmware(uint8_t *ic, uint8_t *version, uint8_t *revision, uint8_t *support)
{
    uint8_t cmd[1] = {0x02};
    int16_t r = pnCommand(cmd, 1, PING_TIMEOUT_MS);

    if (r != 5 || pnFrame[0] != 0x03)
    {
        return false;
    }
    *ic = pnFrame[1];
    *version = pnFrame[2];
    *revision = pnFrame[3];
    *support = pnFrame[4];
    return true;
}

static bool pnConfigure()
{
    uint8_t sam[4] = {0x14, 0x01, 0x14, 0x01};

    return pnCommand(sam, 4, FRAME_TIMEOUT_MS) > 0;
}

static int16_t pnArmTarget(uint32_t timeoutMs)
{
    uint8_t cmd[38];
    int16_t r;

    memset(cmd, 0x00, sizeof(cmd));
    cmd[0] = 0x8C;
    cmd[1] = 0x05;
    cmd[2] = 0x04;
    cmd[3] = 0x00;
    cmd[4] = 0x12;
    cmd[5] = 0x34;
    cmd[6] = 0x56;
    cmd[7] = 0x20;
    cmd[26] = 0xAA;
    cmd[27] = 0x99;
    cmd[28] = 0x88;
    cmd[29] = 0x77;
    cmd[30] = 0x66;
    cmd[31] = 0x55;
    cmd[32] = 0x44;
    cmd[33] = 0x33;
    cmd[34] = 0x22;
    cmd[35] = 0x11;

    pnWakeup();
    r = pnCommand(cmd, sizeof(cmd), timeoutMs);
    if (r < 2 || pnFrame[0] != 0x8D)
    {
        return r < 0 ? r : RES_BAD_FRAME;
    }
    arms++;
    targetMode = pnFrame[1];
    targetFirstLen = (uint8_t)(r - 2);
    if (targetFirstLen > sizeof(targetFirstBytes))
    {
        targetFirstLen = sizeof(targetFirstBytes);
    }
    memcpy(targetFirstBytes, pnFrame + 2, targetFirstLen);
    return RES_DATA;
}

static int16_t pnGetApdu(uint32_t timeoutMs)
{
    uint8_t cmd[1] = {0x86};

    apduLen = 0;
    for (uint8_t pass = 0; pass < 4; pass++)
    {
        int16_t r = pnCommand(cmd, 1, timeoutMs);
        uint8_t status;
        uint16_t n;

        if (r < 0)
        {
            return r;
        }
        if (r < 2 || pnFrame[0] != 0x87)
        {
            return RES_BAD_FRAME;
        }
        status = (uint8_t)(pnFrame[1] & 0x3F);
        if (status == 0x29)
        {
            return RES_RELEASED;
        }
        if (status != 0x00)
        {
            pnStatus = status;
            return RES_TARGET_ERROR;
        }
        n = (uint16_t)(r - 2);
        if ((uint32_t)apduLen + n > sizeof(apduBuf))
        {
            n = (uint16_t)(sizeof(apduBuf) - apduLen);
        }
        memcpy(apduBuf + apduLen, pnFrame + 2, n);
        apduLen += n;
        if ((pnFrame[1] & 0x40) == 0)
        {
            return RES_DATA;
        }
    }
    return RES_DATA;
}

static bool pnSendApdu(const uint8_t *data, uint16_t len)
{
    static uint8_t cmd[300];
    int16_t r;

    cmd[0] = 0x8E;
    memcpy(cmd + 1, data, len);
    r = pnCommand(cmd, (uint16_t)(len + 1), FRAME_TIMEOUT_MS + FRAME_TIMEOUT_MS);
    if (r < 1 || pnFrame[0] != 0x8F)
    {
        return false;
    }
    if (r >= 2 && (pnFrame[1] & 0x3F) != 0x00)
    {
        return false;
    }
    return true;
}

static void pnRelease()
{
    uint8_t cmd[2] = {0x52, 0x00};

    pnCommand(cmd, 2, FRAME_TIMEOUT_MS);
}

static void pnHint()
{
    logText("PN532 не отвечает.");
    logText("Проверьте: питание 3V3 (лучше 3V3, чем 5V) и GND, SDA/TX->GPIO27, SCL/RX->GPIO14,");
    logText("переключатели на плате PN532 в положении HSU (оба OFF),");
    logText("кабель в USB, в Arduino выбран порт ESP32 и скорость 115200.");
}

static bool pnResync()
{
    uint8_t ic = 0;
    uint8_t version = 0;
    uint8_t revision = 0;
    uint8_t support = 0;

    logText("сброс связи с PN532...");
    pnFlush();
    for (uint8_t i = 0; i < 6; i++)
    {
        if (pnFirmware(&ic, &version, &revision, &support))
        {
            stamp();
            Serial.printf("PN532 снова на связи: ic=0x%02X версия=%u.%u\n", ic, version, revision);
            return pnConfigure();
        }
        delay(200);
    }
    pnHint();
    return false;
}

static void respondApdu(const uint8_t *data, uint16_t len)
{
    stamp();
    Serial.print("  -> ответ телефону: ");
    logHex(data, len);
    Serial.println();
    if (!pnSendApdu(data, len))
    {
        logText("  ответ не доставлен: связь с PN532 потеряна");
    }
}

static void handleApdu()
{
    stamp();
    Serial.print("NFC APDU от телефона: ");
    logHex(apduBuf, apduLen);
    Serial.println();

    if (apduLen < 4)
    {
        uint8_t sw[2] = {0x6A, 0x82};
        respondApdu(sw, 2);
        return;
    }

    if (apduBuf[1] == 0xA4)
    {
        uint8_t sw[2] = {0x90, 0x00};
        logText("  SELECT: приложение студента на связи (AID F0391101020304)");
        respondApdu(sw, 2);
        return;
    }

    if (apduBuf[1] != 0x10)
    {
        uint8_t sw[2] = {0x6D, 0x00};
        logText("  команда не наша: отвечаем 6D 00");
        respondApdu(sw, 2);
        return;
    }

    if (apduLen >= 5 && apduBuf[4] > 0 && (uint16_t)(5 + apduBuf[4]) <= apduLen)
    {
        char text[272];
        char *token = text;
        char *device = text;
        uint16_t length = apduBuf[4];
        uint8_t answer[3];

        memcpy(text, apduBuf + 5, length);
        text[length] = 0;
        for (uint16_t i = 0; i < length; i++)
        {
            if (text[i] == '\n')
            {
                device = text + i + 1;
                text[i] = 0;
                break;
            }
        }

        stamp();
        Serial.printf("ОТМЕТКА ПРИНЯТА: код студента = %s\n", token);
        stamp();
        Serial.printf("                 телефон = %s\n", device);
        tapCounter++;
        Serial.printf("TAP:%lu:%s:%s\n", (unsigned long)tapCounter, token, device);

        answer[0] = TAP_REPLY_CODE;
        answer[1] = 0x90;
        answer[2] = 0x00;
        logText("  отвечаем 00 90 00: телефон студента вибрирует и пишет «Вы отмечены»");
        respondApdu(answer, 3);
        return;
    }

    {
        uint8_t sw[2] = {0x90, 0x00};
        logText("  пустая команда: отвечаем 90 00");
        respondApdu(sw, 2);
    }
}

static void session()
{
    stamp();
    Serial.printf("NFC: ТЕЛЕФОН ПРИЛОЖЕН — карта активирована (mode=0x%02X)\n", targetMode);
    if (targetFirstLen > 0)
    {
        stamp();
        Serial.print("  телефон начал ISO14443-4, первые байты: ");
        logHex(targetFirstBytes, targetFirstLen);
        Serial.println();
    }

    for (;;)
    {
        int16_t r = pnGetApdu(DATA_TIMEOUT_MS);

        if (r == RES_DATA)
        {
            handleApdu();
            continue;
        }
        if (r == RES_RELEASED)
        {
            logText("NFC: телефон убран");
            break;
        }
        if (r == RES_NO_ANSWER)
        {
            stamp();
            Serial.printf("NFC: команда от телефона не пришла за %lu с\n",
                          (unsigned long)(DATA_TIMEOUT_MS / 1000));
            logText("  если телефон лежал на считывателе — в приложении студента не считан QR-код");
            break;
        }
        if (r == RES_TARGET_ERROR)
        {
            stamp();
            Serial.printf("NFC: PN532 сообщил статус 0x%02X\n", pnStatus);
            break;
        }
        logText("NFC: связь с PN532 потеряна");
        pnFlush();
        pnResync();
        break;
    }

    pnRelease();
    pnFlush();
}

void setup()
{
    Serial.begin(115200);
    delay(300);
    Serial.println();
    Serial.println("KubGAU-Reader: ESP32 Devkit V1 + PN532 (HSU/UART)");
    Serial.printf("PN532: RX->GPIO%d, TX->GPIO%d, %ld бод\n", PIN_PN532_RX, PIN_PN532_TX, PN532_BAUD);

    PnSerial.begin(PN532_BAUD, SERIAL_8N1, PIN_PN532_RX, PIN_PN532_TX);
    pnFlush();
    pnWakeup();

    {
        uint8_t ic = 0;
        uint8_t version = 0;
        uint8_t revision = 0;
        uint8_t support = 0;
        bool found = false;

        for (uint8_t i = 0; i < 20 && !found; i++)
        {
            found = pnFirmware(&ic, &version, &revision, &support);
            if (!found)
            {
                delay(300);
            }
        }
        if (!found)
        {
            pnHint();
            while (true)
            {
                delay(1000);
            }
        }
        stamp();
        Serial.printf("PN532 найден: ic=0x%02X, версия=%u.%u, поддержка=0x%02X\n",
                      ic, version, revision, support);
    }

    if (!pnConfigure())
    {
        logText("PN532 найден, но не настроился — перезапустите плату");
    }
    Serial.println("Режим: эмуляция карты ISO14443-4 (как телефон преподавателя).");
    Serial.println("Приложите телефон студента к катушке PN532 и подержите 2-3 секунды.");
}

void loop()
{
    static uint8_t errors = 0;
    static bool announced = false;
    int16_t r;

    if (!announced)
    {
        logText("ждём телефон: приложите его к катушке PN532 и подержите 2-3 секунды (точка = один опрос)");
        announced = true;
        dotsOnLine = 0;
    }

    r = pnArmTarget(ARM_TIMEOUT_MS);
    cycles++;

    if (r == RES_DATA)
    {
        errors = 0;
        announced = false;
        lastSummaryAt = millis();
        session();
        return;
    }

    if (r == RES_NO_ANSWER)
    {
        errors = 0;
        Serial.print('.');
        dotLine = true;
        if (++dotsOnLine >= 30)
        {
            Serial.println();
            dotLine = false;
            dotsOnLine = 0;
        }
    }
    else
    {
        errors++;
        if (r == RES_BAD_FRAME)
        {
            frameBad++;
        }
        stamp();
        Serial.printf("PN532: сбой обмена (%u/3)\n", errors);
        if (errors >= 3)
        {
            errors = 0;
            announced = false;
            pnResync();
        }
    }

    if ((uint32_t)(millis() - lastSummaryAt) >= SUMMARY_PERIOD_MS)
    {
        lastSummaryAt = millis();
        stamp();
        Serial.printf("циклов: %lu, плата отвечает: %lu, без ответа на команду: %lu, активаций: %lu, битых кадров: %lu\n",
                      (unsigned long)cycles, (unsigned long)ackOk, (unsigned long)ackBad,
                      (unsigned long)arms, (unsigned long)frameBad);
    }

    if (ackBad >= 10)
    {
        ackBad = 0;
        errors = 0;
        announced = false;
        pnResync();
    }
    delay(20);
}
