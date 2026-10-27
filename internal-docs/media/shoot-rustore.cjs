const puppeteer = require('/Users/xandergusarov/.npm/_npx/ffe2131771d88588/node_modules/puppeteer-core');
const out = '/Users/xandergusarov/Documents/GitHub/sber500-companion/internal-docs/media';
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  const b = await puppeteer.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: 'new' });
  const p = await b.newPage();
  await p.setViewport({ width: 1000, height: 900, deviceScaleFactor: 1080 / 412 });
  await p.goto('file:///Users/xandergusarov/Documents/GitHub/sber500-companion/web/editor/screens.html', { waitUntil: 'networkidle2' });
  await p.addStyleTag({ content: '.phone{height:733.2px!important;border-radius:0!important;box-shadow:none!important}' });
  const click = s => p.click(s);
  const scroll = y => p.evaluate(y => { document.querySelector('.content').scrollTop = y; }, y);
  const shot = async name => { await sleep(900); const el = await p.$('.phone'); await el.screenshot({ path: `${out}/${name}.png` }); };
  const checks = o => p.evaluate(o => { for (const [k, v] of Object.entries(o)) { const c = document.querySelector(`[data-o=${k}]`); if (c.checked !== v) c.click(); } }, o);

  await click('[data-t=light]'); await click('[data-s=home]');
  await shot('01-home-light'); 
  await scroll(520); await shot('02-home-stats-light');
  await scroll(1100); await shot('03-home-checkin-light');
  await scroll(0); await click('[data-t=dark]'); await shot('04-home-dark');
  await click('[data-t=light]'); await click('[data-s=onboarding]');
  for (let i = 1; i <= 7; i++) { await shot(`05-onboarding-${i}`); if (i < 7) await click('#obNext'); }
  await click('[data-s=questions]'); await shot('06-questions');
  await b.close();
})();
