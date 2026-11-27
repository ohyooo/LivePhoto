/** Assert browser-owned media promises, while delivering short holds in one task. */
export async function checkResultPreviewLifecycle(page, playerUrl, movieBytes) {
  return await page.evaluate(async ({playerUrl, movieBytes}) => {
    const {resultPreview}=await import(playerUrl);
    const canvas=document.createElement('canvas');canvas.width=canvas.height=8;
    const paint=canvas.getContext('2d');paint.fillStyle='#fd832a';paint.fillRect(0,0,8,8);
    const cover=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));
    const movie=new Blob([new Uint8Array(movieBytes)],{type:'video/mp4'});
    const checks=[];
    const check=(condition,message)=>{
      if(!condition)throw Error(message);
      checks.push(message);
    };
    async function scenario(source, run) {
      const preview=resultPreview(cover,source,()=>{}),{element}=preview;
      const video=element.querySelector('video'),image=element.querySelector('img');
      // Start before data is available, exercising the real media play() /
      // pause() promise sequence even when the tiny local MP4 loads quickly.
      video.muted=true;video.preload='none';video.load();
      const outcomes=[];
      const play=video.play.bind(video);
      video.play=()=>{
        const promise=play();
        outcomes.push(promise.then(()=> 'playing', error=>error.name));
        return promise;
      };
      const key=type=>element.dispatchEvent(new KeyboardEvent(type,{key:' ',bubbles:true,cancelable:true}));
      let timer;
      try {
        document.body.append(element);
        await Promise.race([
          run({preview,element,video,image,outcomes,hold:()=>key('keydown'),release:()=>key('keyup')}),
          new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Result preview lifecycle timed out')),8000);}),
        ]);
      } finally {
        clearTimeout(timer);preview.dispose();element.remove();
      }
    }
    await scenario(movie,async ({element,video,image,outcomes,hold,release})=>{
      hold();release();
      const outcome=await outcomes[0];
      await Promise.resolve();
      check(outcome==='AbortError','early release really interrupts a pending media promise');
      check(video.paused&&video.hidden&&!image.hidden&&!element.title,'early release returns to cover without a playback error');
    });
    await scenario(movie,async ({element,video,image,outcomes,hold,release})=>{
      hold();release();hold();
      const [old,current]=await Promise.all(outcomes);
      check(old==='AbortError'&&current==='playing'&&!video.paused&&!video.hidden&&image.hidden&&!element.title,
        'an interrupted old hold cannot stop a newer hold');
      release();
      check(video.paused&&video.hidden&&!image.hidden,'releasing the active hold restores the cover');
    });
    await scenario(new Blob([new Uint8Array([1,2,3])],{type:'video/mp4'}),async ({element,video,image,outcomes,hold})=>{
      hold();
      await outcomes[0];
      // The product reaction also runs on the promise microtask queue.
      await Promise.resolve();
      check(video.paused&&video.hidden&&!image.hidden&&element.title==='浏览器无法播放此视频',
        'failure for the active hold restores cover and reports playback failure');
    });
    await scenario(movie,async ({preview,element,video,image,outcomes,hold})=>{
      hold();
      preview.dispose();
      await outcomes[0];
      await Promise.resolve();
      check(video.paused&&video.hidden&&!image.hidden&&!element.title&&!video.hasAttribute('src'),
        'disposed preview ignores pending play rejection');
    });
    return checks;
  },{playerUrl,movieBytes});
}
