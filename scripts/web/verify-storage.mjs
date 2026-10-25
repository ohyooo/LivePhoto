// Real Chromium OPFS lifecycle checks, independent of protocol/UI scenarios.
export async function verifyStorage({page,context,origin,part}) {
  const runtime=origin+'/livephoto/media/core-runtime.mjs';
  const directory='livephoto-media-stage-v1';
  const command=['-hide_banner','-loglevel','error','-n','-i','input.bin','-c','copy','-movflags','+faststart','-f','mp4','output.bin'];
  await page.evaluate(async({runtime,directory,command})=>{
    const module=await import(runtime);
    window.storageRoot=await(await navigator.storage.getDirectory()).getDirectoryHandle(directory,{create:true});
    window.storageInput=await(await fetch('/core-fixture')).blob();
    window.storageRun=(args=command,cancel=()=>false,timeout=30000)=>module.executeCoreFfmpeg(JSON.stringify(args),storageInput,null,'output.bin',timeout,cancel);
    window.storageEntries=async()=>{const names=[];for await(const [name]of storageRoot.entries())names.push(name);return names.sort();};
  },{runtime,directory,command});
  if(part==='storage-publication'){
    const valid=await page.evaluate(async()=>{
      const module=location.origin+'/livephoto/output-storage.mjs';
      const source=new Blob([`import {createOutputJob} from ${JSON.stringify(module)}; onmessage=async({data})=>{try{const job=await createOutputJob();if(data==='write'){const file=await job.create('asset-0');for(let offset=0;offset<150003;){const bytes=new Int8Array(Math.min(65536,150003-offset));for(let i=0;i<bytes.length;i++)bytes[i]=(offset+i)%127;file.write(bytes,offset);offset+=bytes.length;}const output=await file.freeze();await job.finish(true);postMessage({success:true,id:job.id,output});}else{await job.finish(false);postMessage({success:true});}}catch(error){postMessage({success:false,error:String(error)});}}`],{type:'text/javascript'});
      const url=URL.createObjectURL(source);
      const run=action=>new Promise((resolve,reject)=>{
        const worker=new Worker(url,{type:'module'}),timer=setTimeout(()=>{worker.terminate();reject(Error('Disk publication timeout'));},30000);
        worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.success?resolve(data):reject(Error(data.error));};
        worker.onerror=()=>{clearTimeout(timer);worker.terminate();reject(Error('Disk publication Worker failed'));};worker.postMessage(action);
      });
      let release,acquired;
      try{
        const first=await run('write');
        if(!(first.output instanceof File)||first.output.size!==150003)throw Error('Output is not a disk File');
        const ready=new Promise(resolve=>{acquired=resolve;});
        const held=navigator.locks.request('livephoto-output-stage-v1/'+first.id,()=>{acquired();return new Promise(resolve=>{release=resolve;});});
        await ready;
        const root=await(await navigator.storage.getDirectory()).getDirectoryHandle('livephoto-output-stage-v1');
        const job=await root.getDirectoryHandle(first.id);
        const marker=await(await job.getFileHandle('lease.json')).createWritable();await marker.write(JSON.stringify({expires:0}));await marker.close();
        await run('clean');
        const bytes=new Uint8Array(await first.output.arrayBuffer());
        if(bytes.length!==150003||bytes.some((value,index)=>value!==index%127))throw Error('Active disk output was changed/removed');
        release();await held;release=null;
        await run('clean');
        let count=0;for await(const _ of root.entries())count++;
        return count===0;
      }finally{release?.();URL.revokeObjectURL(url);}
    });
    if(!valid)throw Error('Disk output lifetime, chunk IO or expired cleanup failed');
    return part;
  }
  if(part==='storage-cleanup'){
    // Another page owns a real job lock. Scavenging must not remove it.
    const other=await context.newPage();await other.goto(origin+'/livephoto/test');
    const active=await other.evaluate(async directory=>{
      const id=crypto.randomUUID();
      window.hold=navigator.locks.request(directory+'/'+id,async()=>{
        const root=await(await navigator.storage.getDirectory()).getDirectoryHandle(directory,{create:true});
        await root.getDirectoryHandle(id,{create:true});
        await new Promise(resolve=>{window.release=resolve;});
      });
      return id;
    },directory);
    await other.waitForFunction(()=>typeof release==='function');
    try{
      const proof=await page.evaluate(async active=>{
        const abandoned=crypto.randomUUID();await storageRoot.getDirectoryHandle(abandoned,{create:true});
        await storageRoot.getDirectoryHandle('unrelated-user-entry',{create:true});
        const output=await storageRun();
        if(output.code!==0||output.ioFailed||!output.chunks?.length||output.chunks.some(c=>c.length>65536))throw Error(JSON.stringify(output));
        const names=await storageEntries();
        return names.length===2&&names.includes(active)&&names.includes('unrelated-user-entry');
      },active);
      if(!proof)throw Error('Disk success/stale/other-page ownership cleanup failed');
    }finally{await other.evaluate(async()=>{release();await hold;});await other.close();}
  }else if(part==='storage-interruption'){
    let release;
    const blocked=new Promise(resolve=>{release=resolve;});
    await context.route('**/media/vendor/core/ffmpeg-core.wasm',async route=>{await blocked;try{await route.continue();}catch{}});
    try{
      await page.evaluate(()=>{window.stopStorage=false;window.pendingStorage=storageRun(undefined,()=>stopStorage);});
      await page.waitForFunction(async()=> (await storageEntries()).length===1);
      const cancelled=await page.evaluate(async()=>{stopStorage=true;const result=await pendingStorage;return result.cancelled&&!result.chunks&&(await storageEntries()).length===0;});
      if(!cancelled)throw Error('Cancellation left disk files or output');
      await page.evaluate(({runtime,command})=>{
        const script=new Blob([`import {executeCoreFfmpeg} from ${JSON.stringify(runtime)}; onmessage=({data})=>executeCoreFfmpeg(${JSON.stringify(JSON.stringify(command))},data,null,'output.bin',30000);`],{type:'text/javascript'});
        window.storageWorkerUrl=URL.createObjectURL(script);
        window.storageWorker=new Worker(storageWorkerUrl,{type:'module'});storageWorker.postMessage(storageInput);
      },{runtime,command});
      await page.waitForFunction(async()=> (await storageEntries()).length===1);
      await page.evaluate(()=>{storageWorker.terminate();URL.revokeObjectURL(storageWorkerUrl);});
      await page.waitForFunction(async directory=>(await navigator.locks.query()).held.every(lock=>!lock.name.startsWith(directory+'/')),directory);
    }finally{release();await context.unroute('**/media/vendor/core/ffmpeg-core.wasm');}
    if(!await page.evaluate(async()=>{const r=await storageRun();return r.code===0&&!r.ioFailed&&r.chunks?.length>0&&(await storageEntries()).length===0;}))
      throw Error('Abandoned Worker job was not recovered');
  }else if(part==='storage-limit'){
    if(!await page.evaluate(async()=>{
      const r=await storageRun(['-hide_banner','-loglevel','error','-stream_loop','-1','-i','input.bin',
        '-map','0:v:0','-c','copy','-f','mpegts','output.bin'],()=>false,120000);
      return r.outputLimited&&!r.chunks&&!r.timedOut&&(await storageEntries()).length===0;
    }))throw Error('Disk output budget did not stop/clean oversized output');
  }else if(part==='storage-failure'){
    const malformed=await page.evaluate(async()=>{const r=await storageRun(['-invalid-option','output.bin']);return r.code!==0&&!r.chunks&&(await storageEntries()).length===0;});
    if(!malformed)throw Error('FFmpeg failure published output or left files');
    // Force a real Worker storage failure, without filling the runner disk.
    await context.route('**/media/disk-worker.mjs',async route=>{
      const response=await route.fetch();
      await route.fulfill({response,body:`FileSystemFileHandle.prototype.createSyncAccessHandle=async()=>{throw new DOMException('Synthetic quota exhaustion','QuotaExceededError');};\n`+await response.text()});
    });
    try{
      if(!await page.evaluate(async()=>{const r=await storageRun();return r.ioFailed&&!r.chunks&&(await storageEntries()).length===0;}))
        throw Error('Storage failure published output or left files');
    }finally{await context.unroute('**/media/disk-worker.mjs');}
    const timedOut=await page.evaluate(async()=>{const r=await storageRun(undefined,()=>false,1);return r.timedOut&&!r.chunks&&(await storageEntries()).length===0;});
    if(!timedOut)throw Error('Timeout left output or disk files');
  }else throw Error('Unknown disk lifecycle case');
  return part;
}
