"use strict";

var CACHE = "visits11-v2";

var ASSETS = [
  "/",
  "/index.html",
  "/qr.js",
  "/app.js",
  "/style.css",
  "/manifest.json",
  "/fonts/Manrope-Regular.woff2",
  "/fonts/Manrope-Medium.woff2",
  "/fonts/Manrope-SemiBold.woff2",
  "/fonts/Manrope-Bold.woff2",
  "/icons/logo.png",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/apple-touch-icon.png",
  "/icons/favicon.ico"
];

self.addEventListener("install", function (event) {
  event.waitUntil(
    caches.open(CACHE).then(function (cache) {
      return cache.addAll(ASSETS.map(function (url) {
        return new Request(url, { cache: "reload" });
      }));
    }).then(function () {
      return self.skipWaiting();
    })
  );
});

self.addEventListener("activate", function (event) {
  event.waitUntil(
    caches.keys().then(function (names) {
      return Promise.all(names.map(function (name) {
        if (name.indexOf("visits11-") === 0 && name !== CACHE) return caches.delete(name);
        return null;
      }));
    }).then(function () {
      return self.clients.claim();
    })
  );
});

self.addEventListener("fetch", function (event) {
  var request = event.request;
  if (request.method !== "GET") return;

  var url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.indexOf("/api/") === 0) return;

  event.respondWith(fromCache(request, url));
});

function fromCache(request, url) {
  return caches.open(CACHE).then(function (cache) {
    return cache.match(request, { ignoreSearch: true }).then(function (hit) {
      if (hit) return hit;

      var isNavigation = request.mode === "navigate";
      if (isNavigation && (url.pathname === "/" || url.pathname === "/index.html")) {
        return cache.match("/index.html");
      }

      return fetch(request).catch(function (error) {
        if (isNavigation) {
          return cache.match("/index.html").then(function (page) {
            if (page) return page;
            throw error;
          });
        }
        throw error;
      });
    });
  });
}
