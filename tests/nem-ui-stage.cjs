const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({headless: true, args: ['--no-sandbox']});
  const page = await browser.newPage({viewport: {width: 1440, height: 900}});
  const errors = [];
  page.on('pageerror', e => errors.push(e.stack || String(e)));
  const base = process.env.NEM_WEB || 'http://127.0.0.1:8090';
  async function visit(path, heading) {
    const response = await page.goto(base + path);
    assert.equal(response.status(), 200, path);
    await page.getByRole('heading', {name: heading, exact: true}).first().waitFor();
    await page.waitForTimeout(1000);
    if (errors.length) throw Error(errors.join('\n'));
    console.log('PAGE OK', path);
  }
  await visit('/', 'Tableau de bord');
  await visit('/chat', 'Chat test');
  await visit('/next/', 'Tableau de bord');
  await page.waitForFunction(() => document.getElementById('st-ready')?.textContent === '1/1', null, {timeout: 10000});
  assert.match(await page.locator('#dash-providers').innerText(), /DeepSeek/);
  await visit('/next/config', 'Configuration');
  assert.match(await page.locator('body').innerText(), /X-NemApi-Ignored-Parameters/);
  assert.match(await page.locator('body').innerText(), /ne contrôlent pas le fournisseur Android/);
  await page.waitForFunction(() => document.querySelector('#cfg-models')?.textContent.includes('DeepSeek'));
  await page.locator('label:has(#cfg-stream) .slider').click();
  await page.waitForFunction(async () => !(await (await fetch('/web/nem/settings')).json()).stream_enabled);
  await page.locator('label:has(#cfg-stream) .slider').click();
  await page.waitForFunction(async () => (await (await fetch('/web/nem/settings')).json()).stream_enabled);
  await visit('/next/analytics', 'Analytics');
  await page.waitForFunction(() => document.getElementById('total-requests')?.textContent !== '—');
  await visit('/next/integrations', 'Intégration agents IA');
  await visit('/next/api-keys-page', 'Clés API');
  assert.equal(await page.locator('#new-key-btn').isDisabled(), false);
  let key = '', id = '';
  try {
    await page.locator('#new-key-name').fill('Test temporaire');
    await page.locator('#new-key-btn').click();
    await page.waitForFunction(() => document.querySelector('#new-key-value')?.textContent?.startsWith('sk-nem-'));
    key = await page.locator('#new-key-value').innerText();
    let list = await page.evaluate(async () => (await (await fetch('/web/nem/api-keys')).json()).api_keys);
    id = list.find(k => k.key === key).id;
    await page.locator('label:has(#api-protection) .slider').click();
    await page.waitForFunction(async () => (await (await fetch('/web/nem/api-keys/config')).json()).require_auth);
    let status = await page.evaluate(async key => [
      (await fetch('/v1/models')).status,
      (await fetch('/v1/models', {headers: {Authorization: 'Bearer '+key}})).status,
      (await fetch('/v1/models', {headers: {'X-API-Key': key}})).status,
      (await fetch('/web/nem/providers')).status
    ], key);
    assert.deepEqual(status, [401, 200, 200, 200]);
    console.log('API KEY FLOW OK');
  } finally {
    await page.evaluate(async id => {
      await fetch('/web/nem/api-keys/disable', {method:'POST', headers:{'Content-Type':'application/json'}, body:'{}'});
      if (id) await fetch('/web/nem/api-keys/' + encodeURIComponent(id), {method:'DELETE'});
    }, id);
  }
  await visit('/next/chat', 'Chat test');
  await page.waitForFunction(() => document.querySelector('#chat-model option')?.value === 'DeepSeek');
  const providers = await page.evaluate(async () => (await (await fetch('/web/nem/providers')).json()).providers);
  assert.equal(await page.locator('#chat-attach').isDisabled(), !providers[0].supports_images);
  if (process.env.LIVE_CHAT === '1') {
    await page.locator('#chat-input').fill('Reply with exactly NEM110WEBOK and nothing else.');
    await page.locator('#chat-send').click();
    await page.waitForFunction(() => [...document.querySelectorAll('.bubble.assistant .content')].some(x => x.textContent.includes('NEM110WEBOK')), null, {timeout: 180000});
    assert.equal(await page.locator('.bubble.error').count(), 0);
    console.log('LIVE CHAT OK');
  }
  if (errors.length) throw Error(errors.join('\n'));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
