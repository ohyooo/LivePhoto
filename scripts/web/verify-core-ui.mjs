import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {composeDriver} from './ui-interaction.mjs';

/** Real Compose/Wasm UI -> typed adapter -> Core -> Worker. JS Core is used only to audit downloads. */
export async function verifyCoreUi({browser,origin,coreSource,largeSource,monoSource,imagePath,directory,probe,viewport:onlyViewport}){
  const checks=[],outputs=[];
  const original=probe(coreSource),sourceHash=createHash('sha256').update(await readFile(coreSource)).digest('hex');
  const check=(name,valid)=>{if(!valid)throw Error(name);checks.push(name);};
  for(const [size,viewport] of [['mobile',{width:390,height:844}],['wide',{width:1280,height:800}]]){
    if(size!==onlyViewport)continue;
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
    const status=pattern=>page.locator('#player-edit-status').filter({hasText:pattern}).waitFor({state:'attached'});
    const choose=async path=>{
      const previous=await page.evaluate(()=>document.querySelector('video[aria-label="视频预览"]')?.currentSrc??'');
      await driver.choose(/^(?:重新)?选择视频$/,path);
      await page.waitForFunction(previous=>{const v=document.querySelector('video[aria-label="视频预览"]');return v?.readyState>=2&&v.currentSrc!==previous;},previous);
    };
    const seek=async(time=.4)=>{
      await page.getByLabel('视频预览',{exact:true}).evaluate((video,time)=>{video.pause();video.currentTime=time;},time);
      await page.waitForFunction(time=>{const v=document.querySelector('video[aria-label="视频预览"]');return v&&!v.seeking&&Math.abs(v.currentTime-time)<.01;},time);
      await page.waitForTimeout(120);
    };
    const download=async action=>{
      await driver.selectProtocol('Google Motion Photo V2');
      const pending=page.waitForEvent('download');pending.catch(()=>{});
      await driver.click('生成动态照片');
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
      const firstVideo=after.packets.find(p=>p.stream_index===after.streams.find(s=>s.codec_type==='video').index);
      const sourcePacket=original.packets.find(p=>p.stream_index===original.streams.find(s=>s.codec_type==='video').index&&p.data_hash===firstVideo.data_hash);
      return {audited,origin:sourcePacket?Number(sourcePacket.pts_time)-Number(firstVideo.pts_time):null,hash:createHash('sha256').update(bytes).digest('hex')};
    };
    try{
      const response=await page.goto(origin+'/livephoto/');
      if(!response?.ok())throw Error('Wasm UI index was not served: HTTP '+response?.status());
      await page.waitForFunction(()=>document.documentElement.dataset.livephotoRuntime==='kotlin-wasm-compose');
      await driver.choose('选择图片',imagePath);await choose(coreSource);
      check(size+':wasm-runtime-and-engine-lazy',engines===0&&downloads===0);
      if(await text('Core 媒体工具（实验）').count()||await text('旧版兼容裁剪').count()||await text('旧版兼容转码').count())throw Error('Duplicate media paths are still exposed');
      await driver.click('检查视频');await status(/Core Probe：Partial.*MEDIA_DECODE_COMPLETED/);
      check(size+':core-probe-real-decode',engines>0&&downloads===0);
      await page.screenshot({path:`ci-output/web-ui-core-media-${size}.png`});
      for(const [action,button] of [['remux','重新封装'],['trim','应用裁剪'],['transcode','转为 H.264']]){
        if(action!=='remux')await choose(action==='trim'?largeSource:coreSource);
        if(action==='transcode'){
          await driver.click('裁剪范围');await driver.dragRange('end',.80);
          // Compose paints/disables the canvas control; the accessibility
          // element need not expose native HTML disabled/aria-disabled state.
          // Click the real hit region and prove it does not run or replace input.
          const beforeEngines=engines,beforeDownloads=downloads;
          const beforeSource=await page.getByLabel('视频预览',{exact:true}).evaluate(v=>v.currentSrc);
          await driver.click(button);await page.waitForTimeout(500);
          const afterSource=await page.getByLabel('视频预览',{exact:true}).evaluate(v=>v.currentSrc);
          if(engines!==beforeEngines||downloads!==beforeDownloads||afterSource!==beforeSource)throw Error('Default Core transcode silently ignores an open trim selection');
          await choose(coreSource);
          await seek();await driver.click('使用当前画面作为封面');await status(/Core ExtractFrame：ffmpeg-wasm.*实际/s);
        }
        if(action==='trim'){
          const beforeDownloads=downloads,beforeEngines=engines;
          await page.locator('#input-readiness').filter({hasText:/合计达到或超过 32 MiB.*裁剪/}).waitFor({state:'attached'});
          await driver.click('生成动态照片');await page.waitForTimeout(200);
          if(downloads!==beforeDownloads||engines!==beforeEngines)throw Error('Oversized original was processed as a final photo');
          await seek(1.2);await driver.click('使用当前画面作为封面');await status(/Core ExtractFrame：ffmpeg-wasm.*实际/s);
          await driver.click('裁剪范围');await driver.dragRange('start',.60);await driver.dragRange('end',.90);
        }
        await driver.click(button);
        await text(new RegExp('Core '+action[0].toUpperCase()+action.slice(1)+'：ffmpeg-wasm')).waitFor({state:'attached'});
        await text(new RegExp('名称：core-'+action+'\\.mp4')).waitFor({state:'attached'});
        // Generating replaces the screen's operation status with the download
        // result. Inspect the edit report before triggering that next action.
        if(action==='trim')await driver.details('视频处理');
        if(action==='trim')await text(/已验证视频时间平移/).waitFor({state:'attached'});
        if(action==='transcode'){
          const before=downloads;
          await driver.selectProtocol('Google Motion Photo V2');await driver.click('生成动态照片');
          await text(/视频已变化，请重新选择封面/).waitFor({state:'attached'});
          if(downloads!==before)throw Error('Core transcode reused an unverified cover timestamp');
          await driver.choose(/^(?:重新)?选择图片$/,imagePath);
        }
        const result=await download(action);
        if(action==='trim'){
          const key=result.audited.key;
          if(!key||!(result.origin>0)||Math.abs(Number(key.value)/Number(key.timescale)-(1.2-result.origin))>.05)throw Error('Core trim did not retain and rebase the selected cover');
          if(!outputs.at(-1)||outputs.at(-1).bytes>32*1024*1024)throw Error('Trimmed photo still exceeded the final budget');
        }
        check(size+':core-'+action+'-independent-packets',action!=='remux'||result.hash===sourceHash);
      }
      await choose(coreSource);await seek();
      const selectedImage=await page.locator('#image-name').textContent();
      await driver.click('封面位置');
      await driver.drag(page.locator('#cover-progress'),.1,.4/await page.getByLabel('视频预览',{exact:true}).evaluate(v=>v.duration));
      if(await page.locator('#image-name').textContent()!==selectedImage)throw Error('Read-only cover preview replaced the selected image');
      await driver.click('使用当前画面作为封面');await status(/Core ExtractFrame：ffmpeg-wasm.*实际/s);
      const frame=await download('frame');
      const key=frame.audited.key;
      check(size+':core-frame-actual-key-and-decode',frame.audited.width===64&&frame.audited.height===64&&key&&Math.abs(Number(key.value)/Number(key.timescale)-.4)<.1&&frame.hash===sourceHash);
      // Keep the .4 s cover, but exclude it from the actual retained video.
      // A failed edit must not replace the current input or silently drop the cover.
      const beforeOutside=downloads;
      await driver.click('裁剪范围');await driver.dragRange('end',.10);
      await driver.click('应用裁剪');
      await page.locator('#player-edit-status').filter({hasText:/KEY_PHOTO_OUTSIDE_TRIM/}).waitFor({state:'attached'});
      if(downloads!==beforeOutside||await text(/名称：core-input\.mp4/).count()!==1)throw Error('Out-of-range Core cover replaced input');
      await choose(monoSource);const before=downloads;
      await driver.click('裁剪范围');
      await driver.dragRange('end',.80);
      await driver.click('应用裁剪');
      await page.locator('#player-edit-status').filter({hasText:/CAPABILITY_UNSUPPORTED/}).waitFor({state:'attached'});
      if(downloads!==before||await text(/名称：core-mono\.mp4/).count()!==1)throw Error('Default Core trim automatically fell back');
      // Refusal keeps the uncommitted range; explicitly discard it before a separate transcode.
      await driver.click('重置选段');
      await driver.click('裁剪范围');
      await driver.click('转为 H.264');
      await status(/转码失败.*CAPABILITY_UNSUPPORTED/s);
      if(downloads!==before||await text(/名称：core-mono\.mp4/).count()!==1)throw Error('Default Core transcode automatically fell back');
      await driver.click('重新封装');await status(/封装失败.*CAPABILITY_UNSUPPORTED/s);
      check(size+':core-refusal-keeps-input',downloads===before&&await text(/名称：core-mono\.mp4/).count()===1);
      await choose(coreSource);
      // The native video can become ready before Compose's accessibility mirror updates.
      await page.locator('#player-edit-status').waitFor({state:'detached',timeout:5000});
      check(size+':core-reselection-clears-status',await page.locator('#player-edit-status').count()===0);
      let release;
      holdEngine=new Promise(resolve=>{release=resolve;});
      try{
        await driver.click('转为 H.264');await driver.click('取消媒体处理');
        await page.locator('#player-edit-status').filter({hasText:/已取消|CANCELLED/}).waitFor({state:'attached'});
      }finally{release();holdEngine=null;}
      check(size+':core-cancellation-keeps-input',downloads===before&&await text(/名称：core-input\.mp4/).count()===1);
    }catch(error){await page.screenshot({path:`ci-output/web-ui-core-media-${size}-failure.png`});throw error;}
    finally{await context.close();}
  }
  if(checks.length!==9||new Set(checks).size!==9||outputs.length!==4)throw Error('Incomplete Wasm Core UI proof');
  return {success:true,tests:9,failures:0,checks,outputs,scope:'shared-compose-wasm-typed-core-media-real-pointer-native-packet-audit'};
}
