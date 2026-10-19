import {readFile,cp,mkdir} from 'node:fs/promises';
import {resolve,sep,isAbsolute} from 'node:path';
import {createHash} from 'node:crypto';

const root=resolve('ci-output');
const json=async name=>JSON.parse((await readFile(resolve(root,name),'utf8')).replace(/^\uFEFF/,''));
for(const [directory,document,scope] of [
  ['web-library','web-package-evidence.json','kotlin-js-library-browser-file-blob-no-ui-no-media-backend'],
  ['web-ui','web-ui-package-evidence.json','shared-compose-screen-browser-picker-local-core-download-no-server-processing'],
]) {
  const manifest=await json(document),base=resolve(root,directory);
  if(manifest.commit!==process.env.GITHUB_SHA||String(manifest.run)!==process.env.GITHUB_RUN_ID||manifest.scope!==scope||!Array.isArray(manifest.files)||!manifest.files.length)throw Error('Wrong build identity: '+document);
  const seen=new Set();
  if(directory==='web-ui'&&(manifest.kotlinRuntime!=='wasmJs'||!manifest.applicationWasm?.length||
    manifest.applicationWasm.some(name=>name.includes('/')||!name.endsWith('.wasm')||name.includes('skiko')||!manifest.files.some(file=>file.path===name))))throw Error('Missing Kotlin/Wasm Compose application');
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
  for(const [file,count] of [['web-browser-evidence.json',70],['web-media-evidence.json',11],['web-player-evidence.json',91],['web-ui-browser-evidence.json',38],['web-theme-evidence.json',12]]) {
    const proof=await json(file);
    if(proof.commit!==process.env.GITHUB_SHA||String(proof.run)!==process.env.GITHUB_RUN_ID||proof.tests!==count||proof.failures!==0||proof.checks?.length!==count||new Set(proof.checks).size!==count)throw Error('Incomplete/wrong-source test evidence: '+file);
  }
  const browser=await json('web-browser-evidence.json'),worker=browser.worker;
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
