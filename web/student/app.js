/* ==========================================================================
   КубГАУ Студент — PWA для iPhone.
   --------------------------------------------------------------------------
   Логика — порт android/student/MainActivity.java (режим «без NFC»: у iPhone
   нет NFC, доступного сайтам, поэтому работает то же, что в APK на телефоне
   без NFC — QR для камеры преподавателя):

     • нет логина/пароля           → «Войдите по логину и паролю» + карточка входа
     • ПК не отвечает (/api/ping)  → «Нет связи с ПК», красная точка
     • POST /api/login             → токен сессии
     • GET  /api/qr?token=         → QR на весь экран, зелёная точка; опрос раз в 5 с
         204 → «Перекличка не начата», 401 → сразу перевходим тем же логином
     • долгое нажатие на статус    → карточка входа (смена студента)

   Адрес ПК — тот, с которого открыта страница (в Android его ищут перебором
   подсети; сайту из браузера так нельзя). Внешних запросов нет.

   Офлайн: Service Worker (sw.js) кэширует само приложение, так что экран
   открывается без сети и без ПК. Запросы /api/* он никогда не трогает.
   ========================================================================== */

(function () {
  "use strict";

  // ------------------------------------------------------------------ константы

  var POLL_MS = 5000;            // MainActivity.POLL_MS
  var NET_TIMEOUT_MS = 2500;     // таймауты HTTP как в Android
  var LONG_PRESS_MS = 500;
  var TOAST_MS = 2000;           // Toast.LENGTH_SHORT
  var HOST_APP = "visits11-server";

  var LOGIN_OK = 0;
  var LOGIN_REJECTED = 1;
  var LOGIN_NETWORK = 2;
  var LOGIN_DEVICE = 3;          // в Android это код 5 NFC-режима: «Аккаунт привязан к другому телефону»

  var QR_OK = 0;
  var QR_INACTIVE = 1;           // перекличка не идёт
  var QR_STALE = 2;              // сессия устарела — перевойдём
  var QR_ERROR = 3;              // сети нет

  // ключи как у Android SharedPreferences "visits11student": login / password
  var KEY_LOGIN = "visits11.login";
  var KEY_PASSWORD = "visits11.password";
  var KEY_DEVICE = "visits11.device";
  var KEY_TOKEN = "visits11.token";   // токен сессии для звукового режима (как KEY_TOKEN в Android NFC-режиме)

  // коды ответа преподавателя в звуковом режиме — те же, что в NFC-режиме Android
  var AC_TOKEN = 0, AC_MARKED = 1, AC_BAD_CREDENTIALS = 2, AC_RELOGIN = 3,
      AC_UNAVAILABLE = 4, AC_DEVICE_BLOCKED = 5, AC_STALE_TAP = 6;

  // ------------------------------------------------------------------ элементы

  function $(id) { return document.getElementById(id); }

  var loginPanel = $("loginPanel");
  var loginInput = $("loginInput");
  var passwordInput = $("passwordInput");
  var loginButton = $("loginButton");
  var qrView = $("qrView");
  var statusText = $("statusText");
  var dot = $("dot");
  var toastView = $("toast");

  // ------------------------------------------------------------------ хранилище

  var store = {
    get: function (key) {
      try { return window.localStorage.getItem(key); } catch (e) { return null; }
    },
    set: function (key, value) {
      try { window.localStorage.setItem(key, value); } catch (e) { /* приватный режим */ }
    }
  };

  /** Уникальный ID устройства: 16 hex-символов, создаётся один раз. Аккаунт привязывается к первому телефону. */
  function deviceId() {
    var saved = store.get(KEY_DEVICE);
    if (saved) return saved;

    var value = "";
    try {
      var bytes = new Uint8Array(8);
      window.crypto.getRandomValues(bytes);
      for (var i = 0; i < bytes.length; i++) {
        value += ("0" + bytes[i].toString(16)).slice(-2);
      }
    } catch (e) {
      value = "";
    }
    if (value.length !== 16) {
      value = "";
      for (var j = 0; j < 16; j++) {
        value += "0123456789abcdef".charAt(Math.floor(Math.random() * 16));
      }
    }
    store.set(KEY_DEVICE, value);
    return value;
  }

  // ------------------------------------------------------------------ интерфейс

  var toastTimer = null;
  var lastQrUrl = null;

  /** Toast.makeText(...).show() */
  function toast(message) {
    toastView.textContent = message;
    toastView.classList.add("is-shown");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toastView.classList.remove("is-shown");
    }, TOAST_MS);
  }

  /** setStatus: показать текст, спрятать QR. */
  function setStatus(text) {
    statusText.textContent = text;
    statusText.hidden = false;
    qrView.hidden = true;
  }

  function showLogin() {
    loginPanel.hidden = false;
  }

  function hideLogin() {
    loginPanel.hidden = true;
  }

  /** showQr: показать код, спрятать статус и карточку входа. */
  function showQr(blob) {
    var url = window.URL.createObjectURL(blob);
    qrView.onload = function () {
      if (lastQrUrl && lastQrUrl !== url) window.URL.revokeObjectURL(lastQrUrl);
      lastQrUrl = url;
    };
    qrView.src = url;
    qrView.hidden = false;
    statusText.hidden = true;
    loginPanel.hidden = true;
  }

  function dotGreen() { dot.classList.add("is-green"); }
  function dotRed() { dot.classList.remove("is-green"); }

  // ------------------------------------------------------------------ сеть

  /** fetch с таймаутом: Response либо null (нет связи). */
  function request(path, options, timeoutMs) {
    return new Promise(function (resolve) {
      var done = false;
      var controller = null;
      var opts = options || {};
      opts.cache = "no-store";
      try {
        if (typeof window.AbortController === "function") {
          controller = new window.AbortController();
          opts.signal = controller.signal;
        }
      } catch (e) { controller = null; }

      var timer = setTimeout(function () {
        if (controller) {
          try { controller.abort(); } catch (e) { /* уже завершён */ }
        }
        finish(null);
      }, timeoutMs || NET_TIMEOUT_MS);

      function finish(value) {
        if (done) return;
        done = true;
        clearTimeout(timer);
        resolve(value);
      }

      try {
        window.fetch(path, opts).then(finish, function () { finish(null); });
      } catch (e) {
        finish(null);
      }
    });
  }

  /** ping(): отвечает ли ПК приложения (в Android — перебор подсети, здесь адрес один). */
  function ping() {
    return request("/api/ping", {}, NET_TIMEOUT_MS).then(function (response) {
      if (!response || response.status !== 200) return false;
      return response.text().then(function (text) {
        return text.indexOf(HOST_APP) >= 0;
      }, function () { return false; });
    });
  }

  var token = null;      // токен сессии — только в памяти, как в Android (дальше входим тем же логином)

  /** login(): LOGIN_OK / LOGIN_REJECTED / LOGIN_NETWORK / LOGIN_DEVICE. */
  function login(loginValue, passwordValue) {
    var body = JSON.stringify({ login: loginValue, password: passwordValue, device: deviceId() });
    return request("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: body
    }, NET_TIMEOUT_MS).then(function (response) {
      if (!response || response.status !== 200) return LOGIN_NETWORK;
      return response.text().then(function (text) {
        var data = null;
        try { data = JSON.parse(text); } catch (e) { data = null; }
        if (data && data.ok === true && typeof data.token === "string" && data.token) {
          token = data.token;
          return LOGIN_OK;
        }
        if (data && data.device === true) return LOGIN_DEVICE;
        return LOGIN_REJECTED;
      }, function () { return LOGIN_NETWORK; });
    });
  }

  /** fetchQr(): { code, blob }. */
  function fetchQr(session) {
    return request("/api/qr?token=" + encodeURIComponent(session), {}, NET_TIMEOUT_MS)
      .then(function (response) {
        if (!response) return { code: QR_ERROR };
        if (response.status === 200) {
          return response.blob().then(function (blob) {
            return blob && blob.size > 0 ? { code: QR_OK, blob: blob } : { code: QR_ERROR };
          }, function () { return { code: QR_ERROR }; });
        }
        if (response.status === 204) return { code: QR_INACTIVE };
        if (response.status === 401) return { code: QR_STALE };
        return { code: QR_ERROR };
      });
  }

  // ------------------------------------------------------------------ главный цикл

  var running = false;
  var generation = 0;
  var wakeUp = null;
  var hostFound = false;   // «host != null» из Android

  function sleepQuietly(ms) {
    return new Promise(function (resolve) {
      var timer = setTimeout(function () { wakeUp = null; resolve(); }, ms);
      wakeUp = function () { clearTimeout(timer); wakeUp = null; resolve(); };
    });
  }

  /** wake(): прервать ожидание — цикл сразу пойдёт дальше. */
  function wake() {
    if (wakeUp) wakeUp();
  }

  /** Тело одного прохода MainActivity.loop(). Возвращает паузу перед следующим проходом (мс). */
  async function step() {
    var savedLogin = store.get(KEY_LOGIN);
    var savedPassword = store.get(KEY_PASSWORD);

    if (!savedLogin || !savedPassword) {
      setStatus("Войдите по логину и паролю");
      showLogin();
      dotRed();
      return 1000;
    }

    // Открыто по NFC-метке телефона преподавателя (ссылка с #n=код): сети нет,
    // отметка идёт звуком — см. acoustic.js
    if (acousticNonce !== null) {
      hideLogin();
      if (acState === "ready" && statusShown !== "ready") {
        statusShown = "ready";
        setStatus("Нажмите на экран, чтобы отметиться");
      }
      return 1000;
    }

    if (!hostFound) {
      setStatus("Поиск ПК…");
      hostFound = await ping();
    }
    if (!hostFound) {
      setStatus("Нет связи с ПК");
      dotRed();
      return 2000;
    }

    if (token === null) {
      var code = await login(savedLogin, savedPassword);
      if (code === LOGIN_REJECTED) {
        token = null;
        setStatus("НЕВЕРНЫЙ ЛОГИН ИЛИ ПАРОЛЬ — введите другие");
        showLogin();
        dotRed();
        return 1500;
      }
      if (code === LOGIN_DEVICE) {
        token = null;
        setStatus("Аккаунт привязан к другому телефону");
        showLogin();
        dotRed();
        return 1500;
      }
      if (code !== LOGIN_OK) {
        hostFound = false;
        setStatus("Нет связи с ПК");
        dotRed();
        return 2000;
      }
      // вход выполнен: карточка входа больше не нужна (в Android она остаётся
      // до первого QR; здесь убираем сразу, чтобы был виден статус)
      hideLogin();
    }

    var qr = await fetchQr(token);
    if (qr.code === QR_OK) {
      showQr(qr.blob);
      dotGreen();
    } else if (qr.code === QR_INACTIVE) {
      setStatus("Перекличка не начата");
      dotGreen();
    } else if (qr.code === QR_STALE) {
      token = null;
      return 250; // почти сразу перевойдём тем же логином (в Android — continue без паузы)
    } else {
      hostFound = false;
      setStatus("Нет связи с ПК");
      dotRed();
    }
    return POLL_MS;
  }

  async function loop(myGeneration) {
    while (running && myGeneration === generation) {
      var pause = 2000;
      try {
        pause = await step();
      } catch (e) {
        pause = 2000;
      }
      if (!running || myGeneration !== generation) break;
      if (pause > 0) await sleepQuietly(pause);
    }
  }

  // ------------------------------------------------------------------ звуковой режим

  var acousticNonce = null;    // код касания из ссылки NFC-метки: #n=XXXXXXXX
  var acState = "ready";       // ready | busy | done
  var statusShown = "";

  function readNonce() {
    var match = /(?:^|[#&])n=([0-9a-f]{8})(?:&|$)/i.exec(window.location.hash || "");
    return match ? match[1].toLowerCase() : null;
  }

  function applyNonce() {
    var nonce = readNonce();
    if (nonce !== acousticNonce) {
      acousticNonce = nonce;
      acState = "ready";
      statusShown = "";
      if (nonce !== null) loadAcoustic();
      wake();
    }
  }

  var acousticLoading = false;
  function loadAcoustic() {
    if (window.Acoustic || acousticLoading) {
      if (window.Acoustic) window.Acoustic.preload().catch(function () { /* не страшно */ });
      return;
    }
    acousticLoading = true;
    var tag = document.createElement("script");
    tag.src = "/acoustic.js";
    tag.onload = function () {
      acousticLoading = false;
      if (window.Acoustic) window.Acoustic.preload().catch(function () { /* не страшно */ });
    };
    tag.onerror = function () { acousticLoading = false; };
    document.head.appendChild(tag);
  }

  function finishAcoustic(text, state) {
    acState = state;
    statusShown = state === "ready" ? "ready" : text;   // step() не затирает текст результата
    setStatus(text);
  }

  /** Порт onTagDiscovered из Android: type 2 (токен) либо type 1 (логин+пароль), ответ — те же коды. */
  async function acousticMark(prepared) {
    var nonce = acousticNonce;
    var ok = await prepared;
    if (!ok) {
      finishAcoustic("Разрешите микрофон и нажмите на экран ещё раз", "ready");
      return;
    }
    var loginValue = store.get(KEY_LOGIN);
    var passwordValue = store.get(KEY_PASSWORD);
    var device = deviceId();

    for (var attempt = 0; attempt < 2; attempt++) {
      var saved = store.get(KEY_TOKEN);
      var type = saved ? "2" : "1";
      var data = saved ? saved + "\n" + device : loginValue + "\n" + passwordValue + "\n" + device;

      // сначала ультразвук, не услышали — тот же код слышимым сигналом
      var reply = await window.Acoustic.exchange(type, "u", nonce, data, 7000);
      if (!reply) reply = await window.Acoustic.exchange(type, "a", nonce, data, 9000);
      if (nonce !== acousticNonce) return;   // за это время приложили заново

      if (!reply) {
        finishAcoustic("Не получилось — нажмите на экран ещё раз", "ready");
        return;
      }
      if (reply.code === AC_TOKEN && reply.value) {
        store.set(KEY_TOKEN, reply.value);
        finishAcoustic("Готово! Вы отмечены", "done");
        return;
      }
      if (reply.code === AC_MARKED) {
        finishAcoustic(reply.value ? "Вы отмечены — " + reply.value : "Вы отмечены", "done");
        return;
      }
      if (reply.code === AC_RELOGIN) {
        try { window.localStorage.removeItem(KEY_TOKEN); } catch (e) { /* не страшно */ }
        continue;            // сессия устарела — этим же касанием перелогинимся
      }
      if (reply.code === AC_DEVICE_BLOCKED) {
        finishAcoustic("Аккаунт привязан к другому телефону", "done");
        return;
      }
      if (reply.code === AC_BAD_CREDENTIALS) {
        finishAcoustic("НЕВЕРНЫЙ ЛОГИН ИЛИ ПАРОЛЬ — введите другие и приложите телефон ещё раз", "done");
        showLogin();
        return;
      }
      if (reply.code === AC_STALE_TAP) {
        finishAcoustic("Приложите телефон ещё раз", "done");
        return;
      }
      finishAcoustic("Не получилось, попробуйте ещё раз", "ready");   // 4: перекличка не идёт или ПК недоступен
      return;
    }
    finishAcoustic("Не получилось, попробуйте ещё раз", "ready");
  }

  // Звук в Safari — только после нажатия: нажатие на экран и есть «приложил».
  document.addEventListener("click", function (event) {
    if (acousticNonce === null || acState !== "ready") return;
    if (!loginPanel.hidden) return;
    if (!store.get(KEY_LOGIN) || !store.get(KEY_PASSWORD)) return;
    if (!window.Acoustic) { loadAcoustic(); return; }
    acState = "busy";
    statusShown = "busy";
    var prepared = window.Acoustic.prepare();      // синхронно, внутри нажатия
    setStatus("Держите телефоны рядом…");
    acousticMark(prepared);
  });

  window.addEventListener("hashchange", applyNonce);

  // ------------------------------------------------------------------ жизненный цикл

  var wakeLock = null;

  /** FLAG_KEEP_SCREEN_ON: экран не гаснет, пока QR на виду (нужен HTTPS). */
  function holdScreen() {
    try {
      if (!("wakeLock" in navigator) || wakeLock) return;
      navigator.wakeLock.request("screen").then(function (lock) {
        wakeLock = lock;
        lock.addEventListener("release", function () { wakeLock = null; });
      }, function () { /* не разрешили — не страшно */ });
    } catch (e) { /* нет поддержки */ }
  }

  function releaseScreen() {
    try {
      if (wakeLock) wakeLock.release();
    } catch (e) { /* уже отпущен */ }
    wakeLock = null;
  }

  /** onStart */
  function start() {
    if (running) return;
    running = true;
    generation++;
    holdScreen();
    loop(generation);
  }

  /** onStop */
  function stop() {
    running = false;
    generation++;
    wake();
    releaseScreen();
  }

  document.addEventListener("visibilitychange", function () {
    if (document.hidden) stop(); else start();
  });
  window.addEventListener("pageshow", function () {
    if (!document.hidden) start();
  });

  // ------------------------------------------------------------------ события

  loginButton.addEventListener("click", function () {
    var loginValue = loginInput.value.trim();
    var passwordValue = passwordInput.value;
    if (!loginValue || !passwordValue) {
      toast("Введите логин и пароль");
      return;
    }
    store.set(KEY_LOGIN, loginValue);
    store.set(KEY_PASSWORD, passwordValue);
    try { window.localStorage.removeItem(KEY_TOKEN); } catch (e) { /* не страшно */ }
    if (acousticNonce !== null && acState === "done") { acState = "ready"; statusShown = ""; }
    token = null;
    try { passwordInput.blur(); loginInput.blur(); } catch (e) { /* не страшно */ }
    wake();
  });

  // actionNext / actionDone
  loginInput.addEventListener("keydown", function (event) {
    if (event.key === "Enter") {
      event.preventDefault();
      passwordInput.focus();
    }
  });
  passwordInput.addEventListener("keydown", function (event) {
    if (event.key === "Enter") {
      event.preventDefault();
      loginButton.click();
    }
  });

  // Смена студента — только долгим нажатием на статус (чтобы не открыть случайно).
  var pressTimer = null;
  function cancelPress() {
    if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
  }
  statusText.addEventListener("pointerdown", function () {
    cancelPress();
    pressTimer = setTimeout(function () {
      pressTimer = null;
      showLogin();
    }, LONG_PRESS_MS);
  });
  ["pointerup", "pointercancel", "pointerleave", "pointermove"].forEach(function (name) {
    statusText.addEventListener(name, cancelPress);
  });
  document.addEventListener("contextmenu", function (event) {
    if (event.target === statusText) event.preventDefault();
  });
  // :active на iOS работает только при наличии touch-обработчика
  document.addEventListener("touchstart", function () { /* нужен для :active */ }, true);

  // ------------------------------------------------------------------ офлайн-режим

  /** Service Worker — только по HTTPS (иначе браузер его не даёт). */
  if ("serviceWorker" in navigator && window.isSecureContext) {
    window.addEventListener("load", function () {
      navigator.serviceWorker.register("/sw.js").catch(function () {
        /* сертификат не доверенный или HTTP — работаем без офлайн-кэша */
      });
      // просим систему не вытеснять данные приложения
      try {
        if (navigator.storage && navigator.storage.persist) navigator.storage.persist();
      } catch (e) { /* не страшно */ }
    });
  }

  // ------------------------------------------------------------------ старт

  deviceId();
  applyNonce();
  if (!document.hidden) start();
})();
