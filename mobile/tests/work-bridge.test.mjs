import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
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
 <button id="effort" role="menuitemradio" aria-checked="true">Medium</button>
 <button id="speed" role="menuitemradio" aria-checked="true">Fast</button>
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
    await send(page);
    await page.evaluate(() => {const alert = document.createElement('div'); alert.id = 'quota'; alert.setAttribute('role', 'alert'); alert.textContent = 'Usage limit reached'; document.body.append(alert)});
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
    const tooLarge = await send(page, { text: 'a'.repeat(100_001) });
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
    assert.equal((await events(page)).some(event => event.type === 'accepted'), true);
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
});
