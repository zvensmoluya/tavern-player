// Prepare optional, local-only Android test inputs. Never package the community card.
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { imports } from './src/programs.mjs';
import { localSample } from './test/browser/local-sample.mjs';

const hash = text => createHash('sha256').update(text).digest('hex');
const directory = new URL('../mvu-probe/build/android-assets/era/', import.meta.url);
const lock = JSON.parse(await readFile(new URL('test/fixtures/era146-modules.json', import.meta.url), 'utf8'));
const card = await localSample('eaa0451cfbc70b9f1f2d1fc239bc22e0d018ff2b61935ea0b840a322c8545a1b');
const framework = card?.extensions.TavernHelper_scripts.map(item => item.value).find(script => script.enabled &&
  hash(script.content) === 'a0768dd0c855dac07cc6e2da51abfdff733d8e2745dcec2f37ef392035b438ed');
if (!framework) throw new Error('C-11 original required');
const pending = JSON.parse(imports(framework.content)).map(dep => dep.value);
const files = {};
await mkdir(directory, { recursive: true });
await writeFile(new URL('framework.js', directory), framework.content);
for (const address of pending) {
  if (Object.hasOwn(files, address)) continue;
  const url = new URL(address);
  const filename = lock[address];
  if (url.origin !== 'https://testingcf.jsdelivr.net' || !/^[0-9a-f]{64}\.js$/.test(filename ?? ''))
    throw new Error('Unexpected ERA module dependency');
  const local = new URL(filename, directory);
  let source = await readFile(local, 'utf8').catch(error => { if (error.code !== 'ENOENT') throw error; });
  if (source === undefined || hash(source) + '.js' !== filename) {
    const pinned = new URL(address);
    if (url.pathname === '/npm/pinia/+esm') pinned.pathname = '/npm/pinia@4.1.0/+esm';
    if (url.pathname === '/npm/opencc-js/+esm') pinned.pathname = '/npm/opencc-js@1.4.2/+esm';
    const response = await fetch(pinned, { signal: AbortSignal.timeout(30000) });
    if (!response.ok) throw new Error(`Download failed: ${pinned} (${response.status})`);
    source = await response.text();
    if (hash(source) + '.js' !== filename) throw new Error(`Module differs from the verified fixture: ${pinned}`);
    await writeFile(local, source);
  }
  files[address] = filename;
  for (const dep of JSON.parse(imports(source))) if (dep.value) pending.push(new URL(dep.value, url).href);
}
await writeFile(new URL('modules.json', directory), JSON.stringify(files, null, 2));
console.log(`Prepared framework and ${Object.keys(files).length} modules`);
