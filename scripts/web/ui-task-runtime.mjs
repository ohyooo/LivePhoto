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
    const label={Create:'生成',Analyze:'检查',Extract:'提取',Split:'清理拆分',Convert:'转换',Repair:'修复',Key:'关键照片修改'}[pending?.options.kind]??'后台处理';
    finish(null,{report:`${label}失败\n${code==='CANCELLED'?'已取消处理':'后台处理未完成'}\n错误码：${code}\n原片未修改，未发起新的下载；修复需要重新预览。`,failed:true});
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
        ready=true;if(task)active.postMessage({type:'run',task:task.id,options:task.options,sizes:task.files.map(f=>f.size)});
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
          (result.bytes?.byteLength??0)+(result.second?.byteLength??0)>48*1024*1024){stop('WORKER_FAILED');return;}
        finish(null,result);
      }
    };
    return active;
  };
  const runner={
    run(options,files){
      if(pending)return Promise.reject(Error('A task is already running'));
      if(!Array.isArray(files)||files.length<1||files.length>2||files.some(f=>!(f instanceof Blob)))return Promise.reject(Error('Invalid files'));
      if(files.reduce((n,f)=>n+f.size,0)>32*1024*1024){
        const label={Create:'生成',Analyze:'检查',Extract:'提取',Split:'清理拆分',Convert:'转换',Repair:'修复',Key:'关键照片修改'}[options.kind]??'后台处理';
        return Promise.resolve({report:`${label}失败\n错误码：RESOURCE_LIMIT_EXCEEDED\n阶段：Read\n输入超过 32 MiB，未读取文件。`,failed:true});
      }
      return new Promise((resolve,reject)=>{
        const id=++next;
        pending={id,options,files,resolve,reject,timer:setTimeout(()=>stop('TIMEOUT'),120000)};
        try {const active=start();if(ready)active.postMessage({type:'run',task:id,options,sizes:files.map(f=>f.size)});}
        catch {stop('WORKER_FAILED');}
      });
    },
    cancel(){if(pending)stop('CANCELLED');},
    dispose(){stop('CANCELLED');},
  };
  addEventListener('pagehide',()=>runner.dispose());
  return runner;
}
