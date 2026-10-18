import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {resolve,join,sep,extname,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {execFileSync} from 'node:child_process';

const root=resolve('ci-output/web-ui'),fixtures=await mkdtemp(join(tmpdir(),'livephoto-player-'));
const ffmpeg=process.env.LIVEPHOTO_FFMPEG;
if(!ffmpeg)throw Error('Existing FFmpeg fixture generator required');
const cases=[['landscape','320x180','30'],['portrait','180x320','30000/1001'],['square','160x160','30']];
for(const [name,size,rate] of cases) {
  execFileSync(ffmpeg,['-nostdin','-n','-hide_banner','-loglevel','error','-f','lavfi','-i',`testsrc2=size=${size}:rate=${rate}`,
    '-t','3',...(name==='square'?['-vf',"select='not(mod(n,2))+not(mod(n,5))'",'-fps_mode','vfr']:[]),
    '-c:v','libx264','-threads','1','-bf','0','-pix_fmt','yuv420p','-video_track_timescale','90000','-movflags','+faststart',join(fixtures,name+'.mp4')],{timeout:60000});
}
const server=createServer(async(req,res)=>{
  try {
    const path=new URL(req.url,'http://localhost').pathname;
    if(path==='/livephoto/test') {res.setHeader('Content-Type','text/html');res.end('<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Player validation</title><style>body{margin:16px;background:#f5f7fb}main{max-width:960px;height:calc(100vh - 32px);margin:auto}</style><main></main></html>');return;}
    if(path.startsWith('/fixture/')) {
      const name=path.slice('/fixture/'.length);if(!cases.some(c=>name===c[0]+'.mp4'))throw Error('Unknown fixture');
      res.setHeader('Content-Type','video/mp4');res.end(await readFile(join(fixtures,name)));return;
    }
    if(!path.startsWith('/livephoto/'))throw Error('Unknown route');
    const file=resolve(root,'.'+path.slice('/livephoto'.length));if(!file.startsWith(root+sep))throw Error('Invalid path');
    res.setHeader('Content-Type',{'.mjs':'application/javascript','.js':'application/javascript'}[extname(file)]??'application/octet-stream');res.end(await readFile(file));
  }catch{res.writeHead(404).end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const origin=`http://127.0.0.1:${server.address().port}`,browser=await chromium.launch({headless:true});
const checks=[],check=(name,valid)=>{if(!valid)throw Error(name);checks.push(name);};
let lastPage;
try {
  const ffprobe=join(dirname(ffmpeg),process.platform==='win32'?'ffprobe.exe':'ffprobe');
  const timings=JSON.parse(execFileSync(ffprobe,['-v','error','-select_streams','v:0','-show_entries','packet=pts_time','-of','json',join(fixtures,'square.mp4')],{timeout:30000})).packets.map(p=>Number(p.pts_time));
  check('vfr-source-independently-confirmed',new Set(timings.slice(1).map((p,i)=>Math.round((p-timings[i])*1000))).size>1);
  for(const [size,viewport] of [['mobile',{width:390,height:844}],['wide',{width:1280,height:800}]]) {
    for(const [shape] of cases) {
      const context=await browser.newContext({viewport,serviceWorkers:'block'});
      await context.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());
      const page=await context.newPage();lastPage=page;
      await page.goto(origin+'/livephoto/test');
      await page.evaluate(async shape=>{
        const {createPlayer}=await import('/livephoto/media/player.mjs');
        const blob=await(await fetch('/fixture/'+shape+'.mp4')).blob();
        window.player=createPlayer(blob,state=>window.state=state);document.querySelector('main').append(window.player.element);
      },shape);
      await page.waitForFunction(()=>window.state?.ready);
      const label=size+':'+shape,video=page.getByLabel('视频预览',{exact:true}),slider=page.getByRole('slider',{name:'播放进度'});
      await page.waitForTimeout(150);
      const geometry=()=>page.evaluate(()=>{
        const v=window.player.video,b=v.getBoundingClientRect(),c=document.querySelector('.lp-controls').getBoundingClientRect(),s=document.querySelector('.lp-progress').getBoundingClientRect(),card=document.querySelector('.lp-card').getBoundingClientRect();
        return {aspect:Math.abs(b.width/b.height-v.videoWidth/v.videoHeight)<.005,contained:card.right<=innerWidth&&card.left>=0,
          outside:c.top>=b.bottom+6,axis:s.left>=b.right+6&&Math.abs(s.height-b.height)<1,controls:v.controls};
      });
      let g=await geometry();
      check(label+':aspect-and-viewport',g.aspect&&g.contained);
      check(label+':controls-outside-picture',g.outside);
      check(label+':native-overlays-disabled',!g.controls);
      check(label+':correct-progress-axis',await slider.getAttribute('aria-orientation')===(shape==='portrait'?'vertical':'horizontal')&&(shape!=='portrait'||g.axis));
      const scrub=async fraction=>{
        const b=await slider.locator('.lp-track').boundingBox();if(!b)throw Error('Missing slider');
        const x=shape==='portrait'?b.x+b.width/2:b.x+b.width*fraction,y=shape==='portrait'?b.y+b.height*fraction:b.y+b.height/2;
        await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+1,y+1,{steps:3});await page.mouse.up();
        await page.waitForFunction(()=>!window.player.video.seeking);
      };
      await scrub(.2);const before=await video.evaluate(v=>v.currentTime);await scrub(.6);const after=await video.evaluate(v=>v.currentTime);
      check(label+':pointer-seek-paused',after>before&&Math.abs(after-await video.evaluate(v=>v.duration)*.6)<.08&&await video.evaluate(v=>v.paused));
      await slider.focus();await page.keyboard.press('Home');await page.keyboard.press('ArrowDown');
      await page.waitForFunction(()=>!window.player.video.seeking);
      check(label+':keyboard-seek',Math.abs(await video.evaluate(v=>v.currentTime)-1)<.05);
      await page.getByRole('button',{name:'播放',exact:true}).click();await page.waitForFunction(()=>!window.player.video.paused);
      await page.getByRole('button',{name:'暂停',exact:true}).click();
      check(label+':play-and-pause',await video.evaluate(v=>v.paused));
      await video.click();await page.waitForFunction(()=>!window.player.video.paused);await video.click();
      check(label+':picture-click-toggle',await video.evaluate(v=>v.paused));
      await page.getByRole('button',{name:'静音',exact:true}).click();await page.getByRole('button',{name:'取消静音',exact:true}).click();
      check(label+':mute-and-restore',await video.evaluate(v=>!v.muted&&v.volume>0));
      await page.getByRole('button',{name:'播放倍速',exact:true}).click();
      const menuOutside=await page.locator('.lp-rates').evaluate(el=>el.getBoundingClientRect().top>=document.querySelector('video').getBoundingClientRect().bottom);
      await page.getByRole('button',{name:'1.5x',exact:true}).click();
      check(label+':rate-menu-outside-picture',menuOutside&&await video.evaluate(v=>v.playbackRate===1.5));
      await page.getByRole('button',{name:'全屏',exact:true}).click();await page.waitForFunction(()=>document.fullscreenElement);
      g=await geometry();check(label+':fullscreen-ratio-controls',g.aspect&&g.outside&&(shape!=='portrait'||g.axis));
      await page.getByRole('button',{name:'退出全屏',exact:true}).click();await page.waitForFunction(()=>!document.fullscreenElement);
      await slider.focus();await page.keyboard.press('End');await page.waitForFunction(()=>!window.player.video.seeking);
      check(label+':ended-progress-complete',await video.evaluate(v=>Math.abs(v.currentTime-v.duration)<.02)&&Number(await slider.getAttribute('aria-valuenow'))===Number(await slider.getAttribute('aria-valuemax')));
      await scrub(.4);
      const capture=await page.evaluate(async()=>{const frame=await window.player.capture(),image=await createImageBitmap(frame.blob);const valid=image.width===window.player.video.videoWidth&&image.height===window.player.video.videoHeight&&Number.isFinite(frame.time);image.close();return valid;});
      check(label+':current-picture-capture',capture);
      await page.screenshot({path:`ci-output/web-ui-player-${size}-${shape}.png`});
      await page.evaluate(()=>window.player.dispose());await context.close();
    }
  }
  if(checks.length!==79||new Set(checks).size!==79)throw Error('Incomplete player checks: '+checks.length);
  await writeFile('ci-output/web-player-evidence.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,browserVersion:browser.version(),tests:checks.length,failures:0,checks,scope:'real-pointer-keyboard-fullscreen-player-synthetic-no-device-proof'},null,2)+'\n');
  console.log('WEB_PLAYER_EVIDENCE=SUCCESS 79 checks; landscape/portrait/VFR square, two viewports, actual pointer/keyboard/fullscreen/capture');
}catch(error){console.error('Player failure after checks:',checks,error);if(lastPage&&!lastPage.isClosed())await lastPage.screenshot({path:'ci-output/web-ui-player-failure.png'}).catch(()=>{});throw error;}
finally{await browser.close();await new Promise(r=>server.close(r));}
