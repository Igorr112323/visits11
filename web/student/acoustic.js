/* ==========================================================================
   КубГАУ Студент — обмен звуком с телефоном преподавателя (ggwave).
   --------------------------------------------------------------------------
   Телефон преподавателя при касании отдаёт iPhone NFC-метку со ссылкой вида
   https://…/#n=XXXXXXXX (XXXXXXXX — одноразовый код касания). Страница
   открывается в Safari и дальше «разговаривает» с телефоном преподавателя
   звуком — без интернета и без сети:

     студент → преподаватель:  <тип><режим><код касания>\n<данные>
         тип   : 1 — логин\nпароль\nустройство, 2 — токен\nустройство
         режим : u — ультразвук, a — слышимый (запасной)
     преподаватель → студент:  <код касания><результат><значение>
         результат: 0 токен, 1 отмечен (+имя), 2 неверный пароль, 3 сессия
         устарела, 4 нет связи с ПК, 5 другой телефон, 6 код касания устарел

   Те же коды ответа, что в NFC-режиме Android-приложения (TeacherCardService).

   Звук в Safari разрешён только после нажатия пользователя, поэтому prepare()
   вызывается прямо из обработчика нажатия. Внешних адресов нет: ggwave.js
   лежит рядом (ставится в офлайн-кэш Service Worker-ом).
   ========================================================================== */

(function () {
  "use strict";

  var PROTOCOL = {
    u: "GGWAVE_PROTOCOL_ULTRASOUND_FAST",
    a: "GGWAVE_PROTOCOL_AUDIBLE_FAST"
  };
  var VOLUME = { u: 70, a: 45 };
  var RATE = 48000;
  var FRAME = 1024;              // samplesPerFrame ggwave при 48 кГц
  var TAIL_MS = 250;             // пауза после своего звука, чтобы не слушать эхо

  var ggwave = null;
  var instance = null;           // id экземпляра ggwave (0 — тоже настоящий id)
  var ctx = null;
  var inputChunk = FRAME;        // сколько отсчётов ждёт ggwave при частоте этого телефона
  var pending = [];
  var pendingLength = 0;
  var transmitting = false;
  var waiter = null;             // { nonce, resolve }
  var factoryPromise = null;
  var preparing = null;

  // ------------------------------------------------------------------ формат сообщений

  /** Сообщение студента преподавателю. */
  function build(type, mode, nonce, data) {
    return String(type) + mode + nonce + "\n" + data;
  }

  /** Ответ преподавателя: { nonce, code, value } или null. */
  function parseReply(text) {
    if (typeof text !== "string" || text.length < 9) return null;
    var code = text.charCodeAt(8) - 48;
    if (code < 0 || code > 9) return null;
    return { nonce: text.substring(0, 8), code: code, value: text.substring(9) };
  }

  // ------------------------------------------------------------------ ggwave

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var tag = document.createElement("script");
      tag.src = src;
      tag.onload = function () { resolve(); };
      tag.onerror = function () { reject(new Error("load " + src)); };
      document.head.appendChild(tag);
    });
  }

  /** Загружает ggwave.js и поднимает WebAssembly (без звука — можно заранее). */
  function preload() {
    if (!factoryPromise) {
      factoryPromise = (typeof window.ggwave_factory === "function"
        ? Promise.resolve()
        : loadScript("/ggwave.js")
      ).then(function () { return window.ggwave_factory(); })
       .then(function (module) { ggwave = module; return module; });
    }
    return factoryPromise;
  }

  function toFloat32(int8) {
    var copy = new ArrayBuffer(int8.byteLength);
    new Int8Array(copy).set(int8);       // копия: ggwave отдаёт окно в свою память
    return new Float32Array(copy);
  }

  function toInt8(float32) {
    var copy = new ArrayBuffer(float32.byteLength);
    new Float32Array(copy).set(float32);
    return new Int8Array(copy);
  }

  function initInstance() {
    var parameters = ggwave.getDefaultParameters();
    parameters.sampleRateInp = ctx.sampleRate;
    parameters.sampleRateOut = ctx.sampleRate;
    instance = ggwave.init(parameters);
    // ggwave ждёт целое число отсчётов в кадре: при 44,1 кГц это 941, не 1024
    inputChunk = Math.ceil(FRAME * ctx.sampleRate / RATE);
  }

  // ------------------------------------------------------------------ приём

  function onSamples(samples) {
    if (transmitting || instance === null) return;
    pending.push(new Float32Array(samples));
    pendingLength += samples.length;
    while (pendingLength >= inputChunk) {
      var chunk = new Float32Array(inputChunk);
      var filled = 0;
      while (filled < inputChunk) {
        var head = pending[0];
        var take = Math.min(head.length, inputChunk - filled);
        chunk.set(head.subarray(0, take), filled);
        filled += take;
        if (take === head.length) pending.shift(); else pending[0] = head.subarray(take);
        pendingLength -= take;
      }
      var result = ggwave.decode(instance, toInt8(chunk));
      if (result && result.length > 0) {
        var text = "";
        try { text = new TextDecoder("utf-8").decode(new Uint8Array(result)); } catch (e) { text = ""; }
        var reply = parseReply(text);
        if (reply && waiter && reply.nonce === waiter.nonce) {
          var done = waiter;
          waiter = null;
          done.resolve(reply);
        }
      }
    }
  }

  function startMicrophone() {
    return navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, autoGainControl: false, noiseSuppression: false, channelCount: 1 }
    }).then(function (stream) {
      var source = ctx.createMediaStreamSource(stream);
      var recorder = ctx.createScriptProcessor(2048, 1, 1);
      recorder.onaudioprocess = function (event) {
        onSamples(event.inputBuffer.getChannelData(0));
      };
      source.connect(recorder);
      recorder.connect(ctx.destination);   // без этого iOS не запускает обработчик (на выход идёт тишина)
      return true;
    });
  }

  // ------------------------------------------------------------------ интерфейс

  /**
   * Вызывать СИНХРОННО из обработчика нажатия: создаёт звуковой контекст
   * (иначе Safari не даст играть звук) и запрашивает микрофон.
   * Возвращает Promise<boolean>: можно ли говорить и слушать.
   */
  function prepare() {
    if (preparing) return preparing;
    try {
      var AudioContextClass = window.AudioContext || window.webkitAudioContext;
      try { ctx = new AudioContextClass({ sampleRate: RATE }); } catch (e) { ctx = new AudioContextClass(); }
      if (ctx.resume) ctx.resume();
      // «разблокировка» звука: тихий отсчёт внутри нажатия
      var silent = ctx.createBuffer(1, 1, ctx.sampleRate);
      var unlock = ctx.createBufferSource();
      unlock.buffer = silent;
      unlock.connect(ctx.destination);
      unlock.start(0);
    } catch (e) {
      return Promise.resolve(false);
    }
    preparing = preload().then(function () {
      initInstance();
      return startMicrophone();
    }).then(function () { return true; }, function () {
      preparing = null;    // не разрешили микрофон — следующее нажатие попробует снова
      return false;
    });
    return preparing;
  }

  function play(text, mode) {
    return new Promise(function (resolve) {
      var waveform = ggwave.encode(instance, text, ggwave.ProtocolId[PROTOCOL[mode]], VOLUME[mode]);
      var samples = toFloat32(waveform);
      var buffer = ctx.createBuffer(1, samples.length, ctx.sampleRate);
      buffer.getChannelData(0).set(samples);
      var source = ctx.createBufferSource();
      source.buffer = buffer;
      source.connect(ctx.destination);
      transmitting = true;
      var finished = false;
      function finish() {
        if (finished) return;
        finished = true;
        setTimeout(function () { pending = []; pendingLength = 0; transmitting = false; resolve(); }, TAIL_MS);
      }
      source.onended = finish;
      setTimeout(finish, Math.ceil(samples.length / ctx.sampleRate * 1000) + 500);
      source.start(0);
    });
  }

  /**
   * Отправить сообщение преподавателю и ждать ответ с тем же кодом касания.
   * Promise<{nonce, code, value}> либо null, если ответа не было за timeoutMs.
   */
  function exchange(type, mode, nonce, data, timeoutMs) {
    return new Promise(function (resolve) {
      if (instance === null || !ctx) { resolve(null); return; }
      if (waiter) { waiter.resolve(null); waiter = null; }
      var timer = null;
      var mine = {
        nonce: nonce,
        resolve: function (value) {
          if (timer) clearTimeout(timer);
          resolve(value);
        }
      };
      play(build(type, mode, nonce, data), mode).then(function () {
        waiter = mine;
        timer = setTimeout(function () {
          if (waiter === mine) waiter = null;
          resolve(null);
        }, timeoutMs);
      }).catch(function (e) {
        transmitting = false;
        if (window.console) window.console.error(e);
        resolve(null);
      });
    });
  }

  window.Acoustic = {
    prepare: prepare,
    preload: preload,
    exchange: exchange,
    build: build,
    parseReply: parseReply
  };
})();
