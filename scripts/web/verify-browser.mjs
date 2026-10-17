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
const worker = `import {createLivePhoto,extractVideo,repairLivePhoto} from '/livephoto.mjs';
onmessage=async()=>{try{const image=await(await fetch('/fixture-image')).blob();const video=await(await fetch('/fixture-video')).blob();
const result=await createLivePhoto(image,video);if(!result.success)throw Error(result.code+': '+result.message);
const extracted=await extractVideo(result.blob);if(!extracted.success)throw Error(extracted.code);
const repairable=await createLivePhoto(image,video,{target:'google.microvideo.v1'});if(!repairable.success)throw Error('Worker repair fixture');
const preview=await repairLivePhoto(repairable.blob);if(!preview.success||preview.report.kind!=='Repair'||!preview.report.dryRun||preview.assets.length||preview.report.blocked.length)throw Error('Worker repair preview');
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
    let brokenV1;
    // Independently corrupt one owned length attribute in-place; do not touch JPEG
    // segment sizes, image coding, ordinary metadata or the complete video suffix.
    const corruptLength = async (blob, target) => {
      const data = new Uint8Array(await blob.arrayBuffer());
      const header = new TextDecoder('latin1').decode(data.slice(0, data.length - video.size));
      const expression = target === 'google.microvideo.v1'
        ? /\b(?:[A-Za-z_][\w.-]*:)?MicroVideoOffset=(["'])([1-9][0-9]*)\1/
        : /\b(?:[A-Za-z_][\w.-]*:)?Length=(["'])([1-9][0-9]*)\1/;
      const match = expression.exec(header);
      if (!match) throw Error('Missing unique synthetic length field: ' + target);
      const start = match.index + match[0].lastIndexOf(match[2]);
      data.fill(48, start, start + match[2].length);
      return new Blob([data], {type:'image/jpeg'});
    };
    for (const target of ['oplus.olive', 'google.motionphoto.v2', 'google.microvideo.v1']) {
      const created = await api.createLivePhoto(image, video, { target });
      check(target + ':create', created.success && created.blob instanceof Blob && created.mime === 'image/jpeg', JSON.stringify(created));
      const detected = await api.detectLivePhoto(created.blob);
      check(target + ':detect', detected.success && detected.protocol === target, JSON.stringify(detected));
      const validated = await api.validateLivePhoto(created.blob);
      check(target + ':validate', validated.success && validated.verdict !== 'Invalid' && validated.coverage === 'Complete', JSON.stringify(validated));
      const extracted = await api.extractVideo(created.blob);
      check(target + ':extract', extracted.success && await digest(extracted.blob) === beforeVideo &&
        extracted.assets.length === 1 && extracted.assets[0].role === 'MotionVideo' &&
        extracted.report.preservation.records.some(r => r.guarantee === 'ExactExtraction' && r.outcome === 'Verified'), JSON.stringify(extracted));
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
        analyzed.report.mediaCapabilities.backendIds.length === 0 &&
        analyzed.report.mediaCapabilities.operations.filter(o => ['ExtractFrame','Transcode','Trim'].includes(o.operation)).length === 3 &&
        analyzed.report.mediaCapabilities.operations.every(o => !['ExtractFrame','Transcode','Trim'].includes(o.operation) || o.implementation === 'Unsupported'));
      const key = await api.getKeyPhotoPosition(created.blob);
      check(target + ':key-position', key.success && key.report.kind === 'KeyPhoto' &&
        JSON.stringify(key.report) === JSON.stringify(inspected.report.keyPhoto) &&
        (key.report.position === null || /^-?\d+$/.test(key.report.position.value)));
      const split = await api.splitLivePhoto(created.blob);
      const primary = split.assets.find(a => a.role === 'PrimaryImage'), motion = split.assets.find(a => a.role === 'MotionVideo');
      const secondSplit = primary && await api.splitLivePhoto(primary.blob);
      check(target + ':clean-split-assets', split.success && split.blob === null && split.assets.length === 2 && primary && motion &&
        (await api.detectLivePhoto(primary.blob)).verdict === 'NonLive' && await digest(motion.blob) === beforeVideo &&
        split.assets.every(a => a.byteLength === String(a.blob.size) && /^[0-9a-f]{64}$/.test(a.digest)) &&
        split.report.preservation.records.length > 0 && split.report.durability === 'volatile-process-memory' &&
        secondSplit.success && secondSplit.assets.length === 1 && await digest(secondSplit.blob) === await digest(primary.blob));
      const same = await api.convertLivePhoto(created.blob, {target});
      check(target + ':same-target-exact', same.success && await digest(same.blob) === carrierHash);
      for (const other of ['oplus.olive','google.motionphoto.v2','google.microvideo.v1'].filter(p => p !== target)) {
        const converted = await api.convertLivePhoto(created.blob, {target:other});
        // Fresh upstream-compatible Oplus puts XMP before EXIF. Core deliberately
        // refuses a conversion that would resize that slot and move unverified EXIF.
        // Preserve its contract; the Web adapter must not authorize loss or transcode.
        if (target === 'oplus.olive') {
          check(target + ':convert-blocked-exif-' + other,
            !converted.success && converted.code === 'UNSAFE_METADATA_REWRITE' && converted.stage === 'Parse' &&
            converted.assets.length === 0 && converted.blob === null && converted.report.kind === 'Error' &&
            await digest(created.blob) === carrierHash, JSON.stringify(converted));
          continue;
        }
        const convertedDetection = converted.success ? await api.detectLivePhoto(converted.blob) : null;
        const convertedVideo = converted.success ? await api.extractVideo(converted.blob) : null;
        const convertedVideoHash = convertedVideo?.success ? await digest(convertedVideo.blob) : null;
        check(target + ':convert-to-' + other, converted.success && converted.protocol === other &&
          convertedDetection.protocol === other && convertedVideoHash === beforeVideo && await digest(created.blob) === carrierHash &&
          converted.report.preservation.records.length > 0,
          JSON.stringify({success:converted.success,code:converted.code,stage:converted.stage,message:converted.message,
            protocol:converted.protocol,detected:convertedDetection?.protocol,videoHash:convertedVideoHash,expectedVideoHash:beforeVideo,report:converted.report}));
      }
      if (target === 'oplus.olive') {
        // Core intentionally excludes this writer's explicit secondary Padding=0
        // repair dialect. Do not broaden protocol writes in a Web adapter task.
        const damaged = await corruptLength(created.blob, target), damagedHash = await digest(damaged);
        for (const [name, input, options] of [
          ['repair-existing-layout-refused',created.blob,{}],
          ['repair-damaged-layout-refused',damaged,{}],
          ['repair-filtered-layout-refused',damaged,{dryRun:false,allowedIssueCodes:['OTHER_ISSUE']}],
          ['repair-apply-layout-refused',damaged,{dryRun:false}],
        ]) {
          const refused = await api.repairLivePhoto(input, options);
          check(target + ':' + name, !refused.success && refused.code === 'REPAIR_AMBIGUOUS' && refused.stage === 'Plan' &&
            refused.report.kind === 'Error' && refused.assets.length === 0 && refused.blob === null, JSON.stringify(refused));
        }
        check(target + ':repair-refused-input-immutable', await digest(created.blob) === carrierHash && await digest(damaged) === damagedHash);
        continue;
      }
      const unchangedRepair = await api.repairLivePhoto(created.blob, {dryRun:false});
      check(target + ':repair-valid-noop', unchangedRepair.success && unchangedRepair.report.kind === 'Repair' &&
        !unchangedRepair.report.dryRun && unchangedRepair.report.proposedChanges.length === 0 &&
        unchangedRepair.report.changesApplied.length === 0 && unchangedRepair.report.blocked.length === 0 &&
        unchangedRepair.report.operation === null && unchangedRepair.blob === null && unchangedRepair.assets.length === 0,
        JSON.stringify(unchangedRepair));
      const damaged = await corruptLength(created.blob, target), damagedHash = await digest(damaged);
      if (target === 'google.microvideo.v1') brokenV1 = damaged;
      const preview = await api.repairLivePhoto(damaged);
      check(target + ':repair-dry-run', preview.success && preview.report.kind === 'Repair' && preview.report.dryRun &&
        preview.report.proposedChanges.length > 0 && preview.report.proposedChanges.every(c => c.after.value === String(video.size)) &&
        preview.report.changesApplied.length === 0 && preview.report.blocked.length === 0 && preview.report.operation === null &&
        preview.blob === null && preview.assets.length === 0 && await digest(damaged) === damagedHash,
        JSON.stringify(preview));
      const blocked = await api.repairLivePhoto(damaged, {dryRun:false,allowedIssueCodes:['OTHER_ISSUE']});
      check(target + ':repair-filter-blocked', blocked.success && blocked.report.blocked.length > 0 &&
        blocked.report.changesApplied.length === 0 && blocked.report.operation === null &&
        blocked.blob === null && blocked.assets.length === 0 && await digest(damaged) === damagedHash,
        JSON.stringify(blocked));
      const repaired = await api.repairLivePhoto(damaged, {dryRun:false});
      const repairedVideo = repaired.success && repaired.blob ? await api.extractVideo(repaired.blob) : null;
      check(target + ':repair-applied-preserving', repaired.success && repaired.assets.length === 1 && repaired.blob instanceof Blob &&
        !repaired.report.dryRun && repaired.report.blocked.length === 0 &&
        JSON.stringify(repaired.report.changesApplied) === JSON.stringify(preview.report.proposedChanges) &&
        repaired.report.operation.validation.verdict !== 'Invalid' && repaired.report.operation.validation.coverage === 'Complete' &&
        ['ImageDataPreserving','BitstreamPreserving','MetadataPreserving'].every(guarantee =>
          repaired.report.operation.preservation.records.some(r => r.guarantee === guarantee && r.outcome === 'Verified')) &&
        repairedVideo?.success && await digest(repairedVideo.blob) === beforeVideo &&
        await digest(damaged) === damagedHash && await digest(created.blob) === carrierHash,
        JSON.stringify(repaired));
      const again = await api.repairLivePhoto(repaired.blob, {dryRun:false});
      check(target + ':repair-idempotent-no-output', again.success && again.report.proposedChanges.length === 0 &&
        again.report.changesApplied.length === 0 && again.report.blocked.length === 0 &&
        again.report.operation === null && again.assets.length === 0 && again.blob === null);
    }
    let invalidRepairOptions = true;
    for (const options of [{dryRun:'false'},{allowedIssueCodes:'ALL'},{allowedIssueCodes:['']},{allowedIssueCodes:[1]},
      {allowedIssueCodes:['not-valid']},{allowedIssueCodes:Array(65).fill('OTHER_ISSUE')}]) {
      let rejected = false;
      try { await api.repairLivePhoto(image, options); } catch(e) { rejected = e instanceof TypeError; }
      invalidRepairOptions &&= rejected;
    }
    check('repair-invalid-options-rejected', invalidRepairOptions);
    let repairBudget = false;
    try { await api.repairLivePhoto(new Blob([new Uint8Array(48 * 1024 * 1024 + 1)])); } catch(e) { repairBudget = e instanceof RangeError; }
    check('repair-budget-before-reading', repairBudget);
    const ordinaryRepair = await api.repairLivePhoto(image, {dryRun:false});
    check('repair-ordinary-no-output', !ordinaryRepair.success && ordinaryRepair.code.length > 0 && ordinaryRepair.report.kind === 'Error' &&
      ordinaryRepair.blob === null && ordinaryRepair.assets.length === 0, JSON.stringify(ordinaryRepair));
    const malformedRepair = await api.repairLivePhoto(new Blob([new Uint8Array([255,216,255,225,0,16,0,0])]), {dryRun:false});
    check('repair-malformed-no-output', !malformedRepair.success && malformedRepair.report.kind === 'Error' &&
      malformedRepair.blob === null && malformedRepair.assets.length === 0);
    const ambiguousRepair = await api.repairLivePhoto(new Blob([brokenV1,video]), {dryRun:false});
    check('repair-ambiguous-suffix-no-output', !ambiguousRepair.success && ambiguousRepair.code === 'REPAIR_NOT_POSSIBLE' &&
      ambiguousRepair.assets.length === 0 && ambiguousRepair.blob === null, JSON.stringify(ambiguousRepair));
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
    const unknownInspection = await api.inspectLivePhoto(new Blob([new Uint8Array([0,1,2,3])]));
    const brokenInspection = await api.inspectLivePhoto(new Blob([new Uint8Array([255,216,255,225,0,16,0,0])]));
    check('inspect-unknown-and-malformed-structured-results',
      unknownInspection.success && unknownInspection.verdict === 'Unknown' && unknownInspection.report.kind === 'Inspection' &&
      unknownInspection.report.detection.matches.length === 0 && unknownInspection.assets.length === 0 &&
      !brokenInspection.success && brokenInspection.code.length > 0 && brokenInspection.report.kind === 'Error' &&
      brokenInspection.assets.length === 0 && brokenInspection.blob === null,
      JSON.stringify({unknown:unknownInspection,broken:brokenInspection}));
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
  if (checks.length !== 70 || new Set(checks).size !== 70) throw new Error('Incomplete browser check count');
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
