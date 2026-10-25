/** Dedicated Worker IO adapter. No media planning, parsing or publication. */
const limit=48*1024*1024;
onmessage=async({data:{args,input,previousOutput,outputName,timeoutMillis,stage}})=>{
  let handle,outputLimited=false,ioFailed=false,code=null;
  try {
    const {default:createFFmpegCore}=await import('./vendor/core/ffmpeg-core.js');
    const core=await createFFmpegCore();
    core.setLogger(({message})=>postMessage({type:'log',text:String(message)+'\n'}));
    core.setTimeout(timeoutMillis);
    const fs=core.FS;
    fs.mkdir('/input');
    fs.mount(fs.filesystems.WORKERFS,{blobs:[{name:'input.bin',data:input}]},'/input');
    if(!['-',outputName].includes(args.at(-1))||
      (previousOutput&&args.at(-1)===outputName))throw Error('Refusing private output overwrite');
    if(previousOutput){
      fs.mkdir('/previous');
      fs.mount(fs.filesystems.WORKERFS,{blobs:[{name:outputName,data:previousOutput}]},'/previous');
    }
    const writes=args.at(-1)===outputName;
    if(writes){
      if(!/^[0-9a-f-]{36}$/.test(stage))throw Error('Invalid private stage');
      const root=await(await navigator.storage.getDirectory()).getDirectoryHandle('livephoto-media-stage-v1');
      const folder=await root.getDirectoryHandle(stage);
      handle=await(await folder.getFileHandle('output',{create:true})).createSyncAccessHandle();
      const node=fs.create('/'+outputName);
      const originalGetattr=node.node_ops.getattr;
      const bounded=size=>{
        if(!Number.isSafeInteger(size)||size<0)throw new fs.ErrnoError(28);
        if(size>limit){outputLimited=true;throw new fs.ErrnoError(51);}
      };
      // Keep only the inode in MEMFS: data and random seeks use OPFS.
      node.node_ops={
        getattr(node){const stat=originalGetattr(node);stat.size=handle.getSize();stat.blocks=Math.ceil(stat.size/4096);return stat;},
        setattr(node,attr){
          if(attr.size!==undefined){bounded(attr.size);handle.truncate(attr.size);}
          if(attr.mode!==undefined)node.mode=attr.mode;
          if(attr.timestamp!==undefined)node.timestamp=attr.timestamp;
        }
      };
      node.stream_ops={
        read(stream,buffer,offset,length,position){return handle.read(buffer.subarray(offset,offset+length),{at:position});},
        write(stream,buffer,offset,length,position){
          bounded(position+length);
          let written=0;
          while(written<length){
            const count=handle.write(buffer.subarray(offset+written,offset+length),{at:position+written});
            if(!count)throw new fs.ErrnoError(29);
            written+=count;
          }
          return written;
        },
        llseek(stream,offset,whence){
          const position=offset+(whence===1?stream.position:whence===2?handle.getSize():0);
          bounded(position);return position;
        }
      };
    }
    // The upstream binding prepends -y. We own the only writable path and
    // refuse replacing a prior verified output above, including with -n.
    const command=args.filter(arg=>arg!=='-n').map(arg=>arg==='input.bin'?'/input/input.bin':
      arg===outputName&&previousOutput?'/previous/'+outputName:arg);
    if(writes)command.splice(-1,0,'-fs',String(limit+1));
    code=core.exec(...command);
    if(code===0&&handle&&!outputLimited){
      handle.flush();
      const size=handle.getSize();
      for(let offset=0;offset<size;){
        const bytes=new Int8Array(Math.min(65536,size-offset));
        let read=0;
        while(read<bytes.length){
          const count=handle.read(bytes.subarray(read),{at:offset+read});
          if(!count)throw Error('Incomplete private output');
          read+=count;
        }
        offset+=bytes.length;
        postMessage({type:'chunk',bytes},[bytes.buffer]);
      }
    }
  }catch(error){
    ioFailed=!outputLimited;
    postMessage({type:'log',text:'Private media execution failed ('+String(error.name||'Error')+').\n'});
  }finally{
    try{handle?.close();}catch{ioFailed=true;}
    postMessage({type:'done',code,ioFailed,outputLimited});
  }
};
