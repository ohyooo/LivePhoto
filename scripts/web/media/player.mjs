/** Browser decoding/screenshot adapter only. No Live Photo protocol logic. */
import {playerStyle} from './player-style.mjs';
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
  if(!document.getElementById('lp-player-style')) {
    const style=document.createElement('style');style.id='lp-player-style';style.textContent=playerStyle;document.head.append(style);
  }
  const element=document.createElement('div');element.className='lp-player';
  const card=document.createElement('div');card.className='lp-card';element.append(card);
  const stage=document.createElement('div');stage.className='lp-stage';card.append(stage);
  const video = document.createElement('video');
  const url = URL.createObjectURL(file);
  video.controls = false;
  video.disablePictureInPicture = true;
  video.disableRemotePlayback = true;
  video.playsInline = true;
  video.preload = 'metadata';
  video.setAttribute('aria-label', '视频预览');
  stage.append(video);
  const controls=document.createElement('div');controls.className='lp-controls';card.append(controls);
  const icon=path=>`<svg viewBox="0 0 24 24" aria-hidden="true">${path}</svg>`;
  const icons={play:icon('<path d="m8 5 11 7-11 7Z"/>'),pause:icon('<path d="M8 5v14M16 5v14"/>'),
    volume:icon('<path d="M11 4 5 9H2v6h3l6 5ZM15 8q5 4 0 8M18 5q8 7 0 14"/>'),
    muted:icon('<path d="M11 4 5 9H2v6h3l6 5ZM16 9l6 6M22 9l-6 6"/>'),
    expand:icon('<path d="M8 3H3v5M16 3h5v5M21 16v5h-5M8 21H3v-5"/>'),
    shrink:icon('<path d="M3 8h5V3M16 3v5h5M21 16h-5v5M8 21v-5H3"/>')};
  const button=(name,html)=>{const b=document.createElement('button');b.type='button';b.setAttribute('aria-label',name);b.title=name;b.innerHTML=html;controls.append(b);return b;};
  const play=button('播放',icons.play);
  const time=document.createElement('span');time.className='lp-time';controls.append(time);
  const progress=document.createElement('div');progress.className='lp-progress';progress.tabIndex=0;
  progress.setAttribute('role','slider');progress.setAttribute('aria-label','播放进度');progress.setAttribute('aria-valuemin','0');
  progress.innerHTML='<div class="lp-track"><div class="lp-fill"></div><div class="lp-thumb"></div></div>';
  controls.append(progress);
  const mute=button('静音',icons.volume);
  const volume=document.createElement('input');volume.className='lp-volume';volume.type='range';volume.min='0';volume.max='1';volume.step='.01';volume.value='1';volume.setAttribute('aria-label','音量');controls.append(volume);
  const speed=button('播放倍速','1.0x');speed.className='lp-speed';speed.setAttribute('aria-expanded','false');
  const fullscreen=button('全屏',icons.expand);
  const rates=document.createElement('div');rates.className='lp-rates';rates.hidden=true;rates.setAttribute('role','group');rates.setAttribute('aria-label','播放倍速选项');card.append(rates);
  for(const rate of [.5,.75,1,1.25,1.5,2]) {
    const b=document.createElement('button');b.type='button';b.textContent=Number.isInteger(rate)?rate.toFixed(1)+'x':rate+'x';b.setAttribute('aria-pressed',String(rate===1));
    b.onclick=()=>{video.playbackRate=rate;rates.hidden=true;speed.setAttribute('aria-expanded','false');layout();};rates.append(b);
  }
  let portrait=false;
  let frameTime = null, callback = null, disposed = false;
  const clock=seconds=>{const s=Math.max(0,Math.floor(Number.isFinite(seconds)?seconds:0));return (s>=3600?Math.floor(s/3600)+':':'')+String(Math.floor(s/60)%60).padStart(2,'0')+':'+String(s%60).padStart(2,'0');};
  const layout=()=>{
    if(disposed)return;
    const ratio=video.videoWidth>0?video.videoWidth/video.videoHeight:16/9;
    portrait=ratio<1;
    element.classList.toggle('lp-portrait',portrait);
    element.classList.toggle('lp-compact',element.clientWidth<480);
    progress.setAttribute('aria-orientation',portrait?'vertical':'horizontal');
    (portrait?stage:controls).insertBefore(progress,portrait?null:mute);
    const availableHeight=Math.max(1,element.clientHeight-84-(rates.hidden?0:70));
    const availableWidth=Math.max(1,element.clientWidth-24-(portrait?34:0));
    const width=Math.max(1,Math.min(availableWidth,portrait?720:1920,availableHeight*ratio,portrait?1200*ratio:Infinity));
    video.style.width=width+'px';video.style.height=width/ratio+'px';
    card.style.width=(width+24+(portrait?34:0))+'px';
  };
  const seek=seconds=>{
    if(!Number.isFinite(seconds)||!Number.isFinite(video.duration)||video.duration<=0)return;
    video.pause();frameTime=null;video.currentTime=Math.max(0,Math.min(seconds,video.duration));publish();
  };
  const toggle=()=>{if(video.paused)video.play().catch(()=>publish());else video.pause();};
  play.onclick=toggle;video.onclick=toggle;
  let lastVolume=1;
  mute.onclick=()=>{if(video.muted||video.volume===0){video.muted=false;if(video.volume===0)video.volume=lastVolume;}else{lastVolume=video.volume;video.muted=true;}};
  volume.oninput=()=>{video.volume=Number(volume.value);if(video.volume>0)lastVolume=video.volume;video.muted=video.volume===0;};
  speed.onclick=()=>{rates.hidden=!rates.hidden;speed.setAttribute('aria-expanded',String(!rates.hidden));layout();};
  fullscreen.onclick=async()=>{try{if(document.fullscreenElement===element)await document.exitFullscreen();else if(element.requestFullscreen)await element.requestFullscreen();}catch{fullscreen.title='浏览器不允许全屏';}};
  fullscreen.disabled=!document.fullscreenEnabled;
  let dragging=false;
  const scrub=event=>{
    const bounds=progress.querySelector('.lp-track').getBoundingClientRect();
    const fraction=portrait?(event.clientY-bounds.top)/bounds.height:(event.clientX-bounds.left)/bounds.width;
    if(Number.isFinite(fraction))seek(Math.max(0,Math.min(1,fraction))*video.duration);
  };
  progress.onpointerdown=event=>{if(!Number.isFinite(video.duration)||video.duration<=0)return;event.preventDefault();dragging=true;progress.focus();progress.setPointerCapture(event.pointerId);scrub(event);};
  progress.onpointermove=event=>{if(dragging)scrub(event);};
  progress.onpointerup=progress.onpointercancel=progress.onlostpointercapture=()=>{dragging=false;};
  progress.onkeydown=event=>{let next;switch(event.key){case 'ArrowRight':case 'ArrowDown':next=video.currentTime+1;break;case 'ArrowLeft':case 'ArrowUp':next=video.currentTime-1;break;case 'Home':next=0;break;case 'End':next=video.duration;break;default:return;}event.preventDefault();seek(next);};
  const publish = () => {
    if(disposed)return;
    const duration=Number.isFinite(video.duration)?video.duration:0;
    play.innerHTML=video.paused?icons.play:icons.pause;play.setAttribute('aria-label',video.paused?'播放':'暂停');
    play.disabled=video.readyState<2;
    mute.innerHTML=video.muted||video.volume===0?icons.muted:icons.volume;mute.setAttribute('aria-label',video.muted?'取消静音':'静音');volume.value=String(video.muted?0:video.volume);
    speed.textContent=Number.isInteger(video.playbackRate)?video.playbackRate.toFixed(1)+'x':video.playbackRate+'x';
    [...rates.children].forEach(b=>b.setAttribute('aria-pressed',String(parseFloat(b.textContent)===video.playbackRate)));
    time.textContent=clock(video.currentTime)+' / '+clock(duration);
    progress.style.setProperty('--p',(duration?Math.max(0,Math.min(1,video.currentTime/duration))*100:0)+'%');
    progress.setAttribute('aria-valuemax',String(duration));progress.setAttribute('aria-valuenow',String(video.currentTime));progress.setAttribute('aria-valuetext',time.textContent);
    progress.setAttribute('aria-disabled',String(duration<=0));
    if(video.seeking)frameTime=null;
    if (!disposed) changed({duration:Number.isFinite(video.duration)?video.duration:0,
      current:video.currentTime, seeking:video.seeking, ready:video.readyState>=2 && video.videoWidth>0,
      width:video.videoWidth,height:video.videoHeight,
      error:video.error?'浏览器不能播放此视频，不能预览或截取；不会自动转码。':null});
  };
  const frame = (_, metadata) => {
    frameTime = metadata.mediaTime;
    publish();
    if (!disposed) callback = video.requestVideoFrameCallback(frame);
  };
  if (video.requestVideoFrameCallback) callback = video.requestVideoFrameCallback(frame);
  const events=['loadedmetadata','loadeddata','timeupdate','seeking','seeked','error','pause','play','ended','volumechange','ratechange'];
  const update=()=>{publish();layout();};
  for (const event of events) video.addEventListener(event,update);
  const resize=new ResizeObserver(layout);resize.observe(element);
  const fullscreenChanged=()=>{fullscreen.innerHTML=document.fullscreenElement===element?icons.shrink:icons.expand;fullscreen.setAttribute('aria-label',document.fullscreenElement===element?'退出全屏':'全屏');layout();};
  document.addEventListener('fullscreenchange',fullscreenChanged);
  video.src = url;
  const releaseWheel=forwardWheel(element,scroll);
  return {
    element, video, seek,
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
      resize.disconnect();document.removeEventListener('fullscreenchange',fullscreenChanged);
      releaseWheel();
      if (callback!==null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(callback);
      for (const event of events) video.removeEventListener(event,update);
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
