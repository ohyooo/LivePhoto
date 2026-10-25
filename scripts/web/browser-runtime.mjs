import {chromium,firefox} from 'playwright';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {writeFile} from 'node:fs/promises';

// Safari uses Apple's installed browser and driver, not Playwright WebKit.
export async function launchBrowser(){
  const name=process.env.LIVEPHOTO_BROWSER||'chromium';
  if(name==='chromium'||name==='firefox')return ({chromium,firefox})[name].launch({headless:process.env.LIVEPHOTO_HEADLESS!=='false',
    ...(name==='firefox'?{firefoxUserPrefs:{'webgl.force-enabled':true}}:{})});
  if(name!=='safari'||process.platform!=='darwin')throw Error('Unsupported browser runner');
  const reserve=createServer();await new Promise(resolve=>reserve.listen(0,'127.0.0.1',resolve));
  const port=reserve.address().port;await new Promise(resolve=>reserve.close(resolve));
  const driver=spawn('/usr/bin/safaridriver',['--port',String(port)],{stdio:['ignore','pipe','pipe']});
  let log='',session,version;
  for(const stream of [driver.stdout,driver.stderr])stream.on('data',data=>{log=(log+data).slice(-65536);});
  const request=async(method,path,body)=>{
    const response=await fetch(`http://127.0.0.1:${port}${path}`,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(180000)});
    const value=(await response.json()).value;
    if(!response.ok)throw Error('Safari WebDriver: '+JSON.stringify(value));
    return value;
  };
  const close=async()=>{
    try{if(session)await request('DELETE','/session/'+session);}finally{driver.kill();await writeFile('ci-output/web-safaridriver.log',log);}
  };
  try{
    let ready=false;
    for(let i=0;i<100;i++){
      try{await request('GET','/status');ready=true;break;}catch{}
      if(driver.exitCode!==null)throw Error('Safari driver exited: '+log);
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    if(!ready)throw Error('Safari driver did not start');
    const value=await request('POST','/session',{capabilities:{alwaysMatch:{browserName:'safari'}}});
    session=value.sessionId;version=value.capabilities.browserVersion;
    await request('POST',`/session/${session}/timeouts`,{script:180000,pageLoad:180000});
    const evaluate=(fn,arg)=>request('POST',`/session/${session}/execute/async`,{
      script:`const done=arguments[arguments.length-1]; Promise.resolve().then(()=>(${fn.toString()})(arguments[0])).then(value=>done({success:true,value}),error=>done({success:false,error:String(error?.message||error)+'\\n'+String(error?.stack||'')}));`,args:[arg??null]
    }).then(result=>{if(!result.success)throw Error(result.error);return result.value;});
    const page={
      setDefaultTimeout(){},
      goto: url=>request('POST',`/session/${session}/url`,{url}),evaluate,
      keyboard:{press:key=>{if(key!=='Tab')throw Error('Unsupported Safari test key');return request('POST',`/session/${session}/actions`,{actions:[{type:'key',id:'keyboard',actions:[{type:'keyDown',value:'\uE004'},{type:'keyUp',value:'\uE004'}]}]});}},
      mouse:{click:(x,y)=>request('POST',`/session/${session}/actions`,{actions:[{type:'pointer',id:'mouse',parameters:{pointerType:'mouse'},actions:[
        {type:'pointerMove',duration:0,x:Math.round(x),y:Math.round(y),origin:'viewport'},{type:'pointerDown',button:0},{type:'pointerUp',button:0}]}]})},
      waitForFunction: async(fn,arg)=>{
        const end=Date.now()+180000;
        while(Date.now()<end){if(await evaluate(fn,arg))return;await new Promise(resolve=>setTimeout(resolve,100));}
        throw Error('Safari condition timed out');
      },
      screenshot:async({path})=>writeFile(path,Buffer.from(await request('GET',`/session/${session}/screenshot`),'base64'))
    };
    return {version:()=>version,close,newContext:async(options={})=>{
      if(options.viewport)await request('POST',`/session/${session}/window/rect`,options.viewport);
      return {newPage:async()=>page,close:async()=>{}};
    }};
  }catch(error){await close();throw error;}
}
