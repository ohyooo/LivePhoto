import {readFile,writeFile,readdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {testPlan} from './test-cases.mjs';
const scenarios=[['browser','main'],['browser','worker'],['media','main'],['media','worker'],['ui-boot','main']];
const expected=['firefox','safari'].flatMap(browser=>scenarios.map(([suite,part])=>`web-compatibility-${browser}-${suite}-${part}-${process.env.GITHUB_RUN_ID}`));
assert.deepEqual((await readdir('ci-output/compatibility')).sort(),expected.sort());
const evidence=[];
for(const browser of ['firefox','safari'])for(const [suite,part]of scenarios){
  const path=`compatibility/web-compatibility-${browser}-${suite}-${part}-${process.env.GITHUB_RUN_ID}`;
  const value=JSON.parse(await readFile(`ci-output/${path}/web-${suite}-evidence-${part}.json`,'utf8'));
  assert.equal(value.commit,process.env.GITHUB_SHA);assert.equal(String(value.run),process.env.GITHUB_RUN_ID);
  assert.equal(value.browser.toLowerCase(),browser);assert.match(value.browserVersion,/^\d+(\.\d+)*/);assert.equal(value.failures,0);
  const checks=suite==='ui-boot'?['wasm-compose-render','pointer-photo-tools','pointer-return-to-create']:testPlan.find(p=>p.suite===suite&&p.part===part).checks;
  assert.deepEqual(value.checks.slice().sort(),checks.slice().sort());assert.equal(value.tests,checks.length);
  if(part==='worker'){assert.equal(value.worker.success,true);assert.equal(value.worker.failures,0);assert.equal(value.worker.tests,suite==='browser'?42:9);}
  const log=await readFile(`ci-output/${path}/web-compatibility.log`,'utf8');assert.ok(log.includes(`EVIDENCE=SUCCESS ${checks.length} checks`));
  evidence.push({browser,version:value.browserVersion,suite,part,tests:value.tests,path});
}
await writeFile('ci-output/web-compatibility-evidence.json',JSON.stringify({commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,failures:0,scenarios:evidence},null,2)+'\n');
console.log('WEB_COMPATIBILITY_EVIDENCE=SUCCESS Safari and Firefox / 10 independent scenarios');
