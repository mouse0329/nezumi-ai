'use strict';

const CACHE_NAME = 'nezumi-ai-v4';
const STATIC_ASSETS = [
  './',
  './index.html',
  './setup.html',
  './style.css',
  './app.js',
  './inference.js',
  './model.js',
  './manifest.json',
  './nezumi-icon.svg',
  './LICENSE',
  './LGPL_LICENSE',
  './NOTICE',
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  const request = e.request;
  const url = new URL(request.url);

  if (request.method !== 'GET') return;

  // モデルファイル（大容量）はOPFSで管理するためCache Storageには入れない
  if (url.hostname.includes('huggingface.co') || url.pathname.endsWith('.task')) {
    return;
  }

  if (request.mode === 'navigate') {
    const pathname = url.pathname;
    if (pathname.endsWith('/setup') || pathname.endsWith('/setup/')) {
      e.respondWith(networkFirst(request, './setup.html'));
      return;
    }

    e.respondWith(networkFirst(request, './index.html'));
    return;
  }

  if (url.origin === self.location.origin) {
    e.respondWith(cacheFirst(request));
    return;
  }

  // MediaPipe GenAI runtime / wasm は一度オンラインで取得できたら以後オフラインでも使う
  if (url.hostname === 'cdn.jsdelivr.net' && url.pathname.includes('@mediapipe/tasks-genai')) {
    e.respondWith(cacheFirst(request));
  }
});

async function cacheFirst(request) {
  const cached = await caches.match(request);
  if (cached) return cached;

  const response = await fetch(request);
  if (response && response.ok) {
    const cache = await caches.open(CACHE_NAME);
    cache.put(request, response.clone());
  }
  return response;
}

async function networkFirst(request, fallbackUrl) {
  try {
    const response = await fetch(request);
    if (response && response.ok) {
      const cache = await caches.open(CACHE_NAME);
      cache.put(request, response.clone());
    }
    return response;
  } catch {
    return await caches.match(request) || await caches.match(fallbackUrl);
  }
}
