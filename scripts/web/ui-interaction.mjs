/** Drive actual Compose canvas hit regions, never call app handlers or semantic actions. */
export function composeDriver(page,viewport) {
  const find=name=>page.getByRole('button',{name,exact:true}).or(page.getByRole('checkbox',{name,exact:true})).first();
  const reveal=async locator=>{
    for(let attempt=0;attempt<40;attempt++) {
      const b=await locator.boundingBox({timeout:1000}).catch(()=>null);
      if(b&&b.width>0&&b.height>0&&b.y>=64&&b.y+b.height<=viewport.height) {
        // Focus/bring-into-view can still animate after a control first becomes
        // visible. Do not use coordinates captured halfway through that scroll.
        await page.waitForTimeout(120);
        const next=await locator.boundingBox({timeout:1000}).catch(()=>null);
        if(next&&['x','y','width','height'].every(key=>Math.abs(next[key]-b[key])<.5))return next;
        continue;
      }
      await page.mouse.move(viewport.width/2,viewport.height/2);
      await page.mouse.wheel(0,b&&b.height>0?(b.y<64?-280:280):(attempt<25?280:-280));
      await page.waitForTimeout(100);
    }
    throw Error('Shared Compose control is not reachable: '+await locator.count());
  };
  const click=async name=>{
    const locator=find(name);await reveal(locator);
    await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
    const b=await locator.boundingBox({timeout:1000});if(!b)throw Error('Missing shared control: '+name);
    await page.mouse.click(b.x+b.width/2,b.y+b.height/2);await page.waitForTimeout(150);
  };
  const drag=async(locator,from,to,vertical=false,whileHeld=null,startHandle=null)=>{
    await reveal(locator);
    await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
    const b=await locator.boundingBox({timeout:1000});if(!b)throw Error('Missing shared drag control');
    const point=f=>vertical?[b.x+b.width/2,b.y+b.height*f]:[b.x+b.width*f,b.y+b.height/2];
    const handle=startHandle?await startHandle.boundingBox({timeout:1000}):null;
    if(startHandle&&(!handle||handle.width<=0||handle.height<=0))throw Error('Missing visible range handle');
    const a=handle?[handle.x+handle.width/2,handle.y+handle.height/2]:point(from),z=point(to);
    await page.mouse.move(...a);await page.mouse.down();
    try {
      // Deliver a human-speed drag across rendering frames, rather than a burst
      // of moves and mouseup before a new range control has processed its down.
      for(let step=1;step<=12;step++){await page.mouse.move(a[0]+(z[0]-a[0])*step/12,a[1]+(z[1]-a[1])*step/12);await page.waitForTimeout(20);}
      if(whileHeld)await whileHeld();
    } finally {await page.mouse.up();}
    await page.waitForTimeout(180);
  };
  const dragRange=async(side,to)=>{
    if(!['start','end'].includes(side))throw Error('Invalid range handle');
    await drag(page.locator('#trim-progress'),0,to,false,null,page.locator('#trim-'+side+'-thumb'));
  };
  const choose=async(name,path)=>{const pending=page.waitForEvent('filechooser');pending.catch(()=>{});await click(name);await(await pending).setFiles(path);await page.waitForTimeout(200);};
  return {find,reveal,click,drag,dragRange,choose};
}
