import * as compiled from './livephoto-core.mjs';

// Kotlin exports can be namespaced; the public browser entry is always flat.
const core = compiled.livephoto?.web ?? compiled;
const INPUT_LIMIT = 32 * 1024 * 1024;
const CARRIER_LIMIT = 48 * 1024 * 1024;

function source(blob, limit) {
  if (!(blob instanceof Blob)) throw new TypeError('Expected a File or Blob');
  if (blob.size > limit) throw new RangeError('Input exceeds the documented memory budget');
  return {size:blob.size,readAt:async(offset,length)=>new Int8Array(await blob.slice(offset,offset+length).arrayBuffer())};
}

function result(value) {
  const { success, code, stage, message, protocol, verdict, coverage, mime, issues } = value;
  const assets = Array.from(value.assets, asset => ({
    id: asset.id, role: asset.role, mime: asset.mime, byteLength: asset.byteLength,
    digest: asset.digest, recommendedName: asset.recommendedName,
    blob: asset.blob,
  }));
  return {
    success, code, stage, message, protocol, verdict, coverage, mime,
    issues: Array.from(issues),
    report: value.reportJson ? JSON.parse(value.reportJson) : null,
    assets,
    blob: value.blob ?? null,
  };
}

export async function createLivePhoto(image, video, { target = 'oplus.olive', replaceCameraComment = false } = {}) {
  if (!(image instanceof Blob) || !(video instanceof Blob)) throw new TypeError('Expected image and video File/Blob inputs');
  if (image.size + video.size >= INPUT_LIMIT) throw new RangeError('Combined inputs must be smaller than 32 MiB');
  if (typeof target !== 'string' || typeof replaceCameraComment !== 'boolean') throw new TypeError('Invalid Web options');
  const photo=source(image,INPUT_LIMIT),movie=source(video,INPUT_LIMIT);
  return result(await core.createLivePhoto(photo, movie, target, replaceCameraComment));
}

export async function detectLivePhoto(input) { return result(await core.detectLivePhoto(source(input, CARRIER_LIMIT))); }
export async function validateLivePhoto(input) { return result(await core.validateLivePhoto(source(input, CARRIER_LIMIT))); }
export async function extractVideo(input) { return result(await core.extractVideo(source(input, CARRIER_LIMIT))); }
export async function inspectLivePhoto(input) { return result(await core.inspectLivePhoto(source(input, CARRIER_LIMIT))); }
export async function analyzeLivePhoto(input) { return result(await core.analyzeLivePhoto(source(input, CARRIER_LIMIT))); }
export async function getKeyPhotoPosition(input) { return result(await core.getKeyPhotoPosition(source(input, CARRIER_LIMIT))); }
/** Optional same-origin Worker backend. Does not change existing protocol-only defaults. */
export function createMediaBackend({runtimeUrl=new URL('./media/core-runtime.mjs',import.meta.url),signal,onDiagnostic}={}) {
  const runtime=new URL(runtimeUrl,import.meta.url);
  if(runtime.origin!==globalThis.location.origin)throw new TypeError('Media runtime must be same-origin');
  const run=async(input,action,{start=0,end=0,authorizeTranscode=false}={})=>{
    if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<0||start>60||end>60||typeof authorizeTranscode!=='boolean')throw new TypeError('Invalid media range or authorization');
    const data=source(input,512*1024*1024);
    const executor=async(args,offset,length,previous,name,timeout,cancelled)=>{
      if(!Number.isSafeInteger(offset)||!Number.isSafeInteger(length)||offset<0||length<0||offset+length>input.size)throw Error('Unbound media extent');
      const view=offset===0&&length===input.size?input:input.slice(offset,offset+length);
      const response=await(await import(runtime.href)).executeCoreFfmpeg(args,view,previous,name,timeout,cancelled);
      // Explicit opt-in only: decoder diagnostics can include private metadata.
      if(typeof onDiagnostic==='function')try{onDiagnostic({code:response.code,output:response.output.slice(0,2000),cancelled:response.cancelled,timedOut:response.timedOut,ioFailed:response.ioFailed});}catch{}
      return response;
    };
    return result(await core.processMedia(data,action,executor,Math.round(start*1e6),Math.round(end*1e6),authorizeTranscode,()=>signal?.aborted===true));
  };
  return Object.freeze({capabilities:()=>JSON.parse(core.browserMediaCapabilities()),probe:input=>run(input,'probe'),remux:input=>run(input,'remux'),
    trim:(input,options)=>run(input,'trim',options),extractFrame:(input,options)=>run(input,'frame',options),
    transcode:(input,options)=>run(input,'transcode',options)});
}
export async function splitLivePhoto(input) { return result(await core.splitLivePhoto(source(input, CARRIER_LIMIT))); }
export async function convertLivePhoto(input, { target } = {}) {
  if (typeof target !== 'string') throw new TypeError('Convert requires a target protocol');
  return result(await core.convertLivePhoto(source(input, CARRIER_LIMIT), target));
}

/** success means a report was obtained, not that a repair was applied. */
export async function repairLivePhoto(input, { dryRun = true, allowedIssueCodes = [] } = {}) {
  if (typeof dryRun !== 'boolean' || !Array.isArray(allowedIssueCodes) || allowedIssueCodes.length > 64 ||
      !allowedIssueCodes.every(code => typeof code === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(code))) {
    throw new TypeError('Repair requires a boolean dryRun and at most 64 issue codes');
  }
  // Copy the filter before awaiting the Blob so callers cannot change the request in flight.
  const codes = [...allowedIssueCodes];
  return result(await core.repairLivePhoto(source(input, CARRIER_LIMIT), dryRun, codes));
}
