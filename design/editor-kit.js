// Shared helpers for the editor & tools screens (10–17). Mockup-only; no app logic.
// Works inside scaled .device frames (shell.js scales them with a CSS transform).
(function () {
  const BN = {};
  BN.$ = (s, r = document) => r.querySelector(s);
  BN.$$ = (s, r = document) => [...r.querySelectorAll(s)];
  const NS = 'http://www.w3.org/2000/svg';
  BN.el = (tag, attrs = {}, parent) => {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.append(e);
    return e;
  };

  // Pointer position in an element's own (unscaled) CSS pixels.
  BN.local = (el, e) => {
    const r = el.getBoundingClientRect();
    const s = r.width / el.offsetWidth || 1;
    return { x: (e.clientX - r.left) / s, y: (e.clientY - r.top) / s, s };
  };
  // Pointer position in an SVG's user units.
  BN.svgPt = (svg, e) => {
    const m = svg.getScreenCTM();
    if (!m) return { x: 0, y: 0 };
    const p = new DOMPoint(e.clientX, e.clientY).matrixTransform(m.inverse());
    return { x: p.x, y: p.y };
  };

  // Smooth centerline path through points (quadratic through midpoints).
  BN.smooth = (pts) => {
    if (!pts.length) return '';
    if (pts.length < 3) return `M${pts[0].x} ${pts[0].y} ` + pts.map(p => `L${p.x} ${p.y}`).join(' ');
    let d = `M${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`;
    for (let i = 1; i < pts.length - 1; i++) {
      const a = pts[i], b = pts[i + 1];
      d += ` Q${a.x.toFixed(1)} ${a.y.toFixed(1)} ${((a.x + b.x) / 2).toFixed(1)} ${((a.y + b.y) / 2).toFixed(1)}`;
    }
    const l = pts[pts.length - 1];
    return d + ` L${l.x.toFixed(1)} ${l.y.toFixed(1)}`;
  };

  // Variable-width outline (filled polygon) for pen previews.
  // o: { w, pressure (0..1 influence), taper (0..1), nib (fountain angle factor 0..1), angle }
  BN.outline = (pts, o) => {
    const n = pts.length; if (n < 2) return '';
    const L = [], R = [];
    const ang = o.angle ?? -0.6;
    for (let i = 0; i < n; i++) {
      const a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)];
      let dx = b.x - a.x, dy = b.y - a.y; const len = Math.hypot(dx, dy) || 1; dx /= len; dy /= len;
      const t = i / (n - 1);
      let w = o.w * (1 - (o.pressure || 0) + (o.pressure || 0) * (pts[i].p ?? .6));
      if (o.nib) w *= (1 - o.nib) + o.nib * Math.abs(Math.sin(Math.atan2(dy, dx) - ang)) * 1.35;
      if (o.taper) { const e = Math.min(t, 1 - t) / .18; w *= 1 - o.taper + o.taper * Math.min(1, e); }
      w = Math.max(.35, w) / 2;
      L.push([pts[i].x - dy * w, pts[i].y + dx * w]);
      R.push([pts[i].x + dy * w, pts[i].y - dx * w]);
    }
    const poly = L.concat(R.reverse());
    let d = `M${poly[0][0].toFixed(1)} ${poly[0][1].toFixed(1)}`;
    for (let i = 1; i < poly.length; i++) {
      const p = poly[i], q = poly[(i + 1) % poly.length];
      d += ` Q${p[0].toFixed(1)} ${p[1].toFixed(1)} ${((p[0] + q[0]) / 2).toFixed(1)} ${((p[1] + q[1]) / 2).toFixed(1)}`;
    }
    return d + 'Z';
  };

  // Freehand drawing on an SVG. style() returns { color, width, opacity, cls, under } per stroke.
  BN.ink = (svg, style, onEnd) => {
    let path = null, pts = [];
    svg.style.touchAction = 'none';
    svg.addEventListener('pointerdown', e => {
      if (e.button > 0) return;
      const s = style ? style(e) : {};
      if (s === false) return;
      svg.setPointerCapture(e.pointerId);
      pts = [BN.svgPt(svg, e)];
      path = BN.el('path', { class: 'stroke ' + (s.cls || ''), 'stroke-width': s.width || 2.4 });
      if (s.color) path.style.stroke = s.color;
      if (s.opacity) path.style.opacity = s.opacity;
      const layer = s.layer || svg;
      if (s.under) layer.prepend(path); else layer.append(path);
    });
    svg.addEventListener('pointermove', e => {
      if (!path) return;
      pts.push(BN.svgPt(svg, e));
      path.setAttribute('d', BN.smooth(pts));
    });
    const end = () => { if (path) { if (pts.length < 2) path.remove(); else onEnd && onEnd(path, pts); } path = null; };
    svg.addEventListener('pointerup', end);
    svg.addEventListener('pointercancel', end);
  };

  // Hover cursor that follows the pen over an area.
  BN.hover = (area, cur) => {
    area.addEventListener('pointermove', e => {
      const p = BN.local(area, e);
      cur.style.transform = `translate(${p.x}px, ${p.y}px)`;
      cur.classList.add('on');
    });
    area.addEventListener('pointerleave', () => cur.classList.remove('on'));
  };

  // Toolbar fades to 40% while the pen is down (design principle 1).
  BN.writingFade = (ed, area) => {
    let t;
    area.addEventListener('pointerdown', () => { clearTimeout(t); ed.classList.add('writing'); });
    const up = () => { t = setTimeout(() => ed.classList.remove('writing'), 650); };
    area.addEventListener('pointerup', up); area.addEventListener('pointerleave', up);
  };

  // Single-choice groups: children toggle .on
  BN.seg = (box, cb, sel = ':scope > *') => {
    BN.$$(sel, box).forEach(b => b.addEventListener('click', () => {
      BN.$$(sel, box).forEach(x => x.classList.toggle('on', x === b));
      cb && cb(b.dataset.v ?? b.textContent.trim(), b);
    }));
  };

  // Restart CSS animations inside an element.
  BN.replay = (el) => { const c = el.cloneNode(true); el.replaceWith(c); return c; };

  // "Writes itself": give every .wr element (in DOM order or data-o) a delay/duration
  // from its length, so the page is written left→right, line by line, fast.
  BN.writeOn = (root, { cps = 38, pxps = 900, gap = .04, start = .15, min = .12 } = {}) => {
    let t = start;
    const items = BN.$$('.wr', root);
    items.forEach(el => {
      let dur;
      if (el.tagName === 'text') dur = Math.max(min, (el.textContent.trim().length) / cps);
      else { let len = 60; try { len = el.getTotalLength(); } catch (_) {} dur = Math.max(min, len / pxps); el.setAttribute('pathLength', 1); }
      if (el.dataset.at) t = +el.dataset.at;
      el.style.setProperty('--d', t.toFixed(2) + 's');
      el.style.setProperty('--dur', dur.toFixed(2) + 's');
      if (!el.hasAttribute('data-par')) t += dur + gap;
    });
    return t;
  };

  // Pointer-based drag to reorder with spring-y FLIP for the neighbours.
  BN.sortable = (box, itemSel, onDrop) => {
    let drag = null, start, ph, moved = false;
    box.addEventListener('pointerdown', e => {
      const it = e.target.closest(itemSel);
      if (!it || !box.contains(it) || e.target.closest('[data-nodrag]')) return;
      e.preventDefault();
      const p = BN.local(box, e);
      drag = it; start = p; drag.setPointerCapture(e.pointerId);
      drag.classList.add('lifting');
      ph = 0; moved = false;
    });
    box.addEventListener('pointermove', e => {
      if (!drag) return;
      const p = BN.local(box, e);
      if (Math.hypot(p.x - start.x, p.y - start.y) > 5) moved = true;
      drag.style.transform = `translate(${p.x - start.x}px, ${p.y - start.y}px)`;
      // find the sibling under the pointer
      drag.style.pointerEvents = 'none';
      const under = document.elementFromPoint(e.clientX, e.clientY)?.closest(itemSel);
      drag.style.pointerEvents = '';
      if (under && under !== drag && box.contains(under) && Date.now() - ph > 160) {
        ph = Date.now();
        const items = BN.$$(itemSel, box);
        const first = new Map(items.map(i => [i, i.getBoundingClientRect()]));
        const before = items.indexOf(under) < items.indexOf(drag);
        const r0 = drag.getBoundingClientRect();
        under[before ? 'before' : 'after'](drag);
        const r1 = drag.getBoundingClientRect();
        const s = BN.local(box, e).s;
        start.x += (r1.left - r0.left) / s; start.y += (r1.top - r0.top) / s;
        drag.style.transform = `translate(${p.x - start.x}px, ${p.y - start.y}px)`;
        items.forEach(i => {
          if (i === drag) return;
          const a = first.get(i), b = i.getBoundingClientRect();
          const dx = (a.left - b.left) / s, dy = (a.top - b.top) / s;
          if (!dx && !dy) return;
          i.style.transition = 'none'; i.style.transform = `translate(${dx}px, ${dy}px)`;
          requestAnimationFrame(() => requestAnimationFrame(() => { i.style.transition = ''; i.style.transform = ''; }));
        });
      }
    });
    const end = () => {
      if (!drag) return;
      const d = drag; drag = null;
      d.classList.remove('lifting'); d.classList.add('dropping');
      d.style.transform = '';
      setTimeout(() => d.classList.remove('dropping'), 300);
      onDrop && onDrop(d, moved);
    };
    box.addEventListener('pointerup', end); box.addEventListener('pointercancel', end);
  };

  // Simple spring-ish tween (for morphs driven from JS).
  BN.tween = (dur, fn, done) => {
    const t0 = performance.now();
    const ease = t => 1 - Math.pow(1 - t, 3) + Math.sin(t * Math.PI) * .08 * (1 - t);
    const step = now => {
      const t = Math.min(1, (now - t0) / dur);
      fn(ease(t));
      if (t < 1) requestAnimationFrame(step); else done && done();
    };
    requestAnimationFrame(step);
  };

  window.BN = BN;
})();
