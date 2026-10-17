/** Browser decoding/screenshot adapter only. No Live Photo protocol logic. */
export function createPlayer(file, changed) {
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
      if (callback!==null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(callback);
      for (const event of ['loadedmetadata','loadeddata','timeupdate','seeking','seeked','error','pause','play']) video.removeEventListener(event,publish);
      video.pause(); video.removeAttribute('src'); video.load(); URL.revokeObjectURL(url);
    },
  };
}

export function imagePreview(file) {
  const element=document.createElement('img'), url=URL.createObjectURL(file);
  element.src=url; element.alt='已选封面预览';
  element.style.cssText='width:100%;height:100%;object-fit:contain;border-radius:12px';
  return {element,dispose:()=>URL.revokeObjectURL(url)};
}
