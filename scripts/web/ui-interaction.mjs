/** Drive actual Compose canvas hit regions, never call app handlers or semantic actions. */
export function composeDriver(page,viewport) {
  const find=name=>page.getByRole('button',{name,exact:true}).or(page.getByRole('checkbox',{name,exact:true})).first();
  const reveal=async (locator,minHeight=1)=>{
    const history=[];
    for(let attempt=0;attempt<40;attempt++) {
      const b=await locator.boundingBox({timeout:1000}).catch(()=>null);
      history.push(b);
      if(b&&b.width>0&&b.height>=minHeight&&b.y>=64&&b.y+b.height<=viewport.height) {
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
    throw Error('Shared Compose control is not reachable: '+await locator.count()+'; locator='+locator+'; bounds='+JSON.stringify(history));
  };
  const click=async name=>{
    const locator=find(name);await reveal(locator);
    await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
    const b=await locator.boundingBox({timeout:1000});if(!b)throw Error('Missing shared control: '+name);
    await page.mouse.click(b.x+b.width/2,b.y+b.height/2);await page.waitForTimeout(150);
  };
  const drag=async(locator,from,to,vertical=false,whileHeld=null,minHeight=1)=>{
    await reveal(locator,minHeight);
    await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
    const b=await locator.boundingBox({timeout:1000});if(!b)throw Error('Missing shared drag control');
    const point=f=>vertical?[b.x+b.width/2,b.y+b.height*f]:[b.x+b.width*f,b.y+b.height/2];
    const a=point(from),z=point(to);
    if(minHeight>1)console.log('Range pointer geometry',{bounds:b,from:a,to:z});
    await page.mouse.move(...a);await page.mouse.down();
    try {
      // Give Compose a rendered frame to register down before crossing slop.
      await page.waitForTimeout(100);
      // Deliver a human-speed drag across rendering frames, rather than a burst
      // of moves and mouseup before a new range control has processed its down.
      for(let step=1;step<=12;step++){await page.mouse.move(a[0]+(z[0]-a[0])*step/12,a[1]+(z[1]-a[1])*step/12);await page.waitForTimeout(20);}
      if(whileHeld)await whileHeld();
    } finally {await page.mouse.up();}
    await page.waitForTimeout(180);
  };
  const dragRange=async(side,to)=>{
    if(!['start','end'].includes(side))throw Error('Invalid range handle');
    // Live cover capture temporarily disables editing. A pointer-down while
    // disabled is correctly ignored, even if capture finishes during the drag.
    const apply=find('应用裁剪（音视频 copy）');
    for(let attempt=0;!(await apply.isEnabled())&&attempt<100;attempt++)await page.waitForTimeout(50);
    if(!await apply.isEnabled())throw Error('Range editing is still busy');
    // Compose accessibility bounds are clipped to the viewport: require the
    // full 48dp range row, not a sliver whose centre misses the actual track.
    const range=page.locator('#trim-progress'),from=side==='start'?.02:.98;
    // The first pointer-down can focus the Compose canvas and bring its prior
    // focus target into view (observed shifts of 74/278px). Activate with a real
    // click, then remeasure after that scroll before beginning the actual drag.
    const b=await reveal(range,48);
    await page.mouse.click(b.x+b.width*from,b.y+b.height/2);
    await page.waitForTimeout(200);
    await drag(range,from,to,false,null,48);
  };
  const choose=async(name,path)=>{const pending=page.waitForEvent('filechooser');pending.catch(()=>{});await click(name);await(await pending).setFiles(path);await page.waitForTimeout(200);};
  return {find,reveal,click,drag,dragRange,choose};
}
