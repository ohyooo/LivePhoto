import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {resolve,join,sep,extname,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';

const directory=await mkdtemp(join(tmpdir(),'livephoto-web-media-'));
const ffmpeg=process.env.LIVEPHOTO_FFMPEG;
if(!ffmpeg) throw Error('Existing synthetic fixture generator required');
const source=join(directory,'input.mp4');
execFileSync(ffmpeg,['-nostdin','-n','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=64x64:rate=25',
  '-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','3','-c:v','libx264','-threads','1','-pix_fmt','yuv420p',
  '-bf','0','-g','25','-c:a','aac','-movflags','+faststart',source],{timeout:60000});
const input=await readFile(source), root=resolve('ci-output/web-ui');
const server=createServer(async(req,res)=>{
  try {
    const path=new URL(req.url,'http://localhost').pathname;
    if(path==='/livephoto/test') {res.setHeader('Content-Type','text/html');res.end('<!doctype html><title>Media adapter validation</title>');return;}
    if(path==='/fixture') {res.setHeader('Content-Type','video/mp4');res.end(input);return;}
    if(!path.startsWith('/livephoto/')) {res.writeHead(404).end();return;}
    const file=resolve(root,'.'+path.slice('/livephoto'.length));
    if(!file.startsWith(root+sep)) {res.writeHead(403).end();return;}
    res.setHeader('Content-Type',{'.js':'application/javascript','.mjs':'application/javascript','.wasm':'application/wasm','.json':'application/json'}[extname(file)]??'application/octet-stream');
    res.end(await readFile(file));
  }catch {res.writeHead(404).end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const origin=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({headless:true});
const checks=[];
const check=(name,valid)=>{if(!valid) throw Error(name);checks.push(name);};
try {
  const context=await browser.newContext({serviceWorkers:'block'});
  await context.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());
  const page=await context.newPage();
  page.setDefaultTimeout(180000);
  await page.goto(origin+'/livephoto/test');
  const result=await page.evaluate(async()=>{
    const {createPlayer}=await import('/livephoto/media/player.mjs');
    const {startVideoEdit}=await import('/livephoto/media/ffmpeg.mjs');
    const file=await(await fetch('/fixture')).blob();
    const digest=async blob=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))).map(x=>x.toString(16).padStart(2,'0')).join('');
    const originalHash=await digest(file);
    let state;
    const player=createPlayer(file,s=>state=s);document.body.append(player.element);
    const until=async predicate=>{const end=Date.now()+30000;while(!predicate()){if(Date.now()>end)throw Error('Video player timeout');await new Promise(r=>setTimeout(r,20));}};
    await until(()=>state?.ready);
    const loaded=state.duration>2.9&&player.element.videoWidth===64&&player.element.paused;
    player.seek(0.4);await until(()=>!state.seeking&&Math.abs(state.current-.4)<.01);
    const first=await player.capture();
    player.seek(1.4);await until(()=>!state.seeking&&Math.abs(state.current-1.4)<.01);
    const second=await player.capture();
    const decoded=await createImageBitmap(first.blob);
    const captured=first.blob.type==='image/jpeg'&&decoded.width===64&&decoded.height===64&&
      await digest(first.blob)!==await digest(second.blob)&&Math.abs(first.time-.4)<.05;
    decoded.close();player.dispose();player.element.remove();
    const copy=await startVideoEdit(file,{start:1.2,end:2.6}).promise;
    const coded=await startVideoEdit(file,{start:.2,end:1.8,transcodeVideo:true}).promise;
    let range=false,budget=false,cancel=false,malformed=false;
    try{await startVideoEdit(file,{start:2,end:1}).promise;}catch{range=true;}
    try{await startVideoEdit(new Blob([new Uint8Array(32*1024*1024+1)]),{start:0,end:1}).promise;}catch{budget=true;}
    const pending=startVideoEdit(file,{start:0,end:1});pending.cancel();
    try{await pending.promise;}catch{cancel=true;}
    try{await startVideoEdit(new Blob([new Uint8Array([0,1,2,3])]),{start:0,end:1}).promise;}catch{malformed=true;}
    return {loaded,captured,range,budget,cancel,malformed,immutable:await digest(file)===originalHash,
      copy:{...copy,blob:null,bytes:Array.from(new Uint8Array(await copy.blob.arrayBuffer()))},
      coded:{...coded,blob:null,bytes:Array.from(new Uint8Array(await coded.blob.arrayBuffer()))}};
  });
  check('native-player-decodes-local-video',result.loaded);
  check('paused-current-frame-capture',result.captured);
  check('copy-actual-timeline-reported',result.copy.timelineKnown&&result.copy.actualStart<=1.2&&result.copy.actualEnd>result.copy.actualStart&&Number.isFinite(result.copy.origin)&&!result.copy.transcoded);
  check('input-bytes-immutable',result.immutable);
  check('transcode-explicit-audio-copy',result.coded.transcoded&&result.coded.audioCopied&&!result.coded.timelineKnown&&result.coded.actualStart===null);
  check('invalid-range-rejected',result.range);
  check('input-budget-rejected',result.budget);
  check('cancel-stops-operation',result.cancel);
  check('malformed-media-never-accepted-as-probe-success',result.malformed);
  const ffprobe=join(dirname(ffmpeg),process.platform==='win32'?'ffprobe.exe':'ffprobe');
  const probe=path=>JSON.parse(execFileSync(ffprobe,['-v','error','-show_streams','-show_packets','-show_data_hash','sha256','-of','json',path],{timeout:30000,maxBuffer:16*1024*1024}));
  const before=probe(source);
  for(const [kind,output] of [['copy',result.copy],['transcode',result.coded]]) {
    const path=join(directory,kind+'.mp4');await writeFile(path,new Uint8Array(output.bytes));
    const after=probe(path);
    if(after.streams.length!==2||after.streams.find(s=>s.codec_type==='video').codec_name!=='h264') throw Error('Output codec/streams');
    for(const type of kind==='copy'?['video','audio']:['audio']) {
      const a=before.streams.find(s=>s.codec_type===type),b=after.streams.find(s=>s.codec_type===type);
      if(a.codec_name!==b.codec_name)throw Error('Unexpected encoder');
      const original=before.packets.filter(p=>p.stream_index===a.index).map(p=>p.data_hash);
      const retained=after.packets.filter(p=>p.stream_index===b.index).map(p=>p.data_hash);
      if(!retained.length||!original.some((_,i)=>retained.every((hash,j)=>hash&&hash===original[i+j])))throw Error('Independent packet identity failed: '+type);
    }
    check(kind+'-independent-native-packet-audit',true);
  }
  if(checks.length!==11||new Set(checks).size!==11)throw Error('Incomplete media evidence');
  await writeFile('ci-output/web-media-evidence.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,
    browserVersion:browser.version(),tests:checks.length,failures:0,checks,inputSha256:createHash('sha256').update(input).digest('hex'),
    scope:'browser-video-canvas-real-single-thread-ffmpeg-wasm-native-packet-audit-synthetic-audio-no-device-proof'},null,2)+'\n');
  console.log('WEB_MEDIA_EVIDENCE=SUCCESS 11 checks; real Wasm editing, native independent audio/video packet audit');
}finally{await browser.close();await new Promise(r=>server.close(r));}
