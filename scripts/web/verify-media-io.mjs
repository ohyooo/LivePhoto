// CI-only guard for the real shared FFmpeg wrapper, in both browser contexts.
export async function guardMediaInput(input) {
  const {FFmpeg}=await import('/livephoto/media/vendor/ffmpeg/index.js');
  const write=FFmpeg.prototype.writeFile,mount=FFmpeg.prototype.mount;
  const stats={mounts:0,wholeReads:0,inputWrites:0};
  const read=input.arrayBuffer;
  input.arrayBuffer=()=>{stats.wholeReads++;throw Error('Whole media input read');};
  FFmpeg.prototype.writeFile=function(path,...args){
    if(path==='input.bin'||path==='/input/input.bin'){stats.inputWrites++;throw Error('Media input copied to MEMFS');}
    return write.call(this,path,...args);
  };
  FFmpeg.prototype.mount=async function(type,options,path){
    if(type!=='WORKERFS'||path!=='/input'||options.blobs?.length!==1||
      options.blobs[0].name!=='input.bin'||!(options.blobs[0].data instanceof Blob))throw Error('Invalid media mount');
    const mounted=await mount.call(this,type,options,path);
    if(mounted&&options.blobs[0].data===input)stats.mounts++;
    return mounted;
  };
  return {stats,restore(){input.arrayBuffer=read;FFmpeg.prototype.writeFile=write;FFmpeg.prototype.mount=mount;}};
}
