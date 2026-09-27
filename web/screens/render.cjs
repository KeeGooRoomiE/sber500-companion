// Renders home.html to PNGs for the landing:  node web/screens/render.cjs
//   home-screen.png — the bare screen, 1236×2745 (3×, like a real phone screenshot)
//   home-phone.png  — the screen in a phone frame, transparent background (2×)
const path = require('path');
const { chromium } = require('playwright');

(async () => {
  const file = 'file://' + path.join(__dirname, 'home.html');
  const browser = await chromium.launch();

  const screen = await browser.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 3 });
  await screen.goto(file);
  await screen.evaluate(() => document.fonts.ready);
  await screen.locator('#screen').screenshot({ path: path.join(__dirname, 'home-screen.png') });

  const framed = await browser.newPage({ viewport: { width: 520, height: 1023 }, deviceScaleFactor: 2 });
  await framed.goto(file + '?frame=1');
  await framed.evaluate(() => document.fonts.ready);
  await framed.locator('.phone').screenshot({ path: path.join(__dirname, 'home-phone.png'), omitBackground: true });

  await browser.close();
  console.log('home-screen.png, home-phone.png written');
})();
