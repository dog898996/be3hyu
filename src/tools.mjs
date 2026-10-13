import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { ExtensibleTools } from './extensions.mjs';

function checkBrowserSignal(signal){
  if(signal?.aborted)throw Object.assign(new Error('Browser action interrupted by owner'),{name:'AbortError'});
}
export class BrowserTools {
  constructor(dataDir) { this.dataDir=dataDir; this.contexts=new Map(); this.pages=new Map(); this.locks=new Map();this.contextPromises=new Map(); }
  async withPage(owner,fn,{signal}={}) {
    const previous=this.locks.get(owner)||Promise.resolve();
    const work=previous.catch(()=>{}).then(async()=>{
      checkBrowserSignal(signal);
      const separateInstagramPage=['instagram-observer','instagram-reader'].includes(owner);
      const profileOwner=separateInstagramPage?'instagram':owner;
      let context=this.contexts.get(profileOwner);
      if(!context){
        let pending=this.contextPromises.get(profileOwner);
        if(!pending){pending=(async()=>{
        const profile=path.join(this.dataDir,'browser',profileOwner); await mkdir(profile,{recursive:true});
        const proxy=process.env.HTTPS_PROXY||process.env.HTTP_PROXY;
        const created=await chromium.launchPersistentContext(profile,{
          headless:true,viewport:{width:1280,height:900},acceptDownloads:true,
          ...(proxy?{proxy:{server:proxy,bypass:'localhost,127.0.0.1'}}:{}),
          args:process.env.CHROMIUM_NO_SANDBOX==='true'?['--no-sandbox']:[],
          ...(process.env.CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.CHROMIUM_EXECUTABLE_PATH}:{})
        });
        this.contexts.set(profileOwner,created);return created;
        })();this.contextPromises.set(profileOwner,pending);}
        try{context=await pending;}finally{this.contextPromises.delete(profileOwner);}
      }
      checkBrowserSignal(signal);
      let page=this.pages.get(owner);
      if(!page||page.isClosed()){page=separateInstagramPage?await context.newPage():context.pages()[0]||await context.newPage();this.pages.set(owner,page);}
      checkBrowserSignal(signal);
      return fn(page,context);
    });
    this.locks.set(owner,work.then(()=>{},()=>{})); return work;
  }
  async operate(args={}, {signal}={}) {
    const owner=['chatgpt','instagram','general'].includes(args.owner)?args.owner:'general';
    return this.withPage(owner,async(page,context)=>{
      checkBrowserSignal(signal);
      const action=args.action||'read';
      if(action==='open') await page.goto(args.url,{waitUntil:'domcontentloaded',timeout:45000});
      else if(action==='click') {
        if(args.selector)await page.locator(args.selector).first().click({timeout:10000});
        else await page.mouse.click(Number(args.x),Number(args.y));
      } else if(action==='type') {
        if(args.selector)await page.locator(args.selector).first().fill(args.text||'',{timeout:10000});
        else await page.keyboard.insertText(args.text||'');
      } else if(action==='press')await page.keyboard.press(args.key||'Enter');
      else if(action==='upload')await page.locator(args.selector).setInputFiles(args.paths);
      else if(action==='download'){
        const downloadPromise=page.waitForEvent('download',{timeout:30000});
        await page.locator(args.selector).first().click();
        const download=await downloadPromise;
        const destination=path.join(this.dataDir,'files',crypto.randomUUID()+'-'+path.basename(download.suggestedFilename()));
        await mkdir(path.dirname(destination),{recursive:true});await download.saveAs(destination);
        return {path:destination};
      }else if(action==='tabs')return {tabs:context.pages().map((p,i)=>({id:i,url:p.url()}))};
      else if(action==='tab'){
        const chosen=context.pages()[args.id];if(!chosen)throw new Error('Tab not found');this.pages.set(owner,chosen);return {url:chosen.url()};
      }else if(action==='new-tab'){
        const tab=await context.newPage();this.pages.set(owner,tab);checkBrowserSignal(signal);if(args.url)await tab.goto(args.url,{waitUntil:'domcontentloaded'});return {url:tab.url()};
      }else if(!['read','screenshot'].includes(action))throw new Error('Unknown browser action');
      const result={url:page.url(),title:await page.title()};
      if(action!=='screenshot'){
        result.text=(await page.locator('body').innerText({timeout:10000})).slice(0,30000);
        result.elements=await page.evaluate(()=>{
          const nodes=[...document.querySelectorAll('a[href],button,input,textarea,select,[role="button"],[role="textbox"],[contenteditable="true"]')];
          return nodes.filter(node=>node.getClientRects().length&&getComputedStyle(node).visibility!=='hidden').slice(0,200).map(node=>{
            if(!node.hasAttribute('data-hoyoung-target'))node.setAttribute('data-hoyoung-target',globalThis.crypto?.randomUUID?globalThis.crypto.randomUUID():'target-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2));
            return {selector:'[data-hoyoung-target="'+node.getAttribute('data-hoyoung-target')+'"]',tag:node.tagName.toLowerCase(),role:node.getAttribute('role'),type:node.getAttribute('type'),label:node.getAttribute('aria-label'),placeholder:node.getAttribute('placeholder'),text:(node.innerText||'').slice(0,160),disabled:!!node.disabled};
          });
        });
      }
      if(action==='screenshot'||args.screenshot){
        const name=owner+'.png',target=path.join(this.dataDir,'screenshots',name);
        await mkdir(path.dirname(target),{recursive:true});await page.screenshot({path:target});
        result.screenshot='/api/screenshots/'+name;
      }
      return result;
    },{signal});
  }
  async close(){await Promise.allSettled([...this.contexts.values()].map(c=>c.close()));this.contexts.clear();this.pages.clear();}
}

export class TerminalTools {
  constructor(workspace){this.workspace=workspace;this.jobs=new Map();}
  async execute({command,cwd,background=false,timeoutSeconds=180},signal) {
    if(typeof command!=='string'||!command.trim())throw new Error('command is required');
    await mkdir(this.workspace,{recursive:true});
    const id=crypto.randomUUID();
    const child=spawn('/bin/bash',['-lc',command],{cwd:cwd||this.workspace,env:process.env,detached:true,stdio:['ignore','pipe','pipe']});
    const job={id,pid:child.pid,command,status:'running',output:'',startedAt:new Date().toISOString(),exitCode:null};
    this.jobs.set(id,job);
    const capture=d=>{job.output=(job.output+d.toString()).slice(-60000);};
    child.stdout.on('data',capture);child.stderr.on('data',capture);
    let forceTimer;
    const stop=()=>{try{process.kill(-child.pid,'SIGTERM');}catch{};if(!forceTimer){forceTimer=setTimeout(()=>{if(job.status==='running')try{process.kill(-child.pid,'SIGKILL');}catch{}},5000);forceTimer.unref?.();}};
    signal?.addEventListener('abort',stop,{once:true});
    const done=new Promise((resolve,reject)=>{
      child.once('error',e=>{job.status='failed';job.error=e.message;reject(e);});
      child.once('exit',(code,sig)=>{clearTimeout(forceTimer);job.status=code===0?'succeeded':signal?.aborted?'cancelled':'failed';job.exitCode=code;job.signal=sig;job.finishedAt=new Date().toISOString();resolve({...job});});
    });
    if(background){done.catch(()=>{}).finally(()=>signal?.removeEventListener('abort',stop));return {...job};}
    const timer=setTimeout(stop,Math.min(3600,Math.max(1,timeoutSeconds))*1000);
    try{return await done;}finally{clearTimeout(timer);signal?.removeEventListener('abort',stop);}
  }
  inspect(id){const j=this.jobs.get(id);if(!j)throw new Error('Unknown process (server restart ends live process tracking)');return {...j};}
  stop(id){const j=this.jobs.get(id);if(!j)throw new Error('Unknown process');try{process.kill(-j.pid,'SIGTERM');}catch{};const timer=setTimeout(()=>{if(j.status==='running')try{process.kill(-j.pid,'SIGKILL');}catch{}},5000);timer.unref?.();return {...j};}
  close(){for(const j of this.jobs.values())if(j.status==='running')this.stop(j.id);}
}

const fields=(properties,required=[])=>({type:'object',properties,required,additionalProperties:false});
const s={type:'string'},b={type:'boolean'},n={type:'number'};
export function messageRoute(settings,{channel,threadId}={}){
  const selected=channel||settings.channels.default||'web';
  if(selected==='instagram'&&settings.channels.instagram.enabled===true)return {channel:'instagram',threadId:threadId||settings.channels.instagram.ownerThreadUrl||null};
  return {channel:'web',threadId:selected==='instagram'?null:threadId||null};
}
export function storeAppMessage(store,{text,threadId=null,idempotencyKey,metadata={}}){
  // Local publication and its receipt commit together; replay uses the durable outbox key.
  return store.transaction(()=>{
    const out=store.queueOutbound({channel:'web',threadId,text,idempotencyKey,metadata});
    if(out.channel!=='web')throw new Error('Message idempotency key belongs to another channel');
    const externalId='out:'+out.id;
    const message=store.addMessage({channel:'web',role:'assistant',text:out.text,threadId:out.threadId,externalId,metadata:{...out.metadata,deliveryScope:'local-app-storage'}});
    const receipt={state:'confirmed',verified:true,channel:'web',scope:'local-app-storage',messageId:message.id,externalId,storedAt:message.createdAt};
    return {...store.updateOutbound(out.id,{state:'sent',receipt}),receipt};
  });
}
const TOOL_DEFINITIONS=[
 ['browser','browser', 'Read and operate a persistent full browser, tabs, uploads, downloads and screenshots.',fields({action:s,url:s,owner:s,selector:s,text:s,key:s,x:n,y:n,id:n,paths:{type:'array',items:s},screenshot:b},['action'])],
 ['terminal','terminal','Run a bash command, install packages, code, git, build, start servers. Set background for long-lived processes.',fields({command:s,cwd:s,background:b,timeoutSeconds:n},['command'])],
 ['process','terminal','Inspect or stop a background command.',fields({action:s,id:s},['action','id'])],
 ['files','files','Read/write/list files. Relative paths resolve in persistent workspace.',fields({action:s,path:s,content:s},['action','path'])],
 ['memory','memory','Search memory or original conversations using search-conversations; record sourced memories with confidence and kind; archive or forget.',fields({action:s,id:s,key:s,query:s,content:s,kind:s,confidence:n,sourceMessageIds:{type:'array',items:s},deleteSources:b},['action'])],
 ['goals','goals','Create/list ongoing responsibilities, create tasks, or steer existing tasks.',fields({action:s,id:s,title:s,description:s,goalId:s,priority:n,continuous:b,dedupKey:s},['action'])],
 ['messages','messages','Queue a real Instagram DM to owner or another thread. Never claim delivery before a receipt.',fields({threadId:s,text:s,idempotencyKey:s},['text'])],
 ['settings','settings','Read or update owner-delegated settings and custom instructions; owner permission agentCanChange applies.',fields({action:s,patch:{type:'object',additionalProperties:true}},['action'])],
 ['agents','agents','Delegate a persistent task in the shared task queue using the same available tool registry.',fields({title:s,description:s,goalId:s,dedupKey:s},['title'])]
];
export function toolDefinitions(settings,{conversationOnly=false}={}){
  if(conversationOnly)return [];
  return TOOL_DEFINITIONS.filter(([,cap])=>settings.permissions.capabilities[cap]??settings.permissions.default==='allow')
    .map(([name,,description,parameters])=>({type:'function',name,description:name==='messages'&&messageRoute(settings).channel==='web'?'Store a proactive message in the private app conversation. A confirmed receipt proves local app storage only; it does not prove push notification delivery, display, or reading.':description,parameters}));
}
export class ToolRegistry {
  constructor({store,browser,terminal,dataDir,onSettings}){Object.assign(this,{store,browser,terminal,dataDir,onSettings});this.extensions=new ExtensibleTools({dataDir,services:{store,browser,terminal},builtinNames:TOOL_DEFINITIONS.map(t=>t[0])});}
  async definitions(settings,options={}){
    const definitions=toolDefinitions(settings,options);
    if(options.conversationOnly)return definitions;
    return [...definitions,...await this.extensions.definitions(settings)];
  }
  async run(name,args={},context={}){
    const record=TOOL_DEFINITIONS.find(t=>t[0]===name);if(!record)return this.extensions.run(name,args,context);
    const c=this.store.getSettings();
    if(context.authority!=='owner'&&context.authority!=='autonomous')throw new Error('Only the owner can authorize tool actions');
    if(!(c.permissions.capabilities[record[1]]??c.permissions.default==='allow'))throw new Error('Capability disabled: '+record[1]);
    if(context.signal?.aborted)throw new Error('Action interrupted by owner');
    if(name==='browser')return this.browser.operate(args,{signal:context.signal});
    if(name==='terminal')return this.terminal.execute(args,context.signal);
    if(name==='process')return args.action==='stop'?this.terminal.stop(args.id):this.terminal.inspect(args.id);
    if(name==='files'){
      const target=path.resolve(path.join(this.dataDir,'workspace'),args.path);
      if(args.action==='read')return {path:target,content:(await readFile(target,'utf8')).slice(0,100000)};
      if(args.action==='write'){await mkdir(path.dirname(target),{recursive:true});await writeFile(target,args.content||'');return {path:target};}
      if(args.action==='list')return {path:target,items:await readdir(target)};
      if(args.action==='stat'){const info=await stat(target);return {path:target,size:info.size,isDirectory:info.isDirectory()};}
      throw new Error('Unsupported file action');
    }
    if(name==='memory'){
      if(args.action==='search-conversations')return this.store.searchMessages(args.query);
      if(args.action==='search'||args.action==='list')return this.store.listMemories({query:args.query});
      if(args.action==='archive')return this.store.archiveMemory(args.id);
      if(args.action==='forget')return this.store.forgetMemory(args.id,{deleteSources:args.deleteSources!==false});
      if(args.action==='save')return this.store.upsertMemory({...args,sourceMessageIds:args.sourceMessageIds||context.sourceMessageIds||[]});
      throw new Error('Unsupported memory action');
    }
    if(name==='goals'){
      if(args.action==='list')return {goals:this.store.listGoals(),tasks:this.store.listTasks()};
      if(args.action==='create')return this.store.addGoal(args);
      if(args.action==='task')return this.store.addTask(args);
      if(args.action==='steer')return this.store.steerTask(args.id,args);
      throw new Error('Unsupported goal action');
    }
    if(name==='messages'){
      const route=messageRoute(c,{channel:context.channel,threadId:args.threadId||context.threadId});
      const input={...route,text:args.text,idempotencyKey:args.idempotencyKey||context.actionId||crypto.randomUUID(),metadata:{taskId:context.taskId,proactive:true}};
      if(route.channel==='web')return storeAppMessage(this.store,input);
      if(!route.threadId)throw new Error('Instagram thread is not configured');
      return this.store.queueOutbound(input);
    }
    if(name==='settings'){
      if(args.action==='read')return c;
      if(!c.permissions.agentCanChange)throw new Error('Agent setting changes are disabled by the owner');
      if(args.patch?.channels?.instagram && ['ownerUsername','ownerThreadUrl'].some(k=>k in args.patch.channels.instagram) && context.authority!=='owner')throw new Error('Only a current owner instruction can change the owner identity');
      if(args.patch?.budget&&context.authority!=='owner')throw new Error('Only a current owner instruction can change the operating budget');
      if(args.action==='update')return this.onSettings(args.patch,'agent');
      throw new Error('Unsupported settings action');
    }
    if(name==='agents')return this.store.addTask({...args,metadata:{delegated:true,parentTaskId:context.taskId}});
  }
}
