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
generate(['-f','lavfi','-i','testsrc2=size=64x64:rate=25','-t','1','-an','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-bf','0','-g','25','-use_editlist','0','-movflags','+faststart','-map_metadata','-1','-metadata:s:v','encoder=','-fflags','+bitexact','-flags:v','+bitexact','-write_btrt','0',videoPath]);
const video = await readFile(videoPath);
const videoHash = createHash('sha256').update(video).digest('hex');
const server = createServer(async (req,res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const api = url.pathname.startsWith('/api/');
    const directory = api ? apiRoot : root;
    const name = api ? url.pathname.slice(4) : url.pathname === '/' ? '/index.html' : url.pathname;
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
    lastPage = page;
    const errors=[]; page.on('pageerror', error => errors.push(String(error)));
    page.setDefaultTimeout(30000);
    await page.goto(origin);
    await page.locator('canvas').first().waitFor({state:'visible'});
    await page.keyboard.press('Tab');
    const text = value => page.getByText(value).or(page.getByLabel(value)).first();
    const button = name => page.getByRole('button',{name,exact:typeof name==='string'});
    const click = async name => {
      const locator=button(name);
      for(let attempt=0;attempt<10;attempt++) {
        const bounds=await locator.boundingBox().catch(()=>null);
        if(bounds && bounds.y>=64 && bounds.y+bounds.height<=viewport.height) { await locator.click(); return; }
        await page.mouse.move(viewport.width/2,viewport.height/2);
        await page.mouse.wheel(0,bounds && bounds.y<64 ? -320 : 320);
        await page.waitForTimeout(150);
      }
      throw Error('Shared Compose button is not reachable: '+name+'; '+errors.join(';'));
    };
    const choose = async (name,path) => {
      const picker=page.waitForEvent('filechooser');
      await click(name);
      await (await picker).setFiles(path);
    };
    await button('生成并保存 Live Photo').waitFor({state:'attached'});
    check(label+':initial-create-disabled',await button('生成并保存 Live Photo').isDisabled());
    const bar=await text('LivePhoto').boundingBox(), input=await button('选择图片').boundingBox();
    check(label+':appbar-does-not-cover-picker',bar && input && input.y>=bar.y+bar.height);
    await page.screenshot({path:`ci-output/web-ui-${label}-before.png`});
    await choose('选择图片',imagePath);
    await choose('选择视频',videoPath);
    await text('名称：cover.jpg').waitFor({state:'attached'});
    await text('名称：clip.mp4').waitFor({state:'attached'});
    check(label+':shared-file-names-and-sizes',await page.getByText(/大小：/).count()>=2);
    await page.locator('#livephoto-image-input').setInputFiles([]);
    check(label+':cancel-keeps-selection',await text('名称：cover.jpg').count()===1);
    for(const [target,name] of [['oplus.olive',/OPPO \/ OnePlus/],['google.motionphoto.v2',/Google Motion Photo V2/],['google.microvideo.v1',/Google MicroVideo V1/]]) {
      await click(name);
      const pending=page.waitForEvent('download');
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
    }
    await text(/已生成并发起浏览器下载/).waitFor({state:'visible'});
    check(label+':honest-download-status',await text(/已生成并发起浏览器下载/).count()===1);
    await page.screenshot({path:`ci-output/web-ui-${label}-success.png`});
    check(label+':viewport-contained',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await choose('重新选择视频',{name:'broken.mp4',mimeType:'video/mp4',buffer:Buffer.from([0,1,2,3])});
    await click('生成并保存 Live Photo');
    await text(/错误码：/).waitFor({state:'visible'});
    check(label+':malformed-input-error-visible',await text(/生成失败/).count()>0&&errors.length===0);
    await context.close();
  }
  if(checks.length!==20||new Set(checks).size!==20) throw Error('Incomplete shared UI checks: '+checks.length);
  await writeFile('ci-output/web-ui-browser-evidence.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,browser:'Chromium',browserVersion:browser.version(),tests:checks.length,failures:0,checks,scope:'same-common-compose-screen-mobile-wide-real-file-chooser-download-three-profiles-no-device-proof'},null,2)+'\n');
  console.log('WEB_UI_EVIDENCE=SUCCESS 20 checks; shared Compose screen, two sizes, real downloaded outputs audited');
} catch(error) {
  if(lastPage&&!lastPage.isClosed()) {
    await lastPage.screenshot({path:'ci-output/web-ui-failure.png'}).catch(()=>{});
    await writeFile('ci-output/web-ui-failure.html',await lastPage.content()).catch(()=>{});
  }
  throw error;
} finally { await browser.close(); await new Promise(resolve=>server.close(resolve)); }
