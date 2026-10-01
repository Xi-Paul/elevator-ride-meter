// 승강기 주행 계측 — 서비스 워커
// 빌드 시 __VERSION__ 이 커밋 해시로 치환된다. 캐시 이름이 바뀌면 옛 캐시는 폐기된다.
const VERSION = '__VERSION__';
const CACHE = 'ride-meter-' + VERSION;
const ASSETS = ['./', './index.html', './manifest.json', './icon.svg'];

self.addEventListener('install', e => {
  // 새 워커를 즉시 대기 상태로 (업데이트 배너에서 사용자가 적용을 누르면 활성화)
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', e => {
  if (e.data === 'skipWaiting') self.skipWaiting();
  if (e.data === 'version') e.source.postMessage({ version: VERSION });
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  // version.json 은 항상 네트워크 우선 (업데이트 감지용)
  if (url.pathname.endsWith('version.json')) {
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
    return;
  }
  // 그 외는 캐시 우선 + 백그라운드 갱신 → 승강로처럼 전파가 없는 곳에서도 동작
  e.respondWith(
    caches.match(e.request).then(hit => {
      const net = fetch(e.request).then(res => {
        if (res && res.status === 200) caches.open(CACHE).then(c => c.put(e.request, res.clone()));
        return res;
      }).catch(() => hit);
      return hit || net;
    })
  );
});
