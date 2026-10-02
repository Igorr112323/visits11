/* ==========================================================================
   КубГАУ · Отметка — PWA студента (iPhone/Safari)
   --------------------------------------------------------------------------
   Логика полностью повторяет android/student (MainActivity.java):
     • вход по логину и паролю  → POST /api/login  {login, password, device}
     • отметка кнопкой          → POST /api/nfc_mark {token, device}
     • проверка связи с ПК      → GET  /api/ping   каждые 5 секунд
     • запасной путь            → GET  /api/qr?token=  (код для камеры преподавателя)
   Адрес ПК берётся из адресной строки (window.location) — то же, чем открыта
   страница: http://192.168.1.5:8090 и http://visits11.local:8090 работают сами.
   Никаких внешних запросов: только этот же хост. Без Service Worker, без HTTPS.
   ========================================================================== */

(function () {
  "use strict";

  // ------------------------------------------------------------------ константы

  var PING_MS = 5000;        // как в Android: опрос ПК раз в 5 секунд
  var TIMEOUT_MS = 3000;
  var QR_EVERY_MS = 6000;    // QR на ПК живёт ~10 секунд, забираем чуть чаще
  var TOAST_MS = 3000;
  var SUCCESS_MS = 3000;
  var HOST_APP = "visits11-server"; // ответ /api/ping — признак «это наш ПК»

  // Тексты — те же, что в Android-приложении студента.
  var TEXT = {
    needLogin: "Введите логин и пароль",
    wrong: "НЕВЕРНЫЙ ЛОГИН ИЛИ ПАРОЛЬ",
    device: "Этот телефон привязан к другому аккаунту",
    expired: "Сессия истекла",
    noPc: "Нет связи с ПК",
    rollcall: "Перекличка не идёт",
    marked: "Вы отмечены"
  };

  var STORE = {
    device: "visits11.device",
    token: "visits11.token",
    name: "visits11.name",
    login: "visits11.login"
  };

  // ------------------------------------------------------------------ хранилище

  var store = {
    get: function (key) {
      try { return window.localStorage.getItem(key); } catch (e) { return null; }
    },
    set: function (key, value) {
      try { window.localStorage.setItem(key, value); return true; } catch (e) { return false; }
    },
    del: function (key) {
      try { window.localStorage.removeItem(key); } catch (e) { /* приватный режим */ }
    }
  };

  /** Уникальный ID устройства: 16 hex-символов, генерируется один раз и навсегда. */
  function deviceId() {
    var saved = store.get(STORE.device);
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
    store.set(STORE.device, value);
    return value;
  }

  var token = function () { return store.get(STORE.token) || ""; };
  var studentName = function () { return store.get(STORE.name) || ""; };

  // ------------------------------------------------------------------ адрес ПК

  /** Хост, с которого открыта страница: «192.168.1.5:8090», «visits11.local:8090». */
  function hostAddress() {
    var host = window.location.hostname;
    if (!host) return "";
    return window.location.port ? host + ":" + window.location.port : host;
  }

  function apiUrl(path) {
    return window.location.protocol + "//" + window.location.host + path;
  }

  /** Страница открыта с ПК (http/https и осмысленный адрес), а не из файла. */
  function pageServedByPc() {
    var protocol = window.location.protocol;
    return (protocol === "http:" || protocol === "https:") && !!window.location.hostname;
  }

  // ------------------------------------------------------------------ сеть

  /** fetch с таймаутом: всегда отдаёт Response либо null (нет связи). */
  function fetchWithTimeout(url, options, timeoutMs) {
    return new Promise(function (resolve) {
      var finished = false;
      var timer = null;
      var controller = null;

      function finish(value) {
        if (finished) return;
        finished = true;
        if (timer) clearTimeout(timer);
        resolve(value);
      }

      var opts = {};
      for (var key in options) {
        if (Object.prototype.hasOwnProperty.call(options, key)) opts[key] = options[key];
      }

      try {
        if (typeof window.AbortController === "function") controller = new window.AbortController();
      } catch (e) { controller = null; }
      if (controller) opts.signal = controller.signal;

      timer = setTimeout(function () {
        if (controller) {
          try { controller.abort(); } catch (e) { /* уже завершился */ }
        }
        setTimeout(function () { finish(null); }, 40);
      }, timeoutMs || TIMEOUT_MS);

      var request = null;
      try {
        request = window.fetch ? window.fetch(url, opts) : null;
      } catch (e) {
        request = null; // старый браузер без fetch — считаем это «нет связи»
      }
      if (!request || typeof request.then !== "function") {
        finish(null);
        return;
      }
      request.then(function (response) {
        finish(response);
      }, function () {
        finish(null); // обрыв сети, CORS, таймаут — всё это «нет связи»
      });
    });
  }

  function readJson(response) {
    return response.text().then(function (text) {
      try { return JSON.parse(text); } catch (e) { return null; }
    }, function () { return null; });
  }

  /** POST на ПК. Результат: {data} либо {network:true}. */
  function apiPost(path, payload, timeoutMs) {
    return fetchWithTimeout(apiUrl(path), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      cache: "no-store"
    }, timeoutMs || TIMEOUT_MS).then(function (response) {
      if (!response || response.status !== 200) return { network: true };
      return readJson(response).then(function (data) {
        if (!data) return { network: true };
        return { data: data };
      });
    });
  }

  /** GET /api/ping — ПК отвечает «visits11-server». */
  function pingOnce() {
    return fetchWithTimeout(apiUrl("/api/ping"), { cache: "no-store" }, TIMEOUT_MS)
      .then(function (response) {
        if (!response || response.status !== 200) return false;
        return response.text().then(function (text) {
          return text.indexOf(HOST_APP) >= 0;
        }, function () { return false; });
      });
  }

  /** POST /api/login → {ok,name,token} / {device:true} / отказ. */
  function loginOnPc(login, password) {
    return apiPost("/api/login", {
      login: login,
      password: password,
      device: deviceId()
    }, 6000).then(function (result) {
      if (result.network) return { network: true };
      var data = result.data;
      if (data.ok === true && data.token) {
        return { ok: true, token: String(data.token), name: data.name ? String(data.name) : "" };
      }
      if (data.device === true) return { device: true };
      return { rejected: true };
    });
  }

  /** POST /api/nfc_mark → {ok,name} / {relogin} / {device:true} / «не получилось». */
  function markOnPc() {
    return apiPost("/api/nfc_mark", {
      token: token(),
      device: deviceId()
    }, 6000).then(function (result) {
      if (result.network) return { network: true };
      var data = result.data;
      if (data.ok === true) return { ok: true, name: data.name ? String(data.name) : "" };
      if (data.relogin === true) return { relogin: true };
      if (data.device === true) return { device: true };
      return { failed: true };
    });
  }

  /** GET /api/qr?token= — PNG кода для камеры преподавателя (запасной способ). */
  function fetchQr() {
    return fetchWithTimeout(apiUrl("/api/qr?token=" + encodeURIComponent(token())),
      { cache: "no-store" }, 5000).then(function (response) {
      if (!response) return { kind: "error" };
      if (response.status === 204) return { kind: "inactive" };   // перекличка не идёт
      if (response.status === 401) return { kind: "stale" };      // сессия истекла
      if (response.status !== 200) return { kind: "error" };
      return response.blob().then(function (blob) {
        return { kind: "ok", blob: blob };
      }, function () { return { kind: "error" }; });
    });
  }

  // ------------------------------------------------------------------ вибрация

  var audioContext = null;

  /**
   * Попытка «вибрации» на iPhone. Apple не даёт веб-странице доступ к тактильной
   * отдаче: navigator.vibrate() в Safari не реализован, а Web Audio на iOS звучит,
   * но не вибрирует. Обе попытки обёрнуты в try/catch — если ничего не вышло,
   * остаётся визуальная пульсация галочки (см. style.css, @keyframes pulse).
   */
  function tryVibration() {
    // 1. Стандартный API: на Android Chrome сработает, на iOS Safari — нет.
    var vibrated = false;
    try {
      if (typeof navigator.vibrate === "function") {
        vibrated = navigator.vibrate([100, 50, 100]) === true;
      }
    } catch (e) { vibrated = false; }

    // 2. Web Audio: очень низкая частота и минимальная громкость.
    var audio = tryAudioHaptic();
    return vibrated || audio;
  }

  function tryAudioHaptic() {
    try {
      var Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return false;
      if (!audioContext) audioContext = new Ctx();
      if (audioContext.state === "suspended" && audioContext.resume) {
        // возобновляем после жеста пользователя (обещание намеренно не ждём)
        var resumed = audioContext.resume();
        if (resumed && resumed.then) resumed.then(function () {}, function () {});
      }

      var now = audioContext.currentTime;
      var duration = 0.12;
      var oscillator = audioContext.createOscillator();
      var gain = audioContext.createGain();
      oscillator.type = "sine";
      oscillator.frequency.setValueAtTime(0.001, now); // инфразвук: динамик этого не воспроизводит
      gain.gain.setValueAtTime(0.0001, now);           // тише некуда — вдруг отзовётся тактильно
      gain.gain.exponentialRampToValueAtTime(0.00002, now + duration);
      oscillator.connect(gain);
      gain.connect(audioContext.destination);
      oscillator.start(now);
      oscillator.stop(now + duration);
      return true;
    } catch (e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ уведомления

  function isStandalone() {
    try {
      if (window.navigator.standalone === true) return true;
      if (window.matchMedia && window.matchMedia("(display-mode: standalone)").matches) return true;
    } catch (e) { /* нет matchMedia */ }
    return false;
  }

  /**
   * Системные уведомления (Notification API). На iOS они работают только для PWA,
   * добавленного на экран «Домой», и только по HTTPS. Наш сервер отдаёт HTTP —
   * поэтому в Safari это, скорее всего, не сработает, и всё остаётся на in-app
   * баннерах. Пробуем молча, без ошибок в консоли.
   */
  function askNotificationPermission() {
    try {
      if (!("Notification" in window)) return;
      if (window.isSecureContext !== true) return; // по HTTP запрос не имеет смысла
      if (window.Notification.permission !== "default") return;
      var request = window.Notification.requestPermission();
      if (request && request.then) request.then(function () {}, function () {});
    } catch (e) { /* iOS без HTTPS — ожидаемо */ }
  }

  function showSystemNotification(title, body) {
    try {
      if (!("Notification" in window)) return false;
      if (window.isSecureContext !== true) return false;
      if (window.Notification.permission !== "granted") return false;
      if (!isStandalone()) return false;
      var notification = new window.Notification(title, {
        body: body || "",
        icon: "icons/icon-192.png"
      });
      return !!notification;
    } catch (e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ элементы

  var el = {};

  function cacheElements() {
    [
      "toasts", "pcStatus", "pcDot", "pcText",
      "screenLogin", "screenMark", "screenSuccess",
      "loginForm", "loginInput", "passwordInput", "loginButton", "loginHint",
      "helloText", "markButton", "rollcallBadge", "markHint",
      "qrCard", "qrImage", "successCheck", "successTitle", "successName",
      "logoutButton"
    ].forEach(function (id) {
      el[id] = document.getElementById(id);
    });
  }

  // ------------------------------------------------------------------ состояние

  var state = {
    online: false,
    onlineKnown: false,
    onMarkScreen: false,
    busy: false,
    loggingIn: false,
    offlineChecks: 0,
    lastQrAt: 0,
    qrUrl: "",
    successTimer: null,
    rollcallOn: null,
    lastOfflineToast: 0,
    pingTimer: null
  };

  // ------------------------------------------------------------------ баннеры

  function nextFrame(callback) {
    if (typeof window.requestAnimationFrame === "function") window.requestAnimationFrame(callback);
    else setTimeout(callback, 16);
  }

  /** Внутренний баннер сверху экрана: success — зелёный, error — красный, info — фиолетовый. */
  function toast(type, title, text, ms) {
    if (!el.toasts) return;
    try {
      while (el.toasts.children.length >= 3) {
        el.toasts.removeChild(el.toasts.firstChild);
      }

      var node = document.createElement("div");
      node.className = "toast toast-" + type;

      var icon = document.createElement("div");
      icon.className = "toast-icon";
      icon.textContent = type === "success" ? "✓" : (type === "error" ? "!" : "i");

      var body = document.createElement("div");
      body.className = "toast-body";
      var titleNode = document.createElement("div");
      titleNode.className = "toast-title";
      titleNode.textContent = title;
      body.appendChild(titleNode);
      if (text) {
        var textNode = document.createElement("div");
        textNode.className = "toast-text";
        textNode.textContent = text;
        body.appendChild(textNode);
      }

      node.appendChild(icon);
      node.appendChild(body);
      el.toasts.appendChild(node);

      // появление: translateY(-120%) → 0, держим 3 секунды, уезжает обратно
      nextFrame(function () { node.classList.add("is-visible"); });

      setTimeout(function () {
        node.classList.remove("is-visible");
        node.classList.add("is-leaving");
        setTimeout(function () {
          if (node.parentNode) node.parentNode.removeChild(node);
        }, 420);
      }, ms || TOAST_MS);
    } catch (e) { /* баннеры не должны ломать отметку */ }
  }

  // ------------------------------------------------------------------ экраны

  function showScreen(name) {
    if (el.screenLogin) el.screenLogin.hidden = name !== "login";
    if (el.screenMark) el.screenMark.hidden = name !== "mark";
    if (el.screenSuccess) el.screenSuccess.hidden = name !== "success";
    state.onMarkScreen = name === "mark";
    if (name !== "mark") hideQr();
    updateMarkButton();
  }

  function updateMarkButton() {
    if (!el.markButton) return;
    var enabled = state.onMarkScreen && state.online && !state.busy && !!token();
    el.markButton.disabled = !enabled;
  }

  function setOnline(online) {
    state.online = online;
    state.onlineKnown = true;
    if (el.pcStatus) {
      el.pcStatus.classList.toggle("is-online", online);
      el.pcStatus.classList.toggle("is-offline", !online);
    }
    if (el.pcText) el.pcText.textContent = online ? "ПК онлайн" : TEXT.noPc;
    updateMarkButton();
  }

  function setStatusUnknown() {
    state.onlineKnown = false;
    if (el.pcStatus) el.pcStatus.classList.remove("is-online", "is-offline");
    if (el.pcText) el.pcText.textContent = "Поиск ПК…";
  }

  /** «Нет связи с ПК» баннером; force — когда это ответ на действие студента. */
  function reportOffline(force) {
    if (!force && Date.now() - state.lastOfflineToast <= 60000) return;
    state.lastOfflineToast = Date.now();
    toast("error", TEXT.noPc, "Проверьте, что ПК включён и телефон в той же сети Wi-Fi.");
  }

  function showName() {
    var name = studentName();
    if (el.helloText) el.helloText.textContent = name ? "Здравствуйте, " + name : "Студент";
  }

  function setRollcall(on) {
    state.rollcallOn = on;
    if (!el.rollcallBadge) return;
    el.rollcallBadge.hidden = false;
    el.rollcallBadge.textContent = on ? "Перекличка идёт" : TEXT.rollcall;
    el.rollcallBadge.classList.toggle("is-on", on === true);
  }

  function showQr(blob) {
    if (!el.qrCard || !el.qrImage) return;
    try {
      if (state.qrUrl) {
        try { URL.revokeObjectURL(state.qrUrl); } catch (e) { /* уже отозван */ }
        state.qrUrl = "";
      }
      if (window.URL && typeof window.URL.createObjectURL === "function") {
        state.qrUrl = window.URL.createObjectURL(blob);
        el.qrImage.src = state.qrUrl;
        el.qrCard.hidden = false;
      }
    } catch (e) { /* QR — необязательный запасной способ */ }
  }

  function hideQr() {
    if (state.qrUrl) {
      try { URL.revokeObjectURL(state.qrUrl); } catch (e) { /* ок */ }
      state.qrUrl = "";
    }
    if (el.qrImage) el.qrImage.removeAttribute("src");
    if (el.qrCard) el.qrCard.hidden = true;
  }

  function goToMarkScreen() {
    showName();
    showScreen("mark");
    state.lastQrAt = 0;
    if (state.online) refreshQr();
  }

  function goToLoginScreen(reason) {
    store.del(STORE.token);
    hideQr();
    if (el.passwordInput) el.passwordInput.value = "";
    if (el.loginInput) el.loginInput.value = store.get(STORE.login) || "";
    showScreen("login");
    if (reason) {
      toast("error", reason, "Войдите заново — логин и пароль те же.");
    }
  }

  // ------------------------------------------------------------------ сценарии

  function onLoginSubmit(event) {
    if (event) event.preventDefault();
    if (state.loggingIn) return;

    var login = el.loginInput ? el.loginInput.value.trim() : "";
    var password = el.passwordInput ? el.passwordInput.value : "";
    if (!login || !password) {
      toast("error", TEXT.needLogin, "Так же, как в списке группы у преподавателя.");
      return;
    }

    // Разрешение на системные уведомления просим при первом входе — строго
    // в обработчике касания, иначе Safari его не покажет.
    askNotificationPermission();

    state.loggingIn = true;
    if (el.loginButton) el.loginButton.disabled = true;
    store.set(STORE.login, login);
    store.del(STORE.token);

    loginOnPc(login, password).then(function (result) {
      state.loggingIn = false;
      if (el.loginButton) el.loginButton.disabled = false;

      if (result.network) {
        setOnline(false);
        reportOffline(true);
        return;
      }
      if (result.device) {
        toast("error", TEXT.device, "Один аккаунт — один телефон. Обратитесь к преподавателю.");
        return;
      }
      if (result.rejected) {
        toast("error", TEXT.wrong, "Проверьте логин и пароль и попробуйте снова.");
        return;
      }

      store.set(STORE.token, result.token);
      store.set(STORE.name, result.name);
      setOnline(true);
      goToMarkScreen();
      toast("success", result.name ? "Здравствуйте, " + result.name : "Вход выполнен",
        "Теперь нажмите «Отметиться».");
    });
  }

  function onMarkClick() {
    if (state.busy) return;

    if (!token()) {
      goToLoginScreen(TEXT.expired);
      return;
    }
    if (!state.online) {
      toast("error", TEXT.noPc, "Кнопка отметки включится, когда ПК снова ответит.");
      return;
    }

    state.busy = true;
    updateMarkButton();

    markOnPc().then(function (result) {
      state.busy = false;

      if (result.network) {
        setOnline(false);
        updateMarkButton();
        state.lastOfflineToast = Date.now();
        toast("error", TEXT.noPc, "Отметка не ушла — нажмите ещё раз, когда связь вернётся.");
        return;
      }
      if (result.relogin) {
        updateMarkButton();
        goToLoginScreen(TEXT.expired);
        return;
      }
      if (result.device) {
        updateMarkButton();
        toast("error", TEXT.device, "Отметка с этого телефона отклонена.");
        return;
      }
      if (result.failed) {
        updateMarkButton();
        setRollcall(false);
        toast("info", TEXT.rollcall, "Отметка засчитывается только во время переклички.");
        return;
      }

      celebrate(result.name);
    });
  }

  /** Успешная отметка: вибрация (чем получится), баннер, системное уведомление, галочка. */
  function celebrate(name) {
    var who = name || studentName();
    var title = who ? TEXT.marked + ", " + who : TEXT.marked;

    tryVibration();
    setRollcall(true);
    toast("success", title, "Отметка сохранена на ПК преподавателя.");
    showSystemNotification(TEXT.marked, who || "Отметка сохранена");

    if (el.successName) el.successName.textContent = who;
    if (el.successTitle) el.successTitle.textContent = TEXT.marked;
    showScreen("success");

    // Пульсация 1 → 1.15 → 1 (0.4 с, 2 раза) — визуальная замена вибрации.
    if (el.successCheck) {
      el.successCheck.classList.remove("is-pulsing");
      void el.successCheck.offsetWidth; // перезапуск CSS-анимации
      el.successCheck.classList.add("is-pulsing");
    }

    if (state.successTimer) clearTimeout(state.successTimer);
    state.successTimer = setTimeout(function () {
      state.successTimer = null;
      goToMarkScreen();
    }, SUCCESS_MS);
  }

  function onLogoutClick() {
    store.del(STORE.token);
    store.del(STORE.name);
    hideQr();
    if (el.passwordInput) el.passwordInput.value = "";
    if (el.loginInput) el.loginInput.value = "";
    showScreen("login");
    toast("info", "Смена студента", "Введите логин и пароль другого студента.");
  }

  // ------------------------------------------------------------------ опрос ПК

  /** Один цикл: ping → QR/проверка сессии. */
  function tick() {
    if (document.hidden) return;

    pingOnce().then(function (online) {
      if (online) {
        state.offlineChecks = 0;
        setOnline(true);
      } else {
        state.offlineChecks++;
        setOnline(false);
        reportOffline(false); // не чаще раза в минуту, чтобы не заспамить баннерами
        return;
      }

      if (!token() || !state.onMarkScreen) return;
      refreshQr();
    });
  }

  /**
   * QR — запасной способ: код показывает, кто перед камерой. Он же сообщает,
   * идёт ли перекличка (204) и жив ли токен сессии (401).
   */
  function refreshQr() {
    if (!state.online || !token() || !state.onMarkScreen) return;
    if (Date.now() - state.lastQrAt < QR_EVERY_MS) return;
    state.lastQrAt = Date.now();

    fetchQr().then(function (result) {
      if (result.kind === "stale") {
        goToLoginScreen(TEXT.expired);
        return;
      }
      if (result.kind === "error") {
        setOnline(false);
        return;
      }
      if (result.kind === "inactive") {
        setRollcall(false);
        hideQr();
        return;
      }
      setRollcall(true);
      showQr(result.blob);
    });
  }

  function showAddressHint() {
    if (!el.loginHint) return;
    if (!pageServedByPc()) {
      el.loginHint.textContent = "Страница открыта не с ПК. Откройте адрес ПК, например http://192.168.1.5:8090";
      return;
    }
    el.loginHint.textContent = "ПК: " + hostAddress() + " · отметка работает без интернета, только по Wi-Fi";
  }

  // ------------------------------------------------------------------ запуск

  function bindEvents() {
    if (el.loginForm) el.loginForm.addEventListener("submit", onLoginSubmit);
    if (el.markButton) el.markButton.addEventListener("click", onMarkClick);
    if (el.logoutButton) el.logoutButton.addEventListener("click", onLogoutClick);

    // Тап по индикатору — сразу перепроверить связь и показать адрес ПК.
    if (el.pcStatus) {
      el.pcStatus.addEventListener("click", function () {
        toast("info", "Проверка связи", "ПК: " + (hostAddress() || "адрес неизвестен"));
        tick();
      });
    }

    // Вернулись в приложение — сразу опрашиваем ПК, не ждём 5 секунд.
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) {
        state.lastQrAt = 0;
        tick();
      }
    });

    // Уход со страницы: освобождаем blob с QR.
    window.addEventListener("pagehide", hideQr);
  }

  function boot() {
    cacheElements();
    bindEvents();
    showAddressHint();
    deviceId(); // создаём ID устройства сразу — он должен быть стабильным

    setStatusUnknown();
    updateMarkButton();

    // Автологин: токен уже есть — сразу на экран отметки, минуя вход.
    if (token()) {
      showName();
      showScreen("mark");
    } else {
      if (el.loginInput) el.loginInput.value = store.get(STORE.login) || "";
      showScreen("login");
    }

    tick();
    state.pingTimer = setInterval(tick, PING_MS);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
