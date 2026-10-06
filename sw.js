// Service worker: permite instalar la app y abrirla sin conexión.
// Los datos los guarda Firestore en el propio móvil; aquí solo se cachean los archivos de la app.
const VERSION = 'v7';
const SHELL = ['./', 'index.html', 'style.css', 'app.js', 'shot.js', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png'];
const CDN = ['www.gstatic.com', 'cdnjs.cloudflare.com', 'cdn.jsdelivr.net', 'fonts.googleapis.com', 'fonts.gstatic.com'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const sameOrigin = url.origin === self.location.origin;
  const cdn = CDN.includes(url.hostname);
  if (!sameOrigin && !cdn) return; // Firebase (datos y login) va siempre por red
  if (sameOrigin) {
    // Red primero, para recibir las actualizaciones; si no hay conexión, la copia guardada.
    e.respondWith(fetch(req, { cache: 'no-cache' }).then(r => { const copy = r.clone(); caches.open(VERSION).then(c => c.put(req, copy)); return r; })
      .catch(() => caches.match(req).then(r => r || caches.match('index.html'))));
  } else {
    // Librerías de CDN: copia guardada primero (no cambian, van con versión fija).
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(r => { const copy = r.clone(); caches.open(VERSION).then(c => c.put(req, copy)); return r; })));
  }
});
