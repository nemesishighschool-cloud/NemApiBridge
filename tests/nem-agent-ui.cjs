const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage();const errs=[];page.on('pageerror',e=>errs.push(e.toString()));
 const base=process.env.NEM_WEB || 'http://127.0.0.1:8090';
 await page.goto(base+'/next/integrations');
 await page.waitForSelector('#int-qwen-code-enabled', {state:'attached'});
 try {
  assert.equal(await page.locator('#int-qwen-code-reset').count(),1);
  assert.equal(await page.locator('#int-monkey-code-reset').count(),1);
  for(const id of ['qwen-code','monkey-code','nemesis']) {
   await page.locator(`label:has(#int-${id}-enabled) .slider`).click();
   await page.waitForFunction(async id=>(await (await fetch('/web/nem/agent-integrations')).json()).agents.find(x=>x.id===id).enabled,id);
   await page.waitForFunction(id => document.getElementById(`int-${id}-tool_calls`)?.disabled === false, id);
   assert.equal(await page.locator(`#int-${id}-persistent_chat`).isDisabled(),true);
   assert.equal(await page.locator(`#int-${id}-tool_calls`).isDisabled(),false);
   await page.locator(`label:has(#int-${id}-enabled) .slider`).click();
   await page.waitForFunction(async id=>!(await (await fetch('/web/nem/agent-integrations')).json()).agents.find(x=>x.id===id).enabled,id);
   console.log('AGENT_UI_OK',id);
  }
  assert.deepEqual(errs,[]);
 } finally {
  await page.evaluate(async()=>{for(const id of ['qwen-code','monkey-code','nemesis']) await fetch('/web/nem/agent-integrations/'+id,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled:false})});});
  await browser.close();
 }
})().catch(e=>{console.error(e);process.exit(1)});
