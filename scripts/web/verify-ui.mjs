import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdtemp } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { resolve, sep, join, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';

const root = resolve('ci-output/web-ui');
const apiRoot = resolve('ci-output/browser-api/web-library');
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
const check = (name, valid) => { if (!valid) throw Error(name); checks.push(name); };
try {
  for (const [label,viewport] of [['mobile',{width:390,height:844}],['wide',{width:1280,height:800}]]) {
    const context = await browser.newContext({viewport,acceptDownloads:true,serviceWorkers:'block'});
    await context.route('**/*', route => route.request().url().startsWith(origin + '/') ? route.continue() : route.abort());
    const page = await context.newPage();
    const loaded = [];
    page.on('response', response => loaded.push({url:response.url(),status:response.status()}));
    lastPage = page;
    const errors=[]; page.on('pageerror', error => errors.push(String(error)));
    page.setDefaultTimeout(30000);
    await page.goto(origin + '/livephoto/');
    await page.locator('canvas').first().waitFor({state:'visible'});
    await page.keyboard.press('Tab');
    const text = value => page.getByText(value).or(page.getByLabel(value)).first();
    const button = name => page.getByRole('button',{name,exact:typeof name==='string'});
    const settle = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const click = async name => {
      const locator=button(name);
      for(let attempt=0;attempt<20;attempt++) {
        await settle();
        const bounds=await locator.boundingBox().catch(()=>null);
        await settle();
        await page.waitForTimeout(200);
        const current=await locator.boundingBox().catch(()=>null);
        if(bounds && current && ['x','y','width','height'].some(key=>Math.abs(bounds[key]-current[key])>0.5)) {
          await page.waitForTimeout(150);
          continue;
        }
        // The accessibility overlay is pointer-events:none by design; users hit the canvas.
        // Use its semantic bounds to deliver a real pointer event to that canvas, not a DOM click.
        const placed=bounds && bounds.width>0 && bounds.height>0;
        if(placed && bounds.y>=64 && bounds.y+bounds.height<=viewport.height) {
          await page.mouse.click(bounds.x+bounds.width/2,bounds.y+bounds.height/2);
          await settle();
          return;
        }
        await page.mouse.move(viewport.width/2,viewport.height/2);
        // Compose reports clipped controls as 0x0 at (0,0), not their offscreen position.
        // Search down first, then up; never interpret that synthetic zero as "above the app bar".
        const direction=placed ? (bounds.y<64 ? -1 : 1) : (attempt<10 ? 1 : -1);
        await page.mouse.wheel(0,direction*320);
        await page.waitForTimeout(150);
      }
      throw Error('Shared Compose button is not reachable: '+name+'; '+errors.join(';'));
    };
    const choose = async (name,path) => {
      const picker=page.waitForEvent('filechooser');
      picker.catch(()=>{});
      await click(name);
      await (await picker).setFiles(path);
      await settle();
    };
    await button('生成并保存 Live Photo').waitFor({state:'attached'});
    check(label+':subpath-assets-loaded',
      loaded.some(r=>r.url.startsWith(origin+'/livephoto/')&&r.url.endsWith('.wasm')&&r.status===200) &&
      loaded.some(r=>r.url===origin+'/livephoto/fonts/NotoSansSC-Regular.otf'&&r.status===200) &&
      loaded.every(r=>r.status===200&&r.url.startsWith(origin+'/livephoto/')));
    // Compose's semantic DOM does not expose native HTML disabled state consistently.
    // Exercise the real canvas hit target instead: no inputs must not start a download or alter the status.
    let downloads=0; page.on('download',()=>downloads++);
    await click('生成并保存 Live Photo');
    await page.waitForTimeout(200);
    check(label+':initial-create-disabled',downloads===0&&await text(/当前不自动从视频抽取封面/).count()===1);
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
    for(const [target,name] of [['oplus.olive',/OPPO \/ OnePlus/],['google.motionphoto.v2',/Google Motion Photo V2/],['google.microvideo.v1',/Google MicroVideo V1/]]) {
      await click(name);
      await button(name).filter({hasText:/^✓ /}).waitFor({state:'attached'});
      const pending=page.waitForEvent('download');
      pending.catch(()=>{});
      await click('生成并保存 Live Photo');
      const download=await pending;
      if(await download.failure()) throw Error('Browser download failed');
      const output=await readFile(await download.path());
      const audit=await page.evaluate(async ({data,target})=>{
        const api=await import('/api/livephoto.mjs');
        const blob=new Blob([new Uint8Array(data)]);
        const detected=await api.detectLivePhoto(blob), validation=await api.validateLivePhoto(blob), extracted=await api.extractVideo(blob);
        if(!extracted.success) throw Error(extracted.message);
        const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await extracted.blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
        return {valid:detected.protocol===target&&validation.success&&validation.verdict!=='Invalid'&&validation.coverage==='Complete',digest};
      },{data:Array.from(output),target});
      check(label+':'+target+'-download-valid-exact-video',audit.valid&&audit.digest===videoHash&&download.suggestedFilename().endsWith('.jpg'));
      // The shared screen animates back to the result card after generation. Do not
      // chase moving canvas targets while that intentional scroll animation is active.
      await text(/已生成并发起浏览器下载/).waitFor({state:'visible'});
      await page.waitForTimeout(500);
    }
    await text(/已生成并发起浏览器下载/).waitFor({state:'visible'});
    check(label+':honest-download-status',await text(/已生成并发起浏览器下载/).count()===1);
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
    const player=page.getByLabel('视频预览',{exact:true});
    await player.waitFor({state:'attached'});
    const seek=async time=>{
      await player.evaluate((video,time)=>{video.pause();video.currentTime=time;},time);
      await page.waitForFunction(time=>{const v=document.querySelector('video[aria-label="视频预览"]');return v&&!v.seeking&&v.readyState>=2&&Math.abs(v.currentTime-time)<.01;},time);
      await page.waitForTimeout(150);
    };
    await seek(1.4);
    await click('截取当前画面为 C／封面');
    await text('名称：cover-C.jpg').waitFor({state:'attached'});
    await page.waitForFunction(()=>{const image=document.querySelector('img[alt="已选封面预览"]');return image?.complete&&image.naturalWidth>0;});
    check(label+':current-frame-cover-selected',await page.getByAltText('已选封面预览').evaluate(image=>image.complete&&image.naturalWidth===64));
    const generateAndAudit=async(expectedVideoHash,expectedKey=.4)=>{
      const pending=page.waitForEvent('download');pending.catch(()=>{});
      await click('生成并保存 Live Photo');
      const download=await pending;
      if(await download.failure())throw Error('C output download failed');
      const output=await readFile(await download.path());
      const audit=await page.evaluate(async data=>{
        const api=await import('/api/livephoto.mjs'),blob=new Blob([new Uint8Array(data)]);
        const extracted=await api.extractVideo(blob),key=await api.getKeyPhotoPosition(blob),valid=await api.validateLivePhoto(blob);
        if(!extracted.success||!key.success||!valid.success)throw Error('C carrier audit');
        const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await extracted.blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');
        return {hash,key:key.report.position,source:key.report.source,verdict:valid.verdict};
      },Array.from(output));
      await text(/已生成并发起浏览器下载/).waitFor({state:'visible'});
      await page.waitForTimeout(500);
      return audit.hash===expectedVideoHash&&audit.verdict!=='Invalid'&&audit.source==='ProtocolField'&&
        audit.key&&Math.abs(Number(audit.key.value)/audit.key.timescale-expectedKey)<.05;
    };
    check(label+':captured-C-carrier-key-and-video',await generateAndAudit(videoHash,1.4));
    await seek(1.2);await click('设为 A');
    await seek(2.6);await click('设为 B');
    await click('裁剪 A～B 并使用（音视频 copy）');
    await text('名称：trimmed-AB.mp4').waitFor({state:'attached',timeout:120000});
    check(label+':AB-copy-video-used',await text(/copy 裁剪完成；请求/).count()===1);
    await page.waitForFunction(()=>{const v=document.querySelector('video[aria-label="视频预览"]');return v&&v.readyState>=2;});
    const trimmedHash=await player.evaluate(async video=>{
      const data=await(await fetch(video.currentSrc)).arrayBuffer();
      return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',data))).map(v=>v.toString(16).padStart(2,'0')).join('');
    });
    check(label+':AB-carrier-C-rebased-video-exact',await generateAndAudit(trimmedHash));
    await click('显式转为 H.264 并使用（音频 copy）');
    await text('名称：transcoded-H264.mp4').waitFor({state:'attached',timeout:120000});
    check(label+':explicit-transcode-used',await text(/显式 H.264 转码完成/).count()===1);
    const priorDownloads=downloads;
    await click('生成并保存 Live Photo');
    await text(/视频范围或编码已改变，请在当前视频上重新截取 C/).waitFor({state:'visible'});
    check(label+':transcode-stale-C-rejected',downloads===priorDownloads);
    await page.waitForFunction(()=>{const v=document.querySelector('video[aria-label="视频预览"]');return v&&v.readyState>=2;});
    await seek(.2);
    await click('截取当前画面为 C／封面');
    // Compose also exposes this status in its hidden accessibility bridge.
    // Existence acknowledges the callback; the real download/key/video audit
    // below, not DOM visibility of that bridge, proves the recaptured C works.
    await text('当前画面已选为封面 C').waitFor({state:'attached'});
    const transcodedHash=await player.evaluate(async video=>{
      const data=await(await fetch(video.currentSrc)).arrayBuffer();
      return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',data))).map(v=>v.toString(16).padStart(2,'0')).join('');
    });
    check(label+':transcoded-recaptured-C-carrier',await generateAndAudit(transcodedHash,.2));
    await choose('重新选择图片',imagePath);
    await choose('重新选择视频',{name:'broken.mp4',mimeType:'video/mp4',buffer:Buffer.from([0,1,2,3])});
    await click('生成并保存 Live Photo');
    await text(/错误码：/).waitFor({state:'visible'});
    check(label+':malformed-input-error-visible',await text(/生成失败/).count()>0&&errors.length===0);
    await context.close();
  }
  if(checks.length!==38||new Set(checks).size!==38) throw Error('Incomplete shared UI checks: '+checks.length);
  await writeFile('ci-output/web-ui-browser-evidence.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,browser:'Chromium',browserVersion:browser.version(),tests:checks.length,failures:0,checks,scope:'same-common-compose-screen-mobile-wide-real-file-chooser-download-three-profiles-no-device-proof'},null,2)+'\n');
  console.log('WEB_UI_EVIDENCE=SUCCESS 38 checks; shared Compose A/B/C editor under /livephoto/, two sizes, twelve audited downloads, hold-to-play result');
} catch(error) {
  console.error('Shared UI failure after checks:',checks, error);
  if(lastPage&&!lastPage.isClosed()) {
    await lastPage.screenshot({path:'ci-output/web-ui-failure.png'}).catch(()=>{});
    await writeFile('ci-output/web-ui-failure.html',await lastPage.content()).catch(()=>{});
    await writeFile('ci-output/web-ui-failure-a11y.txt',await lastPage.locator('body').ariaSnapshot()).catch(()=>{});
    await writeFile('ci-output/web-ui-failure-shadow.html',await lastPage.evaluate(()=>{
      const visit=root=>Array.from(root.children).map(e=>e.outerHTML+(e.shadowRoot?'\nSHADOW:\n'+visit(e.shadowRoot):'')+'\n'+visit(e)).join('\n');
      return visit(document.body);
    })).catch(()=>{});
  }
  throw error;
} finally { await browser.close(); await new Promise(resolve=>server.close(resolve)); }
