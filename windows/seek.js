// 双击视频右侧快进、左侧后退。播放器自带控件缺少这个功能时补上。
(() => {
  if (window.__yarnSeek) return;
  window.__yarnSeek = true;
  const STEP = 10;          // 每次跳转秒数
  const DOUBLE_MS = 350;    // 两次点击的最大间隔
  const DOUBLE_PX = 40;     // 两次点击的最大位移
  const EDGE = 0.35;        // 左右各占画面宽度的比例，中间区域不处理
  const BOTTOM = 0.15;      // 画面底部留给进度条，不处理

  const videoAt = (x, y) => {
    let best = null, bestArea = 0;
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      if (x < r.left || x > r.right || y < r.top || y > r.bottom) continue;
      const area = r.width * r.height;
      if (area > bestArea && r.width > 120 && r.height > 80) { best = v; bestArea = area; }
    }
    return best;
  };

  let toast, toastTimer;
  const showToast = (v, text, side) => {
    if (!toast) {
      toast = document.createElement('div');
      toast.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;padding:8px 16px;' +
        'border-radius:20px;background:rgba(0,0,0,.65);color:#fff;font:600 16px sans-serif;transition:opacity .2s';
    }
    const r = v.getBoundingClientRect();
    const host = document.fullscreenElement || document.documentElement;
    if (toast.parentNode !== host) host.appendChild(toast);
    toast.textContent = text;
    toast.style.top = (r.top + r.height / 2 - 18) + 'px';
    toast.style.left = (side > 0 ? r.left + r.width * 0.75 : r.left + r.width * 0.25) - 40 + 'px';
    toast.style.opacity = '1';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toast.style.opacity = '0'; }, 700);
  };

  const seek = (x, y) => {
    const v = videoAt(x, y);
    if (!v || !isFinite(v.duration) || v.duration <= 0) return false;
    const r = v.getBoundingClientRect();
    const fx = (x - r.left) / r.width, fy = (y - r.top) / r.height;
    if (fy > 1 - BOTTOM) return false;
    let dir = 0;
    if (fx >= 1 - EDGE) dir = 1; else if (fx <= EDGE) dir = -1;
    if (!dir) return false;
    v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + dir * STEP));
    showToast(v, dir > 0 ? '+' + STEP + 's ▶▶' : '◀◀ -' + STEP + 's', dir);
    return true;
  };

  const swallow = (e) => { e.preventDefault(); e.stopImmediatePropagation(); };

  // 鼠标：直接用 dblclick
  document.addEventListener('dblclick', (e) => {
    if (e.pointerType === 'touch') return;
    if (seek(e.clientX, e.clientY)) swallow(e);
  }, true);

  // 触屏：自己判断双击，WebView 的双击缩放会吞掉 dblclick
  let last = null;
  document.addEventListener('touchend', (e) => {
    if (e.touches.length || e.changedTouches.length !== 1) { last = null; return; }
    const t = e.changedTouches[0];
    const now = Date.now();
    if (last && now - last.t < DOUBLE_MS && Math.hypot(t.clientX - last.x, t.clientY - last.y) < DOUBLE_PX) {
      last = null;
      if (seek(t.clientX, t.clientY)) swallow(e);
      return;
    }
    last = { t: now, x: t.clientX, y: t.clientY };
  }, { capture: true, passive: false });
})();
