import http from 'node:http';
import crypto from 'node:crypto';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Store } from './store.mjs';
import { DEFAULTS, deepMerge, validateSettings } from './defaults.mjs';
import { BrowserTools, TerminalTools, ToolRegistry } from './tools.mjs';
import { AssistantRuntime } from './runtime.mjs';
import { NativeCloudRuntime } from './native-cloud-runtime.mjs';
import { ModelLoginManager } from './model-login.mjs';
import { createModelAdapter } from './model.mjs';

const ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const MIME={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.png':'image/png'};
function equal(a,b){const x=Buffer.from(a||''),y=Buffer.from(b||'');return x.length===y.length&&crypto.timingSafeEqual(x,y);}
async function body(req){
  let data='';for await(const part of req){data+=part;if(Buffer.byteLength(data)>1024*1024)throw new Error('Request body too large');}
  return data?JSON.parse(data):{};
}
function json(res,status,value){res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});res.end(JSON.stringify(value));}
export async function createApplication({dataDir=process.env.DATA_DIR||path.join(ROOT,'.data'),token=process.env.APP_AUTH_TOKEN||'',store:providedStore,browser:providedBrowser,loginManager:providedLoginManager,runtimeFactory,nativeClient,engine=process.env.ASSISTANT_ENGINE||'legacy',seed=true}={}){
  if(!['legacy','codex-native'].includes(engine))throw new Error('Unsupported assistant engine');
  await mkdir(dataDir,{recursive:true});
  const store=providedStore||new Store(dataDir,{defaults:DEFAULTS});
  const browser=providedBrowser||new BrowserTools(dataDir),terminal=new TerminalTools(path.join(dataDir,'workspace'));
  const loginManager=providedLoginManager||new ModelLoginManager({codeHome:path.join(dataDir,'codex')});
  let runtime;
  const onSettings=(patch,actor='owner')=>{
    if(actor==='agent'&&!store.getSettings().permissions.agentCanChange)throw new Error('Agent settings changes are disabled');
    validateSettings(deepMerge(store.getSettings(),patch));
    const settings=store.updateSettings(patch,actor);
    runtime?.settingsChanged();return settings;
  };
  const tools=new ToolRegistry({store,browser,terminal,dataDir,onSettings});
  runtime=runtimeFactory?runtimeFactory({store,tools,browser}):engine==='codex-native'
    ?new NativeCloudRuntime({store,tools,browser,client:nativeClient,dataDir,workspace:path.join(dataDir,'workspace')})
    :new AssistantRuntime({store,tools,browser});
  if(seed&&!store.getRuntimeState?.('source-imported')){
    const source=await readFile(path.join(ROOT,'docs','source-transcript.txt'),'utf8').catch(()=>'');
    const entries=[...source.matchAll(/### (\d+) (사용자|ChatGPT(?: 진행 안내)?)\s+\x60\x60\x60text\n([\s\S]*?)\n\x60\x60\x60/g)];
    for(const entry of entries)store.addMessage({channel:'space-import',externalId:'page_3dbe1b11044481918d703e85554f9e94:'+entry[1],role:entry[2]==='사용자'?'user':'assistant',text:entry[3],metadata:{authority:entry[2]==='사용자'?'historical_owner':'historical_assistant',sourceUrl:'https://chatgpt.com/space/page_3dbe1b11044481918d703e85554f9e94'}});
    store.setRuntimeState?.('source-imported',true);
    store.event('source.imported',{message:'스페이스 대화 원문을 가져왔어. 현재 로그인·배포 완료 사실로 취급하지 않아.',count:entries.length});
  }
  if(seed&&!store.getRuntimeState?.('latest-requirements-imported')){
    const requirements=JSON.parse(await readFile(path.join(ROOT,'docs','latest-requirements.json'),'utf8'));
    for(const entry of requirements.messages){
      const message=store.addMessage({channel:'requirements-import',externalId:requirements.source+':'+entry.key,role:'user',text:entry.text,metadata:{authority:'historical_owner',acceptedRequirement:true}});
      store.upsertMemory({key:'owner-requirement:'+entry.key,content:entry.text,kind:'fact',confidence:1,sourceMessageIds:[message.id]});
    }
    for(const goal of requirements.goals)store.addGoal(goal);
    store.setRuntimeState?.('latest-requirements-imported',true);
    store.event('requirements.imported',{message:'이번 대화의 이름·권한·모델·무료 운영 요구사항을 기억에 반영했어.'});
  }
  if(seed){
    const requirements=JSON.parse(await readFile(path.join(ROOT,'docs','latest-requirements.json'),'utf8'));
    for(const revision of requirements.revisions||[]){
      const onceKey='requirements-revision:'+revision.id;
      if(store.getRuntimeState(onceKey))continue;
      for(const entry of revision.messages||[]){
        const message=store.addMessage({channel:'requirements-import',externalId:requirements.source+':'+revision.id+':'+entry.key,role:'user',text:entry.text,metadata:{authority:'historical_owner',acceptedRequirement:true,revisionId:revision.id}});
        const key='owner-requirement:'+entry.key;
        if(!store.listMemories().some(m=>m.key===key&&m.sourceMessageIds.includes(message.id)))store.upsertMemory({key,content:entry.text,kind:'fact',confidence:1,sourceMessageIds:[message.id]});
      }
      // Migrate only an untouched imported goal, preserving owner-written goals.
      // Run before the revision's once flag; exact matching makes retries harmless.
      for(const update of revision.goalUpdates||[]){
        for(const goal of store.listGoals()){
          if(goal.title===update.matchTitle&&goal.description===update.matchDescription){
            store.updateGoal(goal.id,update.patch||{});
          }
        }
      }
      validateSettings(deepMerge(store.getSettings(),revision.settings||{}));
      store.updateSettings(revision.settings||{},'owner-request:'+revision.id,{onceKey});
      store.event('requirements.revised',{message:'최신 주인 요청을 설정과 기억에 반영했어.',revisionId:revision.id});
    }
  }
  const sessionSecret=crypto.randomBytes(32).toString('hex');
  const sessionCookie=crypto.createHmac('sha256',sessionSecret).update(token||'local-owner').digest('hex');
  const authorized=req=>!token||equal(req.headers.authorization?.replace(/^Bearer /,''),token)||equal(req.headers['x-app-token'],token)||req.headers.cookie?.split(';').some(c=>equal(c.trim(),'assistant_session='+sessionCookie));
  const server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,'http://localhost');
    try{
      if(url.pathname==='/api/health'&&req.method==='GET'){json(res,200,{ok:true,service:'autonomous-assistant',state:runtime.state().state,uptimeSeconds:runtime.state().uptimeSeconds});return;}
      if(url.pathname==='/api/session'&&req.method==='POST'){
        const input=await body(req);
        if(token&&!equal(input.token,token)){json(res,401,{error:'관리 토큰이 일치하지 않아'});return;}
        res.setHeader('set-cookie','assistant_session='+sessionCookie+'; HttpOnly; SameSite=Strict; Path=/');
        json(res,200,{ok:true});return;
      }
      if(url.pathname.startsWith('/api/')){
        if(!authorized(req)){json(res,401,{error:'관리 화면 접근 토큰이 필요해',code:'AUTH_REQUIRED'});return;}
        const origin=req.headers.origin;
        if(origin&&!['GET','HEAD'].includes(req.method)&&new URL(origin).host!==req.headers.host){json(res,403,{error:'Cross-origin mutation rejected'});return;}
        if(url.pathname==='/api/state'&&req.method==='GET'){
          json(res,200,{engine,settings:store.getSettings(),status:runtime.state(),modelLogin:loginManager.status(),goals:store.listGoals(),tasks:store.listTasks(),logs:store.listEvents(100),memories:store.listMemories({query:url.searchParams.get('q')||undefined,includeArchived:true}),messages:store.listMessages(100),counts:store.counts(),outbound:store.listOutbound(),readReceipts:store.listReads()});return;
        }
        if(url.pathname==='/api/model/login'&&req.method==='POST'){
          if(!store.getSettings().permissions.capabilities.settings)throw new Error('모델 연결 설정 권한이 꺼져 있어');
          onSettings({autonomy:{enabled:false}},'owner');
          json(res,202,loginManager.start({command:store.getSettings().model.command||'codex'}));return;
        }
        if(url.pathname==='/api/model/login'&&req.method==='GET'){
          const login=loginManager.status();
          if(login.state==='completed'&&login.needsHealthCheck){
            let health;
            if(engine==='codex-native'&&typeof runtime.authenticationChanged==='function'){
              const refreshed=await runtime.authenticationChanged();
              if(refreshed.deferred){json(res,200,loginManager.status());return;}
              health=await runtime.refreshHealth();
            }else{
              const model=createModelAdapter({...store.getSettings().model,provider:'codex-cli',codeHome:path.join(dataDir,'codex')},{browser});
              health=await model.health();
            }
            loginManager.state={...login,needsHealthCheck:false,authenticated:health.authenticated===true,state:health.authenticated?'completed':'failed',errorCode:health.authenticated?null:'DEVICE_LOGIN_NOT_VERIFIED'};
            runtime.status.model={...health,verified:health.configurationVerified===true,actualModel:health.actualConfigured?.model,actualEffort:health.actualConfigured?.reasoningEffort,actualServiceTier:health.actualConfigured?.serviceTier};
            store.event('model.login.checked',{message:health.authenticated?'ChatGPT 구독 계정 연결을 확인했어.':'구독 계정 연결을 확인하지 못했어.'});
          }
          json(res,200,loginManager.status());return;
        }
        if(url.pathname==='/api/model/login'&&req.method==='DELETE'){json(res,200,loginManager.cancel());return;}
        if(url.pathname==='/api/settings'&&['PUT','PATCH'].includes(req.method)){json(res,200,{settings:onSettings(await body(req),'owner')});return;}
        if(url.pathname==='/api/control'&&req.method==='POST'){
          const input=await body(req);
          if(input.action==='pause')onSettings({autonomy:{enabled:false}});
          if(input.action==='resume')onSettings({autonomy:{enabled:true}});
          runtime.control(input.action);json(res,200,{status:runtime.state()});return;
        }
        if(url.pathname==='/api/chat'&&req.method==='POST'){
          const input=await body(req);if(typeof input.message!=='string'||!input.message.trim())throw new Error('메시지를 입력해');
          const threadId=input.conversationId??null;
          if(threadId!==null&&(typeof threadId!=='string'||! /^[A-Za-z0-9._:-]{1,160}$/.test(threadId)))throw new Error('Invalid conversation id');
          let externalId;
          if(input.clientMessageId!==undefined){
            if(typeof input.clientMessageId!=='string'||! /^[A-Za-z0-9._:-]{1,160}$/.test(input.clientMessageId))throw new Error('Invalid client message id');
            externalId='app:'+input.clientMessageId;
            const existing=store.getMessageByExternalId('web',externalId);
            if(existing){
              if(existing.role!=='user'||existing.text!==input.message||(existing.threadId??null)!==threadId){json(res,409,{error:'Message id belongs to different content',code:'MESSAGE_ID_CONFLICT'});return;}
              json(res,202,{message:existing,status:runtime.state(),duplicate:true});return;
            }
          }
          const message=runtime.receive({channel:'web',role:'user',text:input.message,externalId,threadId,authority:'owner',metadata:{authority:'owner'}});
          json(res,202,{message,status:runtime.state()});return;
        }
        if(url.pathname==='/api/goals'&&req.method==='POST'){json(res,201,{goal:store.addGoal(await body(req))});runtime.nextHeartbeat=0;return;}
        const goalMatch=/^\/api\/goals\/([^/]+)$/.exec(url.pathname);
        if(goalMatch&&req.method==='PATCH'){json(res,200,{goal:store.updateGoal(goalMatch[1],await body(req))});runtime.nextHeartbeat=0;return;}
        if(url.pathname==='/api/memories'&&req.method==='POST'){
          const input=await body(req);
          const source=store.addMessage({channel:'web',role:'user',text:input.content,metadata:{authority:'owner',manualMemory:true}});
          json(res,201,{memory:store.upsertMemory({...input,key:input.key||crypto.randomUUID(),sourceMessageIds:[source.id],confidence:input.confidence??1})});return;
        }
        const taskMatch=/^\/api\/tasks\/([^/]+)$/.exec(url.pathname);
        if(taskMatch&&req.method==='PATCH'){
          const patch=await body(req);runtime.currentAbort?.abort('Owner task edit');
          const task=store.steerTask(taskMatch[1],patch);
          if(task&&typeof runtime.taskChanged==='function')await runtime.taskChanged(task.id);
          json(res,200,{task});return;
        }
        const memoryMatch=/^\/api\/memories\/([^/]+)$/.exec(url.pathname);
        if(memoryMatch&&req.method==='PATCH'){
          const input=await body(req),existing=store.listMemories({includeArchived:true}).find(m=>m.id===memoryMatch[1]);if(!existing)throw new Error('기억을 찾을 수 없어');
          const correction=typeof input.content==='string'&&input.content!==existing.content?store.addMessage({channel:'web',role:'user',text:input.content,metadata:{authority:'owner',manualMemory:true,correctsMemoryId:existing.id}}):null;
          const memory=input.archived?store.archiveMemory(existing.id):store.upsertMemory({...existing,...input,id:undefined,key:existing.key,sourceMessageIds:[...(existing.sourceMessageIds||[]),...(correction?[correction.id]:[])]});
          json(res,200,{memory});return;
        }
        if(memoryMatch&&req.method==='DELETE'){json(res,200,{result:store.forgetMemory(memoryMatch[1],{deleteSources:true})});return;}
        if(url.pathname==='/api/feedback'&&req.method==='POST'){json(res,201,{feedback:store.addFeedback(await body(req))});return;}
        if(url.pathname.startsWith('/api/browser')&&req.method==='POST'){
          const input=await body(req);
          if(!store.getSettings().permissions.capabilities.browser)throw new Error('브라우저 권한이 꺼져 있어');
          const open=input.action==='instagram-login'?'instagram':input.action==='chatgpt-login'?'chatgpt':null;
          if(open==='chatgpt')onSettings({autonomy:{enabled:false}},'owner');
          const args=open?{owner:open,action:'open',url:open==='instagram'?'https://www.instagram.com/':store.getSettings().model.url,screenshot:true}:url.pathname.endsWith('/screenshot')?{...input,action:'screenshot'}:{...input,screenshot:true};
          const result=await browser.operate(args);
          json(res,200,{...result,url:result.screenshot||result.url,pageUrl:result.url});return;
        }
        if(/^\/api\/screenshots\/(chatgpt|instagram|general)\.png$/.test(url.pathname)&&req.method==='GET'){
          const file=path.join(dataDir,'screenshots',path.basename(url.pathname));const content=await readFile(file);res.writeHead(200,{'content-type':'image/png','cache-control':'no-store'});res.end(content);return;
        }
        json(res,404,{error:'API not found'});return;
      }
      if(!['GET','HEAD'].includes(req.method)){json(res,405,{error:'Method not allowed'});return;}
      const relative=decodeURIComponent(url.pathname)==='/'?'index.html':decodeURIComponent(url.pathname).slice(1);
      const filename=path.resolve(ROOT,'public',relative);
      if(!filename.startsWith(path.join(ROOT,'public')+path.sep)){json(res,403,{error:'Invalid path'});return;}
      const data=await readFile(filename);
      res.writeHead(200,{'content-type':MIME[path.extname(filename)]||'application/octet-stream','cache-control':'no-cache','x-content-type-options':'nosniff','content-security-policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"});res.end(data);
    }catch(error){json(res,error.code==='ENOENT'?404:400,{error:error.message,code:error.code||'REQUEST_FAILED'});}
  });
  return {server,store,browser,tools,runtime,onSettings,loginManager,async close(){loginManager.cancel();await runtime.stop();await new Promise(r=>server.listening?server.close(r):r());store.close();}};
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const host=process.env.HOST||'127.0.0.1',port=Number(process.env.PORT||8080);
  if(!['127.0.0.1','localhost','::1'].includes(host)&&!process.env.APP_AUTH_TOKEN)throw new Error('Remote binding requires APP_AUTH_TOKEN. Configure it before exposing the full-permission assistant.');
  const app=await createApplication();
  await new Promise(r=>app.server.listen(port,host,r));
  process.stdout.write('자율비서 관리 화면: http://'+host+':'+port+'\n');
  await app.runtime.start();
  let stopping=false;
  for(const event of ['SIGINT','SIGTERM'])process.on(event,async()=>{if(stopping)return;stopping=true;await app.close();process.exit(0);});
}
