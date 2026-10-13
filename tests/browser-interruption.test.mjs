import test from 'node:test';
import assert from 'node:assert/strict';
import { BrowserTools, ToolRegistry } from '../src/tools.mjs';
import { DEFAULTS } from '../src/defaults.mjs';

test('a queued browser action cannot click after an explicit owner interruption', async () => {
  const browser=new BrowserTools('/unused-test-profile');
  let release,entered;
  const started=new Promise(resolve=>{entered=resolve;});
  const held=new Promise(resolve=>{release=resolve;});
  let clicks=0;
  const page={isClosed:()=>false,url:()=> 'https://example.test/',async title(){return 'Test';},
    locator(){return {first(){return this;},async click(){clicks++;},async innerText(){return 'Test';}};},
    async evaluate(){return [];}};
  browser.contexts.set('general',{pages:()=>[page]});
  browser.pages.set('general',page);
  const first=browser.withPage('general',async()=>{entered();await held;});
  await started;
  const controller=new AbortController();
  const queued=browser.operate({action:'click',selector:'#submit'},{signal:controller.signal});
  const rejected=assert.rejects(queued,{name:'AbortError'});
  controller.abort('Owner paused the task');
  release();
  await first;await rejected;
  assert.equal(clicks,0);
  await browser.operate({action:'click',selector:'#submit'});
  assert.equal(clicks,1,'interruption does not destroy the persistent browser or subsequent owner actions');
});

test('the app browser tool forwards the task signal without binding it to an HTTP caller', async () => {
  const controller=new AbortController();
  let observed;
  const registry=new ToolRegistry({dataDir:'/unused-test-data',store:{getSettings:()=>DEFAULTS},
    browser:{async operate(args,options){observed={args,options};return {url:'https://example.test/'};}},terminal:{}});
  const result=await registry.run('browser',{action:'read'},{authority:'owner',signal:controller.signal});
  assert.equal(result.url,'https://example.test/');
  assert.equal(observed.options.signal,controller.signal);
  assert.equal(controller.signal.aborted,false);
});
