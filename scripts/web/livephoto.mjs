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
  return {
    success, code, stage, message, protocol, verdict, coverage, mime,
    issues: Array.from(issues),
    blob: value.bytes == null ? null : new Blob([value.bytes], { type: mime }),
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
