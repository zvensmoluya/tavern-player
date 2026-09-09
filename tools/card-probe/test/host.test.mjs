import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {installHost} from '../lib/host.mjs';
import {summarize} from '../survey.mjs';
function host(options={}) {
  const ctx=vm.createContext({structuredClone,console:{error(){}},options});
  vm.runInContext('window=globalThis;window.addEventListener=()=>{}',ctx);
  vm.runInContext(`(${installHost.toString()})(options)`,ctx);return ctx;
}
test('direct bound call and apply retain return values and recorded arguments',()=>{
 const c=host({state:{stat_data:{count:7}}});
 for(const f of [()=>c.getVariables({id:1}),()=>c.getVariables.bind(null,{id:2})(),()=>c.getVariables.call(null,{id:3}),()=>c.getVariables.apply(null,[{id:4}])]) assert.equal(f().stat_data.count,7);
 assert.deepEqual(Array.from(c.__PROBE__.log.filter(e=>e.t==='call'),e=>e.a[0].id),[1,2,3,4]);
});
test('events preserve identity and execute once listeners only once',async()=>{
 const c=host();let hits=0;
 assert.equal(c.Mvu.events.VARIABLE_INITIALIZED,c.Mvu.events.VARIABLE_INITIALIZED);
 c.eventOnce(c.Mvu.events.VARIABLE_INITIALIZED,()=>{hits++});
 await c.__PROBE__.fire(c.Mvu.events.VARIABLE_INITIALIZED,[]);await c.__PROBE__.fire(c.Mvu.events.VARIABLE_INITIALIZED,[]);assert.equal(hits,1);
});
test('initialization wait remains pending until the controlled global initializes',async()=>{
 const c=host({initialized:[]});let ready=false;
 const promise=c.waitGlobalInitialized('Mvu').then(()=>{ready=true});await Promise.resolve();assert.equal(ready,false);
 c.initializeGlobal('Mvu');await promise;assert.equal(ready,true);
});
test('controlled message writes and variable updates remain observable',async()=>{
 const c=host();c.replaceVariables({stat_data:{count:8}});assert.equal(c.getVariables().stat_data.count,8);
 await c.setChatMessages([{message_id:0,swipe_id:2}]);assert.equal(c.__PROBE__.snapshot().messages[0].swipe_id,2);
});
test('cyclic arguments do not crash recorder and unknown APIs stay explicit',()=>{
 const c=host(),v={};v.self=v;c.TavernHelper.unknown(v);assert.ok(c.__PROBE__.log.some(e=>e.t==='unsupported'));
});
test('summary keeps failures and zero calls separate',()=>{
 const s=summarize([{status:'failed'},{status:'observed',calls:[]},{status:'observed',calls:['a','a','b']}]);assert.deepEqual(s,{attempted:3,completed:2,failed:1,zeroCalls:1,totals:{a:1,b:1}});
});

import {projectOpening} from '../lib/project.mjs';
test('opening projection applies capture replacement and refuses disabled or unmatched rules',()=>{
 const data={first_mes:'tag:hello',extensions:{regex_scripts:[{markdownOnly:true,findRegex:'/tag:(.*)/',replaceString:'<div>$1</div>'}]}};
 const surface={label:'regex[0]'};
 assert.equal(projectOpening(data,surface,'first').rendered,'<div>hello</div>');
 data.extensions.regex_scripts[0].disabled=true;assert.throws(()=>projectOpening(data,surface,'first'));
 data.extensions.regex_scripts[0].disabled=false;data.first_mes='other';assert.throws(()=>projectOpening(data,surface,'first'));
});
