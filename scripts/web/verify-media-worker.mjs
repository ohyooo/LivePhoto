// CI-only: real production Kotlin/JS Core inside a module Worker, spawning
// the optional FFmpeg Worker itself. No parser or media implementation here.
import {createMediaBackend} from '/api/livephoto.mjs';
import {guardMediaInput} from '/media-io.mjs';

onmessage=async({data:{input}})=>{
  const checks=[];
  const check=(name,valid,details='')=>{if(!valid)throw Error(name+': '+details);checks.push(name);};
  const digest=async blob=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer())))
    .map(value=>value.toString(16).padStart(2,'0')).join('');
  try{
    const inputSha256=await digest(input),runtimeUrl=location.origin+'/livephoto/media/core-runtime.mjs';
    const diagnostics=[];
    const backend=createMediaBackend({runtimeUrl,onDiagnostic:entry=>diagnostics.push(entry)}),caps=backend.capabilities();
    check('worker-context-and-capabilities',typeof document==='undefined'&&self instanceof DedicatedWorkerGlobalScope&&
      caps.backendIds.join(',')==='ffmpeg-wasm'&&caps.operations.length===5&&caps.operations.every(op=>op.implementation==='Experimental'));
    const outputs={};
    const io=await guardMediaInput(input);
    for(const [action,options] of [['probe',undefined],['remux',undefined],['trim',{start:.4,end:1.6}],
      ['extractFrame',{start:.4}],['transcode',{authorizeTranscode:true}]]){
      const value=await backend[action](input,options);
      check('worker-'+action,value.success&&(action==='probe'
        ? value.coverage==='Partial'&&value.issues.includes('MEDIA_DECODE_COMPLETED')&&value.blob===null
        : value.blob instanceof Blob&&value.blob.size>0&&value.report.execution.some(e=>e.backendId==='ffmpeg-wasm')),JSON.stringify({value,diagnostics:diagnostics.slice(-3)}));
      outputs[action]={blob:value.blob,report:value.report};
    }
    const forbidden=await backend.transcode(input),malformed=await backend.probe(new Blob([new Uint8Array([0,1,2,3])]));
    check('worker-refusals-no-output',!forbidden.success&&forbidden.code==='TRANSCODE_NOT_AUTHORIZED'&&
      forbidden.blob===null&&forbidden.assets.length===0&&!malformed.success&&malformed.blob===null&&malformed.assets.length===0);
    const stopped=new AbortController();stopped.abort();
    const cancelled=await createMediaBackend({runtimeUrl,signal:stopped.signal}).probe(input);
    const running=new AbortController();
    const pending=createMediaBackend({runtimeUrl,signal:running.signal}).probe(input);
    const timer=setTimeout(()=>running.abort(),100);
    let interrupted;try{interrupted=await pending;}finally{clearTimeout(timer);}
    let budget=false;
    const oversized=new Blob([new Uint8Array([0])]);Object.defineProperty(oversized,'size',{value:512*1024*1024+1});
    oversized.arrayBuffer=()=>{throw Error('Over-budget input was read');};
    try{await backend.remux(oversized);}catch(error){budget=error instanceof RangeError;}
    check('worker-cancellation-and-budget',!cancelled.success&&cancelled.code==='CANCELLED'&&cancelled.blob===null&&
      !interrupted.success&&interrupted.code==='CANCELLED'&&interrupted.blob===null&&budget);
    io.restore();
    check('worker-input-immutable',await digest(input)===inputSha256&&io.stats.mounts>=5&&
      io.stats.wholeReads===0&&io.stats.inputWrites===0&&io.stats.outputParts>0);
    if(checks.length!==9||new Set(checks).size!==9)throw Error('Incomplete media Worker checks');
    postMessage({success:true,tests:checks.length,failures:0,checks,inputSha256,outputs,inputIo:io.stats});
  }catch(error){postMessage({success:false,message:String(error).slice(0,2000),checks});}
};
