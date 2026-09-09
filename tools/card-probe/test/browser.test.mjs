import test from 'node:test';
import assert from 'node:assert/strict';
import {runSurface} from '../lib/browser.mjs';
test('browser records bindings events clicks and blocked script without confusing failure with zero calls', {timeout:30000}, async()=>{
 const html=`<html><head></head><body><button id="go">Go</button><script src="http://127.0.0.1:9/missing.js"></script><script>
 const read=getVariables.bind(null,{type:'message',message_id:0});
 document.body.dataset.initial=read().stat_data.count;
 eventOn(Mvu.events.VARIABLE_UPDATE_ENDED,()=>{document.body.dataset.updated=read().stat_data.count;});
 document.querySelector('#go').onclick=()=>setChatMessages([{message_id:0,swipe_id:2}]);
 </script></body></html>`;
 const r=await runSurface(html,{waitMs:1000,scenario:{host:{state:{stat_data:{count:1}}},steps:[{type:'state',value:{stat_data:{count:7}}},{type:'event',name:'Mvu.events.VARIABLE_UPDATE_ENDED'},{type:'click',selector:'#go'}]}});
 assert.equal(r.final.log.filter(e=>e.t==='call'&&e.p==='getVariables').length,2);
 assert.equal(r.final.messages[0].swipe_id,2);
 assert.equal(r.dom.dataset.initial,"1"); assert.equal(r.dom.dataset.updated,"7");
 assert.equal(r.actions.filter(a=>a.status==='executed').length,3);
 assert.ok(r.blocked.some(e=>e.url.includes('127.0.0.1'))||r.final.log.some(e=>e.t==='blocked-resource'&&e.p.includes('127.0.0.1')));
 assert.equal(r.exceptions.length,0); assert.equal(r.transportErrors.length,0);
});
