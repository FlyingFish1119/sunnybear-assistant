// SunnyBear 助手 Service Worker
// 仅用于启用 PWA 安装（独立窗口 / 添加到主屏幕）。
// 不做资源缓存，避免本地改动静态文件后仍命中旧缓存。

self.addEventListener('install', () => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

// 存在 fetch 监听即可满足浏览器的“可安装”条件；
// 不调用 respondWith，请求照常走网络，不拦截、不缓存。
self.addEventListener('fetch', () => {});
