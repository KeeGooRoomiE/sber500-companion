const puppeteer = require('/Users/xandergusarov/.npm/_npx/ffe2131771d88588/node_modules/puppeteer-core');
const NO_STATUS = process.argv.includes('--no-status'); // --no-status: hide status bar, write to no-statusbar/
const out = '/Users/xandergusarov/Documents/GitHub/sber500-companion/internal-docs/media' + (NO_STATUS ? '/no-statusbar' : '');
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  const b = await puppeteer.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: 'new' });
  const p = await b.newPage();
  await p.setViewport({ width: 1000, height: 900, deviceScaleFactor: 1080 / 412 });
  await p.goto('file:///Users/xandergusarov/Documents/GitHub/sber500-companion/web/editor/screens.html', { waitUntil: 'networkidle2' });
  await p.addStyleTag({ content: '.phone{height:733.2px!important;border-radius:0!important;box-shadow:none!important}' + (NO_STATUS ? ' .status{display:none!important} .screen{padding-top:24px!important}' : '') });
  const click = s => p.click(s);
  const scrollTo = (sel, off = 24) => p.evaluate((sel, off) => { const c = document.querySelector('.content'); const e = document.querySelector(sel); c.scrollTop = e.getBoundingClientRect().top - c.getBoundingClientRect().top + c.scrollTop - off; }, sel, off);
  const scroll = y => p.evaluate(y => { document.querySelector('.content').scrollTop = y; }, y);
  const shot = async name => { await sleep(900); const el = await p.$('.phone'); await el.screenshot({ path: `${out}/${name}.png` }); };
  const checks = o => p.evaluate(o => { for (const [k, v] of Object.entries(o)) { const c = document.querySelector(`[data-o=${k}]`); if (c.checked !== v) c.click(); } }, o);

  // Every shot must end between cards, never mid-card: optional blocks are toggled so the visible stack fits 9:16.
  const hideAcc = n => p.evaluate(n => { let st = document.getElementById('accHide'); if (!st) { st = document.createElement('style'); st.id = 'accHide'; document.head.appendChild(st); } st.textContent = n ? `#qAcc>:nth-child(n+${n}){display:none!important}` : ''; }, n);
  await click('[data-t=light]'); await click('[data-s=home]');
  await checks({ timeline: false, checkin: false, history: false });
  await scroll(0); await shot('01-home-light');
  await click('[data-t=dark]'); await shot('04-home-dark'); await click('[data-t=light]');
  await checks({ timeline: true, checkin: true, history: false });
  const cut = css => p.evaluate(css => { let st = document.getElementById('cut'); if (!st) { st = document.createElement('style'); st.id = 'cut'; document.head.appendChild(st); } st.textContent = css; }, css);
  await cut('#s-home .top,#s-home .hero{display:none!important}'); await scroll(0); await shot('02-home-stats-light');
  await checks({ history: true });
  await cut('#s-home .top,#s-home .hero,#midday,#stats{display:none!important}'); await scroll(0); await shot('03-home-checkin-light'); await cut('');
  await click('[data-s=onboarding]');
  for (let i = 1; i <= 7; i++) { await shot(`05-onboarding-${i}`); if (i < 7) await click('#obNext'); }
  await click('[data-s=questions]');
  await hideAcc(2); await scroll(0); await shot('06-questions');
  await hideAcc(6); await p.addStyleTag({ content: '.qhead,.grow{display:none!important}' }); await scroll(0); await shot('07-questions-profile');
  await b.close();
})();
