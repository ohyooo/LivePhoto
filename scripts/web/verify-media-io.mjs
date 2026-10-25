// CI-only guard for the real shared FFmpeg wrapper, in both browser contexts.
export async function guardMediaInput(input) {
  const post=Worker.prototype.postMessage;
  const NativeBlob=globalThis.Blob;
  const stats={mounts:0,wholeReads:0,inputWrites:0,outputParts:0};
  globalThis.Blob=class extends NativeBlob {
    constructor(parts=[],options){
      for(const part of parts)if(ArrayBuffer.isView(part)||part instanceof ArrayBuffer){
        if(part.byteLength>65536)throw Error('Whole output buffer passed to Blob');
        stats.outputParts++;
      }
      super(parts,options);
    }
    static [Symbol.hasInstance](value){return value instanceof NativeBlob;}
  };
  const read=input.arrayBuffer;
  input.arrayBuffer=()=>{stats.wholeReads++;throw Error('Whole media input read');};
  Worker.prototype.postMessage=function(message,...args){
    if(message.type==='WRITE_FILE'&&['input.bin','/input/input.bin'].includes(message.data.path)){
      stats.inputWrites++;throw Error('Media input copied to MEMFS');
    }
    if(message.type==='execute'){
      if(!(message.input instanceof Blob)||!(/^[0-9a-f-]{36}$/).test(message.stage)||
        (message.previousOutput!=null&&!(message.previousOutput instanceof Blob)))throw Error('Invalid disk media job');
      if(message.input===input)stats.mounts++;
      this.addEventListener('message',({data})=>{
        if(data.type==='chunk'&&(!(data.bytes instanceof Int8Array)||data.bytes.length>65536))
          throw Error('Unbounded media output transfer');
      });
    }
    return post.call(this,message,...args);
  };
  return {stats,restore(){input.arrayBuffer=read;Worker.prototype.postMessage=post;globalThis.Blob=NativeBlob;}};
}
