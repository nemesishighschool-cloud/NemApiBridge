const {chromium} = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({headless:true,args:['--no-sandbox']});
  const page=await browser.newPage();
  const errors=[]; page.on('pageerror',e=>errors.push(String(e)));
  try {
    await page.goto((process.env.NEM_URL || 'http://127.0.0.1:8090') + '/chat');
    await page.waitForFunction(()=>document.querySelector('#chat-model option')?.value === 'TestPicker');
    await page.waitForFunction(()=>!document.querySelector('#chat-attach').disabled);
    await page.locator('#chat-file').setInputFiles(require('node:path').join(__dirname,'red-test.png'));
    await page.waitForSelector('.attach-chip');
    await page.locator('#chat-input').fill('Test the automatic chooser.');
    await page.locator('#chat-send').click();
    await page.waitForFunction(()=>[...document.querySelectorAll('.bubble.assistant .content')].some(x=>x.textContent.includes('IMAGE_OK')) || document.querySelectorAll('.bubble.error').length > 0, null,{timeout:60000});
    if (await page.locator('.bubble.error').count()) throw new Error(await page.locator('.bubble.error').first().innerText());
    assert.equal(await page.locator('.bubble.error').count(),0);
    assert.deepEqual(errors,[]);
    console.log('FULL_CHOOSER_WEB_OK');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
