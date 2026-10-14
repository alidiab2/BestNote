const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

// Tabs (deep-linkable: index.html#editor)
function show(id) {
  $$('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.s === id));
  $$('.screen').forEach(s => s.classList.toggle('on', s.id === 's-' + id));
  history.replaceState(null, '', '#' + id);
}
$$('#tabs button').forEach(b => b.onclick = () => show(b.dataset.s));
if (location.hash) show(location.hash.slice(1));

// Theme + direction
$('#tTheme').onclick = () => {
  const h = document.documentElement;
  h.dataset.theme = h.dataset.theme === 'dark' ? 'light' : 'dark';
};
$('#tDir').onclick = () => {
  const h = document.documentElement;
  const rtl = h.dir !== 'rtl';
  h.dir = rtl ? 'rtl' : 'ltr';
  h.lang = rtl ? 'ar' : 'en';
};

// Pen popover: tap the active tool again
const pop = $('#penPop');
$$('#toolbar .tool').forEach(t => t.onclick = () => {
  if (t.classList.contains('on') && t.dataset.pop) { pop.classList.toggle('open'); return; }
  $$('#toolbar .tool').forEach(x => x.classList.remove('on'));
  t.classList.add('on');
  pop.classList.remove('open');
});

// Toolbar collapses while "writing" on the canvas
const canvas = $('.canvas');
if (canvas) {
  canvas.addEventListener('pointerdown', () => { $('#toolbar').classList.add('quiet'); pop.classList.remove('open'); });
  canvas.addEventListener('pointerup', () => setTimeout(() => $('#toolbar').classList.remove('quiet'), 700));
  const cur = $('.hover-cursor');
  canvas.addEventListener('pointermove', e => {
    const r = canvas.getBoundingClientRect();
    cur.style.transform = `translate(${e.clientX - r.left - 7}px, ${e.clientY - r.top - 7}px)`;
    cur.style.opacity = 1;
  });
  canvas.addEventListener('pointerleave', () => cur.style.opacity = 0);
}

// Open-notebook shared element transition
$('#newNb')?.addEventListener('click', () => {
  const a = $('#openAnim');
  a.classList.remove('go'); void a.offsetWidth; a.classList.add('go');
  setTimeout(() => a.classList.remove('go'), 1400);
});
$$('#covers .nb').forEach(n => n.addEventListener('click', () => $('#newNb').click()));

// Replay the handwriting animation
document.getElementById('replay')?.addEventListener('click', () => {
  const p = document.getElementById('handPage'); const h = p.innerHTML; p.innerHTML = ''; void p.offsetWidth; p.innerHTML = h;
});
