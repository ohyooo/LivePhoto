/** Browser media surface only. All player controls/layout are Kotlin commonMain. */
function forwardWheel(element, scroll) {
  const wheel=event=>{if(!scroll||event.ctrlKey)return;event.preventDefault();event.stopPropagation();scroll(event.deltaY*(event.deltaMode===1?16:event.deltaMode===2?innerHeight:1));};
  element.addEventListener('wheel',wheel,{passive:false});return ()=>element.removeEventListener('wheel',wheel);
}
export function createPlayer(file, changed, scroll) {
  const video=document.createElement('video'),url=URL.createObjectURL(file);
  video.controls=false;video.disablePictureInPicture=true;video.disableRemotePlayback=true;
  video.playsInline=true;video.preload='metadata';video.setAttribute('aria-label','视频预览');
  video.style.cssText='display:block;width:100%;height:100%;object-fit:contain;border-radius:11px;background:var(--livephoto-media-background,#f5f7fb)';
  let frameTime=null,callback=null,disposed=false,lastVolume=1;
  const publish=()=>{if(!disposed)changed({duration:Number.isFinite(video.duration)?video.duration:0,current:video.currentTime,
    // Seeking briefly drops decoded-picture readiness. Keep the timeline
    // interactive or the shared drag gesture gets cancelled after its first move.
    seeking:video.seeking||video.readyState<2,ready:Number.isFinite(video.duration)&&video.duration>0&&video.videoWidth>0&&!video.error,width:video.videoWidth,height:video.videoHeight,
    paused:video.paused,volume:video.volume,muted:video.muted,rate:video.playbackRate,fullscreen:!!document.fullscreenElement,
    error:video.error?'浏览器不能播放此视频，不能预览或截取；不会自动转码。':null});};
  const frame=(_,metadata)=>{frameTime=metadata.mediaTime;publish();if(!disposed)callback=video.requestVideoFrameCallback(frame);};
  if(video.requestVideoFrameCallback)callback=video.requestVideoFrameCallback(frame);
  const update=()=>{if(video.seeking)frameTime=null;publish();};
  const events=['loadedmetadata','loadeddata','timeupdate','seeking','seeked','error','pause','play','ended','volumechange','ratechange'];
  for(const event of events)video.addEventListener(event,update);
  document.addEventListener('fullscreenchange',publish);
  const togglePlayback=()=>{if(video.paused)video.play().catch(publish);else video.pause();};video.onclick=togglePlayback;
  const releaseWheel=forwardWheel(video,scroll);video.src=url;
  return {element:video,video,togglePlayback,
    seek(seconds){if(!Number.isFinite(seconds)||!Number.isFinite(video.duration)||video.duration<=0)return;video.pause();frameTime=null;video.currentTime=Math.max(0,Math.min(seconds,video.duration));publish();},
    setVolume(value){if(!Number.isFinite(value))return;video.volume=Math.max(0,Math.min(1,value));if(video.volume>0)lastVolume=video.volume;video.muted=video.volume===0;},
    setMuted(value){video.muted=!!value;if(!value&&video.volume===0)video.volume=lastVolume;},
    setRate(value){if([.5,.75,1,1.25,1.5,2].includes(value))video.playbackRate=value;},
    setFullscreen(value){
      if(!value){if(document.fullscreenElement)document.exitFullscreen().then(publish,publish);else publish();return;}
      // Fullscreen includes Compose's canvas and media overlays together. The
      // shared Kotlin dialog renders the player, not a separate Web toolbar.
      document.documentElement.requestFullscreen?.().then(publish,publish);
    },
    async capture(){
      if(disposed||video.seeking||video.readyState<2||!video.videoWidth)throw Error('等待视频画面加载完成后再截取');
      video.pause();await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
      if(disposed||video.seeking)throw Error('画面发生变化，请重试');
      if(video.videoWidth*video.videoHeight>16777216)throw Error('截图上限为1600万像素');
      const canvas=document.createElement('canvas');canvas.width=video.videoWidth;canvas.height=video.videoHeight;
      const context=canvas.getContext('2d');if(!context)throw Error('Canvas不可用');context.drawImage(video,0,0);
      const time=frameTime??video.currentTime,blob=await new Promise(r=>canvas.toBlob(r,'image/jpeg',.95));canvas.width=canvas.height=0;
      if(!blob||disposed)throw Error('截图失败或播放器已关闭');return {blob,time};
    },
    dispose(){disposed=true;releaseWheel();document.removeEventListener('fullscreenchange',publish);video.onclick=null;
      if(callback!==null&&video.cancelVideoFrameCallback)video.cancelVideoFrameCallback(callback);
      for(const event of events)video.removeEventListener(event,update);video.pause();video.removeAttribute('src');video.load();URL.revokeObjectURL(url);}
  };
}

export function imagePreview(file,scroll){
  const element=document.createElement('img'),url=URL.createObjectURL(file);element.src=url;element.alt='已选封面预览';
  element.style.cssText='width:100%;height:100%;object-fit:contain;border-radius:12px';const release=forwardWheel(element,scroll);
  return {element,dispose(){release();URL.revokeObjectURL(url);}};
}

export function resultPreview(photo,movie,scroll){
  const element=document.createElement('div'),image=document.createElement('img'),video=document.createElement('video');
  const imageURL=URL.createObjectURL(photo),videoURL=URL.createObjectURL(movie);
  element.setAttribute('role','button');element.setAttribute('aria-label','按住预览生成结果');element.tabIndex=0;
  element.style.cssText='width:100%;height:100%;background:#111;border-radius:12px;overflow:hidden;touch-action:none';
  image.alt='上次生成的封面';image.src=imageURL;video.src=videoURL;image.style.cssText=video.style.cssText='width:100%;height:100%;object-fit:contain';
  video.hidden=true;video.playsInline=true;video.loop=true;video.preload='metadata';video.setAttribute('aria-label','生成结果动态预览');
  let holding=false,disposed=false;
  const stop=()=>{holding=false;video.pause();video.currentTime=0;video.hidden=true;image.hidden=false;};
  const start=()=>{if(disposed||holding)return;holding=true;video.hidden=false;image.hidden=true;video.play().then(()=>{if(!holding)video.pause();}).catch(()=>{stop();element.title='浏览器无法播放此视频';});};
  element.onpointerdown=event=>{event.preventDefault();element.setPointerCapture(event.pointerId);start();};
  element.onpointerup=element.onpointercancel=element.onlostpointercapture=stop;
  element.onkeydown=event=>{if([' ','Enter'].includes(event.key)){event.preventDefault();start();}};
  element.onkeyup=event=>{if([' ','Enter'].includes(event.key)){event.preventDefault();stop();}};
  element.onblur=stop;element.oncontextmenu=event=>event.preventDefault();
  const visibility=()=>{if(document.hidden)stop();};window.addEventListener('blur',stop);document.addEventListener('visibilitychange',visibility);
  const release=forwardWheel(element,scroll);element.append(image,video);
  return {element,dispose(){disposed=true;stop();release();window.removeEventListener('blur',stop);document.removeEventListener('visibilitychange',visibility);video.removeAttribute('src');video.load();URL.revokeObjectURL(imageURL);URL.revokeObjectURL(videoURL);}};
}
