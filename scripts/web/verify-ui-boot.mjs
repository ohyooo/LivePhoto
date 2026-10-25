import {launchBrowser} from './browser-runtime.mjs';
import {createServer} from 'node:http';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve,sep,extname} from 'node:path';
const root=resolve('ci-output/web-ui');
const server=createServer(async(req,res)=>{
  try{
    const name=new URL(req.url,'http://localhost').pathname;
    if(!name.startsWith('/livephoto/'))throw Error('Outside app');
    const path=resolve(root,'.'+(name==='/livephoto/'?'/index.html':name.slice('/livephoto'.length)));
    if(!path.startsWith(root+sep))throw Error('Outside app');
    res.setHeader('Content-Type',{'.html':'text/html','.js':'text/javascript','.mjs':'text/javascript','.wasm':'application/wasm','.json':'application/json'}[extname(path)]||'application/octet-stream');
    res.end(await readFile(path));
  }catch{res.writeHead(404).end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await launchBrowser(),checks=[];
try{
  const context=await browser.newContext({viewport:{width:1280,height:800}}),page=await context.newPage();
  page.setDefaultTimeout(120000);
  page.on?.('pageerror',error=>console.error('UI_PAGE_ERROR',String(error)));
  await page.goto(`http://127.0.0.1:${server.address().port}/livephoto/`);
  const bounds=name=>page.evaluate(name=>{
    const element=[...document.querySelectorAll('[role="button"],button')].find(e=>e.getAttribute('aria-label')===name||e.textContent.trim()===name);
    if(!element)return null;
    const b=element.getBoundingClientRect();return b.width>0&&b.height>0?{x:b.x+b.width/2,y:b.y+b.height/2}:null;
  },name);
  try{await page.waitForFunction(()=>!!document.querySelector('canvas'));}
  catch(error){console.error('UI_BOOT_STATE',await page.evaluate(()=>({text:document.body.innerText,html:document.body.innerHTML.slice(0,6000),resources:performance.getEntriesByType('resource').map(e=>e.name)})));await page.screenshot({path:'ci-output/web-ui-compatibility.png'});throw error;}
  await page.keyboard.press('Tab');
  await page.waitForFunction(()=>!!document.querySelector('#main-action'));
  const runtime=await page.evaluate(()=>performance.getEntriesByType('resource').some(e=>/livephoto-ui\.[a-f0-9]+\.wasm/.test(e.name)));
  if(!runtime)throw Error('Kotlin/Wasm app did not load');checks.push('wasm-compose-render');
  for(const [name,selector]of [['照片工具','#tool-selector'],['新建','#main-action']]){
    await page.waitForFunction(name=>[...document.querySelectorAll('[role="button"],button')].some(e=>e.getAttribute('aria-label')===name||e.textContent.trim()===name),name);
    const position=await bounds(name);if(!position)throw Error('Missing canvas control '+name);
    await page.mouse.click(position.x,position.y);
    await page.waitForFunction(selector=>!!document.querySelector(selector),selector);
    checks.push(name==='新建'?'pointer-return-to-create':'pointer-photo-tools');
  }
  await page.screenshot({path:'ci-output/web-ui-compatibility.png'});
  await writeFile('ci-output/web-ui-boot-evidence-main.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,browser:process.env.LIVEPHOTO_BROWSER,browserVersion:browser.version(),tests:checks.length,failures:0,checks},null,2)+'\n');
  console.log('WEB_UI_BOOT_EVIDENCE=SUCCESS 3 checks');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
