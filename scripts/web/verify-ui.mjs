import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdtemp } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { resolve, sep, join, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import {composeDriver} from './ui-interaction.mjs';
import {uiCases} from './test-cases.mjs';

const scenario = process.env.LIVEPHOTO_UI_SCENARIO;
const scenarios = ['create', 'analysis', 'extract', 'split', 'convert', 'repair', 'key'];
if (!scenarios.includes(scenario)) throw Error('Select LIVEPHOTO_UI_SCENARIO');
const shard = process.env.LIVEPHOTO_UI_VIEWPORT;
if (shard !== undefined && !['mobile', 'wide'].includes(shard)) throw Error('Invalid LIVEPHOTO_UI_VIEWPORT');
const suffix = `-${scenario}${shard ? `-${shard}` : ''}`;
const viewports = [['mobile',{width:390,height:844}],['wide',{width:1280,height:800}]]
  .filter(([label]) => !shard || label === shard);
const root = resolve('ci-output/web-ui');
const apiRoot = resolve('ci-output/browser-api/web-library');
const uiManifest = JSON.parse((await readFile('ci-output/web-ui-package-evidence.json','utf8')).replace(/^\uFEFF/,''));
const fixtures = await mkdtemp(join(tmpdir(), 'livephoto-ui-'));
const ffmpeg = process.env.LIVEPHOTO_FFMPEG;
if (!ffmpeg) throw Error('Synthetic media generator required');
const generate = args => execFileSync(ffmpeg, ['-nostdin', '-n', '-hide_banner', '-loglevel', 'error', ...args], { timeout: 60000 });
const imagePath = join(fixtures, 'cover.jpg'), videoPath = join(fixtures, 'clip.mp4');
generate(['-f','lavfi','-i','color=c=blue:s=64x64','-frames:v','1','-threads','1','-update','1',imagePath]);
// Deliberately fractional frame PTS: do not hide timestamp failures behind a
// 25fps/microsecond-clock fixture. Audio and byte-exact extraction stay tested.
generate(['-f','lavfi','-i','testsrc2=size=64x64:rate=30000/1001','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','3','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-bf','0','-g','30','-c:a','aac','-use_editlist','0','-video_track_timescale','90000','-movflags','+faststart','-map_metadata','-1','-metadata:s:v','encoder=','-fflags','+bitexact','-flags:v','+bitexact','-write_btrt','0',videoPath]);
const video = await readFile(videoPath);
const videoHash = createHash('sha256').update(video).digest('hex');
const server = createServer(async (req,res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const api = url.pathname.startsWith('/api/');
    if (url.pathname === '/fixture-image') { res.end(await readFile(imagePath)); return; }
    if (url.pathname === '/fixture-video') { res.end(video); return; }
    if (!api && !url.pathname.startsWith('/livephoto/')) { res.writeHead(404).end(); return; }
    const directory = api ? apiRoot : root;
    const relative = api ? url.pathname.slice(4) : url.pathname.slice('/livephoto'.length);
    const name = relative === '/' ? '/index.html' : relative;
    const path = resolve(directory, '.' + decodeURIComponent(name));
    if (!path.startsWith(directory + sep)) { res.writeHead(403).end(); return; }
    res.setHeader('Content-Type', {'.html':'text/html','.js':'text/javascript','.mjs':'text/javascript','.wasm':'application/wasm','.css':'text/css'}[extname(path)] ?? 'application/octet-stream');
    res.end(await readFile(path));
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true });
const checks = [];
let lastPage;
const check = (name, valid) => { if (!valid) throw Error(name); checks.push(name); console.log('UI_CHECK',name); };
try {
  for (const [label,viewport] of viewports) {
    const context = await browser.newContext({viewport,acceptDownloads:true,serviceWorkers:'block',permissions:['clipboard-read','clipboard-write']});
    await context.route('**/*', route => route.request().url().startsWith(origin + '/') ? route.continue() : route.abort());
    const page = await context.newPage();
    const loaded = [];
    page.on('response', response => loaded.push({url:response.url(),status:response.status()}));
    lastPage = page;
    const driver=composeDriver(page,viewport);
    const errors=[]; page.on('pageerror', error => {errors.push(String(error));console.error('Shared UI page error',String(error));});
    page.setDefaultTimeout(30000);
    await page.goto(origin + '/livephoto/');
    await page.locator('canvas').first().waitFor({state:'visible'});
    await page.keyboard.press('Tab');
    const text = value => page.getByText(value).or(page.getByLabel(value)).first();
    const button = name => page.getByRole('button',{name,exact:typeof name==='string'});
    const settle = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const click = name => driver.click(name);
    const choose = (name,path) => driver.choose(name,path);
    let downloads=0; page.on('download',()=>downloads++);
    const analysisInputs=[];
    if (['create','analysis','extract','split','convert','key','repair'].includes(scenario)) {
    await page.evaluate(()=>{
      const slice=Blob.prototype.slice;
      globalThis.analysisIo={fullReads:0,slices:0,maxSlice:0};
      globalThis.taskWorkers={started:0,runs:0,terminated:0,outputBlobs:0,invalidOutputs:0};
      globalThis.releaseTaskReads=[];
      const NativeWorker=globalThis.Worker;
      globalThis.Worker=class extends NativeWorker {
        constructor(url,options){super(url,options);this.taskWorker=String(url).endsWith('/worker/livephoto-worker.js');if(this.taskWorker){
          globalThis.taskWorkers.started++;
          this.addEventListener('message',({data})=>{if(data.type==='done')for(const value of [data.result?.bytes,data.result?.second])if(value!=null){
            if(value instanceof Blob)globalThis.taskWorkers.outputBlobs++;else globalThis.taskWorkers.invalidOutputs++;
          }});
        }}
        postMessage(data,...rest){if(this.taskWorker&&data.type==='run')globalThis.taskWorkers.runs++;return super.postMessage(data,...rest);}
        terminate(){if(this.taskWorker)globalThis.taskWorkers.terminated++;return super.terminate();}
      };
      // Fail if a protocol UI path regresses to eagerly loading a selected File.
      File.prototype.arrayBuffer=function(){
        globalThis.analysisIo.fullReads++;
        return Promise.reject(Error('Photo operations must read bounded Blob slices'));
      };
      Blob.prototype.slice=function(...args){
        const part=slice.apply(this,args);
        if(this instanceof File){
          globalThis.analysisIo.slices++;
          globalThis.analysisIo.maxSlice=Math.max(globalThis.analysisIo.maxSlice,part.size);
          if(['unreadable.jpg','unreadable.mp4'].includes(this.name))Object.defineProperty(part,'arrayBuffer',{
            value:()=>Promise.reject(new DOMException('Synthetic read failure','NotReadableError')),
          });
          if(this.name==='cancel-task.jpg'&&globalThis.holdTaskReads){
            const read=part.arrayBuffer.bind(part);
            Object.defineProperty(part,'arrayBuffer',{value:()=>new Promise(resolve=>globalThis.releaseTaskReads.push(()=>resolve(read())))});
          }
        }
        return part;
      };
    });
    }
    if (scenario === 'create') {
    await page.evaluate(()=>navigator.clipboard.writeText('not-automatically-copied'));
    await button('生成动态照片').waitFor({state:'attached'});
    check(label+':subpath-assets-loaded',
      await page.evaluate(()=>document.documentElement.dataset.livephotoRuntime)==='kotlin-wasm-compose' &&
      uiManifest.kotlinRuntime==='wasmJs' && uiManifest.applicationWasm?.length>0 &&
      uiManifest.applicationWasm.every(name=>loaded.some(r=>r.url===origin+'/livephoto/'+name&&r.status===200)) &&
      loaded.some(r=>r.url.startsWith(origin+'/livephoto/')&&r.url.endsWith('.wasm')&&r.status===200) &&
      loaded.some(r=>r.url===origin+'/livephoto/fonts/NotoSansSC-Regular.otf'&&r.status===200) &&
      loaded.every(r=>r.status===200&&r.url.startsWith(origin+'/livephoto/')));
    // Compose's semantic DOM does not expose native HTML disabled state consistently.
    // Exercise the real canvas hit target instead: no inputs must not start a download or alter the status.
    await click('生成动态照片');
    await page.waitForTimeout(200);
    check(label+':initial-create-disabled',downloads===0&&await page.locator('#result-card').count()===0&&await text('还需选择图片和视频').count()===1);
    await page.mouse.move(viewport.width/2,viewport.height/2);
    await page.mouse.wheel(0,-2000);
    await page.waitForTimeout(200);
    const bar=await text('LivePhoto').boundingBox(), input=await button('选择图片').boundingBox();
    check(label+':appbar-does-not-cover-picker',bar && input && input.y>=bar.y+bar.height);
    await page.screenshot({path:`ci-output/web-ui-${label}-before.png`});
    await choose('选择图片',imagePath);
    await text('名称：cover.jpg').waitFor({state:'attached'});
    await choose('选择视频',videoPath);
    await text('名称：cover.jpg').waitFor({state:'attached'});
    await text('名称：clip.mp4').waitFor({state:'attached'});
    check(label+':shared-file-names-and-sizes',await page.getByText(/大小：/).or(page.getByLabel(/大小：/)).count()>=2);
    await page.locator('#livephoto-image-input').setInputFiles([]);
    check(label+':cancel-keeps-selection',await text('名称：cover.jpg').count()===1);
    await page.waitForFunction(()=>document.querySelector('video[aria-label="视频预览"]')?.readyState>=2);
    const originalVideo=await page.getByLabel('视频预览',{exact:true}).evaluate(v=>{v.pause();v.currentTime=.4;return v.currentSrc;});
    await page.waitForFunction(()=>!document.querySelector('video[aria-label="视频预览"]').seeking);
    let retained=true;
    for(const [layout,size] of [['double',{width:900,height:800}],['triple',{width:1440,height:900}]]){
      await page.setViewportSize(size);await page.locator('#layout-'+layout).waitFor({state:'attached'});await page.waitForTimeout(200);
      retained&&=await page.getByLabel('视频预览',{exact:true}).evaluate((v,src)=>v.currentSrc===src&&v.paused&&Math.abs(v.currentTime-.4)<.05,originalVideo);
      if(label==='wide')await page.screenshot({path:`ci-output/web-ui-layout-${layout}.png`});
    }
    await page.setViewportSize(viewport);await page.waitForTimeout(200);
    await click('照片工具');await button('选择待检查照片').waitFor({state:'attached'});await click('新建');
    retained&&=await page.getByLabel('视频预览',{exact:true}).evaluate((v,src)=>v.currentSrc===src&&v.paused&&Math.abs(v.currentTime-.4)<.05,originalVideo);
    check(label+':responsive-workspace-retains-video',retained&&await text('名称：cover.jpg').count()===1&&await text('名称：clip.mp4').count()===1);
    await click('裁剪范围');await driver.typeField('#trim-end','1.5');
    await click('裁剪范围');const beforeDraft=downloads;await click('生成动态照片');await page.waitForTimeout(200);
    const pendingBlocked=downloads===beforeDraft&&(await page.locator('#input-readiness').textContent()).includes('先应用裁剪');
    await click('裁剪范围');const keptDraft=(await page.locator('#trim-end').textContent()).includes('1.5');
    await click('重置选段');await click('裁剪范围');
    check(label+':pending-trim-and-reset-draft',pendingBlocked&&keptDraft&&downloads===beforeDraft&&
      (await page.locator('#input-readiness').textContent()).includes('素材已准备好'));
    // Cancel even while the transport module is still loading, then recover via the real create flow.
    let releaseRuntime;
    const runtimeHold=new Promise(resolve=>{releaseRuntime=resolve;});
    const runtimeSeen=page.waitForRequest('**/ui-task-runtime.mjs');runtimeSeen.catch(()=>{});
    await page.route('**/ui-task-runtime.mjs',async route=>{await runtimeHold;await route.continue();});
    const beforeLoading=downloads;
    try {
      await click('生成动态照片');await runtimeSeen;
      await click('取消处理');await text(/已取消处理/).waitFor({state:'attached'});
      const report=await driver.report(page.locator('#status'));
      if(downloads!==beforeLoading||!report.includes('CANCELLED'))throw Error('Runtime loading cancellation published output or lost its report');
    } finally {releaseRuntime();}
    for(const [target,name] of [['oplus.olive',/OPPO \/ OnePlus/],['google.motionphoto.v2',/Google Motion Photo V2/],['google.microvideo.v1',/Google MicroVideo V1/]]) {
      await driver.selectProtocol(name.source.includes('OPPO')?'OPPO / OnePlus':name.source.includes('V2')?'Google Motion Photo V2':'Google MicroVideo V1');
      const pending=page.waitForEvent('download');
      pending.catch(()=>{});
      await click('生成动态照片');
      const download=await pending;
      if(await download.failure()) throw Error('Browser download failed');
      const output=await readFile(await download.path());
      analysisInputs.push({target,output});
      const audit=await page.evaluate(async ({data,target})=>{
        const api=await import('/api/livephoto.mjs');
        const blob=new Blob([new Uint8Array(data)]);
        const detected=await api.detectLivePhoto(blob), validation=await api.validateLivePhoto(blob), extracted=await api.extractVideo(blob);
        if(!extracted.success) throw Error(extracted.message);
        const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await extracted.blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
        return {valid:detected.protocol===target&&validation.success&&validation.verdict!=='Invalid'&&validation.coverage==='Complete',digest};
      },{data:Array.from(output),target});
      const io=await page.evaluate(()=>globalThis.analysisIo);
      check(label+':'+target+'-download-valid-exact-video',audit.valid&&audit.digest===videoHash&&download.suggestedFilename().endsWith('.jpg')&&
        io.slices>0&&io.maxSlice<=64*1024&&io.fullReads===0);
      // The shared screen animates back to the result card after generation. Do not
      // chase moving canvas targets while that intentional scroll animation is active.
      await text(/已生成并发起浏览器下载/).waitFor({state:'visible'});
      await page.waitForTimeout(500);
    }
    await text(/已生成并发起浏览器下载/).waitFor({state:'visible'});
    const notCopied=await page.evaluate(()=>navigator.clipboard.readText())==='not-automatically-copied';
    await driver.click('复制报告');await text('已复制').waitFor({state:'attached'});
    const copied=await page.evaluate(()=>navigator.clipboard.readText());
    check(label+':honest-download-status',await text(/已生成并发起浏览器下载/).count()===1&&notCopied&&copied.includes('Core 验证：')&&copied.includes('SHA-256：')&&
      await page.locator('#result-file-name').textContent()==='LivePhoto-google.microvideo.v1.jpg');
    await click('按住预览生成结果');
    const preview=page.getByRole('button',{name:'按住预览生成结果',exact:true});
    const previewBounds=await preview.boundingBox();
    await page.mouse.move(previewBounds.x+previewBounds.width/2,previewBounds.y+previewBounds.height/2);
    await page.mouse.down();
    await page.waitForFunction(()=>{const v=document.querySelector('video[aria-label="生成结果动态预览"]');return v&&!v.paused&&!v.hidden&&v.readyState>=2;});
    await page.mouse.up();
    await page.waitForFunction(()=>{const v=document.querySelector('video[aria-label="生成结果动态预览"]');return v&&v.paused&&v.hidden;});
    check(label+':result-hold-preview',await page.getByAltText('上次生成的封面').evaluate(image=>!image.hidden&&image.complete));
    await page.mouse.wheel(0,-4000);await page.waitForTimeout(250);
    await page.screenshot({path:`ci-output/web-ui-${label}-success.png`});
    check(label+':viewport-contained',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await choose('重新选择图片',imagePath);
    await choose('重新选择视频',{name:'broken.mp4',mimeType:'video/mp4',buffer:Buffer.from([0,1,2,3])});
    await click('生成动态照片');
    await text(/错误码：/).waitFor({state:'visible'});
    const malformedVisible=await text(/生成失败/).count()>0;
    let ioFailuresNoOutput=true;
    for(const kind of ['image','video']) {
      await choose('重新选择图片',kind==='image'?{name:'unreadable.jpg',mimeType:'image/jpeg',buffer:await readFile(imagePath)}:imagePath);
      await choose('重新选择视频',kind==='video'?{name:'unreadable.mp4',mimeType:'video/mp4',buffer:video}:videoPath);
      const prior=downloads;
      await click('生成动态照片');await text(/IO_READ_FAILED/).waitFor({state:'attached'});
      await page.getByText('本次结果',{exact:true}).waitFor({state:'attached'});
      const report=await driver.report(page.locator('#status'));
      ioFailuresNoOutput&&=downloads===prior&&report.includes('阶段：Read')&&
        report.includes('来源：browser-'+kind)&&report.includes('关联对象：'+(kind==='image'?'图片输入':'视频输入'));
    }
    await choose('重新选择图片',imagePath);
    check(label+':malformed-input-error-visible',malformedVisible&&ioFailuresNoOutput&&errors.length===0);
    const beforeReset=downloads;await click('移除视频');
    const removed=await text('视频：未选择').count()===1&&await text('名称：cover.jpg').count()===1;
    await click('重置输入');await click('取消');
    const cancelledReset=await text('名称：cover.jpg').count()===1;
    await click('重置输入');await click('确认重置');
    check(label+':input-removal-and-reset-confirmation',removed&&cancelledReset&&downloads===beforeReset&&
      await text('图片：未选择').count()===1&&await text('视频：未选择').count()===1&&await text('上次结果 · 输入已变化').count()===1);

    } else {
      // Independent setup through the already-tested API, not another UI creation run.
      const inputs = await page.evaluate(async () => {
        const api = await import('/api/livephoto.mjs');
        const image = await (await fetch('/fixture-image')).blob(), video = await (await fetch('/fixture-video')).blob();
        return Promise.all(['oplus.olive','google.motionphoto.v2','google.microvideo.v1'].map(async target => {
          const result = await api.createLivePhoto(image, video, {target});
          if (!result.success) throw Error('UI fixture creation failed: '+result.message);
          return {target, bytes:Array.from(new Uint8Array(await result.blob.arrayBuffer()))};
        }));
      });
      analysisInputs.push(...inputs.map(({target,bytes}) => ({target,output:Buffer.from(bytes)})));
      await driver.click('照片工具');
    const analysisPicker=async input=>driver.choose(/^(?:重新)?选择待检查照片$/,input);
    const analyze=async(input,expected)=>{
      await analysisPicker(input);
      await driver.click('开始检查（只读）');
      await text(expected).waitFor({state:'attached'});
      return driver.report(text(expected));
    };
    const oversized=join(fixtures,'oversized.jpg');
    await writeFile(oversized,Buffer.alloc(32*1024*1024+1));
    if (scenario === 'analysis') {
    const downloadsBeforeAnalysis=downloads;
    for(const {target,output} of analysisInputs) {
      const report=await analyze({name:target+'.jpg',mimeType:'image/jpeg',buffer:output},/只读检查完成/);
      check(label+':analysis-live-'+target,report.includes('识别结果：Live')&&report.includes('首选协议：'+target)&&
        report.includes('Structure /')&&report.includes('Protocol /')&&report.includes('Media /')&&
        report.includes('关键照片来源：')&&report.includes('不是完整播放或设备相册兼容证明'));
      if(target==='oplus.olive') {
        await page.mouse.move(viewport.width/2,viewport.height*.6);
        await page.mouse.wheel(0,320);await page.waitForTimeout(250);
        await page.screenshot({path:`ci-output/web-ui-analysis-${label}.png`});
      }
    }
    const ordinary=await analyze(imagePath,/只读检查完成/);
    check(label+':analysis-ordinary-not-live',ordinary.includes('识别结果：NonLive')&&ordinary.includes('首选协议：无'));
    const unknown=await analyze({name:'unknown.jpg',mimeType:'image/jpeg',buffer:Buffer.from([0,1,2,3])},/只读检查完成/);
    check(label+':analysis-unknown-not-nonlive',unknown.includes('识别结果：Unknown')&&!unknown.includes('识别结果：NonLive'));
    const broken=await analyze({name:'malformed.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,225,0,16,0,0])},/检查失败/);
    const unreadable=await analyze({name:'unreadable.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,217])},/检查失败/);
    check(label+':analysis-malformed-error-not-nonlive',broken.includes('错误码：')&&broken.includes('阶段：')&&broken.includes('检查失败不等于普通照片')&&
      unreadable.includes('IO_READ_FAILED')&&unreadable.includes('阶段：Read')&&!unreadable.includes('识别结果：NonLive'));
    const slicesBeforeBudget=await page.evaluate(()=>globalThis.analysisIo.slices);
    const budget=await analyze(oversized,/检查失败/);
    const io=await page.evaluate(()=>globalThis.analysisIo);
    check(label+':analysis-budget-before-parse',budget.includes('RESOURCE_LIMIT_EXCEEDED')&&budget.includes('阶段：Read')&&
      io.slices===slicesBeforeBudget&&io.slices>0&&io.maxSlice<=64*1024&&io.fullReads===0);
    await page.locator('#livephoto-analysis-input').setInputFiles([]);
    check(label+':analysis-cancel-keeps-report',await driver.report(text(/检查失败/))===budget&&await text('待检查文件：oversized.jpg').count()===1);
    await analysisPicker({name:'cancel-task.jpg',mimeType:'image/jpeg',buffer:await readFile(imagePath)});
    await page.evaluate(()=>{globalThis.holdTaskReads=true;});
    await click('开始检查（只读）');
    await page.waitForFunction(()=>globalThis.releaseTaskReads.length>0);
    await click('取消处理');await text(/已取消处理/).waitFor({state:'attached'});
    const cancelledAnalysis=(await driver.report(text(/检查失败/))).includes('CANCELLED');
    await page.evaluate(()=>{globalThis.holdTaskReads=false;globalThis.releaseTaskReads.splice(0).forEach(release=>release());});
    const recovered=await analyze(imagePath,/只读检查完成/);
    const workers=await page.evaluate(()=>globalThis.taskWorkers);
    check(label+':analysis-does-not-alter-create-state',downloads===downloadsBeforeAnalysis&&
      cancelledAnalysis&&workers.terminated===1&&workers.started===2&&recovered.includes('识别结果：NonLive')&&
      await driver.emptyCreateInputs()&&
      errors.length===0);

    }
    if (scenario === 'extract') {
    const beforeExtraction=downloads;
    let inspectionPreserved=true,staleExportCleared=true;
    for(const {target,output} of analysisInputs.filter(p=>p.target==='oplus.olive')) {
      const inspection=await analyze({name:target+'.jpg',mimeType:'image/jpeg',buffer:output},/只读检查完成/);
      const pending=page.waitForEvent('download');pending.catch(()=>{});
      await driver.click('提取原始视频');
      const download=await pending;
      if(await download.failure())throw Error('Raw video download failed');
      const bytes=await readFile(await download.path());
      await text(/已提取并发起视频下载/).waitFor({state:'attached'});
      const report=await driver.report(text(/已提取并发起视频下载/));
      check(label+':extract-'+target+'-exact-video',createHash('sha256').update(bytes).digest('hex')===videoHash&&
        download.suggestedFilename()==='LivePhoto-video.mp4'&&report.includes(videoHash)&&
        report.includes('ExactExtraction：Verified')&&report.includes('video/mp4')&&report.includes('不是 Clean Split'));
      inspectionPreserved&&=await driver.report(text(/只读检查完成/))===inspection;
      if(target==='oplus.olive') {
        await page.mouse.move(viewport.width/2,viewport.height*.6);
        await page.mouse.wheel(0,180);await page.waitForTimeout(250);
        await page.screenshot({path:`ci-output/web-ui-extraction-${label}.png`});
      }
    }
    for(const [kind,input,code] of [
      ['ordinary',imagePath,'MOTION_VIDEO_MISSING'],
      ['malformed',{name:'malformed.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,225,0,16,0,0])},null],
      ['budget',oversized,'RESOURCE_LIMIT_EXCEEDED'],
    ]) {
      await analysisPicker(input);
      staleExportCleared&&=await text(/已提取并发起视频下载/).count()===0;
      const slicesBefore=await page.evaluate(()=>globalThis.analysisIo.slices);
      const prior=downloads;
      await driver.click('提取原始视频');
      await text(/提取失败/).waitFor({state:'attached'});
      const report=await driver.report(text(/提取失败/));
      const io=await page.evaluate(()=>globalThis.analysisIo);
      check(label+':extract-'+kind+'-no-output',downloads===prior&&report.includes('错误码：')&&
        report.includes('阶段：')&&(!code||report.includes(code))&&
        (kind!=='budget'||io.slices===slicesBefore)&&io.fullReads===0&&io.maxSlice<=64*1024);
    }
    await analysisPicker({name:'unreadable.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,217])});
    await driver.click('提取原始视频');await text(/提取失败/).waitFor({state:'attached'});
    const failedRead=await driver.report(text(/提取失败/));
    check(label+':extract-clears-stale-report',staleExportCleared);
    check(label+':extract-preserves-analysis-and-create-state',inspectionPreserved&&downloads===beforeExtraction+1&&
      failedRead.includes('IO_READ_FAILED')&&failedRead.includes('阶段：Read')&&
      await driver.emptyCreateInputs()&&errors.length===0);

    }
    if (scenario === 'split') {
    const beforeSplit=downloads;
    let splitInspectionPreserved=true,splitStaleCleared=true;
    for(const {target,output} of analysisInputs.filter(p=>p.target==='oplus.olive')) {
      const inspection=await analyze({name:target+'.jpg',mimeType:'image/jpeg',buffer:output},/只读检查完成/);
      const prior=downloads;
      await driver.click('准备清理拆分');
      await text(/清理拆分已准备/).waitFor({state:'attached'});
      const report=await driver.report(text(/清理拆分已准备/));
      const preparedOnly=downloads===prior&&report.includes('尚未保存')&&report.includes('不是外部原子成对保存')&&
        report.includes('ImageDataPreserving：Verified')&&report.includes(videoHash);
      const outputs=[];
      for(const [name,extension] of [['保存拆分图片','jpg'],['保存拆分视频','mp4']]) {
        const pending=page.waitForEvent('download');pending.catch(()=>{});
        await driver.click(name);
        const download=await pending;
        if(await download.failure())throw Error('Clean split download failed');
        if(download.suggestedFilename()!==`LivePhoto-clean.${extension}`)throw Error('Wrong clean split filename');
        outputs.push(await readFile(await download.path()));
        await text(extension==='jpg'?/image\/jpeg：已发起下载/:/video\/mp4：已发起下载/).waitFor({state:'attached'});
      }
      const audit=await page.evaluate(async data=>{
        const api=await import('/api/livephoto.mjs'),blob=new Blob([new Uint8Array(data)],{type:'image/jpeg'});
        const detection=await api.detectLivePhoto(blob), again=await api.splitLivePhoto(blob);
        const digest=async b=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await b.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
        const bitmap=await createImageBitmap(blob);
        const decoded=bitmap.width===64&&bitmap.height===64;bitmap.close();
        return {nonLive:detection.verdict==='NonLive',idempotent:again.success&&again.assets.length===1&&
          await digest(again.blob)===await digest(blob),decoded};
      },Array.from(outputs[0]));
      check(label+':split-'+target+'-clean-independent-files',preparedOnly&&audit.nonLive&&audit.idempotent&&audit.decoded&&
        createHash('sha256').update(outputs[1]).digest('hex')===videoHash&&
        report.includes(createHash('sha256').update(outputs[0]).digest('hex'))&&downloads===prior+2);
      splitInspectionPreserved&&=await driver.report(text(/只读检查完成/))===inspection;
      if(target==='oplus.olive') {
        await page.mouse.move(viewport.width/2,viewport.height*.6);
        await page.mouse.wheel(0,300);await page.waitForTimeout(250);
        await page.screenshot({path:`ci-output/web-ui-split-${label}.png`});
      }
    }
    for(const [kind,input] of [
      ['ordinary',imagePath],
      ['malformed',{name:'malformed.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,225,0,16,0,0])}],
      ['budget',oversized],
    ]) {
      await analysisPicker(input);
      splitStaleCleared&&=await text(/清理拆分已准备/).count()===0&&await button('保存拆分图片').count()===0&&await button('保存拆分视频').count()===0;
      const slicesBefore=await page.evaluate(()=>globalThis.analysisIo.slices);
      const prior=downloads;
      await driver.click('准备清理拆分');
      await text(/清理拆分失败/).waitFor({state:'attached'});
      const report=await driver.report(text(/清理拆分失败/));
      const io=await page.evaluate(()=>globalThis.analysisIo);
      check(label+':split-'+kind+'-no-output',downloads===prior&&report.includes('错误码：')&&report.includes('阶段：')&&
        (kind!=='budget'||report.includes('RESOURCE_LIMIT_EXCEEDED')&&io.slices===slicesBefore)&&
        io.fullReads===0&&io.maxSlice<=64*1024&&await button('保存拆分图片').count()===0);
    }
    await analysisPicker({name:'unreadable.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,217])});
    await driver.click('准备清理拆分');await text(/清理拆分失败/).waitFor({state:'attached'});
    const failedRead=await driver.report(text(/清理拆分失败/));
    check(label+':split-clears-stale-preparation',splitStaleCleared);
    check(label+':split-preserves-analysis-and-create-state',splitInspectionPreserved&&downloads===beforeSplit+2&&
      failedRead.includes('IO_READ_FAILED')&&failedRead.includes('阶段：Read')&&await button('保存拆分图片').count()===0&&
      await driver.emptyCreateInputs()&&errors.length===0);

    }
    if (scenario === 'convert') {
    const conversionNames={'oplus.olive':'OPPO / OnePlus','google.motionphoto.v2':'Google V2','google.microvideo.v1':'Google V1'};
    const conversionReport=()=>text(/(?:已转换并发起下载|转换失败)/);
    const beforeConversion=downloads;
    let conversionInspectionPreserved=true,conversionStaleCleared=true;
    for(const {target:sourceTarget,output} of analysisInputs) {
      const inspection=await analyze({name:sourceTarget+'.jpg',mimeType:'image/jpeg',buffer:output},/只读检查完成/);
      conversionStaleCleared&&=await conversionReport().count()===0;
      const targets = {'oplus.olive':['google.motionphoto.v2'], 'google.motionphoto.v2':['oplus.olive'], 'google.microvideo.v1':['google.microvideo.v1']}[sourceTarget];
      for(const target of targets) {
        await driver.click(new RegExp('^(?:✓ )?转换为 '+conversionNames[target]+'$'));
        conversionStaleCleared&&=await conversionReport().count()===0;
        const blocked=sourceTarget==='oplus.olive'&&target!==sourceTarget;
        const prior=downloads;
        const pending=blocked?null:page.waitForEvent('download');pending?.catch(()=>{});
        await driver.click('转换并另存');
        if(blocked) {
          await text(/转换失败/).waitFor({state:'attached'});
          const report=await driver.report(conversionReport());
          check(label+':convert-'+sourceTarget+'-to-'+target,downloads===prior&&report.includes('UNSAFE_METADATA_REWRITE')&&report.includes('阶段：'));
        } else {
          const download=await pending;
          if(await download.failure())throw Error('Converted photo download failed');
          const bytes=await readFile(await download.path());
          await text(/已转换并发起下载/).waitFor({state:'attached'});
          const report=await driver.report(conversionReport());
          const audit=await page.evaluate(async ({data,original,target})=>{
            const api=await import('/api/livephoto.mjs');
            const blob=new Blob([new Uint8Array(data)]),source=new Blob([new Uint8Array(original)]);
            const detection=await api.detectLivePhoto(blob),validation=await api.validateLivePhoto(blob),video=await api.extractVideo(blob);
            const before=await api.getKeyPhotoPosition(source),after=await api.getKeyPhotoPosition(blob);
            if(!video.success)throw Error(video.message);
            const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await video.blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
            return {valid:detection.protocol===target&&validation.success&&validation.verdict!=='Invalid',digest,
              key:before.success&&after.success&&JSON.stringify(before.report.position)===JSON.stringify(after.report.position)};
          },{data:Array.from(bytes),original:Array.from(output),target});
          check(label+':convert-'+sourceTarget+'-to-'+target,audit.valid&&audit.key&&audit.digest===videoHash&&
            report.includes(createHash('sha256').update(bytes).digest('hex'))&&report.includes('目标：'+target)&&
            download.suggestedFilename()===`LivePhoto-converted-${target}.jpg`&&downloads===prior+1&&
            (sourceTarget===target?bytes.equals(output)&&report.includes('整个载体字节一致'):report.includes('不宣称整个文件字节一致')));
          if(sourceTarget==='google.motionphoto.v2'&&target==='oplus.olive') {
            await page.mouse.move((viewport.width-Math.min(viewport.width,840))/2+8,viewport.height/2);
            await page.mouse.wheel(0,220);await page.waitForTimeout(350);
            await page.screenshot({path:`ci-output/web-ui-conversion-${label}.png`});
          }
        }
        conversionInspectionPreserved&&=await driver.report(text(/只读检查完成/))===inspection;
      }
    }
    for(const [kind,input] of [
      ['ordinary',imagePath],
      ['malformed',{name:'malformed.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,225,0,16,0,0])}],
      ['budget',oversized],
    ]) {
      await analysisPicker(input);
      conversionStaleCleared&&=await conversionReport().count()===0;
      const slicesBefore=await page.evaluate(()=>globalThis.analysisIo.slices);
      const prior=downloads;
      await driver.click('转换并另存');
      await text(/转换失败/).waitFor({state:'attached'});
      const report=await driver.report(conversionReport());
      const io=await page.evaluate(()=>globalThis.analysisIo);
      check(label+':convert-'+kind+'-no-output',downloads===prior&&report.includes('错误码：')&&report.includes('阶段：')&&
        (kind!=='budget'||report.includes('RESOURCE_LIMIT_EXCEEDED')&&io.slices===slicesBefore)&&
        io.fullReads===0&&io.maxSlice<=64*1024);
    }
    await analysisPicker({name:'unreadable.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,217])});
    await driver.click('转换并另存');await text(/转换失败/).waitFor({state:'attached'});
    const failedRead=await driver.report(conversionReport());
    check(label+':convert-clears-stale-report',conversionStaleCleared);
    check(label+':convert-preserves-analysis-and-create-state',conversionInspectionPreserved&&downloads===beforeConversion+2&&
      failedRead.includes('IO_READ_FAILED')&&failedRead.includes('阶段：Read')&&
      await driver.emptyCreateInputs()&&errors.length===0);

    }
    if (scenario === 'repair') {
    const repairReport=()=>text(/(?:修复预览完成|无需修改（未写入）|修复被阻止|修复失败|已应用修复并发起下载)/);
    const readRepairReport=()=>driver.report(repairReport());
    // Compose canvas buttons do not expose reliable HTML disabled attributes.
    // Verify the actual hit target cannot publish or change the review instead.
    const repairCannotApply=async()=>{
      const before=await repairReport().count()?await readRepairReport():null,prior=downloads;
      await driver.click('应用修复并另存');await page.waitForTimeout(200);
      const after=await repairReport().count()?await readRepairReport():null;
      return downloads===prior&&before===after;
    };
    const damageField=(output,target,key=false)=>{
      const damaged=Buffer.from(output),header=damaged.subarray(0,damaged.length-video.length).toString('latin1');
      const expression=key?/\b(?:[A-Za-z_][\w.-]*:)?MicroVideoPresentationTimestampUs=(["'])([1-9][0-9]*)\1/:
        target==='google.microvideo.v1'?/\b(?:[A-Za-z_][\w.-]*:)?MicroVideoOffset=(["'])([1-9][0-9]*)\1/:
        /\b(?:[A-Za-z_][\w.-]*:)?Length=(["'])([1-9][0-9]*)\1/;
      const match=expression.exec(header);if(!match)throw Error('Missing synthetic repair field');
      const start=match.index+match[0].lastIndexOf(match[2]);damaged.fill(key?57:48,start,start+match[2].length);
      if(key)damaged[start]=45; // Invalid negative key, never the reserved -1.
      return damaged;
    };
    const previewRepair=async input=>{
      await analysisPicker(input);
      if(!await repairCannotApply())throw Error('Repair must require a fresh preview');
      await driver.click('预览安全修复（只读）');
      await repairReport().waitFor({state:'attached'});
      return readRepairReport();
    };
    const beforeRepair=downloads;
    let repairAnalysisPreserved=true,repairedV1Digest;
    for(const {target,output} of analysisInputs.filter(p=>p.target==='google.microvideo.v1')) {
      const prior=downloads;
      const noop=await previewRepair({name:'valid-'+target+'.jpg',mimeType:'image/jpeg',buffer:output});
      check(label+':repair-'+target+'-valid-no-output',downloads===prior&&noop.includes('无需修改（未写入）')&&await repairCannotApply());
      const damaged=damageField(output,target);
      const inspection=await analyze({name:'damaged-'+target+'.jpg',mimeType:'image/jpeg',buffer:damaged},/只读检查完成/);
      await driver.click('预览安全修复（只读）');await repairReport().waitFor({state:'attached'});
      const preview=await readRepairReport();
      check(label+':repair-'+target+'-preview-no-output',downloads===prior&&preview.includes('修复预览完成（未写入）')&&
        preview.includes(createHash('sha256').update(damaged).digest('hex'))&&preview.includes('已应用：0'));
      const pending=page.waitForEvent('download');pending.catch(()=>{});
      await driver.click('应用修复并另存');
      const download=await pending;if(await download.failure())throw Error('Repaired photo download failed');
      const repaired=await readFile(await download.path());
      await text(/已应用修复并发起下载/).waitFor({state:'attached'});
      const report=await readRepairReport();
      const audit=await page.evaluate(async data=>{
        const api=await import('/api/livephoto.mjs'),blob=new Blob([new Uint8Array(data)]);
        const validation=await api.validateLivePhoto(blob),extracted=await api.extractVideo(blob);
        if(!extracted.success)throw Error(extracted.message);
        const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await extracted.blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
        return {valid:validation.success&&validation.verdict!=='Invalid'&&validation.coverage==='Complete',digest};
      },Array.from(repaired));
      check(label+':repair-'+target+'-applied-preserving',audit.valid&&audit.digest===videoHash&&downloads===prior+1&&
        report.includes(createHash('sha256').update(repaired).digest('hex'))&&report.includes('ImageDataPreserving: Verified')&&
        report.includes('BitstreamPreserving: Verified')&&download.suggestedFilename()==='LivePhoto-repaired.jpg'&&
        await repairCannotApply());
      repairAnalysisPreserved&&=await driver.report(text(/只读检查完成/))===inspection;
      if(target==='google.microvideo.v1') {
        repairedV1Digest=createHash('sha256').update(repaired).digest('hex');
        await page.mouse.move((viewport.width-Math.min(viewport.width,840))/2+8,viewport.height/2);
        await page.mouse.wheel(0,180);await page.waitForTimeout(350);
        await page.screenshot({path:`ci-output/web-ui-repair-${label}.png`});
      }
      const again=await previewRepair({name:'repaired-'+target+'.jpg',mimeType:'image/jpeg',buffer:repaired});
      check(label+':repair-'+target+'-idempotent-no-output',downloads===prior+1&&again.includes('无需修改（未写入）')&&await repairCannotApply());
    }
    const oplus=analysisInputs.find(p=>p.target==='oplus.olive');
    const beforeOplus=downloads;
    const oplusNoop=await previewRepair({name:'oplus-valid.jpg',mimeType:'image/jpeg',buffer:oplus.output});
    const oplusNoopSafe=downloads===beforeOplus&&oplusNoop.includes('无需修改（未写入）')&&await repairCannotApply();
    const damagedOplus=damageField(oplus.output,oplus.target);
    const oplusPreview=await previewRepair({name:'oplus-damaged.jpg',mimeType:'image/jpeg',buffer:damagedOplus});
    const pendingOplus=page.waitForEvent('download');pendingOplus.catch(()=>{});
    await driver.click('应用修复并另存');
    const oplusDownload=await pendingOplus;
    if(await oplusDownload.failure())throw Error('Oplus zero-padding repair download failed');
    const repairedOplus=await readFile(await oplusDownload.path());
    await text(/已应用修复并发起下载/).waitFor({state:'attached'});
    const oplusAudit=await page.evaluate(async data=>{
      const api=await import('/api/livephoto.mjs'),blob=new Blob([new Uint8Array(data)]);
      const validation=await api.validateLivePhoto(blob),video=await api.extractVideo(blob),again=await api.repairLivePhoto(blob);
      if(!video.success)throw Error(video.message);
      const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await video.blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
      return {valid:validation.success&&validation.verdict!=='Invalid'&&validation.coverage==='Complete',digest,
        noop:again.success&&!again.report.proposedChanges.length&&!again.report.blocked.length&&!again.assets.length&&!again.blob};
    },Array.from(repairedOplus));
    check(label+':repair-oplus-zero-padding-preserving',oplusNoopSafe&&downloads===beforeOplus+1&&
      oplusPreview.includes(createHash('sha256').update(damagedOplus).digest('hex'))&&
      oplusAudit.valid&&oplusAudit.noop&&oplusAudit.digest===videoHash&&await repairCannotApply());
    const v1=analysisInputs.find(p=>p.target==='google.microvideo.v1');
    const invalidKey=damageField(v1.output,v1.target,true),priorBlocked=downloads;
    const blocked=await previewRepair({name:'blocked-key.jpg',mimeType:'image/jpeg',buffer:invalidKey});
    check(label+':repair-blocked-key-no-output',downloads===priorBlocked&&blocked.includes('修复被阻止（未写入）')&&await repairCannotApply());
    const damagedV1=damageField(v1.output,v1.target);
    await previewRepair({name:'changed-after-preview.jpg',mimeType:'image/jpeg',buffer:damagedV1});
    // Simulate a changing IO provider only, not an app/semantic handler. Real UI
    // preview and apply must reject different bytes before any external download.
    await page.evaluate(()=>{
      const slice=Blob.prototype.slice;
      Blob.prototype.slice=function(...args){
        const part=slice.apply(this,args);
        if(this instanceof File&&this.name==='changed-after-preview.jpg'&&Number(args[1])===this.size){
          const read=part.arrayBuffer.bind(part);
          Object.defineProperty(part,'arrayBuffer',{value:async()=>{
            const data=await read();const bytes=new Uint8Array(data);bytes[bytes.length-1]^=1;return data;
          }});
        }
        return part;
      };
      globalThis.restoreRepairRead=()=>{Blob.prototype.slice=slice;delete globalThis.restoreRepairRead;};
    });
    const priorStale=downloads;
    try {
      await driver.click('应用修复并另存');await text(/修复失败/).waitFor({state:'attached'});
      check(label+':repair-source-changed-no-output',downloads===priorStale&&(await readRepairReport()).includes('SOURCE_CHANGED')&&await repairCannotApply());
    } finally {await page.evaluate(()=>globalThis.restoreRepairRead());}
    for(const [kind,input] of [
      ['ordinary',imagePath],
      ['malformed',{name:'malformed.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,225,0,16,0,0])}],['budget',oversized],
    ]) {
      const slicesBefore=await page.evaluate(()=>globalThis.analysisIo.slices);
      const prior=downloads,report=await previewRepair(input);
      const io=await page.evaluate(()=>globalThis.analysisIo);
      check(label+':repair-'+kind+'-no-output',downloads===prior&&report.includes('修复失败')&&report.includes('错误码：')&&
        io.fullReads===0&&io.maxSlice<=65536&&
        (kind!=='budget'||report.includes('RESOURCE_LIMIT_EXCEEDED')&&io.slices===slicesBefore)&&await repairCannotApply());
    }
    await previewRepair({name:'cancel-task.jpg',mimeType:'image/jpeg',buffer:damagedV1});
    await page.evaluate(()=>{globalThis.holdTaskReads=true;});
    await driver.click('应用修复并另存');
    await page.waitForFunction(()=>globalThis.releaseTaskReads.length>0);
    await driver.click('取消处理');await text(/已取消处理/).waitFor({state:'attached'});
    const cancelledRepair=(await readRepairReport()).includes('CANCELLED')&&await repairCannotApply();
    await page.evaluate(()=>{globalThis.holdTaskReads=false;globalThis.releaseTaskReads.splice(0).forEach(release=>release());});
    const selectionPreview=await previewRepair({name:'reselected.jpg',mimeType:'image/jpeg',buffer:damagedV1});
    await page.locator('#livephoto-analysis-input').setInputFiles([]);
    const cancelKept=await readRepairReport()===selectionPreview;
    const cancelDownload=page.waitForEvent('download');cancelDownload.catch(()=>{});
    await driver.click('应用修复并另存');
    const confirmedDownload=await cancelDownload;
    if(await confirmedDownload.failure())throw Error('Repair download after cancelled selection failed');
    const confirmedBytes=await readFile(await confirmedDownload.path());
    await text(/已应用修复并发起下载/).waitFor({state:'attached'});
    const cancelApplied=confirmedDownload.suggestedFilename()==='LivePhoto-repaired.jpg'&&
      createHash('sha256').update(confirmedBytes).digest('hex')===repairedV1Digest;
    await analysisPicker({name:'reselected.jpg',mimeType:'image/jpeg',buffer:damagedV1});
    check(label+':repair-selection-state-isolated',cancelledRepair&&cancelKept&&cancelApplied&&await repairReport().count()===0&&await repairCannotApply());
    const failedRead=await previewRepair({name:'unreadable.jpg',mimeType:'image/jpeg',buffer:damagedV1});
    check(label+':repair-preserves-analysis-and-create-state',repairAnalysisPreserved&&downloads===beforeRepair+3&&
      failedRead.includes('IO_READ_FAILED')&&failedRead.includes('阶段：Read')&&await repairCannotApply()&&
      await driver.emptyCreateInputs()&&errors.length===0);

    }
    if (scenario === 'key') {
    const v1=analysisInputs.find(p=>p.target==='google.microvideo.v1');
    const beforeKey=downloads,keyReport=()=>text(/^(?:已修改关键照片时间并发起下载|关键照片修改失败)/);
    const readKeyReport=()=>driver.report(keyReport());
    const enterKey=async value=>{
      await driver.selectTool('修改关键照片时间');
      await driver.typeField('#key-seconds',value);
    };
    let keyAnalysisPreserved=true;
    for(const {target,output} of analysisInputs.filter(p=>p.target!=='google.motionphoto.v2')) {
      const inspection=await analyze({name:'key-'+target+'.jpg',mimeType:'image/jpeg',buffer:output},/只读检查完成/);
      await enterKey('0.47');
      if(target==='oplus.olive') {
        const prior=downloads;await driver.click('修改关键照片时间并另存');await keyReport().waitFor({state:'attached'});
        check(label+':key-'+target+'-refused',downloads===prior&&(await readKeyReport()).includes('UNSAFE_METADATA_REWRITE'));
        keyAnalysisPreserved&&=await driver.report(text(/只读检查完成/))===inspection;
        continue;
      }
      const pending=page.waitForEvent('download');pending.catch(()=>{});
      await driver.click('修改关键照片时间并另存');
      const download=await pending;if(await download.failure())throw Error('Key photo download failed');
      const edited=await readFile(await download.path());
      await keyReport().waitFor({state:'attached'});
      const report=await readKeyReport();
      const audit=await page.evaluate(async data=>{
        const api=await import('/api/livephoto.mjs'),blob=new Blob([new Uint8Array(data)]);
        const key=await api.getKeyPhotoPosition(blob),extracted=await api.extractVideo(blob),valid=await api.validateLivePhoto(blob);
        if(!key.success||!extracted.success||!key.report.position)throw Error('Cannot inspect edited key');
        const position=Number(key.report.position.value)/key.report.position.timescale;
        const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await extracted.blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
        return {position,hash,valid:valid.success&&valid.verdict!=='Invalid'&&valid.coverage==='Complete'};
      },Array.from(edited));
      check(label+':key-'+target+'-preserving',audit.valid&&Math.abs(audit.position-.47)<=.1&&audit.hash===videoHash&&
        report.includes('实际时间：')&&report.includes('ImageDataPreserving: Verified')&&report.includes('BitstreamPreserving: Verified')&&
        report.includes(createHash('sha256').update(edited).digest('hex'))&&download.suggestedFilename()==='LivePhoto-key.jpg');
      keyAnalysisPreserved&&=await driver.report(text(/只读检查完成/))===inspection;
      if(target==='google.microvideo.v1')await page.screenshot({path:`ci-output/web-ui-key-${label}.png`});
    }
    const beforeInvalid=downloads;
    let invalidNoOutput=true;
    for(const value of ['-1','NaN','0.0000001']) {
      await enterKey(value);await driver.click('修改关键照片时间并另存');
      invalidNoOutput&&=downloads===beforeInvalid&&await keyReport().count()===0;
    }
    check(label+':key-invalid-value-no-output',invalidNoOutput);
    await enterKey('999');await driver.click('修改关键照片时间并另存');await keyReport().waitFor({state:'attached'});
    check(label+':key-outside-no-output',downloads===beforeInvalid&&(await readKeyReport()).includes('INVALID_PRESENTATION_TIMESTAMP'));
    for(const [kind,input] of [['ordinary',imagePath],
      ['malformed',{name:'malformed.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,225,0,16,0,0])}],['budget',oversized]]) {
      await analysisPicker(input);
      const slicesBefore=await page.evaluate(()=>globalThis.analysisIo.slices);
      await driver.click('修改关键照片时间并另存');await keyReport().waitFor({state:'attached'});
      const report=await readKeyReport();
      const io=await page.evaluate(()=>globalThis.analysisIo);
      check(label+':key-'+kind+'-no-output',downloads===beforeInvalid&&report.includes('关键照片修改失败')&&report.includes('错误码：')&&
        (kind!=='budget'||report.includes('RESOURCE_LIMIT_EXCEEDED')&&io.slices===slicesBefore)&&
        io.fullReads===0&&io.maxSlice<=64*1024);
    }
    await analysisPicker({name:'unreadable.jpg',mimeType:'image/jpeg',buffer:Buffer.from([255,216,255,217])});
    await driver.click('修改关键照片时间并另存');await keyReport().waitFor({state:'attached'});
    const failedRead=await readKeyReport();
    await analysisPicker({name:'key-reselect.jpg',mimeType:'image/jpeg',buffer:v1.output});
    check(label+':key-selection-state-isolated',await keyReport().count()===0);
    check(label+':key-preserves-analysis-and-create-state',keyAnalysisPreserved&&downloads===beforeKey+1&&
      failedRead.includes('IO_READ_FAILED')&&failedRead.includes('阶段：Read')&&
      await driver.emptyCreateInputs()&&errors.length===0);
    }
    }
    const workerIo=await page.evaluate(()=>({workers:globalThis.taskWorkers,io:globalThis.analysisIo}));
    if(!workerIo.workers?.runs||!workerIo.workers.started||workerIo.io.fullReads!==0||workerIo.io.maxSlice>65536||
      workerIo.workers.invalidOutputs!==0||(scenario!=='analysis'&&!workerIo.workers.outputBlobs))
      throw Error('UI must execute in Kotlin/Wasm Worker with bounded input reads');
    await context.close();
  }
  const expected = viewports.length * uiCases[scenario].length;
  if(checks.length!==expected||new Set(checks).size!==expected) throw Error('Incomplete shared UI checks: '+checks.length);
  await writeFile(`ci-output/web-ui-browser-evidence${suffix}.json`,JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,browser:'Chromium',browserVersion:browser.version(),viewport:shard,scenario,tests:checks.length,failures:0,checks,scope:'same-common-compose-screen-mobile-wide-real-file-chooser-download-three-profiles-no-device-proof'},null,2)+'\n');
  console.log(`WEB_UI_EVIDENCE=SUCCESS ${expected} checks; ${scenario}; ${shard || 'mobile and wide'}; shared Compose real file chooser and audited downloads; no-op/blocked/stale/unsafe inputs never published`);
} catch(error) {
  console.error('Shared UI failure after checks:',checks, error);
  if(lastPage&&!lastPage.isClosed()) {
    await lastPage.screenshot({path:`ci-output/web-ui-failure${suffix}.png`}).catch(()=>{});
    await writeFile(`ci-output/web-ui-failure${suffix}.html`,await lastPage.content()).catch(()=>{});
    await writeFile(`ci-output/web-ui-failure${suffix}-a11y.txt`,await lastPage.locator('body').ariaSnapshot()).catch(()=>{});
    await writeFile(`ci-output/web-ui-failure${suffix}-shadow.html`,await lastPage.evaluate(()=>{
      const visit=root=>Array.from(root.children).map(e=>e.outerHTML+(e.shadowRoot?'\nSHADOW:\n'+visit(e.shadowRoot):'')+'\n'+visit(e)).join('\n');
      return visit(document.body);
    })).catch(()=>{});
  }
  throw error;
} finally { await browser.close(); await new Promise(resolve=>server.close(resolve)); }
