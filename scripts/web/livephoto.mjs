import * as compiled from './livephoto-core.mjs';

// Kotlin exports can be namespaced; the public browser entry is always flat.
const core = compiled.livephoto?.web ?? compiled;
const INPUT_LIMIT = 32 * 1024 * 1024;
const CARRIER_LIMIT = 48 * 1024 * 1024;

async function bytes(blob, limit) {
  if (!(blob instanceof Blob)) throw new TypeError('Expected a File or Blob');
  if (blob.size > limit) throw new RangeError('Input exceeds the documented memory budget');
  return new Int8Array(await blob.arrayBuffer());
}

function result(value) {
  const { success, code, stage, message, protocol, verdict, coverage, mime, issues } = value;
  const assets = Array.from(value.assets, asset => ({
    id: asset.id, role: asset.role, mime: asset.mime, byteLength: asset.byteLength,
    digest: asset.digest, recommendedName: asset.recommendedName,
    blob: new Blob([asset.bytes], { type: asset.mime }),
  }));
  return {
    success, code, stage, message, protocol, verdict, coverage, mime,
    issues: Array.from(issues),
    report: value.reportJson ? JSON.parse(value.reportJson) : null,
    assets,
    blob: value.bytes == null ? null : assets.length === 1 ? assets[0].blob : new Blob([value.bytes], { type: mime }),
  };
}

export async function createLivePhoto(image, video, { target = 'oplus.olive', replaceCameraComment = false } = {}) {
  if (!(image instanceof Blob) || !(video instanceof Blob)) throw new TypeError('Expected image and video File/Blob inputs');
  if (image.size + video.size > INPUT_LIMIT) throw new RangeError('Combined inputs exceed 32 MiB');
  if (typeof target !== 'string' || typeof replaceCameraComment !== 'boolean') throw new TypeError('Invalid Web options');
  const [photo, movie] = await Promise.all([bytes(image, INPUT_LIMIT), bytes(video, INPUT_LIMIT)]);
  return result(await core.createLivePhoto(photo, movie, target, replaceCameraComment));
}

export async function detectLivePhoto(input) { return result(await core.detectLivePhoto(await bytes(input, CARRIER_LIMIT))); }
export async function validateLivePhoto(input) { return result(await core.validateLivePhoto(await bytes(input, CARRIER_LIMIT))); }
export async function extractVideo(input) { return result(await core.extractVideo(await bytes(input, CARRIER_LIMIT))); }
export async function inspectLivePhoto(input) { return result(await core.inspectLivePhoto(await bytes(input, CARRIER_LIMIT))); }
export async function analyzeLivePhoto(input) { return result(await core.analyzeLivePhoto(await bytes(input, CARRIER_LIMIT))); }
export async function getKeyPhotoPosition(input) { return result(await core.getKeyPhotoPosition(await bytes(input, CARRIER_LIMIT))); }
export async function splitLivePhoto(input) { return result(await core.splitLivePhoto(await bytes(input, CARRIER_LIMIT))); }
export async function convertLivePhoto(input, { target } = {}) {
  if (typeof target !== 'string') throw new TypeError('Convert requires a target protocol');
  return result(await core.convertLivePhoto(await bytes(input, CARRIER_LIMIT), target));
}
