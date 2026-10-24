/** Private MEMFS execution bridge: no protocol parsing, publication, paths or shell. */
const base=new URL('./',import.meta.url);
export async function executeCoreFfmpeg(argsJson,input,previousOutput,outputName,timeoutMillis,isCancelled=()=>false) {
  const args=JSON.parse(argsJson),limit=48*1024*1024;
  if(!Array.isArray(args)||args.length>256||!args.every(a=>typeof a==='string'&&a.length<=4096)||
    !['output.bin','frame.jpg'].includes(outputName)||input.byteLength>256*1024*1024||
    (previousOutput?.byteLength??0)>limit)throw Error('Invalid private memory job');
  let engine,timer,poll,stopped=false;
  const result={code:null,output:'',outputLimited:false,cancelled:false,timedOut:false,ioFailed:false,bytes:null};
  let rejectStop;
  const stop=new Promise((_,reject)=>{rejectStop=reject;});
  const terminate=flag=>{if(stopped)return;stopped=true;result[flag]=true;engine?.terminate();rejectStop(Error(flag));};
  const work=(async()=>{
    if(isCancelled()){terminate('cancelled');return;}
    const {FFmpeg}=await import('./vendor/ffmpeg/index.js');
    if(stopped)return;
    engine=new FFmpeg();
    engine.on('log',({message})=>{
      const line=String(message)+'\n';
      if(result.output.length+line.length>65536){terminate('outputLimited');return;}
      result.output+=line;
    });
    await engine.load({coreURL:new URL('vendor/core/ffmpeg-core.js',base).href,wasmURL:new URL('vendor/core/ffmpeg-core.wasm',base).href});
    await engine.writeFile('input.bin',new Uint8Array(input.buffer,input.byteOffset,input.byteLength));
    if(previousOutput)await engine.writeFile(outputName,new Uint8Array(previousOutput.buffer,previousOutput.byteOffset,previousOutput.byteLength));
    // The wrapper prepends -y, which conflicts with Core's external-process -n.
    // Emulate -n against this fresh, private MEMFS instead; never permit input
    // overwrite or replacement of a previous verified output.
    if(!['-',outputName].includes(args.at(-1))||
      (args.includes('-n')&&previousOutput&&args.at(-1)===outputName))throw Error('Refusing private output overwrite');
    result.code=await engine.exec(args.filter(arg=>arg!=='-n'),timeoutMillis);
    if(result.code===0&&args.at(-1)===outputName){
      const bytes=await engine.readFile(outputName);
      if(bytes.byteLength>limit){result.outputLimited=true;return;}
      result.bytes=new Int8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength);
    }
  })();
  timer=setTimeout(()=>terminate('timedOut'),Math.min(120000,Math.max(1,timeoutMillis)));
  poll=setInterval(()=>{if(isCancelled())terminate('cancelled');},25);
  try{await Promise.race([work,stop]);}catch{if(!stopped)result.ioFailed=true;}
  finally{clearTimeout(timer);clearInterval(poll);engine?.terminate();}
  if(result.cancelled||result.timedOut||result.outputLimited||result.ioFailed)result.bytes=null;
  return result;
}
