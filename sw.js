// Galla service worker: lets the app open with no internet.
// Bump VERSION whenever you upload a changed index.html or images.
const VERSION = "galla-v3";
const SHELL = [
  "./", "./index.html", "./manifest.webmanifest",
  "./assets/icon-96.png", "./assets/icon-192.png", "./assets/icon-512.png",
  "./assets/hero.jpg", "./assets/bike.webp", "./assets/auto.webp", "./assets/cab.webp"
];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  // Firebase / Firestore traffic is handled by Firestore's own offline cache.
  if (url.hostname.includes("googleapis.com") && !url.hostname.startsWith("fonts.")) return;
  if (url.hostname.includes("firebase")) return;
  const cacheable = url.origin === location.origin || url.hostname.startsWith("fonts.") || url.pathname.startsWith("/firebasejs/");

  // Pages: network first (so updates arrive), cache as fallback.
  if (e.request.mode === "navigate") {
    e.respondWith(fetch(e.request).then(r => { const copy = r.clone(); caches.open(VERSION).then(c => c.put("./index.html", copy)); return r; })
      .catch(() => caches.match("./index.html")));
    return;
  }
  // Everything else (images, fonts, the Firebase SDK files): cache first.
  e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request).then(r => {
    if (r.ok && cacheable) { const copy = r.clone(); caches.open(VERSION).then(c => c.put(e.request, copy)); }
    return r;
  })));
});
