// 诊断模式注入脚本：在页面脚本运行前包装 WebSocket / EventSource 的消息回调，
// 每秒把消息频率、处理耗时、帧率等指标发给宿主。只计时、不改参数和返回值，异常照常抛出。
// 不采集域名、地址、消息内容和字段名，载荷只在本地解析出时间戳算延迟，只上报数值。
// 统计不到的情况：在 Web Worker 里收的消息、fetch / XHR 轮询。
(() => {
  'use strict';
  // Windows 版走 WebView2 的 chrome.webview，Android 版走 addWebMessageListener 注入的 yarnDiagHost
  const findHost = () => {
    const h = (window.chrome && window.chrome.webview) || window.yarnDiagHost;
    return h && typeof h.postMessage === 'function' ? h : null;
  };

  const REPORT_MS = 1000;
  const SAMPLE_MS = 500;              // 解析载荷时间戳的最短间隔，避免每条消息都 JSON.parse
  const SAMPLE_MAX_CHARS = 200000;
  const LOOP_TICK_MS = 100;
  const TS_KEY = /^ts$|time|stamp|date/i;

  const now = () => performance.now();
  const docId = Math.random().toString(36).slice(2, 10);
  const isTop = window.top === window;
  let seq = 0;

  const fresh = () => ({
    msgs: 0, bytes: 0, handlerMs: 0, handlerMax: 0,
    frames: 0, frameGapMax: 0, longTasks: 0, longTaskMs: 0, loopLagMax: 0,
    tsLag: null,
  });
  let win = fresh();

  // ---- 消息回调包装 ----

  const sockets = new Map();          // socket -> 'ws' | 'es'
  const wrapped = new WeakMap();      // 原监听器 -> 包装后的监听器
  let lastEvent = null;
  let lastSampleAt = -Infinity;

  function sizeOf(data) {
    if (typeof data === 'string') return data.length;
    if (data instanceof ArrayBuffer) return data.byteLength;
    if (typeof Blob !== 'undefined' && data instanceof Blob) return data.size;
    return 0;
  }

  function toMs(v) {
    let t = null;
    if (typeof v === 'number') t = v;
    else if (typeof v === 'string' && v.length <= 40) {
      t = /^\d{10,13}$/.test(v) ? Number(v) : (/\d{4}-\d{2}-\d{2}/.test(v) ? Date.parse(v) : null);
    }
    if (t == null || !isFinite(t)) return null;
    if (t > 1e9 && t < 1e10) t *= 1000;           // 秒级时间戳
    if (t < 1e12 || t > 1e13) return null;
    return Math.abs(Date.now() - t) < 7 * 86400000 ? t : null;
  }

  function findTs(o, depth) {
    if (!o || typeof o !== 'object') return null;
    if (Array.isArray(o)) return depth < 2 ? findTs(o[0], depth + 1) : null;
    const keys = Object.keys(o).slice(0, 50);
    for (const k of keys) {
      if (!TS_KEY.test(k)) continue;
      const t = toMs(o[k]);
      if (t != null) return [k, t];
    }
    if (depth >= 2) return null;
    for (const k of keys) {
      const v = o[k];
      if (v && typeof v === 'object') {
        const r = findTs(v, depth + 1);
        if (r) return [k + '.' + r[0], r[1]];
      }
    }
    return null;
  }

  function sample(data) {
    const t = now();
    if (t - lastSampleAt < SAMPLE_MS) return;
    lastSampleAt = t;
    if (typeof data !== 'string' || data.length > SAMPLE_MAX_CHARS) return;
    const c = data.charCodeAt(0);
    if (c !== 123 && c !== 91) return;            // 只看 { 或 [ 开头的 JSON
    try {
      const r = findTs(JSON.parse(data), 0);
      if (r) {
        win.tsLag = Date.now() - r[1];
      }
    } catch (e) {
      // 不是 JSON，忽略
    }
  }

  function record(ev, ms) {
    win.handlerMs += ms;
    if (ms > win.handlerMax) win.handlerMax = ms;
    // 同一条消息可能有多个监听器，消息数和数据量只算一次
    if (ev === lastEvent) return;
    lastEvent = ev;
    win.msgs++;
    if (ev && 'data' in ev) {
      win.bytes += sizeOf(ev.data);
      sample(ev.data);
    }
  }

  function wrap(fn) {
    const callable = typeof fn === 'function';
    if (!callable && !(fn && typeof fn.handleEvent === 'function')) return fn;
    let w = wrapped.get(fn);
    if (w) return w;
    w = function (ev) {
      const t = now();
      try {
        return callable ? fn.call(this, ev) : fn.handleEvent(ev);
      } finally {
        record(ev, now() - t);
      }
    };
    wrapped.set(fn, w);
    return w;
  }

  function patch(Ctor, kind, isData) {
    if (typeof Ctor !== 'function' || !Ctor.prototype) return;
    const proto = Ctor.prototype;
    const nativeAdd = EventTarget.prototype.addEventListener;
    const nativeRemove = EventTarget.prototype.removeEventListener;
    const track = (s) => { if (!sockets.has(s)) sockets.set(s, kind); };

    Object.defineProperty(proto, 'addEventListener', {
      configurable: true, writable: true,
      value: function addEventListener(type, listener, options) {
        if (!isData(type)) return nativeAdd.call(this, type, listener, options);
        track(this);
        return nativeAdd.call(this, type, wrap(listener), options);
      },
    });
    Object.defineProperty(proto, 'removeEventListener', {
      configurable: true, writable: true,
      value: function removeEventListener(type, listener, options) {
        const l = isData(type) && listener ? (wrapped.get(listener) || listener) : listener;
        return nativeRemove.call(this, type, l, options);
      },
    });

    const d = Object.getOwnPropertyDescriptor(proto, 'onmessage');
    if (!d || !d.get || !d.set) return;
    const handlers = new WeakMap();
    Object.defineProperty(proto, 'onmessage', {
      configurable: true, enumerable: d.enumerable,
      get() {
        return handlers.has(this) ? handlers.get(this) : d.get.call(this);
      },
      set(v) {
        if (typeof v === 'function') {
          d.set.call(this, wrap(v));
          handlers.set(this, v);
          track(this);
        } else {
          d.set.call(this, v);
          handlers.delete(this);
        }
      },
    });
  }

  patch(window.WebSocket, 'ws', (type) => type === 'message');
  // EventSource 的自定义事件名（event: xxx）也是数据，只有 open / error 不是
  patch(window.EventSource, 'es', (type) => type !== 'open' && type !== 'error');

  // ---- 帧率、长任务、事件循环延迟 ----

  let lastFrame = 0;
  function onFrame(t) {
    win.frames++;
    if (lastFrame) {
      const gap = t - lastFrame;
      if (gap > win.frameGapMax) win.frameGapMax = gap;
    }
    lastFrame = t;
    requestAnimationFrame(onFrame);
  }
  requestAnimationFrame(onFrame);

  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        win.longTasks++;
        win.longTaskMs += e.duration;
      }
    }).observe({ type: 'longtask', buffered: true });
  } catch (e) {
    // 不支持 longtask 时这两项保持 0
  }

  let expected = now() + LOOP_TICK_MS;
  (function tick() {
    const t = now();
    const lag = t - expected;
    if (lag > win.loopLagMax) win.loopLagMax = lag;
    expected = t + LOOP_TICK_MS;
    setTimeout(tick, LOOP_TICK_MS);
  })();

  // ---- 上报 ----

  let windowStart = now();

  setInterval(() => {
    let ws = 0;
    let es = 0;
    for (const [s, kind] of sockets) {
      if (s.readyState === 2) { sockets.delete(s); continue; }
      if (s.readyState !== 1) continue;
      if (kind === 'ws') ws++; else es++;
    }
    const w = win;
    win = fresh();
    const t = now();
    // 主线程被占满时这个定时器本身也会推迟，按实际周期折算成每秒，周期长度单独上报
    const windowMs = t - windowStart;
    windowStart = t;
    const perSec = (v) => Math.round((v * 1000 / Math.max(windowMs, 1)) * 10) / 10;
    if (lastSampleAt === -Infinity || t - lastSampleAt > REPORT_MS * 3) {
      // 长时间没有新样本，不要沿用旧的延迟值
      w.tsLag = null;
    }
    const mem = performance.memory;
    const host = findHost();
    if (!host) return;
    try {
      host.postMessage(JSON.stringify({
        yarnDiag: 1,
        id: docId,
        seq: ++seq,
        top: isTop,
        visible: document.visibilityState === 'visible',
        windowMs: Math.round(windowMs),
        ws, es,
        msgs: perSec(w.msgs),
        bytes: Math.round(perSec(w.bytes)),
        handlerMs: perSec(w.handlerMs),
        handlerMax: Math.round(w.handlerMax * 10) / 10,
        fps: perSec(w.frames),
        frameGapMax: Math.round(w.frameGapMax),
        longTasks: perSec(w.longTasks),
        longTaskMs: Math.round(perSec(w.longTaskMs)),
        loopLagMax: Math.round(Math.max(0, w.loopLagMax)),
        tsLag: w.tsLag,
        heapMb: mem ? Math.round(mem.usedJSHeapSize / 1048576) : null,
      }));
    } catch (e) {
      // 宿主不可用（页面卸载中）时丢弃本轮
    }
  }, REPORT_MS);
})();
