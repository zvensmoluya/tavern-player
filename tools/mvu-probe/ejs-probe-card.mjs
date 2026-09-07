// Explicit local audit only. Original card and reference outputs remain in ignored build directories.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { transform } from 'esbuild';
import lodash from 'lodash';
import { ProbeHost } from './host.mjs';
import { render } from './ejs-entry.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const expectedCard = 'fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe';
const revision = 'd6f520d149aba146305b0b781ddd691d449c28d2';
const sources = [
    ['chat.ts', 'src/function/chat.ts', '60878de4f427e1f0fa84691a852a10896e10691ae47d866541b3de09b82000ad'],
    ['variables.ts', 'src/function/variables.ts', '0c0f671975195fefee6dcebce19fd59a2c36528fd7ab52419490b2c259d0db1c'],
    ['ejs.js', 'src/3rdparty/ejs.js', '66b3c84f33adbf154950e18cb55704062e3fa5e2f4e69001f6df296d11548d54'],
];
const referenceRoot = resolve(root, 'build/ejs-reference');
await mkdir(referenceRoot, { recursive: true });
const referenceSource = {};
for (const [name, path, hash] of sources) {
    const target = resolve(referenceRoot, name);
    let bytes;
    try { bytes = await readFile(target); } catch { /* Explicit probe may fetch its reference. */ }
    if (!bytes || sha(bytes) !== hash) {
        const response = await fetch(`https://raw.githubusercontent.com/zonde306/ST-Prompt-Template/${revision}/${path}`,
            { signal: AbortSignal.timeout(30000) });
        assert.ok(response.ok);
        bytes = Buffer.from(await response.arrayBuffer());
        assert.equal(sha(bytes), hash, `Reference hash: ${name}`);
        await writeFile(target, bytes);
    }
    referenceSource[name] = bytes.toString('utf8');
}

let bytes;
if (process.argv[2]) bytes = await readFile(process.argv[2]);
else {
    for (const name of await readdir(resolve(root, '../../source'))) {
        const candidate = await readFile(resolve(root, '../../source', name));
        if (sha(candidate) === expectedCard) { bytes = candidate; break; }
    }
}
assert.ok(bytes, 'Audited C-04 local source is required');
assert.equal(sha(bytes), expectedCard);
let card;
for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset);
    const type = bytes.subarray(offset + 4, offset + 8).toString('ascii');
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    offset += length + 12;
    if (type === 'tEXt' && data.subarray(0, 6).toString() === 'chara\0') {
        card = JSON.parse(Buffer.from(data.subarray(6).toString(), 'base64').toString()).data;
        break;
    }
}
assert.ok(card);
const fixture = {
    schemaScript: card.extensions.tavern_helper.scripts.find(s => s.enabled && s.content.includes('registerMvuSchema')).content,
    entries: card.character_book.entries,
    greetings: [card.first_mes, ...card.alternate_greetings],
};
const host = new ProbeHost(fixture);
await host.initialize();
const baseline = host.state();
const templates = card.character_book.entries.filter(e => e.content.includes('<%'));
assert.equal(templates.length, 4);

const reference = vm.createContext({
    _: lodash, chat: [], console: { debug() {}, warn() {}, log() {} },
    settings: {}, substituteParams: x => x, getRegexedString: x => x,
    regex_placement: { USER_INPUT: 1, AI_OUTPUT: 2 },
});
vm.runInContext(referenceSource['ejs.js'], reference);
async function module(name) {
    // Retain the original function bodies; replace browser imports with explicit test dependencies.
    const source = referenceSource[name].replace(/^import .*;\s*$/gm, '');
    const { code } = await transform(source, { loader: 'ts', format: 'cjs', target: 'es2022' });
    reference.module = { exports: {} };
    vm.runInContext(`(function(){${code}\n})()`, reference);
    return reference.module.exports;
}
const chatApi = await module('chat.ts');
const variableApi = await module('variables.ts');
async function upstream(input) {
    reference.chat.splice(0, reference.chat.length, ...input.history.map(m => ({ mes: m.content,
        is_user: m.role === 'user', is_system: m.role === 'system' })));
    variableApi.STATE.cacheVars = structuredClone(input.variables);
    const env = {
        getvar: variableApi.getVariable.bind({ runID: 1 }), variables: variableApi.STATE.cacheVars,
        getChatMessage: chatApi.getChatMessage, getChatMessages: chatApi.getChatMessages,
        matchChatMessages: chatApi.matchChatMessages,
    };
    return await reference.ejs.compile(input.template, { client: true, async: true, outputFunctionName: 'print', compileDebug: false })(env, x => x);
}

// Test the original chat implementation as well as the actual card templates. In this reference
// snapshot, end=0 yields [], and the role argument is ignored when end is undefined.
const neutral = { variables: { stat_data: { score: 2 } }, history: [
    { role: 'user', content: 'Tea please.' }, { role: 'assistant', content: 'A coat.' }, { role: 'user', content: 'Continue.' },
] };
for (const expression of [
    "JSON.stringify(getChatMessages(-2,0,'user'))", "JSON.stringify(getChatMessages(-2,'user'))",
    "JSON.stringify(getChatMessages(0,2,'user'))", "matchChatMessages('coat',{start:-2,role:'user'})",
    "matchChatMessages('T.a',{start:-3})", "matchChatMessages(['Tea','Continue'],{start:-3,and:true})",
    "getChatMessage(-1,'user')", "getvar('missing',{defaults:7})", "getvar('stat_data.score')",
]) {
    const input = { ...neutral, template: `<%= ${expression} %>` };
    assert.equal(await render(input), await upstream(input), expression);
}

const cases = [];
async function add(id, entry, changes = [], history = []) {
    const variables = structuredClone(baseline);
    for (const [path, value] of changes) lodash.set(variables, path, value);
    const request = { sourceId: `c04:${entry.id}`, template: entry.content, variables, history };
    const expected = await upstream(request);
    assert.equal(await render(request), expected, id);
    cases.push({ id, request, expected });
}
for (const entry of templates) await add(`entry-${entry.id}-initial`, entry);
const entry = id => templates.find(e => e.id === id);
const locations = [...entry(1).content.matchAll(/currentLocation === '([^']+)'/g)].map(m => m[1]);
const locationPath = entry(1).content.match(/currentLocation = getvar\('([^']+)'/)[1];
for (let i = 0; i < locations.length; i++) await add(`location-${i}`, entry(1), [[locationPath, locations[i]]]);
for (const id of [21, 22]) {
    const path = entry(id).content.match(/getvar\('([^']+)'\)/)[1];
    for (const score of [-1, 0, 0.5, 1, 9, 10, 11, 49, 50, 99, 100, 149, 150, 199, 200])
        await add(`stage-${id}-${score}`, entry(id), [[path, score]]);
}
const inventoryPath = entry(1).content.match(/let inventory = getvar\('([^']+)'/)[1];
const items = [...new Set([...entry(1).content.matchAll(/inventory\['([^']+)'\]/g)].map(m => m[1]))];
const quantityKey = entry(1).content.match(/inventory\['[^']+'\]\.([^\s;>]+)/)[1];
for (const quantity of [0, 1, 3]) {
    const inventory = Object.fromEntries(items.map(name => [name, { [quantityKey]: quantity }]));
    await add(`inventory-${quantity}`, entry(1), [[inventoryPath, inventory]], neutral.history);
}
const dayPath = entry(23).content.match(/getvar\('([^']+)'/)[1];
const closetPath = entry(23).content.match(/Object.keys\(getvar\('([^']+)'/)[1];
const sweetPaths = [...entry(23).content.matchAll(/getvar\('([^']+)', \{ defaults: \{[^}]+\} \}\)/g)].map(m => m[1]);
const stimulusPath = entry(22).content.match(/getvar\('([^']+)'/)[1];
assert.equal(sweetPaths.length, 2);
for (const clothed of [false, true]) for (const sweet of [false, true]) for (const stimulated of [false, true]) {
    await add(`event-${clothed}-${sweet}-${stimulated}`, entry(23), [
        [dayPath, 3], [closetPath, clothed ? { garment: {} } : {}],
        ...sweetPaths.map(path => [path, { [quantityKey]: sweet ? 1 : 0 }]), [stimulusPath, stimulated ? 11 : 10],
    ]);
}
const output = resolve(root, 'build/android-assets/ejs');
await mkdir(output, { recursive: true });
await writeFile(resolve(output, 'c04-cases.json'), JSON.stringify(cases));
await writeFile(resolve(output, 'c04-card.png'), bytes);
await writeFile(resolve(root, 'build/android-assets/mvu/c04-program.json'), JSON.stringify(fixture));
await writeFile(resolve(root, 'build/ejs-card-report.json'), JSON.stringify({ cardSha256: expectedCard,
    referenceCommit: revision, templates: templates.map(e => e.id), differentialCases: cases.length,
    scope: 'Pinned upstream versus Player Node host; generated cases also consumed by actual QuickJS JVM tests',
}, null, 2) + '\n');
console.log(`C-04: ${cases.length} original-template cases match pinned upstream; 9 neutral host comparisons passed.`);
