/** Drive actual Compose canvas hit regions, never call app handlers or semantic actions. */
export function composeDriver(page,viewport) {
  const find=name=>page.getByRole('button',{name,exact:true}).or(page.getByRole('checkbox',{name,exact:true})).first();
  const reveal=async (locator,minHeight=1,overlay=false)=>{
    const history=[];
    for(let attempt=0;attempt<40;attempt++) {
      const b=await locator.boundingBox({timeout:1000}).catch(()=>null);
      history.push(b);
      const header=await page.locator('#app-bar').boundingBox().catch(()=>null);
      const footer=await page.locator('#main-action').boundingBox().catch(()=>null);
      const inFooter=await locator.evaluate(e=>!!e.closest('#main-action')).catch(()=>false);
      const fullscreen=await page.evaluate(()=>!!document.fullscreenElement);
      const top=overlay||fullscreen?0:(header?header.y+header.height:64),bottom=overlay||fullscreen||inFooter?viewport.height:(footer?.y??viewport.height);
      if(b&&b.width>0&&b.height>=minHeight&&b.y>=top&&b.y+b.height<=bottom) {
        // Focus/bring-into-view can still animate after a control first becomes
        // visible. Do not use coordinates captured halfway through that scroll.
        await page.waitForTimeout(120);
        const next=await locator.boundingBox({timeout:1000}).catch(()=>null);
        if(next&&['x','y','width','height'].every(key=>Math.abs(next[key]-b[key])<.5))return next;
        continue;
      }
      // Scroll the shared column's padding, not a selectable report, slider or
      // media surface. Wasm wheel animations must settle before the next delta;
      // otherwise successive deltas can carry a newly visible button past us.
      const scrollX=8;
      await page.mouse.move(scrollX,viewport.height/2);
      await page.mouse.wheel(0,b&&b.height>0?(b.y<top?-280:280):(attempt<25?280:-280));
      await page.waitForTimeout(350);
    }
    throw Error('Shared Compose control is not reachable: '+await locator.count()+'; locator='+locator+'; bounds='+JSON.stringify(history));
  };
  const rawClick=async name=>{
    const overlay=typeof name==='string'&&(['新建','照片工具','主题模式','更多菜单','生成动态照片','重置输入'].includes(name)||/^(工具|协议|主题)：/.test(name));
    const locator=find(name);await reveal(locator,1,overlay);
    await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
    const b=await locator.boundingBox({timeout:1000});if(!b)throw Error('Missing shared control: '+name);
    await page.mouse.click(b.x+b.width/2,b.y+b.height/2);await page.waitForTimeout(150);
  };
  const selectTool=async name=>{
    await rawClick('照片工具');
    if(!(await page.locator('#tool-selector').textContent()).includes(name)){
      await rawClick('选择照片工具');await rawClick('工具：'+name);
    }
  };
  const selectProtocol=async name=>{
    if(await page.locator('#protocol-selector').count()){
      await rawClick('选择目标协议');await rawClick('协议：'+name);
    }else await rawClick(new RegExp('^(?:✓ )?'+name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'$'));
  };
  const click=async name=>{
    const tools={'开始检查（只读）':'检查照片','提取原始视频':'提取原始视频','准备清理拆分':'清理拆分',
      '转换并另存':'协议转换','预览安全修复（只读）':'安全修复','应用修复并另存':'安全修复','修改关键照片时间并另存':'修改关键照片时间'};
    if(typeof name==='string'&&tools[name])await selectTool(tools[name]);
    if(name instanceof RegExp&&name.source.includes('转换为'))await selectTool('协议转换');
    if(['检查视频','重新封装','转为 H.264'].includes(name)&&await find(name).count()===0)await rawClick('更多视频处理');
    if(name==='使用当前画面作为封面'&&await page.locator('#cover-progress').count()===0)await rawClick('封面位置');
    await rawClick(name);
  };
  const details=async title=>{if(await find('展开'+title+'详情').count())await rawClick('展开'+title+'详情');};
  const report=async locator=>{
    const titles={'status':'生成','analysis-report':'检查','extraction-report':'提取','clean-split-report':'拆分',
      'conversion-report':'转换','repair-report':'修复','key-report':'关键照片','player-edit-status':'视频处理'};
    const id=await locator.evaluate((e,ids)=>{for(let p=e;p;p=p.parentElement)if(ids.includes(p.id))return p.id;return null;},Object.keys(titles));
    const title=titles[id];
    if(title)await details(title);
    return locator.evaluate(e=>e.getAttribute('aria-label')||e.textContent||'');
  };
  const emptyCreateInputs=async()=>{
    await rawClick('新建');
    const empty=await page.getByText('图片：未选择',{exact:true}).count()===1&&await page.getByText('视频：未选择',{exact:true}).count()===1;
    await rawClick('照片工具');return empty;
  };
  const typeField=async(selector,value)=>{
    const field=page.locator(selector);
    for(let attempt=0;attempt<2;attempt++){
      const b=await reveal(field,56);await page.mouse.click(b.x+Math.min(60,b.width/2),b.y+b.height*.65);await page.waitForTimeout(100);
    }
    await page.keyboard.press('ControlOrMeta+A');await page.keyboard.press('Backspace');await page.keyboard.type(value,{delay:30});
    await page.keyboard.press('Tab');await page.waitForTimeout(150);
    if(!(await field.textContent()).includes(value))throw Error('Text did not reach Compose field: '+selector+'; rendered='+await field.textContent()+'; focus='+await page.evaluate(()=>({tag:document.activeElement?.tagName,shadow:document.activeElement?.shadowRoot?.activeElement?.outerHTML})));
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
  return {find,reveal,click,drag,dragRange,choose,selectTool,selectProtocol,details,report,emptyCreateInputs,typeField};
}
