// Optional device/browser smoke test. See README for setup; LIVE_CHAT=1 sends a real prompt.
const { chromium } = require('playwright');
const assert = require('assert');
const base = process.env.NEM_WEB_URL || 'http://127.0.0.1:8090';
const model = process.env.NEM_PROVIDER || 'DeepSeek';
(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    assert.strictEqual((await page.goto(base, { waitUntil: 'networkidle' })).status(), 200);
    await page.waitForFunction(() => document.querySelector('#statServer').textContent === 'En ligne');
    const network = await page.evaluate(async () => (await fetch('/web/network')).json());
    await page.locator('button[data-go="connection"]').first().click();
    assert.strictEqual(await page.locator('#usbApi').innerText(), network.usb_api);
    assert.strictEqual(await page.locator('#adbCommand').innerText(), network.adb_command);
    await page.locator('.nav button[data-view="chat"]').click();
    await page.waitForFunction(name => [...document.querySelectorAll('#chatProvider option')].some(o => o.value === name), model);
    await page.locator('#chatProvider').selectOption(model);
    if (process.env.LIVE_CHAT === '1') {
      await page.locator('#prompt').fill('Réponds uniquement : NEMWEBTESTOK');
      await page.locator('#sendChat').click();
      await page.waitForFunction(() => [...document.querySelectorAll('.bubble.assistant span')].some(x => x.textContent.trim() === 'NEMWEBTESTOK'), null, { timeout: 90000 });
      console.log('Échange réel : NEMWEBTESTOK');
    }
    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    await mobile.goto(base + '/chat', { waitUntil: 'networkidle' });
    assert(await mobile.locator('#view-chat').isVisible());
    assert(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepStrictEqual(errors, []);
    console.log('Dashboard, navigation, sélection et affichage mobile : OK');
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
