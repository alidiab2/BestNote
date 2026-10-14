// Shared shell for every design screen: top bar (look / theme / RTL), persistence, fit-to-phone scaling.
(function () {
  const h = document.documentElement;
  const st = JSON.parse(localStorage.getItem('bn-design') || '{}');
  h.dataset.look = st.look || 'paper';
  h.dataset.theme = st.theme || 'light';
  if (st.rtl) { h.dir = 'rtl'; h.lang = 'ar'; }
  const save = () => localStorage.setItem('bn-design', JSON.stringify({ look: h.dataset.look, theme: h.dataset.theme, rtl: h.dir === 'rtl' }));
  const inScreens = location.pathname.includes('/screens/');
  const home = inScreens ? '../index.html' : 'index.html';

  document.addEventListener('DOMContentLoaded', () => {
    const bar = document.createElement('header');
    bar.className = 'shell-bar';
    bar.innerHTML = `
      <a class="shell-home" href="${home}"><span class="logo">B</span><b>BestNote</b></a>
      <span class="shell-title">${document.title.replace('BestNote · ', '')}</span>
      <div class="shell-looks">
        <button data-look="paper">A · Paper</button><button data-look="studio">B · Studio</button><button data-look="galaxy">C · Galaxy</button>
      </div>
      <button class="shell-btn" id="shTheme">◐</button><button class="shell-btn" id="shRtl">ع</button>`;
    document.body.prepend(bar);
    const mark = () => bar.querySelectorAll('[data-look]').forEach(b => b.classList.toggle('on', b.dataset.look === h.dataset.look));
    mark();
    bar.querySelectorAll('[data-look]').forEach(b => b.onclick = () => { h.dataset.look = b.dataset.look; mark(); save(); });
    bar.querySelector('#shTheme').onclick = () => { h.dataset.theme = h.dataset.theme === 'dark' ? 'light' : 'dark'; save(); };
    bar.querySelector('#shRtl').onclick = () => { const r = h.dir !== 'rtl'; h.dir = r ? 'rtl' : 'ltr'; h.lang = r ? 'ar' : 'en'; save(); };
    fit(); addEventListener('resize', fit);
  });

  // Scale device frames down to the viewport width (phones) without reflowing their content.
  function fit() {
    document.querySelectorAll('.device').forEach(d => {
      let w = d.parentElement.classList.contains('fit') ? d.parentElement : null;
      if (!w) { w = document.createElement('div'); w.className = 'fit'; d.before(w); w.append(d); }
      d.style.transform = ''; w.style.height = '';
      const avail = Math.min(innerWidth - 16, w.parentElement.clientWidth || innerWidth);
      const s = Math.min(1, avail / d.offsetWidth);
      d.style.transformOrigin = 'top left';
      d.style.transform = s < 1 ? `scale(${s})` : '';
      w.style.width = d.offsetWidth * s + 'px';
      w.style.height = d.offsetHeight * s + 'px';
    });
  }
  window.bnFit = fit;
})();
