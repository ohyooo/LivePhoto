/** Drive actual Compose canvas hit regions, never call app handlers or semantic actions. */
export function composeDriver(page,viewport) {
  const find=name=>page.getByRole('button',{name,exact:true}).or(page.getByRole('checkbox',{name,exact:true})).first();
  const reveal=async locator=>{
    for(let attempt=0;attempt<40;attempt++) {
      const b=await locator.boundingBox({timeout:1000}).catch(()=>null);
      if(b&&b.width>0&&b.height>0&&b.y>=64&&b.y+b.height<=viewport.height) return b;
      await page.mouse.move(viewport.width/2,viewport.height/2);
      await page.mouse.wheel(0,b&&b.height>0?(b.y<64?-280:280):(attempt<25?280:-280));
      await page.waitForTimeout(100);
    }
    throw Error('Shared Compose control is not reachable: '+await locator.count());
  };
  const click=async name=>{
    const b=await reveal(find(name));await page.waitForTimeout(100);await page.mouse.click(b.x+b.width/2,b.y+b.height/2);await page.waitForTimeout(100);
  };
  const drag=async(locator,from,to,vertical=false)=>{
    const b=await reveal(locator);
    const point=f=>vertical?[b.x+b.width/2,b.y+b.height*f]:[b.x+b.width*f,b.y+b.height/2];
    const a=point(from),z=point(to);await page.mouse.move(...a);await page.mouse.down();await page.mouse.move(...z,{steps:12});await page.mouse.up();await page.waitForTimeout(180);
  };
  const choose=async(name,path)=>{const pending=page.waitForEvent('filechooser');pending.catch(()=>{});await click(name);await(await pending).setFiles(path);await page.waitForTimeout(200);};
  return {find,reveal,click,drag,choose};
}
