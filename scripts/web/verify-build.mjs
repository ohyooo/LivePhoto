import {readFile,cp,mkdir} from 'node:fs/promises';
import {resolve,sep,isAbsolute} from 'node:path';
import {createHash} from 'node:crypto';

const root=resolve('ci-output');
const json=async name=>JSON.parse((await readFile(resolve(root,name),'utf8')).replace(/^\uFEFF/,''));
for(const [directory,document,scope] of [
  ['web-library','web-package-evidence.json','kotlin-js-library-browser-file-blob-optional-media-backend'],
  ['web-ui','web-ui-package-evidence.json','shared-compose-screen-browser-picker-local-core-download-no-server-processing'],
]) {
  const manifest=await json(document),base=resolve(root,directory);
  if(directory==='web-ui'&&manifest.files?.some(file=>file.path==='media/ffmpeg.mjs'))throw Error('Removed legacy media implementation was packaged');
  if(manifest.commit!==process.env.GITHUB_SHA||String(manifest.run)!==process.env.GITHUB_RUN_ID||manifest.scope!==scope||!Array.isArray(manifest.files)||!manifest.files.length)throw Error('Wrong build identity: '+document);
  const seen=new Set();
  if(directory==='web-ui'&&(manifest.kotlinRuntime!=='wasmJs'||!manifest.applicationWasm?.length||
    manifest.applicationWasm.some(name=>!/^livephoto-ui\.[0-9a-f]+\.wasm$/.test(name)||!manifest.files.some(file=>file.path===name))))throw Error('Missing Kotlin/Wasm Compose application');
  if(directory==='web-ui'&&(!manifest.workerWasm?.length||manifest.workerWasm.some(name=>
    !/^worker\/livephoto-worker\.[0-9a-f]+\.wasm$/.test(name)||!manifest.files.some(file=>file.path===name))))throw Error('Missing Kotlin/Wasm UI background worker');
  for(const file of manifest.files) {
    const path=resolve(base,file.path);
    if(isAbsolute(file.path)||!path.startsWith(base+sep)||seen.has(file.path)||/\.(?:jpe?g|mp4|mov|heic|heif|apk|klib)$/i.test(file.path))throw Error('Unsafe/duplicate/media build asset');
    seen.add(file.path);
    const bytes=await readFile(path);
    if(bytes.length!==file.bytes||createHash('sha256').update(bytes).digest('hex')!==file.sha256)throw Error('Build asset hash mismatch: '+file.path);
  }
}
await mkdir(resolve(root,'browser-api'),{recursive:true});
await cp(resolve(root,'web-library'),resolve(root,'browser-api/web-library'),{recursive:true});
if(process.argv.includes('--tests')) {
  for(const [file,count] of [['web-browser-evidence.json',70],['web-media-evidence.json',15],['web-player-evidence.json',91],['web-ui-browser-evidence.json',130],['web-theme-evidence.json',12]]) {
    const proof=await json(file);
    if(proof.commit!==process.env.GITHUB_SHA||String(proof.run)!==process.env.GITHUB_RUN_ID||proof.tests!==count||proof.failures!==0||proof.checks?.length!==count||new Set(proof.checks).size!==count)throw Error('Incomplete/wrong-source test evidence: '+file);
  }
  const browser=await json('web-browser-evidence.json'),worker=browser.worker;
  for(const proof of [browser,worker]){
    const io=proof?.inputIo;
    if(!io||!Number.isSafeInteger(io.slices)||io.slices<=0||io.maxSlice>65536||io.wholeReads!==0)
      throw Error('Missing public JS protocol chunked input proof');
  }
  const media=await json('web-media-evidence.json'),mediaWorker=media.worker;
  const wasmUi=media.wasmUi;
  const wasmChecks=['mobile','wide'].flatMap(size=>['wasm-runtime-and-engine-lazy','core-probe-real-decode',
    'core-remux-independent-packets','core-trim-independent-packets','core-transcode-independent-packets',
    'core-frame-actual-key-and-decode','core-refusal-keeps-input','core-reselection-clears-status','core-cancellation-keeps-input'].map(name=>size+':'+name));
  if(!wasmUi?.success||wasmUi.tests!==18||wasmUi.failures!==0||wasmUi.checks?.length!==18||
    JSON.stringify([...wasmUi.checks].sort())!==JSON.stringify(wasmChecks.sort())||wasmUi.outputs?.length!==8||
    wasmUi.scope!=='shared-compose-wasm-typed-core-media-real-pointer-native-packet-audit'||
    JSON.stringify(wasmUi.outputs.map(o=>o.viewport+':'+o.action).sort())!==JSON.stringify(['mobile','wide'].flatMap(s=>['frame','remux','transcode','trim'].map(a=>s+':'+a)).sort())||
    wasmUi.outputs.some(o=>!Number.isSafeInteger(o.bytes)||o.bytes<=0||! /^[0-9a-f]{64}$/.test(o.sha256)||! /^[0-9a-f]{64}$/.test(o.videoSha256)))throw Error('Incomplete typed Wasm Core media UI or independent download audit');
  const mediaWorkerChecks=['worker-context-and-capabilities','worker-probe','worker-remux','worker-trim','worker-extractFrame',
    'worker-transcode','worker-refusals-no-output','worker-cancellation-and-budget','worker-input-immutable'];
  if(!mediaWorker?.success||mediaWorker.tests!==9||mediaWorker.failures!==0||mediaWorker.checks?.length!==9||
    JSON.stringify([...mediaWorker.checks].sort())!==JSON.stringify([...mediaWorkerChecks].sort())||
    mediaWorker.scope!=='module-worker-production-kotlin-js-core-nested-ffmpeg-worker-native-packet-audit'||
    !/^[0-9a-f]{64}$/.test(mediaWorker.inputSha256)||mediaWorker.outputs?.length!==4||
    JSON.stringify(mediaWorker.outputs.map(o=>o.action).sort())!==JSON.stringify(['extractFrame','remux','transcode','trim'])||
    mediaWorker.outputs.some(o=>!Number.isSafeInteger(o.bytes)||o.bytes<=0||! /^[0-9a-f]{64}$/.test(o.sha256)))throw Error('Incomplete media module Worker execution or independent output audit');
  const workerChecks=['oplus.olive','google.motionphoto.v2','google.microvideo.v1'].flatMap(protocol=>
    ['create','detect','validate','inspect','analyze','key','extract','split','same-target','convert','repair','immutable'].map(name=>protocol+':'+name))
    .concat(['malformed-structured-error','unsupported-no-output','ordinary-convert-not-create','missing-target','invalid-input-type','budget-rejected']);
  if(!worker?.success||worker.tests!==42||worker.failures!==0||worker.checks?.length!==42||new Set(worker.checks).size!==42||
    JSON.stringify([...worker.checks].sort())!==JSON.stringify(workerChecks.sort())||
    worker.imageSha256!==browser.imageSha256||worker.videoSha256!==browser.videoSha256)throw Error('Incomplete/wrong-input module Worker evidence');
  for(const [prefix,runtime] of [['web-common','js'],['web-wasm-common','wasmJs']]) {
  const common=await json(prefix+'-evidence.json');
  if(common.commit!==process.env.GITHUB_SHA||String(common.run)!==process.env.GITHUB_RUN_ID||
    common.scope!=='kotlin-commonTest-pure-protocol-real-chrome-no-host-backend'||common.tests<804||common.tests!==common.expectedTests||
    common.kotlinRuntime!==runtime||common.failures!==0||common.skipped!==0||!Array.isArray(common.suites)||!common.suites.length||
    new Set(common.suites.map(s=>s.name)).size!==common.suites.length||common.suites.reduce((sum,s)=>sum+s.tests,0)!==common.tests||
    common.reports?.length!==common.suites.length)throw Error('Incomplete/wrong-source common Kotlin test evidence');
  const reports=new Set();
  for(const file of common.reports) {
    const path=resolve(root,file.path);
    if(!path.startsWith(resolve(root,prefix+'-results')+sep)||!file.path.endsWith('.xml')||reports.has(file.path))throw Error('Unsafe/duplicate common report');
    reports.add(file.path);
    const bytes=await readFile(path);
    if(bytes.length!==file.bytes||createHash('sha256').update(bytes).digest('hex')!==file.sha256)throw Error('Common Kotlin report digest mismatch');
  }
  }
}
console.log('WEB_BUILD_INPUTS=VERIFIED; same-commit library and UI hashes'+(process.argv.includes('--tests')?'; independent test suites and theme checks complete':''));
