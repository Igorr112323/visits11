(function () {
  "use strict";

  var POLL_MS = 5000;
  var NET_TIMEOUT_MS = 2500;
  var LONG_PRESS_MS = 500;
  var TOAST_MS = 2000;
  var HOST_APP = "visits11-server";

  var LOGIN_OK = 0;
  var LOGIN_REJECTED = 1;
  var LOGIN_NETWORK = 2;
  var LOGIN_DEVICE = 3;

  var QR_OK = 0;
  var QR_INACTIVE = 1;
  var QR_STALE = 2;
  var QR_ERROR = 3;

  var KEY_LOGIN = "visits11.login";
  var KEY_PASSWORD = "visits11.password";
  var KEY_DEVICE = "visits11.device";
  var KEY_QR_ID = "visits11.qrid";
  var KEY_QR_KEY = "visits11.qrkey";
  var OFFLINE_TICK_MS = 500;

  function $(id) { return document.getElementById(id); }

  var loginPanel = $("loginPanel");
  var loginInput = $("loginInput");
  var passwordInput = $("passwordInput");
  var loginButton = $("loginButton");
  var qrView = $("qrView");
  var statusText = $("statusText");
  var dot = $("dot");
  var toastView = $("toast");

  var store = {
    get: function (key) {
      try { return window.localStorage.getItem(key); } catch (e) { return null; }
    },
    set: function (key, value) {
      try { window.localStorage.setItem(key, value); } catch (e) {  }
    }
  };

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

  var toastTimer = null;
  var lastQrUrl = null;

  function toast(message) {
    toastView.textContent = message;
    toastView.classList.add("is-shown");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toastView.classList.remove("is-shown");
    }, TOAST_MS);
  }

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

  function showQrUrl(url) {
    qrView.src = url;
    qrView.hidden = false;
    statusText.hidden = true;
    loginPanel.hidden = true;
  }

  function dotGreen() { dot.classList.add("is-green"); }
  function dotRed() { dot.classList.remove("is-green"); }

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
          try { controller.abort(); } catch (e) {  }
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

  function ping() {
    return request("/api/ping", {}, NET_TIMEOUT_MS).then(function (response) {
      if (!response || response.status !== 200) return false;
      return response.text().then(function (text) {
        return text.indexOf(HOST_APP) >= 0;
      }, function () { return false; });
    });
  }

  var token = null;

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

  function fetchQrKey(session) {
    return request("/api/qrkey?token=" + encodeURIComponent(session), {}, NET_TIMEOUT_MS)
      .then(function (response) {
        if (!response || response.status !== 200) return null;
        return response.text().then(function (text) {
          try {
            var data = JSON.parse(text);
            if (data && data.ok && data.id > 0 && /^[0-9a-f]{64}$/.test(data.key)) return data;
          } catch (e) { return null; }
          return null;
        }, function () { return null; });
      });
  }

  var shownWindow = -1;

  function renderOfflineQr() {
    var studentId = parseInt(store.get(KEY_QR_ID), 10);
    var key = store.get(KEY_QR_KEY);
    if (!window.V11 || !(studentId > 0) || !key) return false;
    var now = Date.now();
    var current = window.V11.windowOf(now);
    if (current !== shownWindow || qrView.hidden) {
      try {
        showQrUrl(window.V11.svgUrl(window.V11.matrix(window.V11.payload(studentId, key, now))));
      } catch (e) {
        return false;
      }
      shownWindow = current;
    }
    return true;
  }

  function forgetQrKey() {
    store.set(KEY_QR_ID, "");
    store.set(KEY_QR_KEY, "");
    shownWindow = -1;
  }

  var running = false;
  var generation = 0;
  var wakeUp = null;
  var hostFound = false;

  function sleepQuietly(ms) {
    return new Promise(function (resolve) {
      var timer = setTimeout(function () { wakeUp = null; resolve(); }, ms);
      wakeUp = function () { clearTimeout(timer); wakeUp = null; resolve(); };
    });
  }

  function wake() {
    if (wakeUp) wakeUp();
  }

  async function step() {
    var savedLogin = store.get(KEY_LOGIN);
    var savedPassword = store.get(KEY_PASSWORD);

    if (!savedLogin || !savedPassword) {
      setStatus("Войдите по логину и паролю");
      showLogin();
      dotRed();
      return 1000;
    }

    if (renderOfflineQr()) {
      dotGreen();
      return OFFLINE_TICK_MS;
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

      hideLogin();
      var provisioned = await fetchQrKey(token);
      if (provisioned) {
        store.set(KEY_QR_ID, String(provisioned.id));
        store.set(KEY_QR_KEY, provisioned.key);
        return 50;
      }
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
      return 250;
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

  var wakeLock = null;

  function holdScreen() {
    try {
      if (!("wakeLock" in navigator) || wakeLock) return;
      navigator.wakeLock.request("screen").then(function (lock) {
        wakeLock = lock;
        lock.addEventListener("release", function () { wakeLock = null; });
      }, function () {  });
    } catch (e) {  }
  }

  function releaseScreen() {
    try {
      if (wakeLock) wakeLock.release();
    } catch (e) {  }
    wakeLock = null;
  }

  function start() {
    if (running) return;
    running = true;
    generation++;
    holdScreen();
    loop(generation);
  }

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

  loginButton.addEventListener("click", function () {
    var loginValue = loginInput.value.trim();
    var passwordValue = passwordInput.value;
    if (!loginValue || !passwordValue) {
      toast("Введите логин и пароль");
      return;
    }
    store.set(KEY_LOGIN, loginValue);
    store.set(KEY_PASSWORD, passwordValue);
    token = null;
    forgetQrKey();
    try { passwordInput.blur(); loginInput.blur(); } catch (e) {  }
    wake();
  });

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

  var pressTimer = null;
  function cancelPress() {
    if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
  }
  function armLongPress(element, action) {
    element.addEventListener("pointerdown", function () {
      cancelPress();
      pressTimer = setTimeout(function () {
        pressTimer = null;
        action();
      }, LONG_PRESS_MS);
    });
    ["pointerup", "pointercancel", "pointerleave", "pointermove"].forEach(function (name) {
      element.addEventListener(name, cancelPress);
    });
  }
  armLongPress(statusText, showLogin);
  armLongPress(qrView, function () {
    forgetQrKey();
    qrView.hidden = true;
    setStatus("Войдите по логину и паролю");
    token = null;
    showLogin();
  });
  document.addEventListener("contextmenu", function (event) {
    if (event.target === statusText || event.target === qrView) event.preventDefault();
  });

  document.addEventListener("touchstart", function () {  }, true);

  if ("serviceWorker" in navigator && window.isSecureContext) {
    window.addEventListener("load", function () {
      navigator.serviceWorker.register("/sw.js").catch(function () {

      });

      try {
        if (navigator.storage && navigator.storage.persist) navigator.storage.persist();
      } catch (e) {  }
    });
  }

  deviceId();
  if (!document.hidden) start();
})();
