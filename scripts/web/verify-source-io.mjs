/** CI-only guard: public protocol calls must slice inputs; independent audits may read results whole. */
export function guardProtocolInputs(module) {
  const io={slices:0,maxSlice:0,wholeReads:0};
  const api=Object.fromEntries(Object.entries(module).map(([name,operation])=>[name,async(...args)=>{
    const restore=[];
    for(const blob of new Set(args.filter(value=>value instanceof Blob))){
      const slice=blob.slice;
      for(const [key,value] of [
        ['arrayBuffer',()=>{io.wholeReads++;throw Error('Protocol input was read whole');}],
        ['slice',function(start=0,end=this.size,...rest){
          const length=end-start;io.slices++;io.maxSlice=Math.max(io.maxSlice,length);
          if(length>65536)throw Error('Protocol slice exceeds 64 KiB');
          return slice.call(this,start,end,...rest);
        }],
      ]){
        const descriptor=Object.getOwnPropertyDescriptor(blob,key);
        Object.defineProperty(blob,key,{value,configurable:true,writable:true});
        restore.push(()=>descriptor?Object.defineProperty(blob,key,descriptor):delete blob[key]);
      }
    }
    try{return await operation(...args);}finally{restore.reverse().forEach(action=>action());}
  }]));
  return {api,io};
}
