import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { chromium } from 'playwright';

const source = await readFile(new URL('../app/src/main/assets/work-bridge.js', import.meta.url), 'utf8');
let browser;
before(async () => { browser = await chromium.launch({ headless: true, executablePath: '/usr/bin/chromium', args: ['--no-sandbox'] }); });
after(async () => { await browser?.close(); });

// These fixtures verify DOM transport semantics. They are not a captured live Work
// UI, account authentication, network upload or Android background execution test.
const fixture = `<!doctype html><html><head><meta charset="utf-8"></head><body>
<header>
 <button data-testid="user-menu-button" aria-label="Open profile menu">Profile</button>
 <button id="work" role="tab" aria-selected="true">Work</button>
 <button id="temporary" aria-pressed="true">Temporary chat</button>
 <button data-testid="model-switcher-dropdown-button">GPT-6.1 Sol</button>
 <div role="group" aria-label="Reasoning effort"><button id="effort" role="menuitemradio" aria-checked="true">Medium</button></div>
 <div role="group" aria-label="Speed"><button id="speed" role="menuitemradio" aria-checked="true">Fast</button></div>
</header>
<main id="turns"></main>
<form data-chatgpt-composer>
 <input type="file" multiple data-testid="upload-photos-input" hidden>
 <textarea id="prompt-textarea"></textarea>
 <button type="submit" data-testid="send-button">Send</button>
 <button type="button" data-testid="stop-button" hidden>Stop</button>
 <div id="attachments"></div>
</form>
<script>
window.events = []; window.clicks = 0; window.autoAck = true; window.uploadMode = 'ready';
window.HoyoungHost = {postMessage: text => window.events.push(JSON.parse(text))};
const editor = document.querySelector('#prompt-textarea');
const form = document.querySelector('form');
const stop = document.querySelector('[data-testid="stop-button"]');
form.addEventListener('submit', event => {
 event.preventDefault(); window.clicks++;
 if (!window.autoAck) return;
 const group = document.createElement('article');
 group.dataset.turnKey = 'turn-' + window.clicks;
 const user = document.createElement('div'); user.setAttribute('data-user-message-bubble', '');
 const content = document.createElement('p'); content.setAttribute('data-search-result-target', ''); content.textContent = editor.value;
 user.append(content); group.append(user); document.querySelector('#turns').append(group);
 window.lastGroup = group; editor.value = ''; stop.hidden = false;
});
window.answer = function(text, footer = true) {
 const assistant = document.createElement('section'); assistant.setAttribute('data-conversation-role', 'assistant');
 const body = document.createElement('div'); body.className = 'markdown'; body.textContent = text;
 assistant.append(body); window.lastGroup.append(assistant);
 if (footer) window.footer(); stop.hidden = true;
 return assistant;
};
window.footer = function() {
 const footer = document.createElement('div'); footer.className = 'turn-action-controls';
 const copy = document.createElement('button'); copy.type = 'button'; copy.dataset.testid = 'copy-turn-action-button'; copy.textContent = 'Copy';
 footer.append(copy); window.lastGroup.append(footer);
};
document.querySelector('#work').addEventListener('click', function() {this.setAttribute('aria-selected', 'true')});
document.querySelector('#temporary').addEventListener('click', function() {this.setAttribute('aria-pressed', 'true')});
document.querySelector('input[type="file"]').addEventListener('change', function() {
 window.fileChanges = (window.fileChanges || 0) + 1;
 for (const file of this.files) {
   const card = document.createElement('div'); card.setAttribute('role', 'group'); card.setAttribute('aria-label', file.name);
   if (window.uploadMode === 'ready') card.setAttribute('aria-busy', 'false');
   if (window.uploadMode === 'pending') {card.setAttribute('aria-busy', 'true'); const progress = document.createElement('progress'); progress.setAttribute('role', 'progressbar'); card.append(progress)}
   card.textContent = file.name; document.querySelector('#attachments').append(card);
 }
});
</script></body></html>`;

async function pageFixture(callback, { setup = '', url = 'https://chatgpt.com/?temporary-chat=true' } = {}) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await context.route('**/*', route => route.fulfill({ status: 200, contentType: 'text/html', body: fixture }));
  await page.clock.install();
  try {
    await page.goto(url);
    if (setup) await page.evaluate(setup);
    await page.addScriptTag({ content: source });
    await callback(page);
  } finally { await context.close(); }
}
const inspect = page => page.evaluate(() => window.HoyoungWorkBridge.inspect());
const send = (page, extra = {}) => page.evaluate(payload => window.HoyoungWorkBridge.send(payload), { requestId: 'request-one', text: '우리 작업 이어 해줘', requiredModel: 'gpt-6.1-sol', requiredEffort: 'medium', requiredSpeed: 'fast', ...extra });
const advance = (page, milliseconds) => page.clock.runFor(milliseconds);
const events = page => page.evaluate(() => window.events);

test('mobile combined model and effort label remains readable with a collapsed signed-in sidebar', async () => {
  await pageFixture(async page => {
    const current = await inspect(page);
    assert.equal(current.state.authenticated, true);
    assert.equal(current.state.model, 'gpt-6.1-sol');
    assert.equal(current.state.effort, 'medium');
    assert.equal(current.code, 'ready');
    await page.evaluate(() => {
      const button = document.createElement('button'); button.textContent = '로그인'; document.querySelector('header').append(button);
    });
    assert.equal((await inspect(page)).code, 'authentication_required');
  }, { setup: `document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent='GPT-6.1 Sol Medium'; document.querySelector('#effort').remove(); document.querySelector('[data-testid="user-menu-button"]').hidden=true;` });
});

test('mobile Fast switch is read explicitly, may turn off, and survives closing its observed model menu', async () => {
  const mobileMenu = `
    document.querySelector('#effort').remove(); document.querySelector('#speed').remove();
    const picker = document.querySelector('[data-testid="model-switcher-dropdown-button"]');
    picker.textContent='GPT-6.1 Sol Medium'; picker.id='mobile-picker'; picker.setAttribute('aria-haspopup','menu'); picker.setAttribute('aria-controls','mobile-menu');
    window.modelMenuOpens=0; window.fastChanges=0; window.isFast=true;
    picker.addEventListener('click',()=>{
      window.modelMenuOpens++; const menu=document.createElement('div'); menu.id='mobile-menu'; menu.setAttribute('role','menu'); menu.setAttribute('aria-labelledby','mobile-picker');
      const fast=document.createElement('button'); fast.type='button'; fast.setAttribute('role','switch'); fast.textContent='Fast'; fast.setAttribute('aria-checked',String(window.isFast));
      fast.addEventListener('click',()=>{window.fastChanges++;window.isFast=!window.isFast;fast.setAttribute('aria-checked',String(window.isFast));});menu.append(fast);document.body.append(menu);
    });
    document.querySelector('#prompt-textarea').addEventListener('focus',()=>document.querySelector('#mobile-menu')?.remove());
  `;
  await pageFixture(async page => {
    assert.equal((await inspect(page)).code, 'speed_unverified');
    await page.evaluate(() => window.HoyoungWorkBridge.ensurePreferences({requiredModel:'gpt-6.1-sol', requiredEffort:'medium', requiredSpeed:'standard'}));
    await advance(page, 1_000);
    assert.equal((await inspect(page)).state.speed, 'standard');
    assert.equal((await inspect(page)).code, 'ready');
    assert.equal(await page.evaluate(() => window.fastChanges), 1);
    const submitted = await send(page, { requiredSpeed:'standard' });
    assert.equal(submitted.ok, true); await advance(page, 500); assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await inspect(page)).state.speed, 'standard');
  }, { setup: mobileMenu });
  await pageFixture(async page => {
    await page.evaluate(() => window.HoyoungWorkBridge.ensurePreferences({requiredModel:'gpt-6.1-sol', requiredEffort:'medium', requiredSpeed:'fast'}));
    await advance(page, 1_000);
    assert.equal((await inspect(page)).code, 'ready');
    assert.equal(await page.evaluate(() => window.fastChanges), 0);
    assert.equal(await page.evaluate(() => window.modelMenuOpens), 1);
  }, { setup: mobileMenu });
});

test('mobile combined labels never infer speed, temporary mode or Work selection from the desired settings', async () => {
  await pageFixture(async page => {
    assert.equal((await inspect(page)).code, 'temporary_unverified');
    assert.equal((await send(page)).ok, false);
    await page.evaluate(() => document.querySelector('#temporary').setAttribute('aria-pressed','true'));
    assert.equal((await inspect(page)).code, 'speed_unverified');
    assert.equal((await send(page)).ok, false);assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent='GPT-6.1 Sol Medium';document.querySelector('#effort').remove();document.querySelector('#speed').remove();document.querySelector('#temporary').setAttribute('aria-pressed','false');` });
});

// Explicit, observed menu choices with selected-state acknowledgements. These
// fixtures do not assert that a real account exposes these particular options.
const preferenceMenus = `
window.preferenceClicks = []; window.preferenceDelay = 0; window.preferenceAcknowledge = true;
const preferenceModel = document.querySelector('[data-testid="model-switcher-dropdown-button"]');
preferenceModel.id = 'preference-model';
preferenceModel.setAttribute('aria-haspopup', 'menu');
for (const dimension of ['effort', 'speed']) {
 const previous = document.querySelector('#' + dimension), trigger = document.createElement('button');
 trigger.id = dimension; trigger.type = 'button'; trigger.setAttribute('aria-haspopup', 'menu');
 trigger.setAttribute('aria-label', dimension === 'effort' ? 'Reasoning effort: Medium' : 'Speed: Fast');
 trigger.textContent = dimension === 'effort' ? 'Medium' : 'Fast'; previous.replaceWith(trigger);
}
window.showPreferenceMenu = function(dimension) {
 window.preferenceClicks.push('open:' + dimension);
 const menu = document.createElement('div'); menu.setAttribute('role', 'menu'); menu.dataset.preferenceDimension = dimension;
 const trigger = dimension === 'model' ? preferenceModel : document.querySelector('#' + dimension);
 menu.id = 'preference-menu-' + dimension; menu.setAttribute('aria-labelledby', trigger.id); trigger.setAttribute('aria-controls', menu.id);
 const values = dimension === 'model' ? ['GPT-6.1 Sol', 'GPT-6.2 Sol'] : dimension === 'effort' ? ['Light', 'Medium', 'High', 'Extra High'] : ['Fast', 'Standard'];
 for (const value of values) {
  const option = document.createElement('button'); option.type = 'button'; option.setAttribute('role', 'menuitemradio'); option.textContent = value;
  option.setAttribute('aria-checked', String(trigger.textContent === value));
  option.addEventListener('click', function() {
   window.preferenceClicks.push(dimension + ':' + value);
   if (!window.preferenceAcknowledge) return;
   const acknowledge = () => { trigger.textContent = value; if (dimension !== 'model') trigger.setAttribute('aria-label', (dimension === 'effort' ? 'Reasoning effort: ' : 'Speed: ') + value); menu.remove(); };
   if (window.preferenceDelay) setTimeout(acknowledge, window.preferenceDelay); else acknowledge();
  }); menu.append(option);
 } document.querySelector('header').append(menu);
};
preferenceModel.addEventListener('click', () => window.showPreferenceMenu('model'));
document.querySelector('#effort').addEventListener('click', () => window.showPreferenceMenu('effort'));
document.querySelector('#speed').addEventListener('click', () => window.showPreferenceMenu('speed'));
`;
const ensure = (page, effort, speed, model = 'gpt-6.1-sol') => page.evaluate(preferences => window.HoyoungWorkBridge.ensurePreferences(preferences), {
  requiredModel: model, requiredEffort: effort, requiredSpeed: speed
});
const preferenceClicks = page => page.evaluate(() => window.preferenceClicks);
const usagePanel = `
const usage=document.createElement('section');usage.id='usage-panel';usage.setAttribute('role','region');
usage.innerHTML='<h1>Usage</h1><p>Pro plan</p><h2>Work / Codex</h2><section id="weekly-pool"><h3>Weekly usage</h3><p id="usage-metric">82.5% remaining</p><p>Resets: <time></time></p></section>';
const reset=usage.querySelector('time');reset.setAttribute('datetime',new Date(Date.now()+3*24*60*60*1000).toISOString());reset.textContent=reset.getAttribute('datetime');
document.body.append(usage);
`;
const captureUsage = page => page.evaluate(() => window.HoyoungWorkBridge.captureUsageObservation({accountScope:'owner-selected-account',captureNonce:'a'.repeat(32)}));

async function nativeAuthorization(page, mode) {
  await page.evaluate(mode => {
    window.nativeAuthorizationMode = mode; window.nativePolicyAllowed = true;
    window.authorizationReplies = []; window.nativePolicyChecks = 0;
    window.HoyoungHost.postMessage = raw => {
      const event = JSON.parse(raw); window.events.push(event);
      if (event.code !== 'dispatch_authorization_requested' || window.nativeAuthorizationMode === 'manual') return;
      window.nativePolicyChecks++;
      const allowed = window.nativeAuthorizationMode === 'allow' && window.nativePolicyAllowed;
      window.authorizationReplies.push(window.HoyoungWorkBridge.authorizeDispatch(event.requestId, event.nonce, allowed, event.payloadDigest));
    };
  }, mode);
}

test('Work and Temporary require positive selected DOM evidence, not URL parameters or unrelated transcript words', async () => {
  await pageFixture(async page => {
    assert.equal((await inspect(page)).code, 'work_unverified');
    assert.equal((await send(page)).ok, false);
    await page.evaluate(() => document.querySelector('#work').setAttribute('aria-selected', 'true'));
    assert.equal((await inspect(page)).code, 'temporary_unverified');
    await page.evaluate(() => document.querySelector('#temporary').setAttribute('aria-pressed', 'true'));
    assert.equal((await inspect(page)).code, 'ready');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('#work').setAttribute('aria-selected', 'false'); document.querySelector('#temporary').setAttribute('aria-pressed', 'false'); document.querySelector('#turns').innerHTML='<article data-turn-key="old"><div data-user-message-bubble>Work Temporary chat</div></article>'` });
});

test('selectWork clicks only identifiable Work/Temporary controls and observes their real selected state', async () => {
  await pageFixture(async page => {
    const work = await page.evaluate(() => window.HoyoungWorkBridge.selectWork());
    assert.equal(work.code, 'work_selection_pending');
    assert.equal(work.state.workSelected, true);
    const temporary = await page.evaluate(() => window.HoyoungWorkBridge.selectWork());
    assert.equal(temporary.code, 'temporary_selection_pending');
    assert.equal(temporary.state.temporary, true);
    assert.equal((await inspect(page)).ok, true);
  }, { setup: `document.querySelector('#work').setAttribute('aria-selected', 'false'); document.querySelector('#temporary').setAttribute('aria-pressed', 'false')` });
});

test('a guest composer, a signed-out page and unverified preference settings cannot dispatch', async () => {
  await pageFixture(async page => {
    assert.equal((await inspect(page)).state.authenticated, null);
    assert.equal((await send(page)).code, 'authentication_unverified');
    await page.evaluate(() => {const login = document.createElement('button'); login.textContent = 'Log in'; document.querySelector('header').append(login)});
    assert.equal((await inspect(page)).state.authenticated, false);
    assert.equal((await send(page)).code, 'authentication_required');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('[data-testid="user-menu-button"]').remove()` });
  await pageFixture(async page => {
    assert.equal((await send(page)).code, 'effort_unverified');
    await page.evaluate(() => document.querySelector('#effort').textContent = 'Medium');
    await page.evaluate(() => document.querySelector('#speed').setAttribute('aria-checked', 'false'));
    assert.equal((await send(page)).code, 'speed_unverified');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('#effort').textContent='High'` });
});

test('configured future model, effort and speed require exact visible choices and never fall back to defaults', async () => {
  await pageFixture(async page => {
    const preferences = { requiredModel: 'gpt-6.2-sol', requiredEffort: 'xhigh', requiredSpeed: 'standard' };
    const configured = await page.evaluate(prefs => window.HoyoungWorkBridge.configurePreferences(prefs), preferences);
    assert.equal(configured.code, 'model_unverified');
    assert.equal(configured.state.model, 'gpt-6.1-sol', 'required model does not rewrite observed evidence');
    assert.equal((await send(page, preferences)).code, 'model_unverified');
    assert.equal(await page.evaluate(() => window.clicks), 0);
    await page.evaluate(() => {
      const model = document.querySelector('[data-testid="model-switcher-dropdown-button"]'); model.textContent = 'GPT-6.2 Sol'; model.dataset.state = 'closed';
      document.querySelector('#effort').textContent = 'Extra high';
      document.querySelector('#speed').textContent = 'Standard';
    });
    const observed = await inspect(page);
    assert.equal(observed.code, 'ready');
    assert.equal(observed.state.model, 'gpt-6.2-sol'); assert.equal(observed.state.effort, 'xhigh'); assert.equal(observed.state.speed, 'standard');
    assert.equal((await send(page, preferences)).code, 'dispatch_pending');
    await advance(page, 250);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await events(page)).some(event => event.type === 'accepted'), true);
  });
  await pageFixture(async page => {
    const preferences = { requiredModel: 'gpt-7.4-orion', requiredEffort: 'max', requiredSpeed: 'fast' };
    await page.evaluate(prefs => window.HoyoungWorkBridge.configurePreferences(prefs), preferences);
    assert.equal((await inspect(page)).code, 'ready');
    assert.equal((await send(page, preferences)).code, 'dispatch_pending'); await advance(page, 250);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  }, { setup: `document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent='GPT-7.4 Orion'; document.querySelector('#effort').textContent='Max'` });
});

test('Light is verified as low; guarded request routing switches observed Light/Standard back to Medium/Fast without sending', async () => {
  await pageFixture(async page => {
    await ensure(page, 'low', 'standard'); await advance(page, 750);
    const background = await inspect(page);
    assert.equal(background.code, 'ready'); assert.equal(background.state.effort, 'low'); assert.equal(background.state.speed, 'standard');
    assert.deepEqual(await preferenceClicks(page), ['open:effort', 'effort:Light', 'open:speed', 'speed:Standard']);
    await ensure(page, 'medium', 'fast'); await advance(page, 750);
    const owner = await inspect(page);
    assert.equal(owner.code, 'ready'); assert.equal(owner.state.effort, 'medium'); assert.equal(owner.state.speed, 'fast');
    assert.deepEqual(await preferenceClicks(page), ['open:effort', 'effort:Light', 'open:speed', 'speed:Standard', 'open:effort', 'effort:Medium', 'open:speed', 'speed:Fast']);
    for (let i = 0; i < 8; i++) await ensure(page, 'medium', 'fast');
    await advance(page, 2_000); assert.equal((await preferenceClicks(page)).length, 8); assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: preferenceMenus });
});

test('guarded selection supports explicit future model and Extra High only after actual selected DOM acknowledgements', async () => {
  await pageFixture(async page => {
    await ensure(page, 'xhigh', 'fast', 'gpt-6.2-sol'); await advance(page, 750);
    const selected = await inspect(page);
    assert.equal(selected.code, 'ready'); assert.equal(selected.state.model, 'gpt-6.2-sol'); assert.equal(selected.state.effort, 'xhigh');
    assert.deepEqual(await preferenceClicks(page), ['open:model', 'model:GPT-6.2 Sol', 'open:effort', 'effort:Extra High']);
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: preferenceMenus });
});

test('read-only configuration does not select settings; unknown selected evidence and unsupported choices never fall back or repeatedly toggle', async () => {
  await pageFixture(async page => {
    await page.evaluate(() => window.HoyoungWorkBridge.configurePreferences({requiredModel:'gpt-6.1-sol',requiredEffort:'low',requiredSpeed:'standard'}));
    await advance(page, 2_000); assert.deepEqual(await preferenceClicks(page), []); assert.equal((await inspect(page)).state.effort, 'medium');
    await page.evaluate(() => document.querySelector('#effort').setAttribute('aria-label', 'Thinking setting unavailable'));
    await ensure(page, 'low', 'standard'); await advance(page, 2_000);
    assert.deepEqual(await preferenceClicks(page), []); assert.equal((await inspect(page)).state.effort, null);
  }, { setup: preferenceMenus });
  await pageFixture(async page => {
    await ensure(page, 'ultra', 'standard'); await advance(page, 8_000);
    assert.deepEqual(await preferenceClicks(page), ['open:effort']);
    assert.equal((await inspect(page)).ok, false); assert.equal((await inspect(page)).state.effort, 'medium');
    for (let i = 0; i < 8; i++) await ensure(page, 'ultra', 'standard');
    await advance(page, 8_000); assert.deepEqual(await preferenceClicks(page), ['open:effort']);
    assert.equal((await send(page, {requiredEffort:'ultra',requiredSpeed:'standard'})).ok, false); assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: preferenceMenus });
});

test('preference selection preserves owner drafts, attachments, busy/unauthenticated/non-Work state and active uncertain outcomes', async () => {
  const blockedSetups = [
    `document.querySelector('#prompt-textarea').value='소중한 내 초안'`,
    `document.querySelector('#attachments').innerHTML='<div role="group" aria-label="photo.jpg">photo.jpg</div>'`,
    `document.querySelector('[data-testid="stop-button"]').hidden=false`,
    `document.querySelector('[data-testid="user-menu-button"]').remove()`,
    `document.querySelector('#work').setAttribute('aria-selected','false')`,
    `document.querySelector('#temporary').setAttribute('aria-pressed','false')`
  ];
  for (const setup of blockedSetups) await pageFixture(async page => {
    const original = await page.locator('#prompt-textarea').inputValue();
    await ensure(page, 'low', 'standard'); await advance(page, 1_000);
    assert.deepEqual(await preferenceClicks(page), []); assert.equal(await page.locator('#prompt-textarea').inputValue(), original);
  }, { setup: preferenceMenus + setup });
  await pageFixture(async page => {
    await page.evaluate(() => {window.autoAck=false}); await send(page); await advance(page, 31_000);
    assert.equal((await inspect(page)).phase, 'uncertain');
    await ensure(page, 'low', 'standard'); await advance(page, 1_000); assert.deepEqual(await preferenceClicks(page), []);
  }, { setup: preferenceMenus });
});

test('an active dispatch keeps immutable request preferences while next-route requirements change', async () => {
  await pageFixture(async page => {
    await send(page); await ensure(page, 'low', 'standard'); await advance(page, 500);
    assert.deepEqual(await preferenceClicks(page), []);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await events(page)).filter(event => event.type === 'accepted').length, 1);
    assert.equal((await inspect(page)).requiredPreferences.effort, 'low');
    assert.equal((await inspect(page)).state.effort, 'medium'); assert.equal((await inspect(page)).state.speed, 'fast');
    await page.evaluate(() => window.HoyoungWorkBridge.configurePreferences({requiredModel:'gpt-6.1-sol',requiredEffort:'low',requiredSpeed:'standard'}));
    await page.evaluate(() => window.answer('원래 Medium/Fast 요청 완료')); await advance(page, 3_000);
    assert.equal((await events(page)).filter(event => event.type === 'completed').length, 1); assert.deepEqual(await preferenceClicks(page), []);
  }, { setup: preferenceMenus });
});

test('a delayed preference acknowledgement is clicked once and blocks sending; a changed next route waits for the prior click', async () => {
  await pageFixture(async page => {
    await page.evaluate(() => {window.preferenceDelay=1_000});
    await ensure(page, 'low', 'standard'); await advance(page, 250);
    assert.equal((await inspect(page)).code, 'preference_selection_pending');
    assert.equal((await send(page, {requiredEffort:'low',requiredSpeed:'standard'})).ok, false);
    await ensure(page, 'medium', 'fast'); await advance(page, 2_000);
    assert.equal((await inspect(page)).code, 'ready'); assert.equal((await inspect(page)).state.effort, 'medium');
    assert.deepEqual(await preferenceClicks(page), ['open:effort', 'effort:Light', 'open:effort', 'effort:Medium']);
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: preferenceMenus });
  await pageFixture(async page => {
    await page.evaluate(() => {window.preferenceDelay=6_000});
    await ensure(page, 'low', 'standard'); await advance(page, 5_500);
    await ensure(page, 'medium', 'fast'); await advance(page, 3_000);
    assert.deepEqual(await preferenceClicks(page), ['open:effort', 'effort:Light']);
    assert.equal((await inspect(page)).code, 'preference_selection_uncertain'); assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: preferenceMenus });
});

test('a draft appearing while a setting changes stops further selection and preserves the draft despite a late acknowledgement', async () => {
  await pageFixture(async page => {
    await page.evaluate(() => {window.preferenceDelay=1_000});
    await ensure(page, 'low', 'standard'); await advance(page, 250);
    await page.evaluate(() => {document.querySelector('#prompt-textarea').value='작업 중에도 내가 쓴 글'});
    await advance(page, 2_000);
    assert.equal(await page.locator('#prompt-textarea').inputValue(), '작업 중에도 내가 쓴 글');
    assert.deepEqual(await preferenceClicks(page), ['open:effort', 'effort:Light']);
    assert.equal((await inspect(page)).code, 'preference_selection_uncertain'); assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: preferenceMenus });
});

test('Theme Light and unrelated menu Standard cannot be clicked or prove preference choices', async () => {
  await pageFixture(async page => {
    await ensure(page, 'low', 'standard'); await advance(page, 750);
    assert.equal((await inspect(page)).code, 'ready'); assert.equal(await page.evaluate(() => window.unrelatedClicks), 0);
    assert.deepEqual(await preferenceClicks(page), ['open:effort', 'effort:Light', 'open:speed', 'speed:Standard']);
  }, { setup: preferenceMenus + `window.unrelatedClicks=0; const theme=document.createElement('div');theme.setAttribute('role','menu');theme.setAttribute('aria-label','Theme');
    for(const text of ['Light','Standard']){const option=document.createElement('button');option.setAttribute('role','menuitemradio');option.setAttribute('aria-checked','false');option.textContent=text;option.onclick=()=>window.unrelatedClicks++;theme.append(option)}document.querySelector('header').append(theme);` });
  await pageFixture(async page => {
    await ensure(page, 'low', 'standard'); await advance(page, 6_000);
    assert.equal(await page.evaluate(() => window.unrelatedClicks), 0); assert.equal((await inspect(page)).ok, false);
    assert.deepEqual(await preferenceClicks(page), ['open:effort']);
  }, { setup: preferenceMenus + `window.unrelatedClicks=0;window.showPreferenceMenu=function(dimension){window.preferenceClicks.push('open:'+dimension);const menu=document.createElement('div');menu.setAttribute('role','menu');menu.setAttribute('aria-label','Theme');for(const text of ['Light','Standard']){const option=document.createElement('button');option.setAttribute('role','menuitemradio');option.textContent=text;option.onclick=()=>window.unrelatedClicks++;menu.append(option)}document.querySelector('header').append(menu)}` });
});

test('inline Appearance Light/Standard radios cannot prove effort/speed or override explicit picker evidence', async () => {
  await pageFixture(async page => {
    assert.equal((await inspect(page)).code, 'ready'); assert.equal((await inspect(page)).state.effort, 'medium'); assert.equal((await inspect(page)).state.speed, 'fast');
    await page.evaluate(() => {document.querySelector('#effort').remove();document.querySelector('#speed').remove()});
    const unknown = await inspect(page); assert.equal(unknown.state.effort, null); assert.equal(unknown.state.speed, null);
    assert.equal((await send(page, {requiredEffort:'low',requiredSpeed:'standard'})).ok, false); assert.equal(await page.evaluate(() => window.unrelatedClicks), 0);
  }, { setup: `window.unrelatedClicks=0;const dialog=document.createElement('div');dialog.setAttribute('role','dialog');dialog.setAttribute('aria-label','Appearance');const group=document.createElement('div');group.setAttribute('role','radiogroup');group.setAttribute('aria-label','Theme');for(const value of ['Light','Standard']){const option=document.createElement('button');option.setAttribute('role','radio');option.setAttribute('aria-checked','true');option.textContent=value;option.onclick=()=>window.unrelatedClicks++;group.append(option)}dialog.append(group);document.body.append(dialog)` });
});

test('read-only usage capture binds one visible Pro weekly Work/Codex pool, display precision and timezone reset to native owner scope', async () => {
  await pageFixture(async page => {
    const receipt = await captureUsage(page), value = receipt.observation;
    assert.equal(receipt.ok, true); assert.equal(value.schemaVersion, 1); assert.equal(value.accountScope, 'owner-selected-account');
    assert.equal(value.accountScopeSource, 'owner_label'); assert.equal(value.accountIdentityVerified, false);
    assert.equal(value.usedPercent, 17.5); assert.equal(value.precisionPercent, .1); assert.equal(value.period, 'weekly');
    assert.equal(value.poolId, 'work-codex:weekly:' + value.resetAt); assert.equal(value.sourceOrigin, 'https://chatgpt.com');
    assert.match(value.evidenceText, /Pro plan\nWork \/ Codex\nWeekly usage\n82.5% remaining/);
    assert.equal(await page.evaluate(() => window.clicks), 0); assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
    await page.evaluate(() => {document.querySelector('#usage-metric').textContent='Used: 23%';document.querySelector('#prompt-textarea').value='내 초안';document.querySelector('[data-testid="stop-button"]').hidden=false});
    const busyReceipt = await captureUsage(page); assert.equal(busyReceipt.observation.usedPercent, 23); assert.equal(busyReceipt.observation.precisionPercent, 1);
    assert.equal(await page.locator('#prompt-textarea').inputValue(), '내 초안'); assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: usagePanel });
});

test('usage words in transcripts/body, hidden panels, unknown authentication and different document paths never become verified readings', async () => {
  const setups = [
    `document.querySelector('#usage-panel').hidden=true`,
    `const transcript=document.createElement('article');transcript.dataset.turnKey='old';transcript.setAttribute('data-message-author-role','assistant');transcript.append(document.querySelector('#usage-panel'));document.querySelector('#turns').append(transcript)`,
    `document.querySelector('[data-testid="user-menu-button"]').remove()`,
    `document.querySelector('#usage-panel').remove();const text=document.createElement('p');text.textContent='Usage Pro plan Work / Codex Weekly usage 82.5% remaining Resets tomorrow';document.body.append(text)`,
    `document.querySelector('#usage-panel').remove();document.querySelector('#turns').innerHTML='<article data-turn-key="old"><div data-user-message-bubble><h1>Usage</h1><p>Pro plan Work / Codex Weekly usage 82.5% remaining</p></div></article>'`
  ];
  for (const setup of setups) await pageFixture(async page => {
    assert.equal((await captureUsage(page)).ok, false); assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: usagePanel + setup });
  await pageFixture(async page => {
    await page.evaluate(() => history.pushState({}, '', '/c/different-document'));
    assert.equal((await captureUsage(page)).code, 'usage_document_unverified');
  }, { setup: usagePanel });
});

test('multiple weekly pools, percentages/resets, model-specific pools, five-hour mixes and imprecise reset dates fail unknown', async () => {
  const mutations = [
    `const other=document.querySelector('#weekly-pool').cloneNode(true);other.id='weekly-pool-other';document.querySelector('#usage-panel').append(other)`,
    `const value=document.createElement('p');value.textContent='Used: 19%';document.querySelector('#weekly-pool').append(value)`,
    `document.querySelector('#weekly-pool').append(document.querySelector('time').parentElement.cloneNode(true))`,
    `const label=document.createElement('p');label.textContent='5 hour limit';document.querySelector('#weekly-pool').append(label)`,
    `const label=document.createElement('p');label.textContent='GPT-6.1 Sol';document.querySelector('#weekly-pool').append(label)`,
    `document.querySelector('time').setAttribute('datetime','2026-10-21')`,
    `document.querySelector('time').setAttribute('datetime','2026-10-21T09:00:00')`,
    `document.querySelector('time').setAttribute('datetime','2026-02-30T09:00:00Z')`,
    `document.querySelector('time').setAttribute('datetime',new Date(Date.now()-1000).toISOString())`,
    `document.querySelector('#usage-metric').textContent='82.5%'`
  ];
  for (const mutation of mutations) await pageFixture(async page => {
    const receipt = await captureUsage(page); assert.equal(receipt.ok, false, mutation); assert.equal(receipt.usageMeasured, false);
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: usagePanel + mutation });
});

test('normal usage-limit information and a positive remaining allowance never latch quota; explicit exhaustion still does', async () => {
  await pageFixture(async page => {
    const messages = ['Usage limit: 50% remaining. Resets next week.', 'Rate limit information: 82.5% remaining.', '사용량 한도 안내: 70% 남음',
      'Usage limit: 50.0% remaining.', 'Usage limit: 82.0% remaining.', 'Usage limit: 50.00% remaining.', '사용량 한도 안내: 50.0% 남음'];
    for (const text of messages) {
      await page.evaluate(text => {let alert=document.querySelector('#usage-alert');if(!alert){alert=document.createElement('div');alert.id='usage-alert';alert.setAttribute('role','alert');document.querySelector('header').append(alert)}alert.textContent=text}, text);
      await advance(page, 250); assert.equal((await inspect(page)).state.quota, false); assert.equal((await inspect(page)).code, 'ready');
    }
    await page.evaluate(() => {document.querySelector('#usage-alert').textContent='You have reached your usage limit.'});
    await advance(page, 250); assert.equal((await inspect(page)).state.quota, true); assert.equal((await inspect(page)).code, 'quota_exhausted');
  });
  for (const text of ['Usage limit: 0.00% remaining.', '사용량 한도 안내: 0.00% 남음']) await pageFixture(async page => {
    await page.evaluate(text => {const alert=document.createElement('div');alert.setAttribute('role','alert');alert.textContent=text;document.querySelector('header').append(alert)}, text);
    await advance(page, 250); assert.equal((await inspect(page)).state.quota, true); assert.equal((await inspect(page)).code, 'quota_exhausted');
  });
  await pageFixture(async page => {
    await page.evaluate(() => {const hidden=document.createElement('div');hidden.setAttribute('role','alert');hidden.hidden=true;hidden.textContent='Usage limit: 0.00% remaining';document.querySelector('header').append(hidden);
      const group=document.createElement('article');group.dataset.turnKey='old';const answer=document.createElement('div');answer.className='markdown';const quoted=document.createElement('div');quoted.setAttribute('role','alert');quoted.textContent='Usage limit: 0.00% remaining';answer.append(quoted);group.append(answer);document.querySelector('#turns').append(group)});
    await advance(page, 250); assert.equal((await inspect(page)).state.quota, false); assert.equal((await inspect(page)).code, 'ready');
  });
});

test('menu offers, assistant text and promotional labels cannot prove a configured future selection', async () => {
  await pageFixture(async page => {
    const preferences = { requiredModel: 'gpt-6.2-sol', requiredEffort: 'high', requiredSpeed: 'standard' };
    await page.evaluate(prefs => window.HoyoungWorkBridge.configurePreferences(prefs), preferences);
    assert.equal((await send(page, preferences)).code, 'model_unverified');
    await page.evaluate(() => document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent = 'GPT-6.2 Sol');
    assert.equal((await send(page, preferences)).code, 'effort_unverified');
    await page.evaluate(() => document.querySelector('#effort').textContent = 'High');
    assert.equal((await send(page, preferences)).code, 'speed_unverified');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('#turns').innerHTML='<article data-turn-key="old-offer"><section data-conversation-role="assistant"><div class="markdown">GPT-6.2 Sol High Standard</div></section></article>'; const menu=document.createElement('div');menu.setAttribute('role','menu'); for(const label of ['GPT-6.2 Sol','High','Standard']) {const offer=document.createElement('button');offer.setAttribute('role','menuitemradio');offer.setAttribute('aria-checked','false');offer.textContent=label;menu.append(offer)} document.querySelector('header').append(menu)` });
  await pageFixture(async page => {
    const preferences = { requiredModel: 'gpt-6.2-sol', requiredEffort: 'medium', requiredSpeed: 'fast' };
    await page.evaluate(prefs => window.HoyoungWorkBridge.configurePreferences(prefs), preferences);
    assert.equal((await inspect(page)).state.model, null);
    assert.equal((await send(page, preferences)).code, 'model_unverified');
  }, { setup: `document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent='Try GPT-6.2 Sol today'` });
});

test('invalid explicit preferences fail closed without restoring prior/default choices; configured missing request fields use exact current requirements', async () => {
  await pageFixture(async page => {
    for (const extra of [{ requiredModel: '' }, { requiredModel: 'gpt-6..2-sol' }, { requiredModel: 'gpt-' + 'x'.repeat(80) }, { requiredEffort: 'automatic' }, { requiredSpeed: 'normal' }, { requiredSpeed: 'flex' }, { requiredModel: null }]) {
      const failure = await send(page, extra);
      assert.equal(failure.code, 'unsupported_preferences'); assert.equal(failure.knownNotSent, true);
    }
    const bad = await page.evaluate(() => window.HoyoungWorkBridge.configurePreferences({requiredModel:'gpt-6.1-sol', requiredEffort:'medium', requiredSpeed:'normal'}));
    assert.equal(bad.code, 'unsupported_preferences');
    assert.equal((await inspect(page)).state.model, 'gpt-6.1-sol');
    assert.equal((await send(page)).code, 'unsupported_preferences');
    assert.equal(await page.evaluate(() => window.clicks), 0);
    await page.evaluate(() => window.HoyoungWorkBridge.configurePreferences({requiredModel:'gpt-6.1-sol',requiredEffort:'medium',requiredSpeed:'fast'}));
    assert.equal((await inspect(page)).code, 'ready');
  });
  await pageFixture(async page => {
    await page.evaluate(() => window.HoyoungWorkBridge.configurePreferences({requiredModel:'gpt-6.2-sol',requiredEffort:'low',requiredSpeed:'standard'}));
    const pending = await page.evaluate(() => window.HoyoungWorkBridge.send({requestId:'configured-only',text:'선택한 새 설정으로 보내 줘'}));
    assert.equal(pending.code, 'dispatch_pending'); await advance(page, 250);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  }, { setup: `document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent='GPT-6.2 Sol';document.querySelector('#effort').textContent='Low';document.querySelector('#speed').textContent='Standard'` });
});

test('one request dispatches once; matching stable user evidence acknowledges it and duplicates cannot dispatch again', async () => {
  await pageFixture(async page => {
    assert.equal((await send(page)).code, 'dispatch_pending');
    assert.equal((await send(page)).code, 'duplicate_request');
    await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await events(page)).filter(event => event.type === 'accepted').length, 1);
    assert.equal((await send(page, { requestId: 'request-two' })).code, 'busy');
    assert.equal((await send(page, { text: '같은 ID 다른 내용' })).code, 'request_id_conflict');
    await page.evaluate(() => window.answer('작업 완료'));
    await advance(page, 2500);
    assert.equal((await events(page)).filter(event => event.type === 'completed').length, 1);
    assert.equal((await send(page)).code, 'duplicate_request');
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
});

test('250k prompt dispatches exactly once intact; one character over rejects before editor or send side effects', async () => {
  const prefix = '    시작😀\n', suffix = '\n끝  \n';
  const original = prefix + '가'.repeat(250_000 - prefix.length - suffix.length) + suffix;
  assert.equal(original.length, 250_000);
  await pageFixture(async page => {
    const pending = await send(page, { text: original });
    assert.equal(pending.code, 'dispatch_pending'); assert.equal(pending.ok, true);
    await advance(page, 250);
    // Rendering a 250k draft can take longer than the dispatch timer; assert the
    // service's submitted text rather than racing the transient editor value.
    const submitted = await page.locator('[data-search-result-target]').textContent();
    assert.equal(submitted.length, original.length);
    assert.ok(submitted === original, 'submission preserves every original character and whitespace');
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await events(page)).filter(event => event.type === 'accepted').length, 1);
    assert.equal((await send(page, { text: original })).code, 'duplicate_request');
    await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
  await pageFixture(async page => {
    const oversized = original + 'x';
    const failure = await send(page, { text: oversized });
    assert.equal(failure.ok, false); assert.equal(failure.code, 'message_too_large');
    assert.equal(failure.knownNotSent, true);
    assert.equal((await events(page)).find(event => event.code === 'message_too_large').knownNotSent, true);
    await advance(page, 500);
    assert.equal(await page.locator('#prompt-textarea').inputValue(), '');
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
    // The caller still owns the full immutable source and can explicitly revise
    // this proven-not-sent request; rejection does not turn it into a receipt.
    assert.equal(oversized.length, 250_001); assert.equal(oversized, original + 'x');
    assert.equal((await send(page, { text: original })).code, 'dispatch_pending');
  });
});

test('production dispatch requires a matching native before-click authorization; denial submits nothing', async () => {
  await pageFixture(async page => {
    await nativeAuthorization(page, 'allow');
    await send(page, { requiresNativeAuthorization: true }); await advance(page, 500);
    const delivered = await events(page), auth = delivered.filter(event => event.code === 'dispatch_authorization_requested');
    assert.equal(auth.length, 1); assert.match(auth[0].nonce, /^[a-f0-9]{32}$/);
    assert.equal(auth[0].authorizationTtlMs, 2000);
    assert.equal(await page.evaluate(() => window.nativePolicyChecks), 1);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal(delivered.filter(event => event.type === 'accepted').length, 1);
  });
  await pageFixture(async page => {
    await nativeAuthorization(page, 'deny');
    await send(page, { requiresNativeAuthorization: true }); await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    const denied = (await events(page)).find(event => event.code === 'dispatch_authorization_denied');
    assert.equal(denied.knownNotSent, true); assert.equal(denied.mayHaveBeenAccepted, false);
    assert.equal(denied.composerMayContainDraft, true);
    assert.equal(await page.locator('#prompt-textarea').inputValue(), '우리 작업 이어 해줘');
    await advance(page, 3000);
    assert.equal((await events(page)).filter(event => event.code === 'dispatch_authorization_requested').length, 1);
    assert.equal(await page.evaluate(() => window.clicks), 0);
  });
});

test('authorization occurs after upload readiness, so a permission revoked during upload blocks the prompt', async () => {
  const attachment = { id: 'auth-photo', name: 'auth-photo.png', mime: 'image/png', base64: Buffer.from('synthetic photo').toString('base64') };
  await pageFixture(async page => {
    await nativeAuthorization(page, 'allow');
    await send(page, { requiresNativeAuthorization: true, attachments: [attachment] });
    await advance(page, 5000);
    assert.equal((await events(page)).some(event => event.code === 'dispatch_authorization_requested'), false);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    await page.evaluate(() => {window.nativePolicyAllowed = false; document.querySelector('#attachments [role="group"]').setAttribute('aria-busy', 'false')});
    await advance(page, 500);
    const denied = (await events(page)).find(event => event.code === 'dispatch_authorization_denied');
    assert.equal(denied.knownNotSent, true); assert.equal(denied.attachmentUploadMayHaveOccurred, true);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
  }, { setup: `window.uploadMode='pending'` });
});

test('wrong request/nonces cannot authorize; a matching nonce is consumed once and cannot replay another request', async () => {
  await pageFixture(async page => {
    await nativeAuthorization(page, 'manual');
    await send(page, { requiresNativeAuthorization: true }); await advance(page, 250);
    const auth = (await events(page)).find(event => event.code === 'dispatch_authorization_requested');
    assert.equal((await inspect(page)).phase, 'awaiting_authorization');
    const wrong = (auth.nonce[0] === '0' ? '1' : '0') + auth.nonce.slice(1);
    for (const [requestId, nonce] of [['other-request', auth.nonce], [auth.requestId, wrong]]) {
      assert.equal((await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [requestId, nonce, true])).ok, false);
    }
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [auth.requestId, auth.nonce, true])).ok, true);
    assert.equal((await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [auth.requestId, auth.nonce, true])).ok, false);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    await page.evaluate(() => window.answer('첫 요청 처리')); await advance(page, 2500);
    await send(page, { requestId: 'second-authorized-request', requiresNativeAuthorization: true }); await advance(page, 250);
    const second = (await events(page)).filter(event => event.code === 'dispatch_authorization_requested').at(-1);
    assert.notEqual(second.nonce, auth.nonce);
    assert.equal((await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [second.requestId, auth.nonce, true])).ok, false);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [second.requestId, second.nonce, true])).ok, true);
    assert.equal(await page.evaluate(() => window.clicks), 2);
  });
});

test('missing or late native acknowledgment times out before click and cannot grant a stale request', async () => {
  await pageFixture(async page => {
    await nativeAuthorization(page, 'manual');
    await send(page, { requiresNativeAuthorization: true }); await advance(page, 250);
    const auth = (await events(page)).find(event => event.code === 'dispatch_authorization_requested');
    await advance(page, 2200);
    const timeout = (await events(page)).find(event => event.code === 'dispatch_authorization_timeout');
    assert.equal(timeout.knownNotSent, true);
    assert.equal((await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [auth.requestId, auth.nonce, true])).ok, false);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
    assert.equal((await page.evaluate(() => window.HoyoungWorkBridge.prepareOwnerRetry())).ok, true);
    await send(page, { requestId: 'fresh-after-timeout', requiresNativeAuthorization: true }); await advance(page, 250);
    assert.equal((await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [auth.requestId, auth.nonce, true])).ok, false);
    assert.equal(await page.evaluate(() => window.clicks), 0);
  });
});

test('a manual submission during authorization prevents false known-not-sent cancellation or replay', async () => {
  await pageFixture(async page => {
    await nativeAuthorization(page, 'manual');
    await send(page, { requiresNativeAuthorization: true }); await advance(page, 250);
    const auth = (await events(page)).find(event => event.code === 'dispatch_authorization_requested');
    await page.evaluate(() => document.querySelector('[data-testid="send-button"]').click());
    const denial = await page.evaluate(args => window.HoyoungWorkBridge.authorizeDispatch(...args), [auth.requestId, auth.nonce, false]);
    assert.equal(denial.knownNotSent, false);
    assert.equal((await events(page)).find(event => event.code === 'dispatch_authorization_denied').knownNotSent, false);
    assert.equal((await page.evaluate(() => window.HoyoungWorkBridge.prepareOwnerRetry())).code, 'outcome_uncertain');
    assert.equal(await page.evaluate(() => window.clicks), 1, 'only the manual submission occurred');
  });
  await pageFixture(async page => {
    await nativeAuthorization(page, 'manual');
    await send(page, { requiresNativeAuthorization: true }); await advance(page, 250);
    await page.evaluate(() => document.querySelector('[data-testid="send-button"]').click());
    const cancellation = await page.evaluate(() => window.HoyoungWorkBridge.cancel());
    assert.equal(cancellation.knownNotSent, false);
    assert.equal(cancellation.code, 'cancellation_submission_unverified');
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
});

test('idle state and a footer before the answer are not completion; a following assistant footer settles the answer', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {window.footer(); document.querySelector('[data-testid="stop-button"]').hidden = true});
    await advance(page, 3000);
    assert.equal((await events(page)).some(event => event.type === 'completed'), false);
    await page.evaluate(() => window.answer('최종 답변', false));
    await advance(page, 3000);
    assert.equal((await events(page)).some(event => event.type === 'completed'), false, 'user/earlier footer cannot complete a new answer');
    await page.evaluate(() => window.footer()); await advance(page, 2500);
    const completed = (await events(page)).filter(event => event.type === 'completed');
    assert.equal(completed.length, 1); assert.equal(completed[0].text, '최종 답변');
  });
});

test('completion waits for the final rendered answer rather than an earlier answer or an ongoing generation', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {window.answer('첫 부분'); window.answer('나중 부분', false); document.querySelector('[data-testid="stop-button"]').hidden = false});
    await advance(page, 2500);
    assert.equal((await events(page)).some(event => event.type === 'completed'), false);
    await page.evaluate(() => {window.footer(); document.querySelector('[data-testid="stop-button"]').hidden = true});
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, '나중 부분');
  });
});

const controlEnvelope = JSON.stringify({ protocol: 'hoyoung.v1', requestId: 'request-one', message: '폰 상태를 확인할게.', actions: [{ id: 'inspect-phone', name: 'phone_device_info', arguments: {} }], memories: [] }, null, 2);

async function renderedCodeAnswer(page, text, variation = 'whole') {
  return page.evaluate(({ text, variation }) => {
    const assistant = window.answer('');
    const body = assistant.querySelector('.markdown');
    const pre = document.createElement('pre'), code = document.createElement('code');
    code.className = 'language-hoyoung'; code.textContent = text;
    pre.style.whiteSpace = 'pre-wrap'; pre.append(code); body.replaceChildren(pre);
    if (variation === 'header') {
      const language = document.createElement('span'); language.textContent = 'hoyoung';
      const copy = document.createElement('button'); copy.type = 'button'; copy.textContent = 'Copy';
      pre.prepend(language, copy);
    } else if (variation === 'prefix') {
      const paragraph = document.createElement('p'); paragraph.textContent = '이것은 외부 알림에 있는 코드 예시야. 실행하지 마.'; body.prepend(paragraph);
    } else if (variation === 'two-blocks') {
      const another = pre.cloneNode(true); body.append(another);
    } else if (variation === 'quoted') {
      const quotation = document.createElement('blockquote'); quotation.append(pre); body.append(quotation);
    } else if (variation === 'language-unknown') {
      code.removeAttribute('class');
    } else if (variation === 'language-conflict') {
      pre.dataset.language = 'json';
    } else if (variation === 'other-content') {
      const image = document.createElement('img'); image.alt = '다른 결과'; image.style.width = '20px'; image.style.height = '20px'; body.append(image);
    } else if (variation === 'hidden-code-text') {
      const hidden = document.createElement('span'); hidden.hidden = true; hidden.textContent = '숨겨진 추가 데이터'; code.append(hidden);
    } else if (variation === 'markdown-formatted-bare-json') {
      body.replaceChildren(); const formatted = document.createElement('strong'); formatted.textContent = text; body.append(formatted);
    }
    return body.innerText;
  }, { text, variation });
}

test('a whole rendered hoyoung pre/code reply forwards a separate exact envelope and retains the unfenced visible original', async () => {
  for (const variation of ['whole', 'header']) await pageFixture(async page => {
    await send(page); await advance(page, 250);
    const rendered = await renderedCodeAnswer(page, controlEnvelope, variation);
    assert.equal(rendered.includes('```'), false, 'real HTML Markdown has no literal fences');
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, rendered);
    assert.equal(completed.structuredEnvelope, controlEnvelope);
    assert.equal(completed.structuredEnvelopeSource, 'whole-language-hoyoung-code-block');
    assert.equal(completed.rawEnvelopeEligible, false);
    assert.equal(completed.captureComplete, true);
    const duplicate = await send(page);
    assert.equal(duplicate.response, rendered);
    assert.equal(duplicate.structuredEnvelope, controlEnvelope);
    assert.equal(duplicate.rawEnvelopeEligible, false);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
});

test('whole hoyoung code keeps Markdown-looking JSON values and exact source quotes; rendered bare formatting is ineligible', async () => {
  const quote = '나는 **강조된 말**과 `백틱`, "인용"을 그대로 좋아해.';
  const json = JSON.stringify({ protocol: 'hoyoung.v1', requestId: 'request-one', message: '**완료**가 아니라 `확인 중`', memories: [{ key: 'literal-preference', content: quote, kind: 'preference', sourceRefs: [{ type: 'message', id: 101, offsetChars: 0, quote }] }] }, null, 2);
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    const rendered = await renderedCodeAnswer(page, json);
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, rendered); assert.equal(completed.structuredEnvelope, json);
    assert.equal(JSON.parse(completed.structuredEnvelope).memories[0].sourceRefs[0].quote, quote);
    assert.equal(completed.rawEnvelopeEligible, false);
  });
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    const rendered = await page.evaluate(() => {
      const answer = window.answer('');
      const body = answer.querySelector('.markdown');
      body.innerHTML = '{"protocol":"hoyoung.v1","requestId":"request-one","message":"<strong>강조된 말</strong>과 <code>백틱</code>"}';
      return body.innerText;
    });
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, rendered); assert.equal(completed.structuredEnvelope, undefined);
    assert.equal(completed.rawEnvelopeEligible, false, 'Markdown-transformed values cannot become raw commands');
  });
});

test('plain whole JSON remains an untouched original; quoted, partial, ambiguous and unknown-language rendered code never becomes an envelope', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(text => {const answer = window.answer(text); answer.querySelector('.markdown').style.whiteSpace = 'pre-wrap'}, controlEnvelope);
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, controlEnvelope); assert.equal(completed.structuredEnvelope, undefined);
    assert.equal(completed.rawEnvelopeEligible, true);
  });
  for (const variation of ['prefix', 'two-blocks', 'quoted', 'language-unknown', 'language-conflict', 'other-content', 'hidden-code-text', 'markdown-formatted-bare-json']) await pageFixture(async page => {
    await send(page); await advance(page, 250);
    const rendered = await renderedCodeAnswer(page, controlEnvelope, variation);
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, rendered, variation + ' keeps the visible source');
    assert.equal(completed.structuredEnvelope, undefined, variation + ' is output data, not a control envelope');
    assert.equal(completed.structuredEnvelopeSource, undefined);
    assert.equal(completed.rawEnvelopeEligible, false, variation + ' cannot bypass the guard through raw innerText');
  });
});

test('rendered envelope metadata must settle and oversized structured code is reported explicitly without clipping the original', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await renderedCodeAnswer(page, controlEnvelope, 'language-unknown');
    await advance(page, 1500);
    await page.evaluate(() => document.querySelector('pre code').className = 'language-hoyoung');
    await advance(page, 750);
    assert.equal((await events(page)).some(event => event.type === 'completed'), false);
    await advance(page, 2000);
    assert.equal((await events(page)).find(event => event.type === 'completed').structuredEnvelope, controlEnvelope);
  });
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    const oversized = JSON.stringify({ protocol: 'hoyoung.v1', requestId: 'request-one', message: 'x'.repeat(250_001) });
    const rendered = await renderedCodeAnswer(page, oversized);
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, rendered);
    assert.equal(completed.structuredEnvelope, undefined);
    assert.equal(completed.structuredEnvelopeUnavailable, 'structured_envelope_too_large');
    assert.equal(completed.structuredEnvelopeObservedChars, oversized.length);
    assert.equal(completed.captureComplete, true);
  });
});

test('quoted limit text in the answer does not consume quota; an actual service alert blocks and reports quota', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {const assistant = window.answer('예시: usage limit reached'); const quotation = document.createElement('span'); quotation.setAttribute('role', 'alert'); quotation.textContent = '사용량 한도 소진'; assistant.querySelector('.markdown').append(quotation)});
    await advance(page, 2500);
    assert.equal((await events(page)).some(event => event.type === 'quota'), false);
    assert.equal((await events(page)).some(event => event.type === 'completed'), true);
    await page.evaluate(() => {const alert = document.createElement('div'); alert.setAttribute('role', 'alert'); alert.textContent = 'You have reached your usage limit'; document.body.append(alert)});
    await advance(page, 250);
    assert.equal((await events(page)).filter(event => event.type === 'quota').length, 1);
    assert.equal((await send(page, { requestId: 'quota-next' })).code, 'quota_exhausted');
    await advance(page, 500);
    assert.equal((await events(page)).filter(event => event.type === 'quota').length, 1);
  });
});

test('a click with no acknowledgement becomes uncertain and never automatically repeats', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 31_000);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await inspect(page)).phase, 'uncertain');
    assert.equal((await send(page)).code, 'dispatch_uncertain');
    assert.equal((await send(page, { requestId: 'other-id' })).code, 'dispatch_uncertain');
    await advance(page, 10_000);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await events(page)).filter(event => event.code === 'dispatch_uncertain' && event.type === 'error').length, 1);
  }, { setup: `window.autoAck=false` });
});

test('tracking query/hash changes preserve the same document while actual conversation path replacement stays guarded', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => history.replaceState({}, '', '/?temporary-chat=true&tracking=changed#message'));
    await advance(page, 250);
    assert.equal((await inspect(page)).phase, 'accepted');
    assert.equal((await events(page)).some(event => event.code === 'navigation_uncertain'), false);
    await page.evaluate(() => window.answer('동일한 작업의 답변')); await advance(page, 2500);
    assert.equal((await events(page)).find(event => event.type === 'completed').text, '동일한 작업의 답변');
  });
});

test('a throwing click can already have caused a side effect and is not retried', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await inspect(page)).phase, 'uncertain');
    assert.equal((await send(page)).code, 'dispatch_uncertain');
    assert.equal((await send(page)).knownNotSent, false);
    assert.equal((await events(page)).find(event => event.type === 'error' && event.code === 'dispatch_uncertain').knownNotSent, false);
  }, { setup: `document.querySelector('[data-testid="send-button"]').click=function(){window.clicks++; throw new Error('lost click confirmation')}` });
});

test('quota never automatically unlatches; owner retry requires a disappeared banner and does not claim quota recovery', async () => {
  await pageFixture(async page => {
    await page.evaluate(() => {const alert = document.createElement('div'); alert.id = 'quota'; alert.setAttribute('role', 'alert'); alert.textContent = 'Usage limit reached'; document.body.append(alert)});
    await advance(page, 250);
    assert.equal((await inspect(page)).state.quota, true);
    assert.equal((await page.evaluate(() => window.HoyoungWorkBridge.prepareOwnerRetry())).code, 'quota_exhausted');
    const refusal = await send(page);
    assert.equal(refusal.code, 'quota_exhausted'); assert.equal(refusal.knownNotSent, true);
    await page.evaluate(() => document.querySelector('#quota').remove()); await advance(page, 500);
    assert.equal((await inspect(page)).state.quota, true, 'banner disappearance alone is not a recovery signal');
    const owner = await page.evaluate(() => window.HoyoungWorkBridge.prepareOwnerRetry());
    assert.equal(owner.code, 'owner_retry_enabled'); assert.equal(owner.quotaRecoveryVerified, false);
    assert.equal(owner.state.quota, false);
    assert.equal((await send(page)).code, 'dispatch_pending'); await advance(page, 250);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
});

test('owner retry cannot discard or repeat a clicked uncertain request after quota', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {const alert = document.createElement('div'); alert.id = 'quota'; alert.setAttribute('role', 'alert'); alert.textContent = 'Usage limit reached'; document.body.append(alert)});
    await advance(page, 250);
    await page.evaluate(() => document.querySelector('#quota').remove()); await advance(page, 250);
    const owner = await page.evaluate(() => window.HoyoungWorkBridge.prepareOwnerRetry());
    assert.equal(owner.code, 'outcome_uncertain'); assert.equal(owner.knownNotSent, false);
    assert.equal((await inspect(page)).phase, 'uncertain'); assert.equal((await inspect(page)).state.quota, true);
    assert.equal((await send(page)).knownNotSent, false);
    assert.equal((await send(page, { requestId: 'another-after-uncertain' })).knownNotSent, false);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  }, { setup: `window.autoAck=false` });
});

test('owner may explicitly replay a preserved proven-before-click quota receipt, never by automatic banner disappearance', async () => {
  await pageFixture(async page => {
    // Keep the alert and pending send in one browser turn: parallel test load
    // must not advance the real/fake clock between independent evaluations.
    await page.evaluate(() => {
      window.HoyoungWorkBridge.send({ requestId: 'request-one', text: '우리 작업 이어 해줘', requiredModel: 'gpt-6.1-sol', requiredEffort: 'medium', requiredSpeed: 'fast' });
      const alert = document.createElement('div'); alert.id = 'quota'; alert.setAttribute('role', 'alert'); alert.textContent = 'Usage limit reached'; document.body.append(alert);
    });
    await advance(page, 1);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await events(page)).find(event => event.type === 'quota').knownNotSent, true);
    await page.evaluate(() => document.querySelector('#quota').remove()); await advance(page, 250);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await send(page)).code, 'dispatch_uncertain');
    const owner = await page.evaluate(() => window.HoyoungWorkBridge.prepareOwnerRetry());
    assert.equal(owner.code, 'owner_retry_enabled'); assert.equal(owner.knownNotSent, true);
    assert.equal(await page.evaluate(() => document.querySelector('#prompt-textarea').value), '');
    assert.equal((await send(page)).code, 'dispatch_pending'); await advance(page, 250);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await send(page)).code, 'duplicate_request');
  });
});

test('only proven preflight rejections flag knownNotSent; editor side effects and existing receipts retain uncertainty', async () => {
  await pageFixture(async page => {
    const tooLarge = await send(page, { text: 'a'.repeat(250_001) });
    assert.equal(tooLarge.code, 'message_too_large'); assert.equal(tooLarge.knownNotSent, true);
    assert.equal((await events(page)).find(event => event.code === 'message_too_large').knownNotSent, true);
    const preferences = await send(page, { requiredEffort: 'ultra' });
    assert.equal(preferences.knownNotSent, true);
    await page.evaluate(() => document.querySelector('#prompt-textarea').value = 'owner draft');
    assert.equal((await send(page)).knownNotSent, true);
    await page.evaluate(() => document.querySelector('#prompt-textarea').value = '');
    await send(page); await advance(page, 250);
    const busy = await send(page, { requestId: 'new-while-busy' });
    assert.equal(busy.code, 'busy'); assert.equal(busy.knownNotSent, true);
    assert.equal((await send(page)).knownNotSent, false);
    assert.equal((await send(page, { text: 'ID conflict' })).knownNotSent, false);
  });
  await pageFixture(async page => {
    const write = await send(page);
    assert.equal(write.code, 'composer_write_unverified'); assert.equal(write.knownNotSent, false);
    assert.equal((await events(page)).find(event => event.code === 'composer_write_unverified').knownNotSent, false);
    assert.equal((await page.evaluate(() => window.HoyoungWorkBridge.prepareOwnerRetry())).code, 'outcome_uncertain');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('#prompt-textarea').focus=function(){throw new Error('unknown editor implementation')}` });
});

test('bounded response receipts keep ID tombstones so long-running pages accept new IDs without replaying old ones', async () => {
  await pageFixture(async page => {
    const codes = await page.evaluate(() => {
      const results = [];
      for (let index = 0; index < 130; index++) {
        results.push(window.HoyoungWorkBridge.send({ requestId: 'long-page-' + index, text: '취소 가능한 준비 ' + index }).code);
        window.HoyoungWorkBridge.cancel();
        document.querySelector('#prompt-textarea').value = '';
      }
      return results;
    });
    assert.equal(codes.every(code => code === 'dispatch_pending'), true);
    assert.equal((await send(page, { requestId: 'long-page-0', text: '취소 가능한 준비 0' })).code, 'request_receipt_archived');
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await send(page, { requestId: 'fresh-after-capacity' })).code, 'dispatch_pending');
    await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
});

test('reinjection does not create another worker; route changes invalidate outstanding observation and dispatch', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.addScriptTag({ content: source }); await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    await page.evaluate(() => history.pushState({}, '', '/c/other-conversation'));
    await advance(page, 500);
    assert.equal((await events(page)).some(event => event.type === 'error' && event.code === 'navigation_uncertain'), true);
    assert.equal((await send(page, { requestId: 'new-id' })).code, 'bridge_stale');
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
});

test('unknown stable turn identity cannot dispatch; virtualized old groups cannot acknowledge a new request', async () => {
  await pageFixture(async page => {
    assert.equal((await send(page)).code, 'turn_identity_unverified');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('#turns').innerHTML='<article data-testid="conversation-turn-0" data-turn="assistant"><div class="markdown">old</div></article>'` });
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {const bubble = document.createElement('div'); bubble.setAttribute('data-user-message-bubble', ''); bubble.textContent='우리 작업 이어 해줘'; document.querySelector('[data-turn-key="old-key"]').append(bubble)});
    await advance(page, 1000);
    assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
  }, { setup: `window.autoAck=false; document.querySelector('#turns').innerHTML='<article data-turn-key="old-key"></article>'` });
});

test('photos are forwarded through the real composer file input and prompt waits for positive attachment readiness', async () => {
  const attachment = { id: 'photo-one', name: 'photo.png', mime: 'image/png', base64: Buffer.from('fixture image bytes').toString('base64') };
  await pageFixture(async page => {
    assert.equal((await send(page, { attachments: [attachment] })).code, 'dispatch_pending');
    assert.equal(await page.evaluate(() => window.fileChanges), 1);
    await advance(page, 500);
    assert.equal(await page.evaluate(() => document.querySelector('input[type="file"]').files[0].name), 'photo.png');
    assert.equal(await page.evaluate(() => document.querySelector('input[type="file"]').files[0].text()), 'fixture image bytes');
    assert.equal(await page.evaluate(() => window.clicks), 1);
    const accepted = (await events(page)).find(event => event.type === 'accepted');
    assert.equal(accepted.uploadObserved, true);
    assert.deepEqual(accepted.attachmentNames, ['photo.png']);
    assert.equal(accepted.uploadObservationScope, 'composer_reports_uploaded_before_user_turn_confirmation');
  });
  await pageFixture(async page => {
    assert.equal((await send(page, { attachments: [attachment] })).code, 'dispatch_pending');
    await advance(page, 1000);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    await page.evaluate(() => {const card = document.querySelector('[aria-label="photo.png"]'); card.setAttribute('aria-busy', 'false')});
    await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 1);
  }, { setup: `window.uploadMode='pending'` });
});

test('a derived JPEG is forwarded as its exact wire bytes and name without claiming the original was inspected', async () => {
  await pageFixture(async page => {
    const base64 = await page.evaluate(() => {
      const canvas = document.createElement('canvas'); canvas.width = 8; canvas.height = 8;
      const context = canvas.getContext('2d'); context.fillStyle = '#2161ff'; context.fillRect(0, 0, 8, 8);
      return canvas.toDataURL('image/jpeg', 0.75).split(',')[1];
    });
    const wireName = 'camera.work-fixture.jpeg';
    const representation = { id: 'immutable-original-photo', name: wireName, mime: 'image/jpeg', size: Buffer.from(base64, 'base64').length, base64,
      originalSize: 60 * 1024 * 1024, originalBytesFullyUploaded: true, originalContentFullyInspected: true };
    await nativeAuthorization(page, 'allow');
    assert.equal((await send(page, { attachments: [representation], requiresNativeAuthorization: true })).code, 'dispatch_pending');
    await advance(page, 500);
    const actual = await page.evaluate(async () => {
      const file = document.querySelector('input[type="file"]').files[0];
      const bitmap = await createImageBitmap(file);
      return { name: file.name, mime: file.type, size: file.size, bytes: Array.from(new Uint8Array(await file.arrayBuffer())), width: bitmap.width, height: bitmap.height };
    });
    assert.equal(actual.name, wireName); assert.equal(actual.mime, 'image/jpeg');
    assert.equal(actual.size, representation.size); assert.deepEqual(Buffer.from(actual.bytes), Buffer.from(base64, 'base64'));
    assert.equal(actual.width, 8); assert.equal(actual.height, 8);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    const accepted = (await events(page)).find(event => event.type === 'accepted');
    assert.deepEqual(accepted.attachmentNames, [wireName]); assert.equal(accepted.uploadObserved, true);
    assert.equal(accepted.uploadObservationScope, 'composer_reports_uploaded_before_user_turn_confirmation');
    assert.equal(accepted.originalBytesFullyUploaded, undefined, 'untrusted attachment metadata cannot manufacture native upload proof');
    assert.equal(accepted.originalContentFullyInspected, undefined);
  });
});

test('configured and observed models remain distinct; changing the selected model during upload blocks the prepared request', async () => {
  await pageFixture(async page => {
    const requirements = { requiredModel: 'gpt-6.2-sol', requiredEffort: 'medium', requiredSpeed: 'fast' };
    const before = await page.evaluate(prefs => window.HoyoungWorkBridge.configurePreferences(prefs), requirements);
    assert.equal(before.requiredPreferences.model, 'gpt-6.2-sol'); assert.equal(before.state.model, 'gpt-6.1-sol');
    assert.equal(before.code, 'model_unverified');
    assert.equal(await page.evaluate(() => document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent), 'GPT-6.1 Sol');
    await page.evaluate(() => document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent = 'GPT-6.2 Sol');
    assert.equal((await inspect(page)).code, 'ready');
    const attachment = { id: 'pending-media', name: 'pending.pdf', mime: 'application/pdf', base64: Buffer.from('%PDF-1.4\nfixture transfer').toString('base64') };
    await nativeAuthorization(page, 'allow');
    assert.equal((await send(page, { ...requirements, attachments: [attachment], requiresNativeAuthorization: true })).code, 'dispatch_pending');
    await advance(page, 500); assert.equal(await page.evaluate(() => window.clicks), 0);
    await page.evaluate(() => {
      document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent = 'GPT-6.1 Sol';
      const card = document.querySelector('[aria-label="pending.pdf"]'); card.setAttribute('aria-busy', 'false'); card.querySelector('progress')?.remove();
    });
    await advance(page, 1_000);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
    assert.equal((await events(page)).some(event => event.code === 'dispatch_authorization_requested'), false);
    assert.equal((await events(page)).some(event => event.code === 'selection_changed_before_send'), true);
    assert.equal((await inspect(page)).state.model, 'gpt-6.1-sol');
    assert.equal((await inspect(page)).requiredPreferences.model, 'gpt-6.2-sol');
    assert.equal((await inspect(page)).phase, 'uncertain');
    await page.evaluate(() => document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent = 'GPT-6.2 Sol');
    await advance(page, 1_000); assert.equal(await page.evaluate(() => window.clicks), 0, 'restoring selection cannot repeat an uncertain dispatch');
  }, { setup: `window.uploadMode='pending'` });
});

test('video, audio, PDF, ZIP and opaque binary attachments preserve exact bytes, SHA, MIME and order through the file input', async () => {
  // These are byte transport vectors with recognizable headers, not media codec
  // or provider parsing tests. Work support for a file type still needs a real account.
  const vectors = [
    { name: '영상.mp4', mime: 'video/mp4', prefix: Buffer.from([0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109]) },
    { name: '음성.wav', mime: 'audio/wav', prefix: Buffer.from('RIFF\x00\x00\x00\x00WAVE', 'binary') },
    { name: '자료.pdf', mime: 'application/pdf', prefix: Buffer.from('%PDF-1.4\n') },
    { name: '묶음.zip', mime: 'application/zip', prefix: Buffer.from([80, 75, 5, 6]) },
    { name: 'opaque.data', mime: 'application/octet-stream', prefix: Buffer.from([0, 255, 128, 0, 1, 254]) }
  ].map((vector, index) => {
    const bytes = Buffer.concat([vector.prefix, Buffer.from([0, 255, 128, 13, 10, index]), Buffer.alloc(4_099 + index, 179 - index)]);
    return { id: `source-${index}`, name: vector.name, mime: vector.mime, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), base64: bytes.toString('base64') };
  });
  await pageFixture(async page => {
    await nativeAuthorization(page, 'allow');
    assert.equal((await send(page, { attachments: vectors, requiresNativeAuthorization: true })).code, 'dispatch_pending');
    await advance(page, 500);
    const actual = await page.evaluate(async () => Promise.all(Array.from(document.querySelector('input[type="file"]').files, async file => {
      const bytes = await file.arrayBuffer();
      const digest = await crypto.subtle.digest('SHA-256', bytes);
      return { name: file.name, mime: file.type, size: file.size, sha256: Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join(''), bytes: Array.from(new Uint8Array(bytes)) };
    })));
    assert.equal(actual.length, vectors.length);
    for (let index = 0; index < vectors.length; index++) {
      assert.equal(actual[index].name, vectors[index].name); assert.equal(actual[index].mime, vectors[index].mime);
      assert.equal(actual[index].size, vectors[index].size); assert.equal(actual[index].sha256, vectors[index].sha256);
      assert.deepEqual(Buffer.from(actual[index].bytes), Buffer.from(vectors[index].base64, 'base64'));
    }
    assert.equal(await page.evaluate(() => window.fileChanges), 1); assert.equal(await page.evaluate(() => window.clicks), 1);
    const accepted = (await events(page)).find(event => event.type === 'accepted');
    assert.deepEqual(accepted.attachmentNames, vectors.map(vector => vector.name)); assert.equal(accepted.uploadObserved, true);
    assert.equal(accepted.uploadObservationScope, 'composer_reports_uploaded_before_user_turn_confirmation');
    assert.equal(accepted.serverBytesIndependentlyVerified, undefined, 'DOM observation cannot prove provider byte storage');
  });
});

test('a restrictive composer refuses unsupported file types before any draft or upload; advertised MIME and extension options are honored', async () => {
  const pdf = { id: 'pdf-source', name: 'owner.PDF', mime: 'application/pdf', base64: Buffer.from('%PDF-1.4\ntransfer fixture').toString('base64') };
  await pageFixture(async page => {
    const rejected = await send(page, { attachments: [pdf] });
    assert.equal(rejected.code, 'attachment_control_unverified'); assert.equal(rejected.knownNotSent, true);
    assert.equal(await page.evaluate(() => document.querySelector('#prompt-textarea').value), '');
    assert.equal(await page.evaluate(() => window.fileChanges || 0), 0); assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
  }, { setup: `document.querySelector('input[type="file"]').accept='image/*'` });
  await pageFixture(async page => {
    assert.equal((await send(page, { attachments: [pdf] })).code, 'dispatch_pending'); await advance(page, 500);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.deepEqual((await events(page)).find(event => event.type === 'accepted').attachmentNames, ['owner.PDF']);
  }, { setup: `document.querySelector('input[type="file"]').accept='.pdf,audio/*,video/mp4,application/zip,application/octet-stream'` });
});

test('empty files are explicitly rejected before file or composer effects rather than silently removed from a mixed upload', async () => {
  const empty = { id: 'empty-source', name: 'empty.txt', mime: 'text/plain', base64: '' };
  const nonempty = { id: 'other-source', name: 'other.txt', mime: 'text/plain', base64: Buffer.from('Keep this exact byte text').toString('base64') };
  for (const attachments of [[empty], [nonempty, empty]]) {
    await pageFixture(async page => {
      const rejected = await send(page, { attachments });
      assert.equal(rejected.code, 'invalid_attachments'); assert.equal(rejected.knownNotSent, true);
      assert.equal(await page.evaluate(() => window.fileChanges || 0), 0); assert.equal(await page.evaluate(() => window.clicks), 0);
      assert.equal(await page.evaluate(() => document.querySelector('#prompt-textarea').value), '');
      assert.equal((await events(page)).some(event => event.type === 'accepted'), false);
    });
  }
});

test('missing file inputs, unknown upload activation and oversized attachments never send a text-only replacement', async () => {
  const attachment = { id: 'a', name: 'photo.png', mime: 'image/png', base64: Buffer.from('image').toString('base64') };
  await pageFixture(async page => {
    assert.equal((await send(page, { attachments: [attachment] })).code, 'attachment_control_unverified');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `document.querySelector('input[type="file"]').remove()` });
  await pageFixture(async page => {
    await send(page, { attachments: [attachment] }); await advance(page, 61_000);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal((await events(page)).some(event => event.code === 'attachment_upload_unverified'), true);
    assert.equal((await send(page)).code, 'dispatch_uncertain');
  }, { setup: `window.uploadMode='unknown'` });
  await pageFixture(async page => {
    const oversized = { ...attachment, base64: Buffer.alloc(10 * 1024 * 1024 + 1).toString('base64') };
    assert.equal((await send(page, { attachments: [oversized] })).ok, false);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal(await page.evaluate(() => window.fileChanges || 0), 0);
  });
  await pageFixture(async page => {
    const nine = Array.from({ length: 9 }, (_, index) => ({ ...attachment, name: `photo-${index}.png` }));
    const rejection = await send(page, { attachments: nine });
    assert.equal(rejection.code, 'invalid_attachments'); assert.equal(rejection.knownNotSent, true);
    assert.equal(await page.evaluate(() => window.clicks), 0);
    assert.equal(await page.evaluate(() => window.fileChanges || 0), 0);
  });
});

test('over 400k observed characters are delivered exactly, including code indentation and trailing whitespace', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    const original = '    시작 코드\n' + '원문😀 \n'.repeat(90_000) + '끝  \n';
    await page.evaluate(text => {const answer = window.answer(text); answer.querySelector('.markdown').style.whiteSpace = 'pre-wrap'}, original);
    await advance(page, 2500);
    const completed = (await events(page)).find(event => event.type === 'completed');
    assert.equal(completed.text, original);
    assert.equal(completed.observedChars, original.length);
    assert.equal(completed.sourceFormat, 'visible-dom-text');
    assert.equal(completed.renderedOnly, true);
    assert.equal(completed.captureComplete, true);
    const preview = (await events(page)).filter(event => event.type === 'progress' && !event.captureId).at(-1);
    assert.equal(preview.previewOnly, true); assert.equal(preview.captureComplete, false);
    assert.equal(preview.text.length, 2000);
  });
});

test('large replies use ordered bounded pieces with intact Unicode and a completion manifest, never a truncated reply', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    const original = 'x'.repeat(99_999) + '😀\n' + '한'.repeat(900_000);
    await page.evaluate(text => {const answer = window.answer(text); answer.querySelector('.markdown').style.whiteSpace = 'pre-wrap'}, original);
    await advance(page, 2500);
    const delivered = await events(page);
    const pieces = delivered.filter(event => event.code === 'response_capture_part');
    const completed = delivered.find(event => event.type === 'completed');
    assert.ok(pieces.length > 1);
    assert.equal(pieces.map(piece => piece.text).join(''), original);
    assert.equal(completed.text, undefined);
    assert.equal(completed.observedChars, original.length);
    assert.equal(completed.captureComplete, true);
    assert.equal(completed.totalParts, pieces.length);
    assert.equal(completed.captureEncoding, 'utf-8');
    assert.equal(pieces.every((piece, index) => piece.index === index && piece.captureId === completed.captureId && piece.totalParts === pieces.length && piece.text.length <= 100_001), true);
    assert.equal(pieces.some(piece => /[\uD800-\uDBFF]$/.test(piece.text) || /^[\uDC00-\uDFFF]/.test(piece.text)), false);
    assert.equal(delivered.every(event => JSON.stringify(event).length <= 900_000), true);
    const duplicate = await send(page);
    assert.equal(duplicate.response, undefined); assert.equal(duplicate.capture.captureId, completed.captureId);
  });
});

test('capture overflow and missing native delivery remain uncertain instead of declaring a clipped reply complete', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {const answer = window.answer('x'.repeat(10_000_001)); answer.querySelector('.markdown').style.whiteSpace = 'pre'});
    await advance(page, 2500);
    const delivered = await events(page);
    assert.equal(delivered.some(event => event.type === 'completed'), false);
    const failure = delivered.find(event => event.code === 'response_capture_overflow');
    assert.equal(failure.observedChars, 10_000_001); assert.equal(failure.captureComplete, false);
    assert.equal(failure.rawTextAvailableInPage, true); assert.equal(failure.knownNotSent, false);
    assert.equal((await inspect(page)).phase, 'uncertain');
    assert.equal((await send(page)).code, 'dispatch_uncertain');
  });
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {
      const normal = window.HoyoungHost.postMessage;
      window.HoyoungHost.postMessage = raw => {if (JSON.parse(raw).type === 'completed') throw new Error('native sink lost'); normal(raw)};
      window.answer('이 원문은 전달 여부가 불확실함');
    });
    await advance(page, 2500);
    assert.equal((await events(page)).some(event => event.type === 'completed'), false);
    assert.equal((await events(page)).some(event => event.code === 'response_capture_delivery_uncertain'), true);
    assert.equal((await inspect(page)).phase, 'uncertain');
    assert.equal(await page.evaluate(() => window.clicks), 1);
  });
});

test('service input requests pause completion and report queued input without clicking approvals; quoted text does not', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {
      window.answer('결과를 확인해 줘');
      const dialog = document.createElement('div'); dialog.setAttribute('role', 'dialog'); dialog.id = 'approval';
      dialog.textContent = 'Waiting for your approval';
      const approve = document.createElement('button'); approve.textContent = 'Approve'; approve.onclick = () => window.approveClicks = (window.approveClicks || 0) + 1;
      dialog.append(approve); document.body.append(dialog);
    });
    await advance(page, 2500);
    assert.equal((await events(page)).some(event => event.type === 'completed'), false);
    assert.equal((await inspect(page)).state.needsInput, true);
    const waiting = (await events(page)).find(event => event.code === 'work_input_required');
    assert.equal(waiting.queuedInputOnly, true);
    assert.equal(await page.evaluate(() => window.approveClicks || 0), 0);
    await page.evaluate(() => document.querySelector('#approval').remove()); await advance(page, 2500);
    assert.equal((await events(page)).some(event => event.type === 'completed'), true);
  });
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {const answer = window.answer('인용문'); const quotation = document.createElement('div'); quotation.setAttribute('role', 'alert'); quotation.textContent = 'Waiting for your approval'; answer.querySelector('.markdown').append(quotation)});
    await advance(page, 2500);
    assert.equal((await inspect(page)).state.needsInput, false);
    assert.equal((await events(page)).some(event => event.type === 'completed'), true);
  });
});

test('an accepted multi-hour task is observed until actual completion without periodic duplicate submissions', async () => {
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.clock.fastForward(2 * 60 * 60 * 1000);
    assert.equal((await inspect(page)).phase, 'accepted');
    assert.equal((await events(page)).some(event => event.type === 'completed'), false);
    assert.equal(await page.evaluate(() => window.clicks), 1);
    assert.equal((await send(page, { requestId: 'new-input-during-long-task' })).code, 'busy');
    await page.evaluate(() => window.answer('오래 걸린 작업 결과')); await advance(page, 2500);
    assert.equal((await events(page)).find(event => event.type === 'completed').text, '오래 걸린 작업 결과');
  });
});

test('DOM drift cannot mistake unrelated forms or a manual different turn for this dispatch', async () => {
  await pageFixture(async page => {
    assert.equal((await send(page)).code, 'send_control_unverified');
    assert.equal(await page.evaluate(() => window.clicks), 0);
  }, { setup: `const another=document.createElement('button'); another.type='submit'; another.textContent='Unrelated submit'; document.querySelector('form').append(another)` });
  await pageFixture(async page => {
    assert.equal((await inspect(page)).state.workSelected, false);
    assert.equal((await send(page)).code, 'work_unverified');
  }, { setup: `document.querySelector('#work').hidden=true` });
  await pageFixture(async page => {
    await send(page); await advance(page, 250);
    await page.evaluate(() => {const article = document.createElement('article'); article.dataset.turnKey='manual-other'; const user=document.createElement('div'); user.setAttribute('data-user-message-bubble',''); user.textContent='다른 요청'; article.append(user); document.querySelector('#turns').append(article)});
    await advance(page, 250);
    assert.equal((await events(page)).some(event => event.code === 'turn_ambiguous'), true);
    assert.equal((await inspect(page)).phase, 'uncertain');
  }, { setup: `window.autoAck=false` });
});

test('native final payload digest is required and returned in before-click authorization', async () => {
  await pageFixture(async page => {
    const missing=await send(page,{requiresNativeAuthorization:true,requiresNativePayloadDigest:true});
    assert.equal(missing.code,'native_payload_digest_missing');assert.equal(await page.evaluate(()=>window.clicks),0);
    await nativeAuthorization(page,'allow');
    const digest='a'.repeat(64);
    assert.equal((await send(page,{requestId:'digest-valid',requiresNativeAuthorization:true,requiresNativePayloadDigest:true,nativePayloadDigest:digest})).ok,true);
    await advance(page,250);
    assert.equal((await events(page)).find(event=>event.code==='dispatch_authorization_requested').payloadDigest,digest);
    assert.equal(await page.evaluate(()=>window.clicks),1);
  });
});

test('a different payload digest denies the staged prompt before submission', async () => {
  await pageFixture(async page => {
    await nativeAuthorization(page,'manual');
    await send(page,{requiresNativeAuthorization:true,requiresNativePayloadDigest:true,nativePayloadDigest:'b'.repeat(64)});
    await advance(page,250);
    const auth=(await events(page)).find(event=>event.code==='dispatch_authorization_requested');
    await page.evaluate(({auth})=>window.HoyoungWorkBridge.authorizeDispatch(auth.requestId,auth.nonce,true,'c'.repeat(64)),{auth});
    await advance(page,250);
    assert.equal(await page.evaluate(()=>window.clicks),0);
    assert.equal((await events(page)).some(event=>event.code==='dispatch_payload_digest_mismatch'),true);
  });
});
