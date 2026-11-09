const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('node:path');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']}); const page=await browser.newPage();
 const errors=[]; page.on('pageerror',e=>errors.push(e.toString()));
 try {
  await page.goto('http://127.0.0.1:8090/next/chat');
  await page.waitForFunction(()=>document.querySelector('#chat-model option')?.value === 'DeepSeek');
  await page.waitForFunction(()=>!document.querySelector('#chat-attach').disabled);
  await page.locator('#chat-file').setInputFiles(path.join(__dirname,'red-test.png'));
  await page.waitForSelector('.attach-chip');
  await page.locator('#chat-input').fill('What is the dominant color of the image? Reply using only the English color name.');
  await page.locator('#chat-send').click();
  await page.waitForFunction(()=>[...document.querySelectorAll('.bubble.assistant .content')].some(x=>/red/i.test(x.textContent)),null,{timeout:180000});
  assert.equal(await page.locator('.bubble.error').count(),0);
  await page.locator('#chat-input').fill('What color was the image? Reply with only its English color name.');
  await page.locator('#chat-send').click();
  await page.waitForFunction(()=>[...document.querySelectorAll('.bubble.assistant .content')].filter(x=>/red/i.test(x.textContent)).length >= 2,null,{timeout:180000});
  assert.equal(await page.locator('.bubble.error').count(),0);
  assert.deepEqual(errors,[]);
  console.log('IMAGE_WEB_HISTORY_OK');
 } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
