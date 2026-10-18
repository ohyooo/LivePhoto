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
}
console.log('WEB_BUILD_INPUTS=VERIFIED; same-commit library and UI hashes'+(process.argv.includes('--tests')?'; independent test suites and theme checks complete':''));
