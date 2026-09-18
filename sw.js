/* 北京 5 日行程 · Service Worker
   策略：
   - 应用外壳（HTML/manifest/icon）预缓存，导航请求 network-first、离线回退缓存
   - Leaflet CDN 资源 cache-first（版本固定，可长期缓存）
   - 高德地图瓦片 cache-first + 容量上限（离线时已看过的区域仍可显示）
*/
const V = 'bj2026-v1';
const SHELL = `${V}-shell`;
const TILE  = `${V}-tile`;
const CDN   = `${V}-cdn`;

const SHELL_FILES = [
  './',
  './mobile.html',
  './index.html',
  './manifest.webmanifest',
  './icon.svg'
];

const TILE_MAX = 400;   // 瓦片缓存条目上限，超出按插入顺序淘汰

self.addEventListener('install', e => {
  e.waitUntil((async () => {
    const c = await caches.open(SHELL);
    // 逐个添加：任一文件缺失不至于让整个安装失败
    await Promise.all(SHELL_FILES.map(f => c.add(new Request(f, {cache:'reload'})).catch(() => {})));
    self.skipWaiting();
  })());
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(k => !k.startsWith(V)).map(k => caches.delete(k)));
    await self.clients.claim();
  })());
});

addEventListener('message', e => {
  if (e.data === 'skipWaiting') self.skipWaiting();
});

function isTile(url) {
  return /(^|\.)(is\.autonavi\.com|tile\.openstreetmap\.org)$/.test(url.hostname);
}

async function trimTiles() {
  const c = await caches.open(TILE);
  const keys = await c.keys();
  if (keys.length <= TILE_MAX) return;
  for (const k of keys.slice(0, keys.length - TILE_MAX)) await c.delete(k);
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;

  let url;
  try { url = new URL(req.url); } catch (_) { return; }
  if (!/^https?:$/.test(url.protocol)) return;

  // 1. 地图瓦片：cache-first，失败就让浏览器自己处理（离线时显示为空白而非报错）
  if (isTile(url)) {
    e.respondWith((async () => {
      const c = await caches.open(TILE);
      const hit = await c.match(req);
      if (hit) return hit;
      try {
        const res = await fetch(req);
        if (res && (res.ok || res.type === 'opaque')) {
          c.put(req, res.clone()).then(trimTiles).catch(() => {});
        }
        return res;
      } catch (err) {
        return new Response('', {status: 504, statusText: 'offline'});
      }
    })());
    return;
  }

  // 2. Leaflet CDN：cache-first
  if (url.hostname === 'unpkg.com') {
    e.respondWith((async () => {
      const c = await caches.open(CDN);
      const hit = await c.match(req);
      if (hit) return hit;
      try {
        const res = await fetch(req);
        if (res && res.ok) c.put(req, res.clone()).catch(() => {});
        return res;
      } catch (err) {
        return new Response('', {status: 504, statusText: 'offline'});
      }
    })());
    return;
  }

  // 3. 同源：导航请求 network-first（保证拿到最新行程），其余 cache-first
  if (url.origin === location.origin) {
    if (req.mode === 'navigate') {
      e.respondWith((async () => {
        try {
          const res = await fetch(req);
          const c = await caches.open(SHELL);
          c.put(req, res.clone()).catch(() => {});
          return res;
        } catch (err) {
          const hit = await caches.match(req)
            || await caches.match('./mobile.html')
            || await caches.match('./index.html');
          return hit || new Response('<h1>离线</h1><p>请连网后重新打开一次。</p>',
            {status: 200, headers: {'Content-Type': 'text/html; charset=utf-8'}});
        }
      })());
      return;
    }
    e.respondWith((async () => {
      const hit = await caches.match(req);
      if (hit) return hit;
      try {
        const res = await fetch(req);
        if (res && res.ok) (await caches.open(SHELL)).put(req, res.clone()).catch(() => {});
        return res;
      } catch (err) {
        return new Response('', {status: 504, statusText: 'offline'});
      }
    })());
  }
});
