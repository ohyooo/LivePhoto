import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdtemp } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { resolve, sep, join, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';

const root = resolve('ci-output/web-library');
const fixtures = await mkdtemp(join(tmpdir(), 'livephoto-web-'));
const ffmpeg = process.env.LIVEPHOTO_FFMPEG;
if (!ffmpeg) throw new Error('Real FFmpeg required only to generate independent synthetic test inputs');
const imagePath = join(fixtures, 'image.jpg');
const videoPath = join(fixtures, 'video.mp4');
const generate = args => execFileSync(ffmpeg, ['-nostdin', '-n', '-hide_banner', '-loglevel', 'error', ...args], { timeout: 60000 });
generate(['-f', 'lavfi', '-i', 'color=c=blue:s=64x64', '-frames:v', '1', '-threads', '1', '-update', '1', imagePath]);
generate(['-f', 'lavfi', '-i', 'testsrc2=size=64x64:rate=25', '-t', '1', '-an', '-c:v', 'libx264', '-threads', '1',
  '-pix_fmt', 'yuv420p', '-bf', '0', '-g', '25', '-use_editlist', '0', '-movflags', '+faststart', '-map_metadata', '-1',
  '-metadata:s:v', 'encoder=', '-fflags', '+bitexact', '-flags:v', '+bitexact', '-write_btrt', '0', videoPath]);
const image = await readFile(imagePath), video = await readFile(videoPath);
const worker = `import {createLivePhoto,extractVideo} from '/livephoto.mjs';
onmessage=async()=>{try{const image=await(await fetch('/fixture-image')).blob();const video=await(await fetch('/fixture-video')).blob();
const result=await createLivePhoto(image,video);if(!result.success)throw Error(result.code+': '+result.message);
const extracted=await extractVideo(result.blob);if(!extracted.success)throw Error(extracted.code);
const a=new Uint8Array(await extracted.blob.arrayBuffer()),b=new Uint8Array(await video.arrayBuffer());
postMessage({success:a.length===b.length&&a.every((v,i)=>v===b[i])});}catch(e){postMessage({success:false,message:String(e)})}};`;
const server = createServer(async (request, response) => {
  try {
    const name = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    if (name === '/') { response.setHeader('Content-Type', 'text/html'); response.end('<!doctype html><title>Headless protocol validation</title>'); return; }
    if (name === '/fixture-image' || name === '/fixture-video') { response.end(name === '/fixture-image' ? image : video); return; }
    if (name === '/worker.mjs') { response.setHeader('Content-Type', 'text/javascript'); response.end(worker); return; }
    const file = resolve(root, '.' + name);
    if (!file.startsWith(root + sep)) { response.writeHead(403).end(); return; }
    response.setHeader('Content-Type', ['.mjs', '.js'].includes(extname(file)) ? 'text/javascript' : 'application/octet-stream');
    response.end(await readFile(file));
  } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ serviceWorkers: 'block' });
  await context.route('**/*', route => route.request().url().startsWith(origin + '/') ? route.continue() : route.abort());
  const page = await context.newPage();
  page.setDefaultTimeout(120000);
  await page.goto(origin);
  const checks = await page.evaluate(async () => {
    const api = await import('/livephoto.mjs');
    const image = new File([await (await fetch('/fixture-image')).blob()], 'misleading.mp4', { type: 'application/octet-stream' });
    const video = new File([await (await fetch('/fixture-video')).blob()], 'misleading.jpg', { type: 'application/octet-stream' });
    const digest = async blob => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))).map(v => v.toString(16).padStart(2, '0')).join('');
    const beforeImage = await digest(image), beforeVideo = await digest(video);
    const checks = [];
    const check = (name, condition, details = '') => { if (!condition) throw Error(name + ': ' + details); checks.push(name); };
    for (const target of ['oplus.olive', 'google.motionphoto.v2', 'google.microvideo.v1']) {
      const created = await api.createLivePhoto(image, video, { target });
      check(target + ':create', created.success && created.blob instanceof Blob && created.mime === 'image/jpeg', JSON.stringify(created));
      const detected = await api.detectLivePhoto(created.blob);
      check(target + ':detect', detected.success && detected.protocol === target, JSON.stringify(detected));
      const validated = await api.validateLivePhoto(created.blob);
      check(target + ':validate', validated.success && validated.verdict !== 'Invalid' && validated.coverage === 'Complete', JSON.stringify(validated));
      const extracted = await api.extractVideo(created.blob);
      check(target + ':extract', extracted.success && await digest(extracted.blob) === beforeVideo, JSON.stringify(extracted));
      check(target + ':inputs-immutable', await digest(image) === beforeImage && await digest(video) === beforeVideo);
      check(target + ':embedded-video', await digest(new Blob([await created.blob.slice(-video.size).arrayBuffer()])) === beforeVideo);
      const carrierHash = await digest(created.blob);
      const inspected = await api.inspectLivePhoto(created.blob);
      check(target + ':inspect-layout', inspected.success && inspected.report.kind === 'Inspection' &&
        inspected.report.detection.matches.some(m => m.protocol === target) && inspected.report.layout.sources[0].size === String(created.blob.size) &&
        inspected.report.layout.regions.every(r => /^\d+$/.test(r.range.offset) && /^\d+$/.test(r.range.length)));
      const analyzed = await api.analyzeLivePhoto(created.blob);
      check(target + ':analyze-no-media-backend', analyzed.success && analyzed.report.kind === 'Analysis' && analyzed.verdict !== 'Invalid' &&
        analyzed.report.validation.checks.every(c => ['Structure','Protocol'].includes(c.layer)) &&
        analyzed.report.capabilities.operations.some(o => o.operation === 'ExtractRaw') &&
        analyzed.report.capabilities.operations.every(o => !['ExtractFrame','Transcode','Trim'].includes(o.operation) || o.implementation === 'Unsupported'));
      const key = await api.getKeyPhotoPosition(created.blob);
      check(target + ':key-position', key.success && key.report.kind === 'KeyPhoto' &&
        JSON.stringify(key.report) === JSON.stringify(inspected.report.keyPhoto) &&
        (key.report.position === null || /^-?\d+$/.test(key.report.position.value)));
      const split = await api.splitLivePhoto(created.blob);
      const primary = split.assets.find(a => a.role === 'PrimaryImage'), motion = split.assets.find(a => a.role === 'MotionVideo');
      check(target + ':clean-split-assets', split.success && split.blob === null && split.assets.length === 2 && primary && motion &&
        (await api.detectLivePhoto(primary.blob)).verdict === 'NonLive' && await digest(motion.blob) === beforeVideo &&
        split.assets.every(a => a.byteLength === String(a.blob.size) && /^[0-9a-f]{64}$/.test(a.digest)) &&
        split.report.preservation.records.length > 0 && split.report.durability === 'volatile-process-memory');
      const same = await api.convertLivePhoto(created.blob, {target});
      check(target + ':same-target-exact', same.success && await digest(same.blob) === carrierHash);
      for (const other of ['oplus.olive','google.motionphoto.v2','google.microvideo.v1'].filter(p => p !== target)) {
        const converted = await api.convertLivePhoto(created.blob, {target:other});
        check(target + ':convert-to-' + other, converted.success && converted.protocol === other &&
          (await api.detectLivePhoto(converted.blob)).protocol === other &&
          await digest((await api.extractVideo(converted.blob)).blob) === beforeVideo && await digest(created.blob) === carrierHash &&
          converted.report.preservation.records.length > 0);
      }
    }
    const plain = await api.detectLivePhoto(image);
    check('ordinary-jpeg-is-not-live', plain.success && plain.protocol === '' && plain.verdict === 'NonLive', JSON.stringify(plain));
    const unsupported = await api.createLivePhoto(image, video, { target: 'not-a-protocol' });
    check('unknown-target-rejected', !unsupported.success && unsupported.code === 'CAPABILITY_UNSUPPORTED' && unsupported.blob === null);
    const swapped = await api.createLivePhoto(video, image);
    check('swapped-content-rejected', !swapped.success && swapped.code.length > 0 && swapped.stage.length > 0 && swapped.message.length > 0 && swapped.blob === null);
    const malformed = await api.createLivePhoto(image, video.slice(0, 20));
    check('truncated-video-no-output', !malformed.success && malformed.blob === null);
    let oversized = false;
    try { await api.createLivePhoto(new Blob([new Uint8Array(32 * 1024 * 1024)]), video); } catch (e) { oversized = e instanceof RangeError; }
    check('budget-rejected-before-reading', oversized);
    let badType = false;
    try { await api.createLivePhoto('not a File', video); } catch (e) { badType = e instanceof TypeError; }
    check('invalid-js-input-rejected', badType);
    const rejectedConvert = await api.convertLivePhoto(image, {target:'unsupported'});
    check('convert-unknown-target-no-assets', !rejectedConvert.success && rejectedConvert.blob === null && rejectedConvert.assets.length === 0);
    const ordinaryConvert = await api.convertLivePhoto(image, {target:'google.motionphoto.v2'});
    check('convert-ordinary-is-not-create', !ordinaryConvert.success && ordinaryConvert.assets.length === 0 && ordinaryConvert.code === 'SOURCE_NOT_LIVE');
    const brokenInspection = await api.inspectLivePhoto(new Blob([new Uint8Array([0,1,2,3])]));
    check('inspect-malformed-structured-error', !brokenInspection.success && brokenInspection.code.length > 0 && brokenInspection.report.kind === 'Error' && brokenInspection.assets.length === 0);
    let missingTarget = false;
    try { await api.convertLivePhoto(image); } catch(e) { missingTarget = e instanceof TypeError; }
    check('convert-target-required', missingTarget);
    const workerResult = await new Promise((resolve, reject) => {
      const worker = new Worker('/worker.mjs', { type: 'module' });
      const timeout = setTimeout(() => { worker.terminate(); reject(Error('Worker timeout')); }, 120000);
      worker.onmessage = event => { clearTimeout(timeout); worker.terminate(); resolve(event.data); };
      worker.onerror = event => { clearTimeout(timeout); worker.terminate(); reject(Error(event.message)); };
      worker.postMessage({});
    });
    check('module-worker-round-trip', workerResult.success, workerResult.message);
    return checks;
  });
  if (checks.length !== 50 || new Set(checks).size !== 50) throw new Error('Incomplete browser check count');
  const evidence = {
    commit: process.env.GITHUB_SHA, run: process.env.GITHUB_RUN_ID,
    browser: 'Chromium', browserVersion: browser.version(), tests: checks.length, failures: 0, checks,
    imageSha256: createHash('sha256').update(image).digest('hex'),
    videoSha256: createHash('sha256').update(video).digest('hex'),
    scope: 'real-browser-production-js-library-synthetic-media-no-decoder-no-device-proof-no-ui',
  };
  await writeFile('ci-output/web-browser-evidence.json', JSON.stringify(evidence, null, 2) + '\n');
  console.log('WEB_BROWSER_EVIDENCE=SUCCESS ' + checks.length + ' checks; no source or generated media uploaded');
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
