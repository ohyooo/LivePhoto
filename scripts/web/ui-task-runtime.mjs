/** Transport only: bounded IO on the owner thread, Kotlin/Wasm computation in a dedicated Worker. */
export function createTaskRunner() {
  let worker=null, ready=false, pending=null, next=0;
  const finish=(error,result)=>{
    const current=pending;if(!current)return;
    pending=null;clearTimeout(current.timer);
    if(error)current.reject(error);else current.resolve(result);
  };
  const stop=code=>{
    worker?.terminate();worker=null;ready=false;
    finish(null,{report:'',failed:true,code});
  };
  const start=()=>{
    if(worker)return worker;
    const active=new Worker(new URL('./worker/livephoto-worker.js',import.meta.url));
    worker=active;
    active.onerror=event=>{event.preventDefault();if(worker===active)stop('WORKER_FAILED');};
    active.onmessageerror=()=>{if(worker===active)stop('WORKER_FAILED');};
    active.onmessage=async({data})=>{
      const task=pending;
      if(worker===active&&data.type==='ready'){
        ready=true;if(task)active.postMessage({type:'run',task:task.id,options:task.options,sizes:task.files.map(f=>f.size),files:task.files});
        return;
      }
      if(worker!==active||!task||data.task!==task.id)return;
      if(data.type==='read'){
        let bytes=null;
        try {
          const file=task.files[data.source];
          if(!file||!Number.isSafeInteger(data.offset)||!Number.isSafeInteger(data.length)||
            data.offset<0||data.length<0||data.length>65536||data.offset+data.length>file.size)throw Error('Invalid read');
          bytes=await file.slice(data.offset,data.offset+data.length).arrayBuffer();
          if(bytes.byteLength!==data.length)bytes=null;
        } catch { /* The worker reports a structured source-specific IO error. */ }
        if(worker===active&&pending===task)active.postMessage({type:'read',id:data.id,bytes},bytes?[bytes]:[]);
      } else if(data.type==='done'){
        const result=data.result;
        if(typeof result?.report!=='string'||typeof result.failed!=='boolean'||
          [result.bytes,result.second].some(value=>value!=null&&!(value instanceof Blob))||
          (result.bytes?.size??0)+(result.second?.size??0)>48*1024*1024||
          (result.failed&&(result.bytes!=null||result.second!=null))){stop('WORKER_FAILED');return;}
        finish(null,result);
      }
    };
    return active;
  };
  const runner={
    run(options,files){
      if(pending)return Promise.reject(Error('A task is already running'));
      if(!Array.isArray(files)||files.length<1||files.length>2||files.some(f=>!(f instanceof Blob)))return Promise.reject(Error('Invalid files'));
      const inputLimit=['Probe','Remux','Trim','ExtractFrame','Transcode'].includes(options?.kind)?512*1024*1024:32*1024*1024;
      const inputSize=files.reduce((n,f)=>n+f.size,0);
      if(inputSize>inputLimit||(options?.kind==='Create'&&inputSize===inputLimit)){
        return Promise.resolve({report:'',failed:true,code:'RESOURCE_LIMIT_EXCEEDED'});
      }
      return new Promise((resolve,reject)=>{
        const id=++next;
        pending={id,options,files,resolve,reject,timer:setTimeout(()=>stop('TIMEOUT'),120000)};
        try {const active=start();if(ready)active.postMessage({type:'run',task:id,options,sizes:files.map(f=>f.size),files});}
        catch {stop('WORKER_FAILED');}
      });
    },
    cancel(){if(pending)stop('CANCELLED');},
    dispose(){stop('CANCELLED');},
  };
  addEventListener('pagehide',()=>runner.dispose());
  return runner;
}
