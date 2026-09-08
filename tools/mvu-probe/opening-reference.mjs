import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import {parse} from 'acorn';
// Differential reference for the audited C-01 fixture; never part of compilation input.
const directory=path.resolve(process.argv[2]);
const metadata=JSON.parse(fs.readFileSync(path.join(directory,'metadata.json'),'utf8'));
if(metadata.sourceSha256!=='1945abd1e2368ec332399830cd85c62be4a9a527f0c43dae6f34db9c9b3b2e1a') throw Error('Unexpected fixture hash');
const view=JSON.parse(fs.readFileSync(path.join(directory,'program-view.json'),'utf8'));
const html=view.sources.find(s=>s.id==='regex2').content;
const source=html.match(/<script>([\s\S]*?)<\/script>/i)[1];
const body=parse(source,{ecmaVersion:2022}).body[0].expression.callee.body.body;
const vars=new Set(['ROLES','PRESET_GUIDE','CUSTOM_GUIDE','draft']);
const funcs=new Set(['normalizeBody','isCustomStory','presentText','buildInitLines','buildOpeningMessage']);
const selected=body.filter(n=>n.type==='FunctionDeclaration'?funcs.has(n.id.name):n.type==='VariableDeclaration'&&n.declarations.every(d=>vars.has(d.id.name)));
if(selected.length!==9) throw Error('Unexpected source structure: '+selected.length);
const code=selected.map(n=>source.slice(n.start,n.end)).join('\n');
const context=vm.createContext({}); vm.runInContext(code,context);
const cases=[];
for(const story of ['0','1','2']) for(const populated of [false,true]) {
 const draft={story,time:populated?'audit_time':'',place:populated?'audit_place':'',notes:populated?'audit_notes':'',present:populated?[context.ROLES[2],context.ROLES[0]]:[],身体:populated?'TS魔法少女':'少女',额外力量:populated?'audit_power':''};
 context.draft=draft;
 cases.push({draft,expected:vm.runInContext('buildOpeningMessage()',context)});
}
fs.writeFileSync(path.join(directory,'opening-reference.json'),JSON.stringify({cases},null,2));
console.log('Extracted original pure builder; cases:',cases.length);
