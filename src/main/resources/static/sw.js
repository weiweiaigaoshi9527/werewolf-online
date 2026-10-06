/* 狼人杀 PWA Service Worker —— 网络优先、离线回退缓存，保证更新即时、断网可开。 */
const CACHE = 'ww-shell-v3';                 // 缓存名带版本，便于 activate 时清理旧版本，避免旧资源长期滞留

// 仅这些静态资源参与离线缓存写入；其余（接口 / 上传 / 下载 / 带 .zip、.db 的请求）一律不缓存
const CACHEABLE_PREFIXES = ['/css/', '/js/', '/icons/'];
const CACHEABLE_EXACT = ['/', '/index.html', '/manifest.json'];

function isCacheable(url) {
  const p = url.pathname;
  if (CACHEABLE_EXACT.includes(p)) return true;
  if (CACHEABLE_PREFIXES.some(pre => p.startsWith(pre))) return true;
  return false;
}

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (e) => e.waitUntil((async () => {
  // 清理所有非当前版本的缓存
  const keys = await caches.keys();
  await Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)));
  await self.clients.claim();
})()));

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  let url;
  try { url = new URL(req.url); } catch (_) { return; }
  if (url.origin !== self.location.origin) return;      // 不拦截跨域 / ws
  if (url.pathname === '/ws' || url.pathname === '/ws/voice') return;

  // 明确排除：接口、上传、下载、以及 .zip/.db 等大文件或数据文件，始终走网络且不做缓存
  const isExcluded = url.pathname.startsWith('/api/')
    || url.pathname.startsWith('/download/')
    || url.pathname.startsWith('/uploads/')
    || /\.(zip|db)$/i.test(url.pathname);
  if (isExcluded) return;

  const cacheable = isCacheable(url);

  e.respondWith(
    fetch(req)
      .then((res) => {
        // 只有白名单静态资源、且响应有效（ok 且 200）才写入缓存；非 200 一律不缓存
        if (cacheable && res && res.ok && res.status === 200) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      })
      .catch(async () => {
        // 离线回退：接口 / JSON 请求不允许返回 HTML（否则调用方会解析失败），直接让请求失败
        const accept = req.headers.get('accept') || '';
        if (url.pathname.startsWith('/api/') || accept.includes('application/json') || req.headers.get('x-requested-with') === 'XMLHttpRequest') {
          throw new Error('offline: api/json not available');
        }
        const hit = await caches.match(req);
        if (hit) return hit;
        // 仅对可缓存的静态资源回退首页，保证 PWA 离线仍能打开首页
        return cacheable ? caches.match('/') : Response.error();
      })
  );
});
