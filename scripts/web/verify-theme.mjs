import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {resolve,join,sep,extname} from 'node:path';
import {tmpdir} from 'node:os';
import {execFileSync} from 'node:child_process';
import {composeDriver} from './ui-interaction.mjs';

const part=process.env.LIVEPHOTO_TEST_PART;
if(!['mobile','wide'].includes(part))throw Error('Select theme viewport');
const root=resolve('ci-output/web-ui'),fixtures=await mkdtemp(join(tmpdir(),'livephoto-theme-'));
const manifest=JSON.parse((await readFile('ci-output/web-ui-package-evidence.json','utf8')).replace(/^\uFEFF/,''));
const appWasm=new Set(manifest.applicationWasm.map(name=>'/livephoto/'+name));
if(appWasm.size!==1)throw Error('Expected one packaged application Wasm entry');
if(!process.env.LIVEPHOTO_FFMPEG)throw Error('Synthetic fixture generator required');
const movie=join(fixtures,'theme.mp4'),cover=join(fixtures,'theme.jpg');
execFileSync(process.env.LIVEPHOTO_FFMPEG,['-nostdin','-n','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=160x90:rate=25','-t','1','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-bf','0','-use_editlist','0','-video_track_timescale','90000','-movflags','+faststart','-map_metadata','-1','-metadata:s:v','encoder=','-fflags','+bitexact','-flags:v','+bitexact','-write_btrt','0',movie],{timeout:60000});
execFileSync(process.env.LIVEPHOTO_FFMPEG,['-nostdin','-n','-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=blue:s=64x64','-frames:v','1','-threads','1','-update','1',cover],{timeout:60000});
const server=createServer(async(req,res)=>{try{
  const url=new URL(req.url,'http://localhost');if(!url.pathname.startsWith('/livephoto/'))throw Error('subpath');
  const path=resolve(root,'.'+(url.pathname==='/livephoto/'?'/index.html':url.pathname.slice('/livephoto'.length)));
  if(!path.startsWith(root+sep))throw Error('path');
  res.setHeader('Content-Type',{'.html':'text/html','.js':'application/javascript','.mjs':'application/javascript','.wasm':'application/wasm','.otf':'font/otf'}[extname(path)]??'application/octet-stream');res.end(await readFile(path));
}catch{res.writeHead(404).end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const origin='http://127.0.0.1:'+server.address().port,browser=await chromium.launch({headless:true}),checks=[];
let lastPage;
const check=(name,ok)=>{if(!ok)throw Error(name);checks.push(name);};
try {
  for(const [size,viewport] of [['mobile',{width:390,height:844}],['wide',{width:1280,height:800}]]) {
    if(size!==part)continue;
    const context=await browser.newContext({viewport,colorScheme:'dark',serviceWorkers:'block'});
    await context.route('**/*',r=>r.request().url().startsWith(origin+'/')?r.continue():r.abort());
    const page=await context.newPage();lastPage=page;page.setDefaultTimeout(30000);
    const errors=[];page.on('pageerror',e=>{errors.push(String(e));console.error('Theme page error',String(e));});
    await page.goto(origin+'/livephoto/');await page.locator('canvas').first().waitFor({state:'visible'});await page.keyboard.press('Tab');
    const driver=composeDriver(page,viewport);
    const theme=async(mode,path)=>{
      await page.waitForFunction(mode=>getComputedStyle(document.documentElement).colorScheme===mode,mode);
      await driver.find('主题模式').waitFor({state:'attached'});
      await page.waitForTimeout(150);
      const png=await page.screenshot(path?{path}:{});
      // Inspect the actual rendered app bar, not just a DOM theme flag.
      const brightness=await page.evaluate(async({png,x})=>{
        const image=await createImageBitmap(new Blob([new Uint8Array(png)],{type:'image/png'}));
        const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
        const ctx=canvas.getContext('2d');ctx.drawImage(image,0,0);image.close();
        const pixel=ctx.getImageData(x,16,1,1).data;return (pixel[0]+pixel[1]+pixel[2])/3;
      },{png:Array.from(png),x:Math.floor(viewport.width/2)});
      return mode==='dark'?brightness<100:brightness>170;
    };
    await driver.choose('选择视频',movie);
    await page.waitForFunction(()=>document.querySelector('video[aria-label="视频预览"]')?.readyState>=2);
    const video=page.getByLabel('视频预览',{exact:true}),source=await video.getAttribute('src');
    check(size+':default-follows-dark-browser',await theme('dark','ci-output/web-ui-theme-'+size+'-dark.png'));
    await page.emulateMedia({colorScheme:'light'});
    check(size+':browser-preference-updates-rendering',await theme('light','ci-output/web-ui-theme-'+size+'-light.png'));
    const button=await driver.find('主题模式').boundingBox();
    check(size+':toggle-in-top-right',button&&button.x>viewport.width/2&&button.x+button.width<=viewport.width&&button.y>=0&&button.y+button.height<=64);
    // Unlike body controls, this button deliberately lives inside the fixed
    // app bar. Deliver a real pointer click to its already-checked bounds.
    await page.mouse.click(button.x+button.width/2,button.y+button.height/2);
    await driver.click('主题：深色');
    check(size+':manual-toggle-keeps-selected-media',await theme('dark')&&await video.getAttribute('src')===source&&await page.getByText('名称：theme.mp4',{exact:true}).count()===1);
    await page.emulateMedia({colorScheme:'dark'});await page.emulateMedia({colorScheme:'light'});
    check(size+':manual-override-is-respected',await theme('dark'));
    await driver.click('主题模式');await driver.click('主题：跟随系统');
    if(!await theme('light'))throw Error('Explicit System mode did not restore browser following');
    await driver.click('主题模式');await driver.click('主题：浅色');
    await page.emulateMedia({colorScheme:'dark'});
    if(!await theme('light'))throw Error('Explicit Light mode did not override dark browser');
    await page.emulateMedia({colorScheme:'light'});
    await page.reload();await page.locator('canvas').first().waitFor({state:'visible'});await page.keyboard.press('Tab');
    check(size+':reload-follows-browser-again',await theme('light')&&errors.length===0);
    await context.close();

    const blocked=await browser.newContext({viewport,serviceWorkers:'block'});
    await blocked.route('**/*',r=>r.request().url().startsWith(origin+'/')?r.continue():r.abort());
    await blocked.addInitScript(throwOnRequest => {
      const original=HTMLCanvasElement.prototype.getContext;
      HTMLCanvasElement.prototype.getContext=function(kind,...options){
        if(kind!=='webgl2')return Reflect.apply(original,this,[kind,...options]);
        if(throwOnRequest)throw new DOMException('Graphics request blocked','NotSupportedError');
        return null;
      };
    },size==='wide');
    const fallback=await blocked.newPage();lastPage=fallback;
    const resources=[],fallbackErrors=[];
    fallback.on('request',r=>resources.push(new URL(r.url()).pathname));
    fallback.on('pageerror',e=>fallbackErrors.push(e.message));
    await fallback.goto(origin+'/livephoto/');
    await fallback.getByRole('heading',{name:'当前浏览器无法启用 WebGL 2'}).waitFor({state:'visible'});
    const reloaded=fallback.waitForEvent('load');
    await fallback.getByRole('button',{name:'刷新重试'}).click();
    await reloaded;
    check(size+':blocked-graphics-shows-html-without-loading-media',
      await fallback.getByRole('alert').getByText(/未读入你的图片或视频/).isVisible()&&
      resources.filter(path=>path==='/livephoto/').length===2&&
      resources.every(path=>!(/\.(wasm|m?js)$/.test(path)))&&
      await fallback.locator('canvas').count()===0&&fallbackErrors.length===0);
    await fallback.screenshot({path:`ci-output/web-ui-startup-${size}.png`});
    await blocked.close();

    const missing=await browser.newContext({viewport,serviceWorkers:'block'});
    await missing.route('**/*',r=>{
      const url=r.request().url();
      if(!url.startsWith(origin+'/')||url.endsWith('/livephoto-ui.js'))return r.abort();
      return r.continue();
    });
    const retry=await missing.newPage();lastPage=retry;
    await retry.goto(origin+'/livephoto/');
    await retry.getByRole('heading',{name:'LivePhoto 未能加载'}).waitFor({state:'visible'});
    check(size+':bundle-load-failure-is-not-reported-as-graphics',
      await retry.getByRole('alert').getByText(/检查网络连接/).isVisible()&&await retry.getByRole('button',{name:'刷新重试'}).isVisible());
    await missing.close();

    const missingWasm=await browser.newContext({viewport,serviceWorkers:'block'});
    let failWasm=true,wasmFailures=0;
    await missingWasm.route('**/*',r=>{
      const url=r.request().url();
      if(!url.startsWith(origin+'/'))return r.abort();
      if(!failWasm||!appWasm.has(new URL(url).pathname))return r.continue();
      wasmFailures++;
      if(size==='mobile')return r.abort();
      return r.fulfill({contentType:'application/wasm',body:Buffer.from('not a wasm module')});
    });
    const wasmPage=await missingWasm.newPage();lastPage=wasmPage;
    const wasmErrors=[];wasmPage.on('pageerror',e=>wasmErrors.push(e.message));
    const scriptResponse=wasmPage.waitForResponse(r=>r.url()===origin+'/livephoto/livephoto-ui.js');scriptResponse.catch(()=>{});
    await wasmPage.goto(origin+'/livephoto/');
    await wasmPage.getByRole('heading',{name:'LivePhoto 未能启动',exact:true}).waitFor({state:'visible'});
    const restart=wasmPage.getByRole('button',{name:'刷新重试'});
    check(size+':application-wasm-failure-shows-retry-after-script-success',
      (await scriptResponse).ok()&&wasmFailures===1&&await restart.isVisible()&&
      await wasmPage.getByRole('alert').getByText(/未读入你的图片或视频/).isVisible()&&
      await wasmPage.locator('canvas,input[type=file],html[data-livephoto-runtime]').count()===0&&wasmErrors.length===0);
    failWasm=false;
    await restart.click();
    await wasmPage.locator('canvas').first().waitFor({state:'visible'});await wasmPage.keyboard.press('Tab');
    await composeDriver(wasmPage,viewport).find('选择视频').waitFor({state:'attached'});
    check(size+':retry-after-wasm-failure-restores-workspace',
      await wasmPage.locator('html[data-livephoto-runtime="kotlin-wasm-compose"]').count()===1&&
      !await wasmPage.locator('#startup').isVisible()&&wasmErrors.length===0);
    await missingWasm.close();

    const withoutPreview=await browser.newContext({viewport,serviceWorkers:'block'});
    let playerBlocked=false;
    await withoutPreview.route('**/*',r=>{
      const url=r.request().url();
      if(!url.startsWith(origin+'/'))return r.abort();
      if(new URL(url).pathname==='/livephoto/media/player.mjs'){playerBlocked=true;return r.abort();}
      return r.continue();
    });
    const basic=await withoutPreview.newPage();lastPage=basic;basic.setDefaultTimeout(25000);
    const basicErrors=[];basic.on('pageerror',e=>basicErrors.push(e.message));
    await basic.goto(origin+'/livephoto/');
    await basic.locator('canvas').first().waitFor({state:'visible'});await basic.keyboard.press('Tab');
    const basicDriver=composeDriver(basic,viewport);
    await basic.getByText(/预览和视频编辑暂不可用/).waitFor({state:'attached',timeout:12000});
    await basicDriver.choose('选择视频',movie);
    await basicDriver.choose('选择图片',cover);
    await basic.getByText('名称：theme.mp4',{exact:true}).waitFor({state:'attached'});
    await basic.getByText('名称：theme.jpg',{exact:true}).waitFor({state:'attached'});
    check(size+':missing-player-shows-limited-mode-with-file-pickers',playerBlocked&&
      await basic.getByText(/Unable to load bundled font/).count()===0&&
      await basicDriver.find('从视频选封面').count()===0&&await basic.locator('video').count()===0);
    const pending=basic.waitForEvent('download');pending.catch(()=>{});
    await basicDriver.click('生成动态照片');
    const download=await pending;
    const output=await readFile(await download.path()),original=await readFile(movie);
    await basicDriver.click('照片工具');
    await basicDriver.choose('选择待检查照片',{name:download.suggestedFilename(),mimeType:'image/jpeg',buffer:output});
    await basicDriver.click('开始检查（只读）');
    await basic.getByText(/识别为动态照片/).waitFor({state:'attached'});
    check(size+':missing-player-keeps-generation-and-read-only-inspection',
      output.length>original.length&&output.subarray(-original.length).equals(original)&&basicErrors.length===0);
    await withoutPreview.close();
  }
  if(checks.length!==12||new Set(checks).size!==12)throw Error('Incomplete theme and startup evidence');
  await writeFile(`ci-output/web-theme-evidence-${part}.json`,JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,browserVersion:browser.version(),part,tests:checks.length,failures:0,checks,scope:'browser-preference-shared-compose-real-pixels-and-pointer-session-override'},null,2)+'\n');
  console.log(`WEB_THEME_EVIDENCE=SUCCESS 12 checks; ${part}`);
}catch(error){
  if(lastPage&&!lastPage.isClosed()){
    await lastPage.screenshot({path:`ci-output/web-ui-theme-${part}-failure.png`}).catch(()=>{});
    console.error('Theme accessibility',await lastPage.locator('body').ariaSnapshot().catch(()=>''));}
  console.error('Theme failure after checks',checks,error);throw error;
}finally{await browser.close();await new Promise(r=>server.close(r));}
