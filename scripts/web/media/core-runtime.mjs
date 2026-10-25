/** Private disk staging only; commands and verification belong to Kotlin Core. */
const directory='livephoto-media-stage-v1';
const lockName=id=>directory+'/'+id;
const owned=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

async function remove(root,id) {
  try{await root.removeEntry(id,{recursive:true});}catch(error){if(error.name!=='NotFoundError')throw error;}
}

export async function executeCoreFfmpeg(argsJson,input,previousOutput,outputName,timeoutMillis,isCancelled=()=>false) {
  const args=JSON.parse(argsJson),limit=48*1024*1024;
  if(!Array.isArray(args)||args.length>256||!args.every(a=>typeof a==='string'&&a.length<=4096)||
    !['output.bin','frame.jpg'].includes(outputName)||!(input instanceof Blob)||input.size>512*1024*1024||
    (previousOutput!=null&&(!(previousOutput instanceof Blob)||previousOutput.size>limit))||!Number.isFinite(timeoutMillis))
    throw Error('Invalid private media job');
  const result={code:null,output:'',outputLimited:false,cancelled:false,timedOut:false,ioFailed:false,chunks:null};
  let engine,timer,poll,stopped=false,finish;
  const stop=flag=>{if(stopped)return;stopped=true;result[flag]=true;engine?.terminate();finish?.();};
  timer=setTimeout(()=>stop('timedOut'),Math.min(120000,Math.max(1,timeoutMillis)));
  poll=setInterval(()=>{if(isCancelled())stop('cancelled');},25);
  try {
    if(isCancelled()){stop('cancelled');return result;}
    if(!navigator.storage?.getDirectory||!navigator.locks)throw Error('Browser private disk storage is unavailable');
    const root=await(await navigator.storage.getDirectory()).getDirectoryHandle(directory,{create:true});
    // The lock precedes directory creation, also in other tabs. Browser
    // termination releases locks, so only abandoned jobs are scavenged.
    for await(const [id] of root.entries())if(owned.test(id))
      await navigator.locks.request(lockName(id),{ifAvailable:true},lock=>lock?remove(root,id):undefined);
    if(stopped)return result;
    const id=crypto.randomUUID();
    await navigator.locks.request(lockName(id),async()=>{
      if(stopped)return;
      try {
        const stage=await root.getDirectoryHandle(id,{create:true});
        if(stopped)return;
        engine=new Worker(new URL('./disk-worker.mjs',import.meta.url),{type:'module'});
        const chunks=[];let length=0;
        await new Promise(resolve=>{
          finish=resolve;
          engine.onerror=()=>stop('ioFailed');
          engine.onmessage=({data})=>{
            if(stopped)return;
            if(data.type==='log'){
              if(typeof data.text!=='string'||result.output.length+data.text.length>65536){stop('outputLimited');return;}
              result.output+=data.text;
            }else if(data.type==='chunk'){
              if(!(data.bytes instanceof Int8Array)||data.bytes.length>65536||!data.bytes.length){stop('ioFailed');return;}
              length+=data.bytes.length;
              if(length>limit){stop('outputLimited');return;}
              chunks.push(data.bytes);
            }else if(data.type==='done'){
              result.code=data.code;result.ioFailed=data.ioFailed;result.outputLimited=data.outputLimited;
              if(data.code===0&&!data.ioFailed&&!data.outputLimited&&args.at(-1)===outputName)result.chunks=chunks;
              resolve();
            }else stop('ioFailed');
          };
          engine.postMessage({type:'execute',args,input,previousOutput,outputName,timeoutMillis,stage});
        });
      }finally{
        engine?.terminate();
        await remove(root,id);
      }
    });
  }catch(error){
    if(!stopped){result.ioFailed=true;result.output+='Private media storage failed ('+String(error.name||'Error')+').\n';}
  }finally{clearTimeout(timer);clearInterval(poll);engine?.terminate();}
  if(result.cancelled||result.timedOut||result.outputLimited||result.ioFailed)result.chunks=null;
  return result;
}
