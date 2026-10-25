/** Browser-private output files. Kotlin owns transaction state, budgets and verification. */
const directory='livephoto-output-stage-v1',limit=48*1024*1024;
const owned=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
async function remove(root,id){try{await root.removeEntry(id,{recursive:true});}catch(error){if(error.name!=='NotFoundError')throw error;}}
export async function createOutputJob(){
  if(!navigator.storage?.getDirectory||!navigator.locks)throw Error('Private disk output is unavailable');
  const root=await(await navigator.storage.getDirectory()).getDirectoryHandle(directory,{create:true});
  for await(const [id]of root.entries())if(owned.test(id))await navigator.locks.request(directory+'/'+id,{ifAvailable:true},async lock=>{
    if(!lock)return;
    const job=await root.getDirectoryHandle(id);
    try{
      const marker=await(await job.getFileHandle('lease.json')).getFile();
      if(marker.size<=1024){const lease=JSON.parse(await marker.text());if(Number.isSafeInteger(lease.expires)&&lease.expires>Date.now())return;}
    }catch(error){if(error.name!=='NotFoundError'&&error.name!=='SyntaxError')throw error;}
    await remove(root,id);
  });
  const id=crypto.randomUUID();let release,ready;
  const acquired=new Promise(resolve=>{ready=resolve;});
  const lifetime=navigator.locks.request(directory+'/'+id,()=>{ready();return new Promise(resolve=>{release=resolve;});});
  await acquired;
  let stage;
  const writers=[];
  try{stage=await root.getDirectoryHandle(id,{create:true});}catch(error){release();await lifetime;throw error;}
  return {
    id,
    async create(name){
      if(!/^asset-[0-9]+$/.test(name))throw Error('Unowned output file');
      const file=await stage.getFileHandle(name,{create:true});
      let access=await file.createSyncAccessHandle();
      const writer={
        write(bytes,offset){
          if(!(bytes instanceof Int8Array)||bytes.length>65536||!Number.isSafeInteger(offset)||offset<0||offset+bytes.length>limit)throw Error('Invalid output write');
          return access.write(bytes,{at:offset});
        },
        truncate(size){if(!Number.isSafeInteger(size)||size<0||size>limit)throw Error('Invalid output length');access.truncate(size);},
        flush(){access.flush();},
        close(){if(access){access.close();access=null;}},
        async freeze(){if(access){access.flush();writer.close();}return file.getFile();}
      };
      writers.push(writer);return writer;
    },
    async finish(keep){
      let published=false;
      try{
      let failure;
      for(const writer of writers)try{writer.close();}catch(error){failure=error;}
      if(failure)throw failure;
      if(keep){
        // Downloads can outlive the page. Retain committed files for 24h;
        // the owner page separately locks live previews beyond that interval.
        const marker=await(await stage.getFileHandle('lease.json',{create:true})).createSyncAccessHandle();
        try{const bytes=new TextEncoder().encode(JSON.stringify({expires:Date.now()+24*60*60*1000}));marker.write(bytes,{at:0});marker.flush();}finally{marker.close();}
        published=true;
      }
      }finally{release();await lifetime;if(!published)await remove(root,id);}
    }
  };
}
