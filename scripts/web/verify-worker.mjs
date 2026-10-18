// CI-only module Worker: exercise the production library, not a second parser.
import * as api from '/livephoto.mjs';

onmessage = async ({data: {image, video}}) => {
  const checks = [];
  const check = (name, valid, detail) => {
    if (!valid) throw Error(name + ': ' + JSON.stringify(detail));
    checks.push(name);
  };
  const digest = async blob => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())))
    .map(v => v.toString(16).padStart(2, '0')).join('');
  try {
    const imageHash = await digest(image), videoHash = await digest(video);
    let returnedVideo;
    for (const target of ['oplus.olive', 'google.motionphoto.v2', 'google.microvideo.v1']) {
      const created = await api.createLivePhoto(image, video, {target});
      check(target + ':create', created.success && created.blob instanceof Blob, created);
      const carrierHash = await digest(created.blob);
      const detected = await api.detectLivePhoto(created.blob);
      check(target + ':detect', detected.success && detected.protocol === target, detected);
      const validated = await api.validateLivePhoto(created.blob);
      check(target + ':validate', validated.success && validated.verdict !== 'Invalid' && validated.coverage === 'Complete', validated);
      const inspected = await api.inspectLivePhoto(created.blob);
      check(target + ':inspect', inspected.success && inspected.report.kind === 'Inspection' &&
        inspected.report.layout.sources[0].size === String(created.blob.size), inspected);
      const analyzed = await api.analyzeLivePhoto(created.blob);
      check(target + ':analyze', analyzed.success && analyzed.report.kind === 'Analysis' && analyzed.verdict !== 'Invalid' &&
        analyzed.report.mediaCapabilities.backendIds.length === 0, analyzed);
      const key = await api.getKeyPhotoPosition(created.blob);
      check(target + ':key', key.success && key.report.kind === 'KeyPhoto' &&
        JSON.stringify(key.report) === JSON.stringify(inspected.report.keyPhoto), key);
      const extracted = await api.extractVideo(created.blob);
      check(target + ':extract', extracted.success && await digest(extracted.blob) === videoHash &&
        extracted.report.preservation.records.some(r => r.guarantee === 'ExactExtraction' && r.outcome === 'Verified'), extracted);
      returnedVideo = extracted.blob;
      const split = await api.splitLivePhoto(created.blob);
      const primary = split.assets.find(a => a.role === 'PrimaryImage'), motion = split.assets.find(a => a.role === 'MotionVideo');
      check(target + ':split', split.success && split.assets.length === 2 && primary && motion &&
        (await api.detectLivePhoto(primary.blob)).verdict === 'NonLive' && await digest(motion.blob) === videoHash &&
        split.assets.every(a => a.byteLength === String(a.blob.size) && /^[0-9a-f]{64}$/.test(a.digest)), split);
      const same = await api.convertLivePhoto(created.blob, {target});
      check(target + ':same-target', same.success && await digest(same.blob) === carrierHash, same);
      const other = target === 'google.motionphoto.v2' ? 'google.microvideo.v1' : 'google.motionphoto.v2';
      const converted = await api.convertLivePhoto(created.blob, {target: other});
      check(target + ':convert', target === 'oplus.olive'
        ? !converted.success && converted.code === 'UNSAFE_METADATA_REWRITE' && converted.blob === null && converted.assets.length === 0
        : converted.success && (await api.detectLivePhoto(converted.blob)).protocol === other &&
          await digest((await api.extractVideo(converted.blob)).blob) === videoHash, converted);
      if (target === 'oplus.olive') {
        const refused = await api.repairLivePhoto(created.blob, {dryRun: false});
        check(target + ':repair', !refused.success && refused.code === 'REPAIR_AMBIGUOUS' && refused.blob === null && refused.assets.length === 0, refused);
      } else {
        // Corrupt only an owned decimal length, preserving segment sizes/media.
        const bytes = new Uint8Array(await created.blob.arrayBuffer());
        const header = new TextDecoder('latin1').decode(bytes.slice(0, bytes.length - video.size));
        const expression = target === 'google.microvideo.v1'
          ? /\b(?:[A-Za-z_][\w.-]*:)?MicroVideoOffset=(["'])([1-9][0-9]*)\1/
          : /\b(?:[A-Za-z_][\w.-]*:)?Length=(["'])([1-9][0-9]*)\1/;
        const match = expression.exec(header);
        if (!match) throw Error('Missing independent Worker repair length');
        const offset = match.index + match[0].lastIndexOf(match[2]);
        bytes.fill(48, offset, offset + match[2].length);
        const damaged = new Blob([bytes]), damagedHash = await digest(damaged);
        const preview = await api.repairLivePhoto(damaged);
        const applied = await api.repairLivePhoto(damaged, {dryRun: false});
        check(target + ':repair', preview.success && preview.report.dryRun && preview.assets.length === 0 &&
          preview.report.proposedChanges.length > 0 && applied.success && applied.blob instanceof Blob &&
          applied.report.blocked.length === 0 && applied.report.operation.validation.verdict !== 'Invalid' &&
          JSON.stringify(applied.report.changesApplied) === JSON.stringify(preview.report.proposedChanges) &&
          await digest((await api.extractVideo(applied.blob)).blob) === videoHash && await digest(damaged) === damagedHash,
          {preview, applied});
      }
      check(target + ':immutable', await digest(image) === imageHash && await digest(video) === videoHash &&
        await digest(created.blob) === carrierHash);
    }
    const malformed = await api.inspectLivePhoto(new Blob([new Uint8Array([255,216,255,225,0,16,0,0])]));
    check('malformed-structured-error', !malformed.success && malformed.report.kind === 'Error' && malformed.assets.length === 0 && malformed.blob === null, malformed);
    const unsupported = await api.createLivePhoto(image, video, {target: 'not-a-protocol'});
    check('unsupported-no-output', !unsupported.success && unsupported.code === 'CAPABILITY_UNSUPPORTED' && unsupported.blob === null, unsupported);
    const ordinary = await api.convertLivePhoto(image, {target: 'google.motionphoto.v2'});
    check('ordinary-convert-not-create', !ordinary.success && ordinary.code === 'SOURCE_NOT_LIVE' && ordinary.assets.length === 0, ordinary);
    for (const [name, action, type] of [
      ['missing-target', () => api.convertLivePhoto(image), TypeError],
      ['invalid-input-type', () => api.detectLivePhoto('not a Blob'), TypeError],
      ['budget-rejected', () => api.inspectLivePhoto(new Blob([new Uint8Array(48 * 1024 * 1024 + 1)])), RangeError],
    ]) {
      let rejected = false;
      try { await action(); } catch (error) { rejected = error instanceof type; }
      check(name, rejected);
    }
    if (checks.length !== 42 || new Set(checks).size !== 42) throw Error('Incomplete Worker checks');
    postMessage({success: true, tests: checks.length, failures: 0, checks,
      imageSha256: imageHash, videoSha256: videoHash, returnedVideo});
  } catch (error) { postMessage({success: false, message: String(error), checks}); }
};
