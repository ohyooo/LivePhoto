import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {resolve,join,sep,extname,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {execFileSync} from 'node:child_process';
import {composeDriver} from './ui-interaction.mjs';

const root=resolve('ci-output/web-ui'),fixtures=await mkdtemp(join(tmpdir(),'livephoto-player-'));
const ffmpeg=process.env.LIVEPHOTO_FFMPEG;if(!ffmpeg)throw Error('Existing FFmpeg fixture generator required');
const cases=[['landscape','320x180','30'],['portrait','180x320','30000/1001'],['square','160x160','30']];
for(const [shape,size,rate] of cases)execFileSync(ffmpeg,['-nostdin','-n','-hide_banner','-loglevel','error','-f','lavfi','-i',`testsrc2=size=${size}:rate=${rate}`,'-t','3',
  ...(shape==='square'?['-vf',"select='not(mod(n,2))+not(mod(n,5))'",'-fps_mode','vfr']:[]),'-c:v','libx264','-threads','1','-bf','0','-pix_fmt','yuv420p','-video_track_timescale','90000','-movflags','+faststart',join(fixtures,shape+'.mp4')],{timeout:60000});
const server=createServer(async(req,res)=>{try{const path=new URL(req.url,'http://localhost').pathname;if(!path.startsWith('/livephoto/'))throw Error();const file=resolve(root,'.'+(path==='/livephoto/'?'/index.html':path.slice('/livephoto'.length)));if(!file.startsWith(root+sep))throw Error();res.setHeader('Content-Type',{'.html':'text/html','.js':'application/javascript','.mjs':'application/javascript','.wasm':'application/wasm'}[extname(file)]??'application/octet-stream');res.end(await readFile(file));}catch{res.writeHead(404).end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const origin=`http://127.0.0.1:${server.address().port}`,browser=await chromium.launch({headless:true}),checks=[];
const check=(name,valid)=>{if(!valid)throw Error(name);checks.push(name);};let lastPage;
try {
  const ffprobe=join(dirname(ffmpeg),process.platform==='win32'?'ffprobe.exe':'ffprobe');
  const pts=JSON.parse(execFileSync(ffprobe,['-v','error','-select_streams','v:0','-show_entries','packet=pts_time','-of','json',join(fixtures,'square.mp4')],{timeout:30000})).packets.map(p=>Number(p.pts_time));
  check('vfr-source-independently-confirmed',new Set(pts.slice(1).map((p,i)=>Math.round((p-pts[i])*1000))).size>1);
  for(const [size,viewport] of [['mobile',{width:390,height:844}],['wide',{width:1280,height:800}]]) for(const [shape] of cases) {
    const context=await browser.newContext({viewport,serviceWorkers:'block'});await context.route('**/*',r=>r.request().url().startsWith(origin+'/')?r.continue():r.abort());
    const page=await context.newPage();lastPage=page;await page.goto(origin+'/livephoto/');await page.locator('canvas').first().waitFor({state:'visible'});await page.keyboard.press('Tab');
    const driver=composeDriver(page,viewport),label=size+':'+shape;
    await driver.choose('选择视频',join(fixtures,shape+'.mp4'));
    const video=page.getByLabel('视频预览',{exact:true});await video.waitFor({state:'attached'});await page.waitForFunction(()=>document.querySelector('video[aria-label="视频预览"]')?.readyState>=2);
    check(label+':tools-collapsed-by-default',await page.locator('#trim-progress').count()===0&&await page.locator('#cover-progress').count()===0);
    const progress=page.locator('#playback-progress');await driver.reveal(progress);
    const v=await video.boundingBox(),bar=await progress.boundingBox(),play=await driver.find('播放').boundingBox();
    check(label+':aspect-and-viewport',v.width/v.height>0&&Math.abs(v.width/v.height-await video.evaluate(v=>v.videoWidth/v.videoHeight))<.01&&await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    check(label+':controls-outside-picture',play&&play.y>=v.y+v.height-1);
    check(label+':native-overlays-disabled',await video.evaluate(v=>!v.controls));
    check(label+':correct-progress-axis',shape==='portrait'?bar.height>bar.width&&bar.x>=v.x+v.width:bar.width>bar.height);
    await page.screenshot({path:`ci-output/web-ui-player-${size}-${shape}.png`});
    await driver.drag(progress,.1,.6,shape==='portrait');await page.waitForFunction(()=>!document.querySelector('video').seeking);
    const seekState=await video.evaluate(v=>({paused:v.paused,current:v.currentTime,duration:v.duration}));
    console.log('Shared pointer seek',label,seekState);
    check(label+':pointer-seek-paused',seekState.paused&&Math.abs(seekState.current/seekState.duration-.6)<.08);
    // Deliver keys to the actual focus target selected by pointer interaction.
    await page.keyboard.press('ArrowDown');await page.waitForTimeout(150);
    check(label+':keyboard-seek',await video.evaluate(v=>v.currentTime>2.3));
    await driver.click('播放');await page.waitForFunction(()=>!document.querySelector('video').paused);await driver.click('暂停');
    check(label+':play-and-pause',await video.evaluate(v=>v.paused));
    await video.click();await page.waitForFunction(()=>!document.querySelector('video').paused);await video.click();
    check(label+':picture-click-toggle',await video.evaluate(v=>v.paused));
    await driver.click('静音');await driver.click('取消静音');check(label+':mute-and-restore',await video.evaluate(v=>!v.muted&&v.volume>0));
    await driver.click('播放倍速');await driver.click('1.5x');check(label+':rate-menu-outside-picture',await video.evaluate(v=>v.playbackRate===1.5));
    await driver.click('全屏');await page.waitForFunction(()=>!!document.fullscreenElement);
    await driver.find('退出全屏').waitFor({state:'attached'});await page.waitForTimeout(200);
    check(label+':fullscreen-ratio-controls',await video.evaluate(v=>Math.abs(v.getBoundingClientRect().width/v.getBoundingClientRect().height-v.videoWidth/v.videoHeight)<.01));
    await driver.click('退出全屏');await page.waitForFunction(()=>!document.fullscreenElement);
    await driver.drag(page.locator('#playback-progress'),.6,1,shape==='portrait');await page.waitForFunction(()=>!document.querySelector('video').seeking);
    check(label+':ended-progress-complete',await video.evaluate(v=>Math.abs(v.currentTime-v.duration)<.05));
    await driver.click('截取封面');
    await page.getByText('名称：video-cover.jpg',{exact:true}).waitFor({state:'attached'});
    const pictureHash=()=>page.getByAltText('已选封面预览').last().evaluate(async image=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await(await fetch(image.src)).arrayBuffer()))).join(','));
    const oldPicture=await pictureHash();
    await driver.drag(page.locator('#cover-progress'),.9,.4);
    await page.waitForTimeout(350);
    await page.waitForFunction(()=>[...document.querySelectorAll('img[alt="已选封面预览"]')].some(i=>i.complete&&i.naturalWidth>0));
    check(label+':current-picture-capture',oldPicture!==await pictureHash()&&await page.getByAltText('已选封面预览').last().evaluate(i=>i.complete&&i.naturalWidth>0));
    await driver.click('裁剪视频');await driver.drag(page.locator('#trim-progress'),.02,.25);await driver.drag(page.locator('#trim-progress'),.98,.75);
    const rangeText=await page.getByText(/^保留片段：/).first().textContent(),values=[...rangeText.matchAll(/([0-9.]+) 秒/g)].map(m=>Number(m[1]));
    check(label+':range-handles-select-inner-segment',values.length===2&&values[0]>0&&values[0]<values[1]&&values[1]<await video.evaluate(v=>v.duration));
    if(shape==='landscape')await page.screenshot({path:`ci-output/web-ui-tools-${size}.png`});await context.close();
  }
  if(checks.length!==91||new Set(checks).size!==91)throw Error('Incomplete player evidence');
  await writeFile('ci-output/web-player-evidence.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,browserVersion:browser.version(),tests:checks.length,failures:0,checks,scope:'shared-kotlin-compose-controls-real-browser-pointer-keys-fullscreen-live-cover-preview'},null,2)+'\n');
  console.log('WEB_PLAYER_EVIDENCE=SUCCESS 91 checks; actual shared Kotlin controls, optional range handles and live cover preview on two viewports');
}catch(error){console.error('Player failure after checks:',checks,error);if(lastPage&&!lastPage.isClosed()){await lastPage.screenshot({path:'ci-output/web-ui-player-failure.png'}).catch(()=>{});await writeFile('ci-output/web-ui-player-failure-a11y.txt',await lastPage.locator('body').ariaSnapshot()).catch(()=>{});}throw error;}
finally{await browser.close();await new Promise(r=>server.close(r));}
