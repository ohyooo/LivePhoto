import {readFile, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

const scope = 'same-common-compose-screen-mobile-wide-real-file-chooser-download-three-profiles-no-device-proof';
const proofs = [], shards = [], logs = [];
for (const viewport of ['mobile', 'wide']) {
  const path = `web-ui-browser-evidence-${viewport}.json`;
  const bytes = await readFile(`ci-output/${path}`);
  const proof = JSON.parse(bytes.toString('utf8'));
  if (proof.commit !== process.env.GITHUB_SHA || String(proof.run) !== process.env.GITHUB_RUN_ID ||
      proof.viewport !== viewport || proof.scope !== scope || proof.browser !== 'Chromium' ||
      typeof proof.browserVersion !== 'string' || !proof.browserVersion ||
      proof.tests !== 89 || proof.failures !== 0 ||
      !Array.isArray(proof.checks) || proof.checks.length !== 89 || new Set(proof.checks).size !== 89 ||
      proof.checks.some(check => typeof check !== 'string' || !check.startsWith(`${viewport}:`))) {
    throw Error(`Incomplete or mismatched UI shard: ${viewport}`);
  }
  const log = await readFile(`ci-output/web-ui-browser-${viewport}.log`, 'utf8');
  if (!log.includes(`WEB_UI_EVIDENCE=SUCCESS 89 checks; ${viewport};`)) throw Error(`Missing execution log: ${viewport}`);
  proofs.push(proof);
  shards.push({viewport, path, sha256: createHash('sha256').update(bytes).digest('hex'), tests: proof.tests});
  logs.push(`=== ${viewport} ===\n${log}`);
}
const [mobile, wide] = proofs;
if (mobile.browserVersion !== wide.browserVersion ||
    JSON.stringify(mobile.checks.map(c => c.slice(c.indexOf(':') + 1)).sort()) !==
    JSON.stringify(wide.checks.map(c => c.slice(c.indexOf(':') + 1)).sort())) {
  throw Error('UI shards use different browser versions or check sets');
}
const checks = proofs.flatMap(proof => proof.checks);
if (checks.length !== 178 || new Set(checks).size !== 178) throw Error('Incomplete UI aggregate');
const {viewport, ...base} = mobile;
await writeFile('ci-output/web-ui-browser-evidence.json', JSON.stringify({...base, tests: checks.length, checks, shards}, null, 2) + '\n');
const message = 'WEB_UI_EVIDENCE=SUCCESS 178 checks; two independently verified parallel viewport shards';
await writeFile('ci-output/web-ui-browser.log', logs.join('\n') + '\n' + message + '\n');
console.log(message);
