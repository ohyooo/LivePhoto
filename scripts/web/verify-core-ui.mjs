import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {composeDriver} from './ui-interaction.mjs';

/** Real Compose/Wasm UI -> typed adapter -> Core -> Worker. JS Core is used only to audit downloads. */
export async function verifyCoreUi({browser,origin,coreSource,monoSource,imagePath,directory,probe}){
  const checks=[],outputs=[];
  const original=probe(coreSource),sourceHash=createHash('sha256').update(await readFile(coreSource)).digest('hex');
  const check=(name,valid)=>{if(!valid)throw Error(name);checks.push(name);};
  for(const [size,viewport] of [['mobile',{width:390,height:844}],['wide',{width:1280,height:800}]]){
    const context=await browser.newContext({viewport,acceptDownloads:true,serviceWorkers:'block'});
    const page=await context.newPage();
    let engines=0,downloads=0,holdEngine=null;
    page.on('request',request=>{if(request.url().endsWith('/media/vendor/core/ffmpeg-core.wasm'))engines++;});
    page.on('download',()=>downloads++);
    await context.route('**/*',async route=>{
      if(holdEngine&&route.request().url().endsWith('/media/vendor/core/ffmpeg-core.wasm'))await holdEngine;
      try{await(route.request().url().startsWith(origin+'/')?route.continue():route.abort());}catch{}
    });
    page.setDefaultTimeout(180000);
    const driver=composeDriver(page,viewport);
    const text=pattern=>page.getByText(pattern).or(page.getByLabel(pattern)).first();
    const status=pattern=>page.locator('#core-media-status').filter({hasText:pattern}).waitFor({state:'attached'});
    const choose=async path=>{
      await driver.choose(/^(?:重新)?选择视频$/,path);
      await page.waitForFunction(()=>{const v=document.querySelector('video[aria-label="视频预览"]');return v?.readyState>=2;});
      await driver.click('Core 媒体工具（实验）');
    };
    const seek=async()=>{
      await page.getByLabel('视频预览',{exact:true}).evaluate(video=>{video.pause();video.currentTime=.4;});
      await page.waitForFunction(()=>{const v=document.querySelector('video[aria-label="视频预览"]');return v&&!v.seeking&&Math.abs(v.currentTime-.4)<.01;});
      await page.waitForTimeout(120);
    };
    const download=async action=>{
      await driver.click(/Google Motion Photo V2/);
      const pending=page.waitForEvent('download');pending.catch(()=>{});
      await driver.click('生成并保存 Live Photo');
      const file=await pending;
      const saved=join(directory,`wasm-ui-${size}-${action}.jpg`);await file.saveAs(saved);
      const carrier=await readFile(saved);
      const audited=await page.evaluate(async bytes=>{
        const api=await import('/api/livephoto.mjs');
        const photo=new Blob([new Uint8Array(bytes)]),video=await api.extractVideo(photo),key=await api.getKeyPhotoPosition(photo);
        const validation=await api.validateLivePhoto(photo),split=await api.splitLivePhoto(photo);
        const image=split.assets.find(asset=>asset.role==='PrimaryImage');
        let width=0,height=0;
        if(image){const bitmap=await createImageBitmap(image.blob);width=bitmap.width;height=bitmap.height;bitmap.close();}
        if(!video.success||!key.success||!validation.success||validation.verdict==='Invalid'||!split.success)throw Error('Wasm UI output audit failed');
        return {video:Array.from(new Uint8Array(await video.blob.arrayBuffer())),key:key.report.position,width,height};
      },Array.from(carrier));
      const bytes=new Uint8Array(audited.video),path=join(directory,`wasm-ui-${size}-${action}.mp4`);
      await writeFile(path,bytes);const after=probe(path);
      if(after.streams.length!==2)throw Error('Wasm UI changed track count');
      for(const kind of action==='transcode'?['audio']:['video','audio']){
        const a=original.streams.find(s=>s.codec_type===kind),b=after.streams.find(s=>s.codec_type===kind);
        if(!a||!b||a.codec_name!==b.codec_name)throw Error('Wasm UI changed copied codec');
        const from=original.packets.filter(p=>p.stream_index===a.index).map(p=>p.data_hash),to=after.packets.filter(p=>p.stream_index===b.index).map(p=>p.data_hash);
        if(!to.length||!from.some((_,i)=>to.every((hash,j)=>hash&&hash===from[i+j]))||(action!=='trim'&&from.length!==to.length))throw Error('Wasm UI independent packet proof: '+kind);
      }
      if(action==='transcode'){
        const a=original.streams.find(s=>s.codec_type==='video'),b=after.streams.find(s=>s.codec_type==='video');
        const hashes=new Set(original.packets.filter(p=>p.stream_index===a.index).map(p=>p.data_hash));
        if(b.codec_name!=='h264'||!after.packets.some(p=>p.stream_index===b.index&&!hashes.has(p.data_hash)))throw Error('Wasm UI transcode did not change video');
      }
      outputs.push({viewport:size,action,bytes:carrier.length,sha256:createHash('sha256').update(carrier).digest('hex'),videoSha256:createHash('sha256').update(bytes).digest('hex')});
      return {audited,hash:createHash('sha256').update(bytes).digest('hex')};
    };
    try{
      const response=await page.goto(origin+'/livephoto/');
      if(!response?.ok())throw Error('Wasm UI index was not served: HTTP '+response?.status());
      await page.waitForFunction(()=>document.documentElement.dataset.livephotoRuntime==='kotlin-wasm-compose');
      await driver.choose('选择图片',imagePath);await choose(coreSource);
      check(size+':wasm-runtime-and-engine-lazy',engines===0&&downloads===0);
      await driver.click('Core 检查整段（含解码）');await status(/Core Probe：Partial.*MEDIA_DECODE_COMPLETED/);
      check(size+':core-probe-real-decode',engines>0&&downloads===0);
      await page.screenshot({path:`ci-output/web-ui-core-media-${size}.png`});
      for(const [action,button] of [['remux','Core 封装整段并使用（不编码）'],['trim','Core 裁剪并使用（音视频 copy）'],['transcode','Core 转码整段并使用（音频 copy）']]){
        if(action!=='remux')await choose(coreSource);
        if(action==='trim'){
          await driver.click('裁剪视频');await driver.dragRange('start',.20);await driver.dragRange('end',.80);
        }
        await driver.click(button);
        await text(new RegExp('Core '+action[0].toUpperCase()+action.slice(1)+'：ffmpeg-wasm')).waitFor({state:'attached'});
        await text(new RegExp('名称：core-'+action+'\\.mp4')).waitFor({state:'attached'});
        const result=await download(action);
        check(size+':core-'+action+'-independent-packets',action!=='remux'||result.hash===sourceHash);
      }
      await choose(coreSource);await seek();
      await driver.click('Core 抽取当前画面为封面');await status(/Core ExtractFrame：ffmpeg-wasm.*实际/s);
      const frame=await download('frame');
      const key=frame.audited.key;
      check(size+':core-frame-actual-key-and-decode',frame.audited.width===64&&frame.audited.height===64&&key&&Math.abs(Number(key.value)/Number(key.timescale)-.4)<.1&&frame.hash===sourceHash);
      await choose(monoSource);const before=downloads;
      await driver.click('Core 封装整段并使用（不编码）');await status(/CAPABILITY_UNSUPPORTED/);
      check(size+':core-refusal-keeps-input',downloads===before&&await text(/名称：core-mono\.mp4/).count()===1);
      await choose(coreSource);
      check(size+':core-reselection-clears-status',await page.locator('#core-media-status').count()===0);
      let release;
      holdEngine=new Promise(resolve=>{release=resolve;});
      try{
        await driver.click('Core 检查整段（含解码）');await driver.click('取消 Core 媒体处理');await status(/已取消|CANCELLED/);
      }finally{release();holdEngine=null;}
      check(size+':core-cancellation-keeps-input',downloads===before&&await text(/名称：core-input\.mp4/).count()===1);
    }catch(error){await page.screenshot({path:`ci-output/web-ui-core-media-${size}-failure.png`});throw error;}
    finally{await context.close();}
  }
  if(checks.length!==18||new Set(checks).size!==18||outputs.length!==8)throw Error('Incomplete Wasm Core UI proof');
  return {success:true,tests:18,failures:0,checks,outputs,scope:'shared-compose-wasm-typed-core-media-real-pointer-native-packet-audit'};
}
