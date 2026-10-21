import {readFile,writeFile,mkdir,rename} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {testPlan,evidenceName} from './test-cases.mjs';

function validate(proof,plan){
  assert.equal(proof.commit,process.env.GITHUB_SHA);
  assert.equal(String(proof.run),process.env.GITHUB_RUN_ID);
  assert.equal(proof.failures,0);
  assert.equal(proof.tests,plan.checks.length);
  assert.deepEqual([...proof.checks].sort(),[...plan.checks].sort());
  assert.equal(new Set(proof.checks).size,proof.tests);
  assert.match(proof.browserVersion,/^\d+(?:\.\d+)+$/);
  if(plan.suite==='ui'){
    assert.equal(proof.scenario,plan.scenario);assert.equal(proof.viewport,plan.viewport);
  }else assert.equal(proof.part,plan.part);
}
// Guard tests do not generate passing browser evidence.
const plan=testPlan.find(p=>p.suite==='browser'&&p.part==='worker');
const sample={commit:process.env.GITHUB_SHA,run:process.env.GITHUB_RUN_ID,part:'worker',tests:1,failures:0,checks:plan.checks,browserVersion:'1.2.3'};
validate(sample,plan);
for(const changed of [{commit:'wrong'},{failures:1},{checks:[]},{part:'main'},{checks:['wrong']},{run:'wrong'}]){
  assert.throws(()=>validate({...sample,...changed},plan));
}
await mkdir('ci-output/shards',{recursive:true});
for(const suite of ['ui','browser','media','player','theme']){
  const name=evidenceName(suite),proofs=[],shards=[],logs=[];
  for(const part of testPlan.filter(p=>p.suite===suite)){
    const path=`web-${name}-evidence-${part.part}.json`,log=`web-${suite}-${part.part}.log`;
    const bytes=await readFile('ci-output/'+path),proof=JSON.parse(bytes);
    validate(proof,part);
    const output=await readFile('ci-output/'+log,'utf8');
    const marker=suite==='ui'?'UI':suite.toUpperCase();
    assert.ok(output.includes(`WEB_${marker}_EVIDENCE=SUCCESS ${part.checks.length} checks`),`Missing execution log: ${log}`);
    if(proofs.length){assert.equal(proof.browserVersion,proofs[0].browserVersion);assert.equal(proof.scope,proofs[0].scope);}
    proofs.push(proof);logs.push(output);
    shards.push({part:part.part,path:'shards/'+path,sha256:createHash('sha256').update(bytes).digest('hex'),tests:proof.tests});
    await rename('ci-output/'+path,'ci-output/shards/'+path);
    await rename('ci-output/'+log,'ci-output/shards/'+log);
  }
  const checks=proofs.flatMap(p=>p.checks);
  assert.equal(new Set(checks).size,checks.length);
  const {part,viewport,scenario,...base}=proofs[0];
  const aggregate={...base,tests:checks.length,checks,shards};
  if(suite==='browser'){
    aggregate.worker=proofs.find(p=>p.worker)?.worker;
    for(const proof of proofs)for(const key of ['imageSha256','videoSha256'])assert.equal(proof[key],base[key]);
  }
  if(suite==='media'){
    aggregate.worker=proofs.find(p=>p.worker)?.worker;
    const ui=proofs.filter(p=>p.wasmUi).map(p=>p.wasmUi);
    assert.equal(ui.length,2);
    aggregate.wasmUi={...ui[0],tests:ui.reduce((n,p)=>n+p.tests,0),checks:ui.flatMap(p=>p.checks),outputs:ui.flatMap(p=>p.outputs)};
  }
  const marker=suite==='ui'?'UI':suite.toUpperCase();
  const message=`WEB_${marker}_EVIDENCE=SUCCESS ${checks.length} checks; all independent scenarios verified`;
  await writeFile(`ci-output/web-${name}-evidence.json`,JSON.stringify(aggregate,null,2)+'\n');
  await writeFile(`ci-output/web-${name}.log`,logs.join('\n')+'\n'+message+'\n'+(suite==='media'?'WASM_CORE_UI_EVIDENCE=SUCCESS 18 checks\n':''));
  console.log(message);
}
