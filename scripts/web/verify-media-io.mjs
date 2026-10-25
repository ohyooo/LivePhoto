// CI-only guard for the real shared FFmpeg wrapper, in both browser contexts.
export async function guardMediaInput(input) {
  const post=Worker.prototype.postMessage;
  const stats={mounts:0,wholeReads:0,inputWrites:0};
  const read=input.arrayBuffer;
  input.arrayBuffer=()=>{stats.wholeReads++;throw Error('Whole media input read');};
  Worker.prototype.postMessage=function(message,...args){
    if(message.type==='WRITE_FILE'&&['input.bin','/input/input.bin'].includes(message.data.path)){
      stats.inputWrites++;throw Error('Media input copied to MEMFS');
    }
    if(message.type==='MOUNT'){
      const {fsType,options,mountPoint}=message.data;
      if(fsType!=='WORKERFS'||mountPoint!=='/input'||options.blobs?.length!==1||
        options.blobs[0].name!=='input.bin'||!(options.blobs[0].data instanceof Blob))throw Error('Invalid media mount');
      if(options.blobs[0].data===input)stats.mounts++;
    }
    return post.call(this,message,...args);
  };
  return {stats,restore(){input.arrayBuffer=read;Worker.prototype.postMessage=post;}};
}
