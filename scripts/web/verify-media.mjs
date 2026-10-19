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
const coreSource=join(directory,'core-input.mp4');
execFileSync(ffmpeg,['-nostdin','-n','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=64x64:rate=25',
  '-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','2','-c:v','libx264','-threads','1','-pix_fmt','yuv420p','-bf','0','-g','25',
  '-color_range','tv','-colorspace','bt709','-color_primaries','bt709','-color_trc','bt709','-c:a','aac','-use_editlist','0',
  '-map_metadata','-1','-metadata:s:v','encoder=','-fflags','+bitexact','-flags:v','+bitexact','-write_btrt','0','-movflags','+faststart',coreSource],{timeout:60000});
const coreInput=await readFile(coreSource);
const input=await readFile(source), root=resolve('ci-output/web-ui'),apiRoot=resolve('ci-output/browser-api/web-library');
const server=createServer(async(req,res)=>{
  try {
    const path=new URL(req.url,'http://localhost').pathname;
    if(path==='/livephoto/test') {res.setHeader('Content-Type','text/html');res.end('<!doctype html><title>Media adapter validation</title>');return;}
    if(path==='/fixture') {res.setHeader('Content-Type','video/mp4');res.end(input);return;}
    if(path==='/core-fixture') {res.setHeader('Content-Type','video/mp4');res.end(coreInput);return;}
    if(path.startsWith('/api/')) {
      const file=resolve(apiRoot,'.'+path.slice(4));
      if(!file.startsWith(apiRoot+sep)){res.writeHead(403).end();return;}
      res.setHeader('Content-Type','text/javascript');res.end(await readFile(file));return;
    }
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
    const loaded=state.duration>2.9&&player.video.videoWidth===64&&player.video.paused;
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
    if(kind==='transcode') {
      const originalVideo=before.streams.find(s=>s.codec_type==='video'),outputVideo=after.streams.find(s=>s.codec_type==='video');
      const originalHashes=new Set(before.packets.filter(p=>p.stream_index===originalVideo.index).map(p=>p.data_hash));
      if(!after.packets.some(p=>p.stream_index===outputVideo.index&&!originalHashes.has(p.data_hash))) throw Error('Video was not re-encoded');
    }
    for(const type of kind==='copy'?['video','audio']:['audio']) {
      const a=before.streams.find(s=>s.codec_type===type),b=after.streams.find(s=>s.codec_type===type);
      if(a.codec_name!==b.codec_name)throw Error('Unexpected encoder');
      const original=before.packets.filter(p=>p.stream_index===a.index).map(p=>p.data_hash);
      const retained=after.packets.filter(p=>p.stream_index===b.index).map(p=>p.data_hash);
      if(!retained.length||!original.some((_,i)=>retained.every((hash,j)=>hash&&hash===original[i+j])))throw Error('Independent packet identity failed: '+type);
    }
    check(kind+'-independent-native-packet-audit',true);
  }
  const coreResults=await page.evaluate(async()=>{
    const {createMediaBackend}=await import('/api/livephoto.mjs');
    const diagnostics=[];
    const backend=createMediaBackend({runtimeUrl:location.origin+'/livephoto/media/core-runtime.mjs',onDiagnostic:entry=>diagnostics.push(entry)});
    const file=await(await fetch('/core-fixture')).blob();
    const hash=async blob=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))).map(x=>x.toString(16).padStart(2,'0')).join('');
    const before=await hash(file),results={};
    for(const [action,options] of [['probe',undefined],['remux',undefined],['trim',{start:.4,end:1.6}],['extractFrame',{start:.4}],['transcode',{authorizeTranscode:true}]]) {
      const value=await backend[action](file,options);
      if(!value.success)throw Error('Core '+action+': '+value.code+' '+value.stage+' '+value.message+' '+JSON.stringify(diagnostics.slice(-3)));
      results[action]={...value,assets:undefined,blob:undefined,bytes:value.blob?Array.from(new Uint8Array(await value.blob.arrayBuffer())):null};
    }
    const forbidden=await backend.transcode(file);
    const malformed=await backend.probe(new Blob([new Uint8Array([0,1,2,3])]));
    const controller=new AbortController();controller.abort();
    const cancelled=await createMediaBackend({runtimeUrl:location.origin+'/livephoto/media/core-runtime.mjs',signal:controller.signal}).probe(file);
    const running=new AbortController();
    const interrupted=createMediaBackend({runtimeUrl:location.origin+'/livephoto/media/core-runtime.mjs',signal:running.signal}).probe(file);
    const stop=setTimeout(()=>running.abort(),100);
    let stopped;try{stopped=await interrupted;}finally{clearTimeout(stop);}
    let budget=false;try{await backend.remux(new Blob([new Uint8Array(32*1024*1024+1)]));}catch{budget=true;}
    return {results,forbidden,malformed,cancelled,stopped,budget,immutable:before===await hash(file)};
  });
  const r=coreResults.results;
  check('core-probe-decode-partial',r.probe.coverage==='Partial'&&r.probe.issues.includes('MEDIA_DECODE_COMPLETED')&&r.probe.report.backendId==='ffmpeg-wasm'&&!r.probe.bytes);
  const coreBefore=probe(coreSource);
  for(const action of ['remux','trim','transcode']) {
    const value=r[action],path=join(directory,'core-'+action+'.mp4');await writeFile(path,new Uint8Array(value.bytes));
    const after=probe(path);
    if(!value.report.execution.some(e=>e.backendId==='ffmpeg-wasm'))throw Error('Actual Wasm backend identity missing');
    for(const type of action==='transcode'?['audio']:['video','audio']) {
      const a=coreBefore.streams.find(s=>s.codec_type===type),b=after.streams.find(s=>s.codec_type===type);
      if(!a||!b||a.codec_name!==b.codec_name)throw Error('Core dropped/encoded '+type);
      const original=coreBefore.packets.filter(p=>p.stream_index===a.index).map(p=>p.data_hash),retained=after.packets.filter(p=>p.stream_index===b.index).map(p=>p.data_hash);
      if(!retained.length||!original.some((_,i)=>retained.every((hash,j)=>hash===original[i+j])))throw Error('Core packet proof failed: '+action+'/'+type);
      if(action!=='trim'&&original.length!==retained.length)throw Error('Whole-stream packet count changed');
    }
    if(action==='remux')check('core-remux-independent-video-audio',value.report.execution.some(e=>e.remuxed&&!e.transcoded));
    if(action==='trim')check('core-trim-actual-timeline',!value.report.actual.wasTranscoded&&value.report.actual.actualStart&&value.report.actual.actualEnd);
    if(action==='transcode')check('core-transcode-explicit-audio-copy',value.report.execution.some(e=>e.transcoded&&e.hardwareUsed===false));
  }
  const frame=await page.evaluate(async data=>{
    const bitmap=await createImageBitmap(new Blob([new Uint8Array(data)],{type:'image/jpeg'}));
    const valid=bitmap.width===64&&bitmap.height===64;bitmap.close();return valid;
  },r.extractFrame.bytes);
  check('core-extract-frame-decodes',frame&&r.extractFrame.report.actual.actualTime&&r.extractFrame.report.execution.some(e=>e.backendId==='ffmpeg-wasm'));
  check('core-policy-and-malformed-no-output',!coreResults.forbidden.success&&coreResults.forbidden.code==='TRANSCODE_NOT_AUTHORIZED'&&!coreResults.forbidden.blob&&!coreResults.malformed.success&&!coreResults.malformed.blob);
  check('core-cancel-and-budget-no-output',!coreResults.cancelled.success&&coreResults.cancelled.code==='CANCELLED'&&!coreResults.cancelled.blob&&
    !coreResults.stopped.success&&coreResults.stopped.code==='CANCELLED'&&!coreResults.stopped.blob&&coreResults.budget);
  check('core-input-bytes-immutable',coreResults.immutable);
  if(checks.length!==19||new Set(checks).size!==19)throw Error('Incomplete media evidence');
  await writeFile('ci-output/web-media-evidence.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,
    browserVersion:browser.version(),tests:checks.length,failures:0,checks,inputSha256:createHash('sha256').update(input).digest('hex'),
    scope:'browser-video-canvas-real-single-thread-ffmpeg-wasm-native-packet-audit-synthetic-audio-no-device-proof'},null,2)+'\n');
  console.log('WEB_MEDIA_EVIDENCE=SUCCESS 19 checks; real Wasm editing and Core backend, native independent audio/video packet audit');
}finally{await browser.close();await new Promise(r=>server.close(r));}
