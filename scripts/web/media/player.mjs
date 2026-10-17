/** Browser decoding/screenshot adapter only. No Live Photo protocol logic. */
function forwardWheel(element, scroll) {
  const wheel=event=>{
    if(!scroll||event.ctrlKey)return;
    event.preventDefault();event.stopPropagation();
    scroll(event.deltaY*(event.deltaMode===1?16:event.deltaMode===2?innerHeight:1));
  };
  element.addEventListener('wheel',wheel,{passive:false});
  return ()=>element.removeEventListener('wheel',wheel);
}
export function createPlayer(file, changed, scroll) {
  const video = document.createElement('video');
  const url = URL.createObjectURL(file);
  video.controls = true;
  video.playsInline = true;
  video.preload = 'metadata';
  video.setAttribute('aria-label', '视频预览');
  video.style.cssText = 'width:100%;height:100%;object-fit:contain;background:#111;border-radius:12px';
  let frameTime = null, callback = null, disposed = false;
  const publish = () => {
    if (!disposed) changed({duration:Number.isFinite(video.duration)?video.duration:0,
      current:video.currentTime, seeking:video.seeking, ready:video.readyState>=2 && video.videoWidth>0,
      error:video.error?'浏览器不能播放此视频，不能预览或截取；不会自动转码。':null});
  };
  const frame = (_, metadata) => {
    frameTime = metadata.mediaTime;
    publish();
    if (!disposed) callback = video.requestVideoFrameCallback(frame);
  };
  if (video.requestVideoFrameCallback) callback = video.requestVideoFrameCallback(frame);
  for (const event of ['loadedmetadata','loadeddata','timeupdate','seeking','seeked','error','pause','play']) video.addEventListener(event,publish);
  video.src = url;
  const releaseWheel=forwardWheel(video,scroll);
  return {
    element:video,
    seek(seconds) {
      if (!Number.isFinite(seconds) || seconds<0 || seconds>video.duration) return;
      video.pause(); frameTime = null; video.currentTime = seconds; publish();
    },
    async capture() {
      if (disposed || video.seeking || video.readyState<2 || !video.videoWidth) throw Error('等待视频画面加载完成后再截取');
      video.pause();
      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
      if (disposed || video.seeking) throw Error('画面发生变化，请重试');
      const canvas = document.createElement('canvas');
      // Bound screenshot memory independently of compressed file size.
      if (video.videoWidth*video.videoHeight>16777216) throw Error('截图上限为 1600 万像素');
      canvas.width=video.videoWidth; canvas.height=video.videoHeight;
      const context=canvas.getContext('2d');
      if (!context) throw Error('Canvas 不可用');
      context.drawImage(video,0,0);
      const time=frameTime??video.currentTime;
      const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',0.95));
      canvas.width=canvas.height=0;
      if (!blob || disposed) throw Error('截图失败或播放器已关闭');
      return {blob,time};
    },
    dispose() {
      disposed=true;
      releaseWheel();
      if (callback!==null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(callback);
      for (const event of ['loadedmetadata','loadeddata','timeupdate','seeking','seeked','error','pause','play']) video.removeEventListener(event,publish);
      video.pause(); video.removeAttribute('src'); video.load(); URL.revokeObjectURL(url);
    },
  };
}

export function imagePreview(file, scroll) {
  const element=document.createElement('img'), url=URL.createObjectURL(file);
  element.src=url; element.alt='已选封面预览';
  element.style.cssText='width:100%;height:100%;object-fit:contain;border-radius:12px';
  const releaseWheel=forwardWheel(element,scroll);
  return {element,dispose(){releaseWheel();URL.revokeObjectURL(url);}};
}

/** Display the verified result's composite JPEG and its unchanged input video. */
export function resultPreview(photo, movie, scroll) {
  const element=document.createElement('div'),image=document.createElement('img'),video=document.createElement('video');
  const imageURL=URL.createObjectURL(photo),videoURL=URL.createObjectURL(movie);
  element.setAttribute('role','button');element.setAttribute('aria-label','按住预览生成结果');element.tabIndex=0;
  element.style.cssText='width:100%;height:100%;background:#111;border-radius:12px;overflow:hidden;touch-action:none';
  image.alt='上次生成的封面';image.src=imageURL;video.src=videoURL;
  image.style.cssText=video.style.cssText='width:100%;height:100%;object-fit:contain';
  video.hidden=true;video.playsInline=true;video.loop=true;video.preload='metadata';
  video.setAttribute('aria-label','生成结果动态预览');
  let holding=false,disposed=false;
  const stop=()=>{holding=false;video.pause();video.currentTime=0;video.hidden=true;image.hidden=false;};
  const start=()=>{
    if(disposed||holding)return;
    holding=true;video.hidden=false;image.hidden=true;
    video.play().then(()=>{if(!holding)video.pause();}).catch(()=>{stop();element.title='浏览器无法播放此视频';});
  };
  element.onpointerdown=event=>{event.preventDefault();element.setPointerCapture(event.pointerId);start();};
  element.onpointerup=element.onpointercancel=element.onlostpointercapture=stop;
  element.onkeydown=event=>{if([' ','Enter'].includes(event.key)){event.preventDefault();start();}};
  element.onkeyup=event=>{if([' ','Enter'].includes(event.key)){event.preventDefault();stop();}};
  element.onblur=stop;element.oncontextmenu=event=>event.preventDefault();
  const visibility=()=>{if(document.hidden)stop();};
  window.addEventListener('blur',stop);document.addEventListener('visibilitychange',visibility);
  const releaseWheel=forwardWheel(element,scroll);
  element.append(image,video);
  return {element,dispose(){disposed=true;stop();releaseWheel();window.removeEventListener('blur',stop);document.removeEventListener('visibilitychange',visibility);video.removeAttribute('src');video.load();URL.revokeObjectURL(imageURL);URL.revokeObjectURL(videoURL);}};
}
