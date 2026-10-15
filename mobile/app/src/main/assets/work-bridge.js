/*
 * DOM-only ChatGPT Work transport for Hoyoung's Android WebView.
 * Composer, stable turn identities and answer/footer observation are adapted from
 * miuuyy/codex-chatgpt-web (MIT), pinned 0b6087c5307c7a58adda9c420358f3f70d2035f5.
 * Response/commentary boundaries rechecked at f137c2f84da5df214c1e228d74f5ff43e7641c48.
 * This is not that project's desktop terminal bridge or an official Work API.
 * It deliberately fails closed when Work/model selection cannot be
 * proven from the current UI. It never reads cookies, passwords or web storage.
 */
(function installHoyoungWorkBridge() {
  'use strict';
  if (window.HoyoungWorkBridge && window.HoyoungWorkBridge.__document === document) {
    window.HoyoungWorkBridge.inspectAndEmit();
    return;
  }
  if (window.HoyoungWorkBridge && typeof window.HoyoungWorkBridge.__dispose === 'function') {
    window.HoyoungWorkBridge.__dispose('document_changed');
  }

  const initialDocument = document;
  let initialPath = location.pathname;
  let suspendedRoute = false;
  let disposalReason = '';
  let recoveryObservation = null;
  const recoveryDigests = new Map();
  let ownedConversationId = '';
  let verifiedWorkSession = false;
  let cleaningSession = false;
  const cos = window.HoyoungCoSDom;
  const COMPOSER = '[data-testid="prompt-textarea"], #prompt-textarea, [contenteditable="true"][data-lexical-editor="true"], form[data-chatgpt-composer] [data-composer-markdown][contenteditable="true"][role="textbox"]';
  const SEND = '[data-testid="send-button"], button[type="submit"]';
  const STOP = '[data-testid="stop-button"], button[aria-label="Stop generating"], button[aria-label="응답 생성 중지"]';
  const ANSWER = '.markdown, [data-markdown-text-style="assistant-message"], .puik-root.not-markdown > [class*="_DilResponseRoot"]';
  const FOOTER = 'button[data-testid="copy-turn-action-button"], .turn-action-controls button, button[aria-label="Copy response"], button[aria-label="응답 복사"]';
  const TRANSCRIPT = '[data-turn-key], [data-testid^="conversation-turn-"], [data-turn-id-container], [data-message-author-role], [data-conversation-role], [data-user-message-bubble]';
  const CONTROL = 'button, [role="button"], [role="tab"], [role="switch"], [role="checkbox"], [role="menuitemcheckbox"], [role="menuitemradio"], [role="radio"], [role="option"], [role="menuitem"], a[aria-current]';
  const MODEL_CONTROL = 'button[data-testid="model-switcher-dropdown-button"], button[data-codex-intelligence-trigger], button[data-composer-navigation-target="reasoning"]';
  const PICKER_CONTENT = '[data-testid="composer-intelligence-picker-content"], [data-model-picker-view]';
  const EFFORT_SLIDER = '[data-model-reasoning-effort-slider], [data-model-picker-power-slider]';
  const STOP_ICON_PATH = 'M4.5 5.75C4.5 5.05964 5.05964 4.5 5.75 4.5H14.25C14.9404 4.5 15.5 5.05964 15.5 5.75V14.25C15.5 14.9404 14.9404 15.5 14.25 15.5H5.75C5.05964 15.5 4.5 14.9404 4.5 14.25V5.75Z';
  const DEFAULTS = { model: 'gpt-6.1-sol', effort: 'medium', speed: 'fast' };
  const MODEL_ID = /^gpt-[a-z0-9]+(?:[.-][a-z0-9]+)*$/;
  const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max', 'ultra'];
  const SPEEDS = ['fast', 'standard'];
  let configuredPreferences = Object.assign({}, DEFAULTS);
  let preferenceConfigurationValid = true;
  // UI selection is an explicit native operation, never a side effect of the
  // read-only configure/inspect APIs. One observed option is clicked once; an
  // unacknowledged click cannot be replayed by the 250 ms DOM polling loop.
  const PREFERENCE_SELECTION_TIMEOUT_MS = 5_000;
  const PREFERENCE_OPERATION_TIMEOUT_MS = 25_000;
  const MAX_PREFERENCE_SELECTION_CLICKS = 16;
  let preferenceSelection = null;
  let preferenceSelectionFlight = null;
  let preferenceSelectionUncertain = false;
  let openedPreferenceTrigger = null;
  let observedModelTrigger = null;
  let preferenceCloseFlight = null;
  const ACCEPT_TIMEOUT_MS = 30_000;
  const SEND_ENABLE_TIMEOUT_MS = 5_000;
  const ATTACHMENT_READY_TIMEOUT_MS = 60_000;
  const DISPATCH_AUTHORIZATION_TTL_MS = 2_000;
  const COMPLETION_SETTLE_MS = 2_000;
  // UTF-16 character limit shared with the Android transport/runtime. Prompt
  // originals are rejected intact before any editor/upload/send side effect.
  const MAX_PROMPT_CHARS = 250_000;
  const MAX_STRUCTURED_ENVELOPE_CHARS = 250_000;
  const MAX_INLINE_EVENT_CHARS = 900_000;
  const CAPTURE_PART_CHARS = 100_000;
  const MAX_CAPTURE_CHARS = 10_000_000;
  const MAX_PROGRESS_PREVIEW_CHARS = 2_000;
  let disposed = false;
  let active = null;
  let observer;
  let interval;
  let inspectionScheduled = false;
  let lastStateKey = '';
  let lastQuotaKey = '';
  let quotaLatched = false;
  let observedSpeed = null;
  let lastInputRequiredKey = '';
  let authenticationObservedAt = 0;
  let authenticationInvalidated = false;
  let connectionPreparation = null;
  const ACCOUNT_EVIDENCE_TTL_MS = 5 * 60_000;
  const ACCOUNT_CONTROL = '[data-testid="user-menu-button"], [data-testid="profile-button"], [data-testid="accounts-profile-button"], [data-testid="account-menu-button"]';
  const receipts = new Map();
  const seenRequestIds = new Set();

  function normalized(value) {
    return String(value || '').normalize('NFKC').replace(/[\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/g, '').replace(/\s+/g, ' ').trim();
  }
  function textOf(element) { return normalized(element && (element.innerText || element.textContent)); }
  // Accessibility names such as "추론 수준 선택" describe the action, not the
  // current GPT-6.1 Sol / Medium value. Read rendered text independently.
  function renderedWords(element, editor = false) {
    const readable=node=>{
      if(!editor)return visible(node);
      if(!rendered(node,false))return false;
      // A modal can aria-hide the composer outside the editor. Decorations
      // inside the editable still must not count as the owner's draft.
      for(let ancestor=node;ancestor&&element.contains(ancestor);ancestor=ancestor.parentElement) {
        if(ancestor.getAttribute('aria-hidden')==='true')return false;
      }
      return true;
    };
    if (!element || !readable(element)) return '';
    const words = [], walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const parent = node.parentElement;
      if (parent && readable(parent) && !parent.closest('svg,script,style,[data-lexical-placeholder],.ProseMirror-widget,[data-testid="composer-placeholder"]')) {
        const word = normalized(node.textContent); if (word) words.push(word);
      }
    }
    return normalized(words.join(' '));
  }
  function controlLabels(element) { return [...new Set([labelOf(element), normalized(element && element.getAttribute('title')), renderedWords(element)].filter(Boolean))]; }
  function labelOf(element) { return normalized(element && (element.getAttribute('aria-label') || element.getAttribute('title') || element.innerText || element.textContent)); }
  function rendered(element, accessibility = true, opacity = true) {
    if (!element || !element.isConnected) return false;
    for (let parent = element; parent; parent = parent.parentElement) {
      if (parent.matches('[hidden], [inert]') || accessibility && parent.getAttribute('aria-hidden') === 'true') return false;
      const style = getComputedStyle(parent);
      if (style.display === 'none' || /^(hidden|collapse)$/.test(style.visibility) || opacity && style.opacity === '0') return false;
    }
    return element.getClientRects().length > 0;
  }
  function visible(element) { return rendered(element); }
  function all(selector, scope) {
    try { return Array.from((scope || document).querySelectorAll(selector)).filter(visible); }
    catch (_) { return []; }
  }
  function controls() { return all(CONTROL).filter(element => !element.closest(TRANSCRIPT)); }
  function selected(element) {
    return element.getAttribute('aria-pressed') === 'true' || element.getAttribute('aria-selected') === 'true'
      || element.getAttribute('aria-checked') === 'true' || /^(?:page|true)$/.test(element.getAttribute('aria-current') || '')
      || /^(?:on|checked|active)$/.test(element.getAttribute('data-state') || '')
      || element.getAttribute('data-selected') === 'true';
  }
  function isWork(element) { return /^(?:Work|ChatGPT Work|Work mode|워크|작업|Work 모드)$/i.test(labelOf(element)); }
  function isTemporary(element) { return /^(?:Temporary(?: chat)?|임시 (?:채팅|대화)|Turn (?:on|off) temporary chat|임시 (?:채팅|대화) (?:켜기|끄기))$/i.test(labelOf(element)); }
  function temporaryOn(element) { return isTemporary(element) && (selected(element) || /^(?:Turn off temporary chat|임시 (?:채팅|대화) 끄기)$/i.test(labelOf(element))); }
  function composer() {
    // Some layouts place the historic ID on the composer shell. Never read or
    // fill its toolbar/help text as if it were the editable message body.
    const raw = cos ? cos.composer() : null;
    const editable = node => node && node.matches('textarea,input:not([type="hidden"]),[contenteditable="true"]')
      && !node.closest(TRANSCRIPT) && rendered(node,false);
    if (editable(raw)) return raw;
    const nested = raw ? Array.from(raw.querySelectorAll('textarea,[contenteditable="true"]')).filter(editable) : [];
    if (nested.length) return nested.length === 1 ? nested[0] : null;
    const candidates = Array.from(document.querySelectorAll(COMPOSER)).filter(editable);
    return candidates.length === 1 ? candidates[0] : null;
  }
  function composerScope(element) { return element && (element.closest('form') || element.closest('[data-chatgpt-composer]')); }
  function stopButtons() {
    const cosStop = cos && cos.stopButton();
    const scope = composerScope(composer());
    const buttons = cosStop && visible(cosStop) ? [cosStop] : all(STOP, scope || document).filter(element => !element.closest(TRANSCRIPT));
    if (scope) for (const button of all('button[type="button"].size-token-button-composer', scope)) {
      if (Array.from(button.querySelectorAll('svg.icon-primary-action > path')).some(path => path.getAttribute('d') === STOP_ICON_PATH)
        && !buttons.includes(button)) buttons.push(button);
    }
    return buttons;
  }
  function sendButtons(editor) {
    const scope = composerScope(editor);
    const button = cos && cos.sendButton();
    return scope && button && scope.contains(button) && visible(button) ? [button] : [];
  }
  function enabled(element) { return element && !element.disabled && element.getAttribute('aria-disabled') !== 'true'; }
  function notify(event) {
    if (disposed) return false;
    try {
      if (window.HoyoungHost && typeof window.HoyoungHost.postMessage === 'function') {
        const payload = JSON.stringify(event);
        if (payload.length > MAX_INLINE_EVENT_CHARS) return false;
        window.HoyoungHost.postMessage(payload);
        return true;
      }
    } catch (_) { /* Native journal retains uncertain dispatches; never replay to repair a lost event. */ }
    return false;
  }
  function result(code, extras) {
    return Object.assign({ ok: code === 'ready', code, state: readState(), knownNotSent: false }, extras || {});
  }
  function emitError(code, requestId, extras) {
    const event = Object.assign({ type: 'error', code, state: readState(), knownNotSent: false }, extras || {});
    if (requestId) event.requestId = requestId;
    notify(event);
  }
  function preflightFailure(code, requestId, extras) {
    const metadata = Object.assign({ knownNotSent: true, retryAllowed: code === 'busy' }, extras || {});
    emitError(code, requestId, metadata);
    return result(code, Object.assign({ requestId }, metadata));
  }
  function parseModel(label) {
    // Match the entire observable label, not promotional text, a menu offer or
    // a model name embedded in an assistant answer. Known compact family labels
    // remain compatible; other future names require an explicit GPT prefix.
    const combined = parseCombinedModel(label);
    if (combined) return combined.model;
    const family = /^(?:GPT[-\s]?)?(\d+(?:\.\d+)?)\s*[- ]?\s*(Sol|Astra|Luna)$/i.exec(label);
    if (family) return 'gpt-' + family[1] + '-' + family[2].toLowerCase();
    const canonical = label.toLowerCase().replace(/^gpt(?:\s*-\s*|\s+)/, 'gpt-').replace(/\s+/g, '-');
    // A styled header can split GPT-6.1 / Sol / Medium into separate spans.
    // The version fragment is not a second, conflicting model identity.
    if (/^gpt-\d+(?:\.\d+)?$/.test(canonical)) return null;
    return canonical.length <= 80 && MODEL_ID.test(canonical) ? canonical : null;
  }
  function requiredPreferences(payload, defaults) {
    const preferences = {
      model: payload.requiredModel === undefined ? defaults.model : payload.requiredModel,
      effort: payload.requiredEffort === undefined ? defaults.effort : payload.requiredEffort,
      speed: payload.requiredSpeed === undefined ? defaults.speed : payload.requiredSpeed
    };
    if (typeof preferences.model !== 'string' || preferences.model.length > 80 || !MODEL_ID.test(preferences.model)
      || !EFFORTS.includes(preferences.effort) || !SPEEDS.includes(preferences.speed)) return null;
    return preferences;
  }
  function applyPreferences(payload) {
    const validObject = payload && typeof payload === 'object' && !Array.isArray(payload)
      && Object.keys(payload).length === 3 && ['requiredModel', 'requiredEffort', 'requiredSpeed'].every(key => Object.prototype.hasOwnProperty.call(payload, key));
    const preferences = validObject && requiredPreferences(payload, {});
    preferenceConfigurationValid = !!preferences;
    if (preferences) configuredPreferences = preferences;
    return preferences;
  }
  function configurePreferences(payload) {
    applyPreferences(payload);
    // A malformed explicit setting does not restore old/default requirements.
    // Active requests keep their immutable per-dispatch preferences. Explicit
    // read-only configuration disarms automatic mutation, including after an
    // active request completes; pending click evidence is nevertheless retained.
    if (preferenceSelection) preferenceSelection.enabled = false;
    return inspectAndEmit();
  }
  function uniqueValue(values) {
    const unique = Array.from(new Set(values.filter(Boolean)));
    return unique.length === 1 ? unique[0] : null;
  }
  function parseEffort(label) {
    const labels = { low: 'low', light: 'low', medium: 'medium', high: 'high', xhigh: 'xhigh', 'extra high': 'xhigh', max: 'max', ultra: 'ultra', 미디엄: 'medium', 중간: 'medium' };
    return labels[label.toLowerCase()] || null;
  }
  function parseSpeed(label) {
    return { fast: 'fast', standard: 'standard', flex: 'flex', 빠름: 'fast' }[label.toLowerCase()] || null;
  }
  function parseCombinedModel(label) {
    const match = /^(?:GPT[-\s]?)?(\d+(?:\.\d+)?)\s*[- ]?\s*(Sol|Astra|Luna)\s*(Low|Light|Medium|High|XHigh|Extra high|Max|Ultra|미디엄|중간)(?:\s*[·|,]?\s*(Fast|Standard|빠름))?$/i.exec(label);
    return match ? { model: 'gpt-' + match[1] + '-' + match[2].toLowerCase(), effort: parseEffort(match[3]), speed: match[4] ? parseSpeed(match[4]) : null } : null;
  }
  function modelTriggers() {
    const candidates=controls();
    // A modal popup can aria-hide its still rendered owner. Keep only the exact
    // button already identified in this document, not arbitrary hidden controls.
    if(observedModelTrigger && rendered(observedModelTrigger,false) && !candidates.includes(observedModelTrigger))candidates.push(observedModelTrigger);
    const triggers=candidates.filter(element => !element.closest('[role="menu"], [role="listbox"], [data-model-picker-view], '+TRANSCRIPT)
      && (element.matches(MODEL_CONTROL) || element.matches('button[aria-haspopup="menu"], button[aria-haspopup="dialog"]')
        && !!element.closest('form, [data-chatgpt-composer]') && (element===observedModelTrigger
          || controlLabels(element).some(label => !!parseCombinedModel(label)
            || /^(?:추론 수준 선택|추론 강도 선택|모델 선택|Select (?:model|reasoning effort|thinking effort)|Thinking effort)$/i.test(label)))));
    if(triggers.length===1)observedModelTrigger=triggers[0];
    return triggers;
  }
  function pickerVisible(element, animating = false) {
    if(rendered(element,true,!animating))return true;
    // display:contents is common on portal wrappers and has no own layout box.
    return !!(element && element.isConnected && getComputedStyle(element).display==='contents'
      && Array.from(element.children).some(child=>pickerVisible(child,animating)));
  }
  function ownedMenus(triggers, animating = false) {
    if (triggers.length !== 1) return [];
    const trigger = triggers[0];
    if (trigger.getAttribute('aria-expanded') === 'false' || trigger.getAttribute('data-state') === 'closed') return [];
    const eligible=menu=>menu && !menu.matches('html,body,main,form,textarea,input,[contenteditable="true"]')
      && !menu.contains(trigger) && !menu.closest(TRANSCRIPT+', [data-state="closed"]') && pickerVisible(menu,animating);
    const roots=nodes=>[...new Set(nodes)].filter(menu=>!nodes.some(other=>other!==menu&&other.contains(menu)));
    const ids = normalized([trigger.getAttribute('aria-controls'), trigger.getAttribute('aria-owns')].filter(Boolean).join(' ')).split(' ').filter(Boolean);
    // aria-controls points to an element ID, not necessarily a role=menu node.
    const linked=ids.map(id=>document.getElementById(id)).concat(Array.from(document.querySelectorAll('[aria-labelledby]'))
      .filter(menu=>trigger.id&&normalized(menu.getAttribute('aria-labelledby')).split(' ').includes(trigger.id))).filter(eligible);
    if(linked.length)return roots(linked);
    if(!trigger.matches(MODEL_CONTROL) && trigger!==observedModelTrigger && trigger!==openedPreferenceTrigger)return [];
    const panels=Array.from(document.querySelectorAll(PICKER_CONTENT)).filter(eligible);
    // Older/current mobile builds also expose a header plus power slider without
    // the picker wrapper marker. Bind their smallest shared non-composer surface.
    for(const header of Array.from(document.querySelectorAll('[data-model-picker-view-toggle]'))) {
      if(!pickerVisible(header,animating) || header.closest(TRANSCRIPT))continue;
      for(let panel=header.parentElement;panel&&!panel.matches('html,body,main,form');panel=panel.parentElement) {
        if(panel.contains(trigger))break;
        if(Array.from(panel.querySelectorAll(EFFORT_SLIDER)).some(slider=>pickerVisible(slider,animating))) {
          if(eligible(panel))panels.push(panel.closest('[role="menu"],[role="dialog"]')||panel);
          break;
        }
      }
    }
    const unique=roots(panels.filter(eligible));
    return unique.length===1?unique:[];
  }
  function finishOwnedPickerAnimations() {
    if(!openedPreferenceTrigger)return;
    // A hidden WebView may suspend the popup entrance/exit animation. Finish only
    // finite animations on the explicitly owned, open picker; other UI is untouched.
    const closing=preferenceCloseFlight&&preferenceCloseFlight.surface;
    const menus=closing&&closing.isConnected?[closing]:ownedMenus([openedPreferenceTrigger],true);
    if(menus.length!==1)return;
    for(const animation of menus[0].getAnimations ? menus[0].getAnimations({subtree:true}) : []) {
      try {if(animation.playState!=='finished' && Number.isFinite(animation.effect.getComputedTiming().endTime))animation.finish();}catch(_){}
    }
  }
  function pickerHeaders() {
    return ownedMenus(modelTriggers()).flatMap(menu => {
      const marked=all('[data-model-picker-view-toggle]',menu);
      if(marked.length)return marked;
      return all('button,[role="button"],[role="menuitem"]',menu).filter(node=>
        /^(?:모델 선택|Select model|Choose model)$/i.test(normalized(node.getAttribute('aria-label')))
        && !node.closest('[role="menuitemradio"],[role="option"]'));
    });
  }
  function pickerReadings() {
    const values = [];
    for (const header of pickerHeaders()) {
      values.push(renderedWords(header));
      for (const node of all('[data-menu-row-content],span,div',header)) {
        if (!node.querySelector('button,[role="menuitemradio"],[role="option"]')) values.push(renderedWords(node));
      }
    }
    for (const menu of ownedMenus(modelTriggers())) for (const region of all(EFFORT_SLIDER,menu)) {
      const owner=region.closest('[aria-describedby]');
      for (const id of normalized(owner && owner.getAttribute('aria-describedby')).split(' ')) {
        const node=document.getElementById(id);
        if (node && menu.contains(node) && visible(node)) values.push(renderedWords(node).split(/[,，]/)[0]);
      }
      for (const slider of region.querySelectorAll('[role="slider"]')) {
        const value=normalized(slider.getAttribute('aria-valuetext')); if(value)values.push(value);
      }
    }
    const models=[], efforts=[];
    for (const label of values) {
      const combined=parseCombinedModel(label);
      const reverse=/^(Low|Light|Medium|High|XHigh|Extra high|Max|Ultra)\s+((?:GPT[-\s]?)?\d+(?:\.\d+)?\s*[- ]?\s*(?:Sol|Astra|Luna))$/i.exec(label);
      const model=combined ? combined.model : reverse ? parseModel(reverse[2]) : parseModel(label);
      const effort=combined ? combined.effort : reverse ? parseEffort(reverse[1]) : parseEffort(label);
      if(model)models.push(model);if(effort)efforts.push(effort);
    }
    return {models,efforts};
  }
  function fastControlState(element) {
    const labels=controlLabels(element).concat(normalized(element.getAttribute('aria-describedby')).split(' ').map(id=>document.getElementById(id))
      .filter(node=>node && visible(node) && !node.closest(TRANSCRIPT)).map(node=>renderedWords(node)));
    const name=/^(?:Fast(?: mode| responses?| response mode| 모드)?|빠름|빠른 (?:응답|모드)|빠른 응답 모드)$/i;
    const onAction=/^(?:(?:Disable|Turn off) fast(?: mode| responses?| response mode)?|(?:Enable|Turn on) standard(?: mode| responses?| response mode)?)$|^(?:Fast(?: 모드)?|빠름|빠른 (?:응답|모드)|빠른 응답 모드) (?:끄기|해제|비활성화)$|^Standard(?: 모드)? (?:켜기|활성화)$/i;
    const offAction=/^(?:Enable|Turn on) (?:fast(?: mode| responses?| response mode)?)$|^(?:Fast(?: 모드)?|빠름|빠른 (?:응답|모드)|빠른 응답 모드) (?:켜기|활성화)$/i;
    const values=[];
    if(labels.some(label=>name.test(label)||onAction.test(label)||offAction.test(label))) {
      for(const attribute of ['aria-checked','aria-pressed']) {
        const value=element.getAttribute(attribute);if(value==='true'||value==='false')values.push(value==='true'?'fast':'standard');
      }
      const state=element.getAttribute('data-state');
      if(/^(on|checked)$/.test(state||''))values.push('fast');if(/^(off|unchecked)$/.test(state||''))values.push('standard');
    }
    // Work's Fast checkbox is checked=true when Fast is ON, but its action
    // label then becomes "Standard 모드 활성화" (the mode a click would enable).
    // Also accept versions with a constant Fast label. Read both sides without
    // retaining a DOM node: the provider may replace it after the click.
    // Explicit ARIA state wins over whether the caption sounds like an action.
    if(values.length)return uniqueValue(values);
    for(const label of labels) {if(onAction.test(label))values.push('fast');if(offAction.test(label))values.push('standard');}
    return uniqueValue(values);
  }
  function fastToggles() {
    const menus = ownedMenus(modelTriggers());
    return controls().filter(element => element.matches('button,[role="switch"], [role="checkbox"], [role="menuitemcheckbox"]')
      && (menus.some(menu => menu.contains(element)) || element.closest('[role="group"][aria-label="Speed"], [role="group"][aria-label="속도"]'))
      && !!fastControlState(element));
  }
  function fastToggleValue(element) { return fastControlState(element); }
  function invalidatePopupEvidence(event) {
    if (event.target instanceof Element && (modelTriggers().some(trigger => trigger.contains(event.target))
      || ownedMenus(modelTriggers()).some(menu=>menu.contains(event.target)))) observedSpeed = null;
  }
  function preferenceEvidence() {
    const chosen = controls().filter(selected);
    const triggers = { model: modelTriggers(), effort: preferenceTriggers('effort'), speed: preferenceTriggers('speed') };
    const menus = { model: ownedMenus(triggers.model), effort: ownedMenus(triggers.effort), speed: ownedMenus(triggers.speed) };
    const toggles = fastToggles();
    const modelControls = triggers.model
      .filter(element => !element.closest(TRANSCRIPT + ', [role="menu"], [role="listbox"]')
        && (!['aria-pressed', 'aria-selected', 'aria-checked', 'aria-current', 'data-selected'].some(name => element.hasAttribute(name)) || selected(element))
        && !/^(?:off|unchecked|inactive)$/.test(element.getAttribute('data-state') || ''));
    const isDimensionEvidence = (element, dimension) => {
      if (element.closest('[role="menu"], [role="listbox"]')) return menus[dimension].some(menu => menu.contains(element));
      if (dimension === 'model' && modelControls.includes(element) || triggers[dimension].includes(element)) return true;
      const explicitDescription = dimension === 'effort'
        ? /^(?:Reasoning effort|Thinking effort|추론 강도|생각 강도)\s*[:：]/i
        : dimension === 'speed' ? /^(?:Speed|Response speed|속도|응답 속도)\s*[:：]/i : /^(?:Model|AI model|모델)\s*[:：]/i;
      if (explicitDescription.test(labelOf(element))) return true;
      const group = element.closest('[role="group"], [role="radiogroup"]');
      if (!group) return false;
      const labels = [normalized(group.getAttribute('aria-label'))].concat(normalized(group.getAttribute('aria-labelledby')).split(' ').filter(Boolean)
        .map(id => document.getElementById(id)).filter(label => label && visible(label) && !label.closest(TRANSCRIPT)).map(textOf));
      const groupLabel = dimension === 'effort' ? /^(?:Reasoning effort|Thinking effort|추론 강도|생각 강도)$/i
        : dimension === 'speed' ? /^(?:Speed|Response speed|속도|응답 속도)$/i : /^(?:Model|AI model|모델)$/i;
      return labels.some(label => groupLabel.test(label));
    };
    const picker=pickerReadings();
    const model = uniqueValue(modelControls.concat(chosen.filter(element => isDimensionEvidence(element, 'model'))).flatMap(element => controlLabels(element).map(parseModel)).concat(picker.models));
    const currentControls = chosen.concat(modelControls, triggers.effort, triggers.speed);
    const combined = modelControls.flatMap(element => controlLabels(element).map(parseCombinedModel)).filter(Boolean);
    const effort = uniqueValue(picker.efforts.concat(combined.map(item => item.effort), currentControls.filter(element => isDimensionEvidence(element, 'effort')).map(element => {
      const label = labelOf(element);
      if (selected(element) && parseEffort(label)) return parseEffort(label);
      const description = /^(?:Reasoning effort|Thinking effort|추론 강도|생각 강도)\s*[:：]\s*(Low|Light|Medium|High|XHigh|Extra high|Max|Ultra|미디엄|중간)$/i.exec(label);
      if (description) return parseEffort(description[1]);
      return null;
    })));
    const speedValues = combined.map(item => item.speed).concat(toggles.map(fastToggleValue), currentControls.filter(element => isDimensionEvidence(element, 'speed')).map(element => {
      const label = labelOf(element);
      if (selected(element) && parseSpeed(label)) return parseSpeed(label);
      const description = /^(?:Speed|Response speed|속도|응답 속도)\s*[:：]\s*(Fast|Standard|Flex|빠름)$/i.exec(label);
      if (description) return parseSpeed(description[1]);
      return null;
    }));
    let speed = uniqueValue(speedValues);
    const popupSpeed = toggles.some(element => menus.model.some(menu=>menu.contains(element)))
      || chosen.some(element => element.closest('[role="menu"], [role="listbox"]') && isDimensionEvidence(element, 'speed') && parseSpeed(labelOf(element)));
    if (speed) observedSpeed = popupSpeed && model && effort ? { value: speed, model, effort } : null;
    else if (speedValues.filter(Boolean).length) observedSpeed = null; // Conflicting evidence is never hidden by a remembered menu.
    else if (observedSpeed && observedSpeed.model === model && observedSpeed.effort === effort) speed = observedSpeed.value;
    else observedSpeed = null;
    return { model, effort, speed };
  }

  function preferenceSelectionGate(state) {
    if (disposed || document !== initialDocument || location.origin !== 'https://chatgpt.com' || location.pathname !== initialPath) return 'unsupported_origin';
    if (!preferenceConfigurationValid) return 'unsupported_preferences';
    if (active) return active.phase === 'uncertain' ? 'dispatch_uncertain' : 'busy';
    if (state.authenticated !== true) return state.authenticated === false ? 'authentication_required' : 'authentication_unverified';
    if (connectionPreparation && connectionPreparation.enabled) return 'connection_preparing';
    if (!state.workSelected) return 'work_unverified';
    if (cleaningSession) return 'conversation_cleanup_pending';
    if (state.conversationOwned !== true) return 'unowned_conversation';
    if (state.busy || state.quota || state.needsInput) return state.quota ? 'quota_exhausted' : state.needsInput ? 'input_required' : 'busy';
    const editor = composer(), scope = composerScope(editor);
    if (!editor || !scope) return 'composer_unverified';
    if (editorText(editor)) return 'composer_has_draft';
    if (all('[data-testid="attachment"], [data-file-name], [role="group"][aria-label], [role="progressbar"], progress', scope).length
      || Array.from(scope.querySelectorAll('input[type="file"]')).some(input => input.files && input.files.length)) return 'composer_has_attachments';
    // An empty current value is a reason to OPEN the known picker and read it,
    // not a reason to prevent the only operation that can reveal that value.
    if ((!state.model || !state.effort) && modelTriggers().length !== 1) return 'preference_selection_unverified';
    return 'safe';
  }
  function optionValue(element, dimension) {
    return uniqueValue(controlLabels(element).map(label=>dimension === 'model' ? parseModel(label) : dimension === 'effort' ? parseEffort(label) : parseSpeed(label)));
  }
  function preferenceOptions(dimension, value) {
    if (dimension === 'speed') {
      const toggles = fastToggles();
      if (toggles.length === 1 && fastToggleValue(toggles[0]) !== value) return toggles;
    }
    const menus = preferenceMenus(dimension);
    if (menus.length !== 1) return [];
    return controls().filter(element => {
      const role = element.getAttribute('role');
      const explicitOption = ['menuitemradio', 'radio', 'option', 'menuitem'].includes(role)
        || element.matches('button') && !!element.closest('[role="menu"], [role="listbox"]');
      return explicitOption && menus[0].contains(element) && optionValue(element, dimension) === value;
    });
  }
  function preferenceMenus(dimension) {
    return ownedMenus(preferenceTriggers(dimension));
  }
  function preferenceTriggers(dimension) {
    if (dimension === 'model') return modelTriggers().filter(element => element.matches('button, [role="button"]'));
    const dedicated = controls().filter(element => {
      if (element.closest('[role="menu"], [role="listbox"]') || !element.matches('button, [role="button"]')) return false;
      if (!/^(?:menu|listbox|true)$/.test(element.getAttribute('aria-haspopup') || '')) return false;
      const label = labelOf(element);
      return dimension === 'effort'
        ? /^(?:Reasoning effort|Thinking effort|추론 강도|생각 강도)(?:\s*[:：]\s*(?:Low|Light|Medium|High|XHigh|Extra high|Max|Ultra|미디엄|중간))?$/i.test(label)
        : /^(?:Speed|Response speed|속도|응답 속도)(?:\s*[:：]\s*(?:Fast|Standard|Flex|빠름))?$/i.test(label);
    });
    return dedicated.length || dimension === 'model' ? dedicated : modelTriggers().filter(element => /^(?:menu|listbox|dialog|true)$/.test(element.getAttribute('aria-haspopup') || ''));
  }
  function activatePickerControl(element, opening) {
    // Radix mobile triggers open on pointerdown, while ordinary buttons use
    // click. A consumed pointerdown must not be followed by a second toggle.
    if(opening && typeof PointerEvent==='function') {
      const before=element.getAttribute('aria-expanded');
      const down=new PointerEvent('pointerdown',{bubbles:true,cancelable:true,button:0,pointerId:1,pointerType:'mouse',isPrimary:true});
      element.dispatchEvent(down);
      element.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,button:0,pointerId:1,pointerType:'mouse',isPrimary:true}));
      if(down.defaultPrevented || element.getAttribute('aria-expanded')!==before)return;
    }
    element.click();
  }
  function clickPreference(element, dimension, value, opening) {
    const selection = preferenceSelection;
    if (!selection || !selection.enabled || selection.clicks >= MAX_PREFERENCE_SELECTION_CLICKS || !enabled(element)) {
      if (selection) selection.status = 'preference_selection_limit';
      return;
    }
    selection.clicks++;
    if(selection.deadline==null)selection.deadline=performance.now()+PREFERENCE_OPERATION_TIMEOUT_MS;
    const ownsOpening=opening && modelTriggers().includes(element) && ownedMenus([element]).length===0;
    if(ownsOpening)openedPreferenceTrigger=element;
    preferenceSelectionFlight = { dimension, value, opening, deadline: performance.now() + PREFERENCE_SELECTION_TIMEOUT_MS };
    selection.status = 'preference_selection_pending';
    try { activatePickerControl(element,opening); }
    catch (_) { preferenceSelectionUncertain = true; selection.status = 'preference_selection_uncertain'; }
  }
  function sliderControls() {
    return ownedMenus(modelTriggers()).flatMap(menu=>all(EFFORT_SLIDER,menu).map(region=>{
      const thumbs=Array.from(region.querySelectorAll('[role="slider"]'));
      if(thumbs.length!==1 || region.closest('[aria-disabled="true"]'))return null;
      const thumb=thumbs[0], min=Number(thumb.getAttribute('aria-valuemin')), max=Number(thumb.getAttribute('aria-valuemax')), now=Number(thumb.getAttribute('aria-valuenow'));
      if(!['aria-valuemin','aria-valuemax','aria-valuenow'].every(a=>thumb.hasAttribute(a)) || ![min,max,now].every(Number.isInteger) || min<0 || max-min>12 || now<min || now>max)return null;
      const target=region.closest('[role="menuitem"],[aria-keyshortcuts]')||thumb;
      return enabled(target)?{target,now,min,max}:null;
    }).filter(Boolean));
  }
  function moveEffortSlider(state) {
    const sliders=sliderControls(), desired=EFFORTS.indexOf(configuredPreferences.effort), current=EFFORTS.indexOf(state.effort);
    if(sliders.length!==1 || current<0 || desired<0 || desired===current)return false;
    const slider=sliders[0], direction=desired>current?1:-1;
    if(slider.now+direction<slider.min || slider.now+direction>slider.max)return false;
    preferenceSelection.clicks++;
    if(preferenceSelection.deadline==null)preferenceSelection.deadline=performance.now()+PREFERENCE_OPERATION_TIMEOUT_MS;
    preferenceSelection.status='preference_selection_pending';
    preferenceSelectionFlight={dimension:'effort',value:configuredPreferences.effort,slider:true,previousPosition:slider.now,deadline:performance.now()+PREFERENCE_SELECTION_TIMEOUT_MS};
    try {
      slider.target.focus({preventScroll:true});
      slider.target.dispatchEvent(new KeyboardEvent('keydown',{key:direction>0?'ArrowRight':'ArrowLeft',bubbles:true,cancelable:true}));
    } catch(_) {preferenceSelectionUncertain=true;preferenceSelection.status='preference_selection_uncertain';}
    return true;
  }
  function closeOwnedPicker() {
    if(!openedPreferenceTrigger)return false;
    const menus=ownedMenus([openedPreferenceTrigger]);
    if(!menus.length){
      // aria-expanded=false may precede removal of the modal's focus scope.
      // Wait for the exact previously observed surface to stop rendering.
      if(preferenceCloseFlight && pickerVisible(preferenceCloseFlight.surface))return true;
      openedPreferenceTrigger=null;preferenceCloseFlight=null;return false;
    }
    if(preferenceCloseFlight) {
      if(performance.now()>=preferenceCloseFlight.deadline) {preferenceSelectionUncertain=true;preferenceSelection.status='preference_selection_uncertain';}
      return true;
    }
    preferenceCloseFlight={deadline:performance.now()+PREFERENCE_SELECTION_TIMEOUT_MS,surface:menus[0]};
    preferenceSelection.status='preference_selection_pending';
    const target=menus[0].contains(document.activeElement)?document.activeElement:menus[0];
    target.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));
    return true;
  }
  function advancePreferenceSelection() {
    const selection = preferenceSelection;
    if (!selection || !selection.enabled || selection.failed || !preferenceConfigurationValid) return;
    finishOwnedPickerAnimations();
    // Deadlines are evaluated BEFORE draft/model gates. An unmounted value or a
    // newly typed draft must never turn a five-second attempt into infinite loading.
    if((preferenceSelectionFlight && performance.now()>=preferenceSelectionFlight.deadline)
      || (preferenceCloseFlight && performance.now()>=preferenceCloseFlight.deadline)
      || (selection.deadline!=null && performance.now()>=selection.deadline)) {
      preferenceSelectionUncertain=true;selection.status='preference_selection_uncertain';selection.enabled=false;return;
    }
    const state = readState(), gate = preferenceSelectionGate(state);
    if (gate !== 'safe') {
      selection.status = gate;
      // A draft/active response appearing during a delayed selection forbids
      // further automatic changes, even if a late UI acknowledgement arrives.
      if (preferenceSelectionFlight) preferenceSelectionUncertain = true;
      return;
    }
    if (preferenceSelectionUncertain) { selection.status = 'preference_selection_uncertain'; return; }
    if (preferenceSelectionFlight) {
      const flight = preferenceSelectionFlight;
      if (performance.now() >= flight.deadline) {
        preferenceSelectionUncertain = true; selection.status = 'preference_selection_uncertain'; return;
      }
      if(flight.slider && sliderControls().length===1 && sliderControls()[0].now!==flight.previousPosition && state.effort) {
        preferenceSelectionFlight=null;
      } else if (!flight.opening && state[flight.dimension] === flight.value) {
        preferenceSelectionFlight = null;
      } else if (flight.opening) {
        if (state[flight.dimension] === configuredPreferences[flight.dimension]) {
          preferenceSelectionFlight = null;
          selection.status = 'pending';
          return;
        }
        const choices = preferenceOptions(flight.dimension, configuredPreferences[flight.dimension]);
        if (choices.length === 1 && enabled(choices[0])) {
          // If the owner has changed the next route while the menu was opening,
          // use only the new route's actually observed option, never the old one.
          const requested = configuredPreferences[flight.dimension];
          const nextChoices = preferenceOptions(flight.dimension, requested);
          if (nextChoices.length !== 1 || !enabled(nextChoices[0])) { selection.status = 'preference_option_unavailable'; return; }
          clickPreference(nextChoices[0], flight.dimension, requested, false);
        } else if(flight.dimension==='effort' && moveEffortSlider(state)) {
          return;
        } else if(flight.dimension==='model' && pickerHeaders().length===1 && ownedMenus(modelTriggers()).length && !flight.headerOpened) {
          flight.headerOpened=true;
          try {activatePickerControl(pickerHeaders()[0],true);}catch(_){preferenceSelectionUncertain=true;}
        }
        return;
      } else return;
    }
    const dimension = ['model', 'effort', 'speed'].find(name => state[name] !== configuredPreferences[name]);
    if (!dimension) { if(closeOwnedPicker())return; selection.status = 'verified'; selection.deadline=null; return; }
    if (selection.failed || selection.clicks >= MAX_PREFERENCE_SELECTION_CLICKS) { selection.status = 'preference_selection_limit'; return; }
    const requested = configuredPreferences[dimension], choices = preferenceOptions(dimension, requested);
    if (choices.length === 1 && enabled(choices[0])) { clickPreference(choices[0], dimension, requested, false); return; }
    if (choices.length > 1) { selection.failed = true; selection.status = 'preference_option_ambiguous'; return; }
    if(dimension==='effort' && moveEffortSlider(state))return;
    if(dimension==='model' && pickerHeaders().length===1 && ownedMenus(modelTriggers()).length) {
      clickPreference(pickerHeaders()[0],dimension,requested,true);return;
    }
    const triggers = preferenceTriggers(dimension);
    if (triggers.length !== 1 || !enabled(triggers[0])) { selection.failed = true; selection.status = 'preference_option_unavailable'; return; }
    if(ownedMenus(triggers).length) { selection.failed=true;selection.status='preference_option_unavailable';return; }
    clickPreference(triggers[0], dimension, requested, true);
  }
  function ensurePreferences(payload) {
    const preferences = applyPreferences(payload);
    if (!preferences) { if (preferenceSelection) preferenceSelection.enabled = false; return inspectAndEmit(); }
    const key = JSON.stringify(preferences);
    if (!preferenceSelection || preferenceSelection.key !== key) preferenceSelection = { key, clicks: 0, enabled: true, failed: false, status: 'pending', deadline:null };
    else if(!preferenceSelectionUncertain && !preferenceSelection.failed)preferenceSelection.enabled = true;
    if (connectionPreparation && connectionPreparation.status === 'verified' && readState().authenticated === null) {
      connectionPreparation = { enabled: true, status: 'pending', accountAttempted: false, sidebarOpened: false, workAttempted: false };
      advanceConnectionPreparation();
    }
    advancePreferenceSelection();
    return inspectAndEmit();
  }
  function captureUsageObservation(payload) {
    const unavailable = code => ({ ok: false, code, usageMeasured: false });
    if (disposed || document !== initialDocument || location.origin !== 'https://chatgpt.com' || location.pathname !== initialPath || window.top !== window)
      return unavailable('usage_document_unverified');
    if (!payload || typeof payload.accountScope !== 'string' || !payload.accountScope.trim() || payload.accountScope.length > 256
      || /[\r\n\x00]/.test(payload.accountScope) || typeof payload.captureNonce !== 'string' || !/^[a-f0-9]{32}$/.test(payload.captureNonce))
      return unavailable('usage_capture_arguments_invalid');
    if (readState().authenticated !== true) return unavailable('usage_authentication_unverified');
    const headings = 'h1, h2, h3, h4, h5, h6, [role="heading"]';
    const usageHeading = /^(?:Usage|Usage limits|사용량|사용량 한도)$/i;
    const weeklyHeading = /^(?:Weekly (?:usage|limit|limits)|주간 (?:사용량|한도))$/i;
    const providerHeading = /^(?:Work|ChatGPT Work|Codex|Work\s*(?:\/|&|and|·)\s*Codex)$/i;
    const outsideTranscript = element => !element.closest(TRANSCRIPT + ', ' + ANSWER);
    const usageHeadings = all(headings).filter(element => outsideTranscript(element) && usageHeading.test(textOf(element)));
    const panels = Array.from(new Set(usageHeadings.map(heading => heading.closest('[role="dialog"], [role="region"], section, main')).filter(Boolean)))
      .filter(panel => outsideTranscript(panel) && all(headings, panel).filter(element => usageHeading.test(textOf(element))).length === 1);
    if (panels.length !== 1) return unavailable('usage_panel_unverified');
    const panel = panels[0];
    // The account's plan and whole Work/Codex pool must both be visible. A model
    // card, five-hour pool or assistant's "Usage" text cannot substitute for it.
    const planLabels = all('p, span, label, h1, h2, h3, [role="heading"]', panel)
      .filter(element => outsideTranscript(element) && /^(?:Pro|Pro plan|ChatGPT Pro|프로 요금제)$/i.test(textOf(element)));
    const providerLabels = all(headings, panel).filter(element => outsideTranscript(element) && providerHeading.test(textOf(element)));
    if (!planLabels.length || providerLabels.length !== 1) return unavailable('usage_pool_scope_unverified');
    const weeklyLabels = all(headings, panel).filter(element => outsideTranscript(element) && weeklyHeading.test(textOf(element)));
    const pools = Array.from(new Set(weeklyLabels.map(heading => heading.closest('section, [role="region"], [role="group"]')).filter(Boolean)))
      .filter(pool => panel.contains(pool) && all(headings, pool).filter(element => weeklyHeading.test(textOf(element))).length === 1);
    if (pools.length !== 1) return unavailable('usage_weekly_pool_unverified');
    const pool = pools[0], poolText = textOf(pool);
    if (/(?:5\s*(?:hour|시간)|five[- ]hour|GPT[-\s]?\d|\b(?:Sol|Astra|Luna)\b)/i.test(poolText)) return unavailable('usage_weekly_pool_ambiguous');
    const metric = text => {
      let match = /^(?:Used|Usage|사용량|사용됨)\s*[:：]?\s*(\d{1,3}(?:\.\d{1,3})?)\s*%$/i.exec(text), remaining = false;
      if (!match) match = /^(\d{1,3}(?:\.\d{1,3})?)\s*%\s*(?:used|사용됨|사용)$/i.exec(text);
      if (!match) { match = /^(?:Remaining|남음|남은 사용량)\s*[:：]?\s*(\d{1,3}(?:\.\d{1,3})?)\s*%$/i.exec(text); remaining = true; }
      if (!match) { match = /^(\d{1,3}(?:\.\d{1,3})?)\s*%\s*(?:remaining|남음)$/i.exec(text); remaining = true; }
      if (!match) return null;
      const displayed = Number(match[1]); if (displayed < 0 || displayed > 100) return null;
      return { usedPercent: remaining ? Number((100 - displayed).toFixed(3)) : displayed,
        precisionPercent: 10 ** -(match[1].split('.')[1] || '').length, text };
    };
    const metricNodes = all('p, span, label, div, [role="status"]', pool).filter(outsideTranscript)
      .map(element => ({ element, value: metric(textOf(element)) })).filter(candidate => candidate.value);
    const metrics = metricNodes.filter(candidate => !metricNodes.some(other => other !== candidate && candidate.element.contains(other.element)));
    if (metrics.length !== 1) return unavailable('usage_percent_unverified');
    const resetTimes = all('time[datetime]', pool).filter(outsideTranscript).filter(element => {
      const row = element.closest('p, label, [role="status"]') || element.parentElement;
      return row && row !== pool && /^(?:Resets?(?: (?:at|on))?|Reset time|재설정|초기화)\s*[:：]?\s*\S/i.test(textOf(row));
    });
    if (resetTimes.length !== 1) return unavailable('usage_reset_unverified');
    const rawReset = resetTimes[0].getAttribute('datetime');
    const dateMatch = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})$/.exec(rawReset || '');
    if (!dateMatch) return unavailable('usage_reset_timezone_unverified');
    const calendar = new Date(Date.UTC(Number(dateMatch[1]), Number(dateMatch[2]) - 1, Number(dateMatch[3])));
    if (calendar.getUTCFullYear() !== Number(dateMatch[1]) || calendar.getUTCMonth() + 1 !== Number(dateMatch[2])
      || calendar.getUTCDate() !== Number(dateMatch[3]) || Number(dateMatch[4]) > 23 || Number(dateMatch[5]) > 59 || Number(dateMatch[6] || 0) > 59)
      return unavailable('usage_reset_date_invalid');
    const observedAt = Date.now(), resetAt = Date.parse(rawReset);
    if (!Number.isFinite(resetAt) || resetAt <= observedAt || resetAt - observedAt > 8 * 24 * 60 * 60 * 1000)
      return unavailable('usage_reset_stale_or_unverified');
    const evidenceText = ['Usage', textOf(planLabels[0]), textOf(providerLabels[0]), textOf(weeklyLabels[0]), metrics[0].value.text,
      'Reset: ' + textOf(resetTimes[0]) + ' [datetime=' + rawReset + ']'].join('\n');
    if (evidenceText.length > 4_000) return unavailable('usage_evidence_unverified');
    return { ok: true, code: 'usage_observed', usageMeasured: true, observation: {
      schemaVersion: 1, captureNonce: payload.captureNonce, accountScope: payload.accountScope.trim(), poolId: 'work-codex:weekly:' + resetAt,
      accountScopeSource: 'owner_label', accountIdentityVerified: false,
      observedAt, resetAt, usedPercent: metrics[0].value.usedPercent, precisionPercent: metrics[0].value.precisionPercent,
      evidenceText, sourceOrigin: location.origin, sourceKind: 'visible-usage-panel', period: 'weekly'
    } };
  }
  function quotaBanner() {
    const candidates = all('[role="alert"], [data-testid="error-message"], [data-testid="toast"], [data-testid*="usage-limit"], [data-testid*="rate-limit"]');
    for (const element of candidates) {
      if (element.closest(ANSWER + ', [data-user-message-bubble]')) continue;
      const text = textOf(element);
      const exhausted = /(?:limit (?:reached|exceeded|exhausted)|(?:reached|hit).{0,40}limit|(?:usage|rate) limit.{0,40}(?:reached|exceeded|exhausted|used up)|too many requests|quota (?:exceeded|exhausted)|no remaining (?:usage|requests|messages|credits)|한도.{0,20}(?:소진|도달|초과)|요청.{0,20}너무.{0,10}많)/i.test(text);
      const zeroRemaining = /(?:usage|rate|quota|credits?|messages?|사용량|한도)/i.test(text)
        && /(?:(?:^|[^\d.])0(?:\.0+)?\s*%\s*remaining|(?:remaining|남은 사용량)\s*[:：]?\s*0(?:\.0+)?\s*%|(?:^|[^\d.])0(?:\.0+)?\s*%\s*남음)/i.test(text);
      // A usage-limit heading or positive remaining allowance is information,
      // not evidence of exhaustion. The advisory target never creates quota.
      if (exhausted || zeroRemaining) return text.slice(0, 800);
    }
    return null;
  }
  function inputRequiredStatus() {
    for (const element of all('[role="dialog"], [role="alert"], [role="status"]')) {
      if (element.closest(ANSWER + ', [data-user-message-bubble]')) continue;
      const text = textOf(element);
      if (/(?:waiting for (?:your|user) (?:input|approval|confirmation)|(?:approval|confirmation|sign[- ]?in|login) required|needs? your (?:input|approval|confirmation)|take over (?:the )?browser|사용자.{0,15}(?:입력|승인).{0,15}(?:기다|필요)|(?:로그인|승인|확인).{0,15}필요|브라우저.{0,15}직접.{0,15}(?:조작|제어))/i.test(text)) return text.slice(0, 800);
    }
    return null;
  }
  function authenticationEvidence(editor, selectable) {
    const logIn = /^(?:Log in|Login|Sign in|로그인)$/i;
    const loggedOut = selectable.some(element => logIn.test(labelOf(element)))
      || all('a[href]').some(element => !element.closest(TRANSCRIPT) && logIn.test(labelOf(element)));
    if (loggedOut) { authenticationObservedAt = 0; authenticationInvalidated = false; return { authenticated: false, authenticationEvidence: 'signed_out_control' }; }
    if (authenticationInvalidated) return { authenticated: null, authenticationEvidence: 'account_change_pending' };
    // Account controls can be mounted in a collapsed sidebar, or rendered only
    // after opening it. Never infer authentication from a model, Work tab, task
    // recommendation, avatar image, or absence of a login button.
    const account = Array.from(document.querySelectorAll(ACCOUNT_CONTROL)).some(element =>
      element.isConnected && element.matches('button,[role="button"]') && !element.closest(TRANSCRIPT))
      || selectable.some(element => /^(?:Open (?:profile|account|user) menu|(?:프로필|계정|사용자) 메뉴(?: 열기)?|프로필 열기|계정 설정 열기)$/i.test(labelOf(element)))
      || selectable.some(element => element.closest('[role="menu"],[role="dialog"],nav,aside')
        && /^(?:Log out|Logout|Sign out|로그아웃)$/i.test(labelOf(element)));
    if (editor && account) authenticationObservedAt = Date.now();
    const age = Date.now() - authenticationObservedAt;
    const cached = authenticationObservedAt > 0 && age >= 0 && age < ACCOUNT_EVIDENCE_TTL_MS;
    return { authenticated: editor && (account || cached) ? true : null,
      authenticationEvidence: account ? 'account_control' : cached ? 'recent_account_control_same_document' : 'unobserved' };
  }
  function invalidateAccountEvidence(event) {
    const element = event.target && event.target.closest && event.target.closest('button,[role="button"],[role="menuitem"],a[href]');
    if (element && !element.closest(TRANSCRIPT) && /^(?:Log out|Logout|Sign out|로그아웃|Switch account|계정 전환)$/i.test(labelOf(element))) {
      authenticationObservedAt = 0; authenticationInvalidated = true;
    }
  }
  function sidebarControls(open) {
    const labels = open ? /^(?:Open sidebar|Expand sidebar|Open navigation menu|사이드바 열기|사이드바 펼치기|탐색 메뉴 열기)$/i
      : /^(?:Close sidebar|Collapse sidebar|Close navigation menu|사이드바 닫기|사이드바 접기|탐색 메뉴 닫기)$/i;
    const testIds = open ? ['open-sidebar-button','sidebar-open-button'] : ['close-sidebar-button','sidebar-close-button'];
    return controls().filter(element => element.matches('button,[role="button"]') && (!open || !element.closest('[role="menu"],[role="dialog"]'))
      && (labels.test(labelOf(element)) || testIds.includes(element.getAttribute('data-testid'))));
  }
  function connectionCanChangeUi(state) {
    if (disposed || active || cleaningSession || document !== initialDocument || location.pathname !== initialPath
      || state.busy || state.quota || state.needsInput) return false;
    const editor = composer(), scope = composerScope(editor);
    return !!(editor && scope && !editorText(editor)
      && !all('[data-testid="attachment"], [data-file-name], [role="group"][aria-label], [role="progressbar"], progress',scope).length
      && !Array.from(scope.querySelectorAll('input[type="file"]')).some(input=>input.files && input.files.length));
  }
  function advanceConnectionPreparation() {
    const preparation = connectionPreparation;
    if (!preparation || !preparation.enabled) return;
    const state = readState();
    if (!connectionCanChangeUi(state)) { preparation.enabled = false; preparation.status = 'preserved_existing_work'; return; }
    if (state.authenticated === false) { preparation.status = 'login_required'; return; }
    if (state.authenticated !== true) {
      if (preparation.accountAttempted) {
        if (performance.now() >= preparation.accountDeadline) { preparation.enabled = false; preparation.status = 'account_unverified'; }
        return;
      }
      const controls = sidebarControls(true);
      if (controls.length !== 1 || !enabled(controls[0])) { preparation.enabled = false; preparation.status = 'account_unverified'; return; }
      preparation.accountAttempted = true; preparation.accountDeadline = performance.now() + 5_000;
      preparation.sidebarOpened = true; preparation.status = 'account_opening';
      try { controls[0].click(); } catch (_) { preparation.enabled = false; preparation.status = 'account_unverified'; }
      return;
    }
    if (preparation.sidebarOpened) {
      // Close only a sidebar this attempt opened, never an owner's menu.
      const close = sidebarControls(false);
      if (close.length === 1 && enabled(close[0])) {
        preparation.sidebarOpened = false;
        try { close[0].click(); } catch (_) { preparation.enabled = false; preparation.status = 'account_menu_review'; return; }
        return; // Let the layout settle before locating Work/model controls.
      }
      if (performance.now() < preparation.accountDeadline) return;
      preparation.enabled = false; preparation.status = 'account_menu_review'; return;
    }
    if (!state.workSelected) {
      // Mode switching is allowed only in an empty, unsubmitted home composer.
      if (location.pathname !== '/' || turnSnapshot().turns.length) { preparation.enabled = false; preparation.status = 'work_unverified'; return; }
      if (preparation.workAttempted) {
        if (performance.now() >= preparation.workDeadline) { preparation.enabled = false; preparation.status = 'work_unverified'; }
        return;
      }
      const work = controls().filter(isWork);
      if (work.length !== 1 || !enabled(work[0])) { preparation.enabled = false; preparation.status = 'work_unverified'; return; }
      preparation.workAttempted = true; preparation.workDeadline = performance.now() + 5_000; preparation.status = 'work_selecting';
      try { work[0].click(); } catch (_) { preparation.enabled = false; preparation.status = 'work_unverified'; }
      return;
    }
    preparation.enabled = false; preparation.status = 'verified';
  }
  function connect(payload) {
    if (!connectionCanChangeUi(readState())) return inspectAndEmit();
    if (connectionPreparation && connectionPreparation.enabled) return ensurePreferences(payload);
    // Explicit connection owns this picker workflow even when the owner opened
    // it first. Release its focus trap before the queued message can be filled.
    const triggers=modelTriggers();
    if(triggers.length===1 && ownedMenus(triggers).length===1)openedPreferenceTrigger=triggers[0];
    // A failed observation/open attempt may be retried explicitly. Never replay
    // an unacknowledged relative slider movement or speed toggle.
    if(preferenceSelection && (preferenceSelection.failed || preferenceSelectionUncertain)) {
      const state=readState(), flight=preferenceSelectionFlight;
      const acknowledged=flight && (state[flight.dimension]===flight.value
        || flight.slider && sliderControls().length===1 && sliderControls()[0].now!==flight.previousPosition);
      if(!flight || flight.opening || acknowledged) {
        preferenceSelection=null;preferenceSelectionFlight=null;preferenceCloseFlight=null;preferenceSelectionUncertain=false;
      }
    }
    connectionPreparation = { enabled: true, status: 'pending', accountAttempted: false, sidebarOpened: false, workAttempted: false };
    advanceConnectionPreparation();
    return ensurePreferences(payload);
  }
  function readState() {
    const selectable = controls();
    const editor = composer();
    const authentication = authenticationEvidence(editor, selectable);
    const temporaryBanner = all('header [role="status"], main > [role="status"], header h1, main > h1')
      .some(element => isTemporary(element) && !element.closest(TRANSCRIPT));
    const selectedWork = selectable.some(element => isWork(element) && selected(element));
    const selectedChat = selectable.some(element => /^(?:Chat|Chat mode|채팅|Chat 모드)$/i.test(labelOf(element)) && selected(element));
    if (selectedChat) verifiedWorkSession = false;
    if (selectedWork && location.pathname === initialPath) verifiedWorkSession = true;
    const ownPath = ownedConversationId && conversationIdFromPath(location.pathname) === ownedConversationId;
    const fresh = !ownedConversationId && location.pathname === '/' && turnSnapshot().turns.length === 0;
    return Object.assign({
      authenticated: authentication.authenticated,
      authenticationEvidence: authentication.authenticationEvidence,
      connectionStatus: connectionPreparation ? connectionPreparation.status : null,
      workSelected: selectedWork || !!(verifiedWorkSession && !selectedChat && (ownPath
        || openedPreferenceTrigger && ownedMenus([openedPreferenceTrigger]).length===1)),
      conversationId: ownPath ? ownedConversationId : null,
      conversationOwned: !!ownPath || fresh,
      temporary: selectable.some(temporaryOn) || temporaryBanner,
      busy: cleaningSession || !!(cos && cos.generating()) || stopButtons().length > 0 || !!(composerScope(editor) && composerScope(editor).getAttribute('aria-busy') === 'true'),
      quota: quotaLatched || !!quotaBanner(),
      needsInput: !!inputRequiredStatus(),
      steeringVerified: false,
      pendingInputPolicy: 'queued'
    }, preferenceEvidence());
  }
  function readyCode(state, preferences) {
    if (disposed || document !== initialDocument || location.origin !== 'https://chatgpt.com') return 'unsupported_origin';
    if (suspendedRoute) return 'conversation_changed';
    if (!preferenceConfigurationValid) return 'unsupported_preferences';
    if (state.authenticated === false) return 'authentication_required';
    if (connectionPreparation && connectionPreparation.enabled && /^(?:pending|account_opening|work_selecting)$/.test(connectionPreparation.status)) return 'connection_preparing';
    if (state.authenticated !== true) return 'authentication_unverified';
    if (!state.workSelected) return 'work_unverified';
    if (cleaningSession) return 'conversation_cleanup_pending';
    if (state.conversationOwned !== true) return 'unowned_conversation';
    if(preferenceSelectionUncertain)return 'preference_selection_uncertain';
    if(!active && composer() && editorText(composer()))return 'composer_has_draft';
    if(preferenceSelection && preferenceSelection.failed)return preferenceSelection.status;
    if(preferenceSelection && /^(?:composer_has_draft|composer_has_attachments)$/.test(preferenceSelection.status))return preferenceSelection.status;
    if(preferenceCloseFlight)return 'preference_selection_pending';
    if (preferenceSelectionFlight) return preferenceSelectionUncertain ? 'preference_selection_uncertain' : 'preference_selection_pending';
    // Observing the requested value is not enough: the opening acknowledgement
    // can precede the next tick that releases the modal focus/aria-hidden scope.
    // Never expose a sendable state while our observed picker is still open.
    if(openedPreferenceTrigger && ownedMenus([openedPreferenceTrigger]).length)return 'preference_selection_pending';
    if (state.model !== (preferences || configuredPreferences).model) return 'model_unverified';
    if (state.effort !== (preferences || configuredPreferences).effort) return 'effort_unverified';
    if (state.speed !== (preferences || configuredPreferences).speed) return 'speed_unverified';
    if (state.quota) return 'quota_exhausted';
    if (state.needsInput) return 'input_required';
    if (state.busy) return 'busy';
    if (!composer() || !composerScope(composer())) return 'composer_unverified';
    return 'ready';
  }
  function inspect() {
    const state = readState();
    state.selectionStatus = preferenceSelection ? preferenceSelection.status : null;
    state.pickerUi={triggerCount:modelTriggers().length,menuCount:ownedMenus(modelTriggers()).length,sliderCount:sliderControls().length,
      fastControlCount:fastToggles().length,composerChars:composer()?editorText(composer()).length:0,
      autoOpened:!!openedPreferenceTrigger,selectionClicks:preferenceSelection?preferenceSelection.clicks:0};
    const code = readyCode(state);
    return { ok: code === 'ready', code, state, requiredPreferences: Object.assign({}, configuredPreferences),
      preferenceSelection: preferenceSelection ? { status: preferenceSelection.status, clicks: preferenceSelection.clicks, enabled: preferenceSelection.enabled } : null,
      activeRequestId: active ? active.requestId : null, phase: active ? active.phase : null };
  }
  function connectionDiagnostics() {
    const snapshot=inspect(), triggers=modelTriggers(), menus=ownedMenus(triggers), editor=composer(), raw=cos&&cos.composer();
    const shape=node=>node?{
      tag:node.tagName.toLowerCase(),role:node.getAttribute('role'),expanded:node.getAttribute('aria-expanded'),state:node.getAttribute('data-state'),
      editable:node.getAttribute('contenteditable'),visible:visible(node),rendered:rendered(node,false),display:getComputedStyle(node).display,
      opacity:getComputedStyle(node).opacity,ariaHidden:!!node.closest('[aria-hidden="true"]'),inert:!!node.closest('[inert]'),
      knownTrigger:node.matches(MODEL_CONTROL),knownPanel:node.matches(PICKER_CONTENT),slider:node.matches(EFFORT_SLIDER)
    }:null;
    // Only control structure and model-picker labels. Never serialize the page,
    // draft body, transcript, input values, storage, tokens, or account menu.
    snapshot.route={path:location.pathname.slice(0,256),observedPath:initialPath.slice(0,256),suspended:suspendedRoute,disposed,disposalReason};
    const turns=turnSnapshot();snapshot.transcript={valid:turns.valid,users:turns.turns.filter(turn=>turn.role==='user').length,assistants:turns.turns.filter(turn=>turn.role==='assistant').length,
      matchingActiveUsers:active?turns.turns.filter(turn=>turn.role==='user'&&!active.baseline.has(turn.id)&&turn.text===normalized(active.text)).length:0};
    snapshot.requestObservation=active?{clicked:!!active.clicked,accepted:!!active.acceptedUserId,uncertaintyCode:active.uncertaintyCode||''}:null;
    snapshot.replyShapes=turns.turns.filter(turn=>turn.role==='assistant').slice(-3).map(turn=>{
      const answer=answerSnapshot(turn);return {complete:answer.complete,chars:answer.text.length,structured:!!answer.structuredEnvelope,
        structuredUnavailable:answer.structuredEnvelopeUnavailable||'',rawEnvelopeEligible:!!answer.rawEnvelopeEligible};
    });
    snapshot.dom={editor:shape(editor),rawComposer:shape(raw),editorWasWrapper:!!raw&&raw!==editor,
      triggers:triggers.slice(0,3).map(trigger=>({...shape(trigger),linked:normalized(trigger.getAttribute('aria-controls')).split(' ')
        .filter(Boolean).slice(0,3).map(id=>shape(document.getElementById(id)))})),
      panels:Array.from(document.querySelectorAll(PICKER_CONTENT+', '+EFFORT_SLIDER)).filter(node=>!node.closest(TRANSCRIPT)).slice(0,8).map(shape),
      pickerControls:menus.length===1?all(CONTROL,menus[0]).slice(0,12).map(node=>({...shape(node),label:controlLabels(node).join(' | ').slice(0,160),pressed:node.getAttribute('aria-pressed'),checked:node.getAttribute('aria-checked')})):[]};
    return snapshot;
  }

  function conversationIdFromPath(path) {
    const match=/^\/(?:g\/[^/]+\/)?c\/([A-Za-z0-9_-]{8,128})\/?$/.exec(path);
    return match?match[1]:'';
  }
  function authoredUserText(element) {
    // A collapsed user bubble includes translated Show more/Copy controls.
    // Compare the authored subtree, never the controls or a loose text prefix.
    const clone=element.cloneNode(true);
    for(const control of clone.querySelectorAll('button,[role="button"],script,style,svg,[hidden],[aria-hidden="true"],[data-markdown-copy="exclude"]'))control.remove();
    for(const block of clone.querySelectorAll('br,div,p,li,ul,ol,blockquote,pre')) {
      if(block.tagName==='BR')block.replaceWith(document.createTextNode('\n'));
      else {block.before(document.createTextNode('\n'));block.after(document.createTextNode('\n'));}
    }
    return normalized(clone.textContent);
  }
  function transcriptRendered(element) {
    if(!element||!element.isConnected)return false;
    for(let node=element;node;node=node.parentElement) {
      const style=getComputedStyle(node);
      if(node.hidden||node.inert||style.display==='none'||/^(hidden|collapse)$/.test(style.visibility))return false;
    }
    return true; // Modal aria-hidden and off-screen/virtualized layout do not change authorship.
  }
  function transcriptAll(selector,root) {return Array.from((root||document).querySelectorAll(selector)).filter(transcriptRendered);}
  function replyRendered(element) {
    if(!transcriptRendered(element))return false;
    for(let node=element;node;node=node.parentElement)if(getComputedStyle(node).opacity==='0')return false;
    return true; // No geometry requirement: detached/off-screen WebViews still own their replies.
  }
  function turnSnapshot() {
    const turns = [];
    const known = new Set();
    const groupKeys = new Set();
    let valid = true;
    for (const group of Array.from(document.querySelectorAll('[data-turn-key]'))) {
      if (group.parentElement && group.parentElement.closest('[data-turn-key]')) continue;
      if(!transcriptRendered(group))continue; // Ignore retained, display:none pages from other conversations.
      const key = normalized(group.getAttribute('data-turn-key'));
      if (!key || groupKeys.has(key)) { valid = false; continue; }
      groupKeys.add(key);
      known.add('group:user:' + key); known.add('group:assistant:' + key);
      const userRoots = transcriptAll('[data-user-message-bubble]', group);
      const assistantSelector='[data-conversation-role="assistant"], [data-content-search-unit-key$=":assistant"], [data-chatgpt-search-unit-key$=":assistant"], [data-chatgpt-agent-turn-start]';
      const assistantRoots = transcriptAll(assistantSelector, group)
        .filter(element => !element.parentElement || !element.parentElement.closest(assistantSelector));
      if (userRoots.length > 1) valid = false;
      if (userRoots.length === 1) {
        const user = userRoots[0];
        const content = transcriptAll('[data-search-result-target]', user);
        turns.push({ id: 'group:user:' + key, key, role: 'user', root: group, content: user, text: authoredUserText(content.length === 1 ? content[0] : user) });
      }
      if (assistantRoots.length) turns.push({ id: 'group:assistant:' + key, key, role: 'assistant', root: group, content: assistantRoots[assistantRoots.length - 1] });
    }
    const legacyKeys = new Set();
    for (const turn of Array.from(document.querySelectorAll('[data-testid^="conversation-turn-"]'))) {
      if (turn.closest('[data-turn-key]') || turn.parentElement && turn.parentElement.closest('[data-testid^="conversation-turn-"]')) continue;
      if(!transcriptRendered(turn))continue;
      const role = turn.getAttribute('data-turn') || turn.getAttribute('data-message-author-role')
        || (turn.querySelector('[data-message-author-role="assistant"]') ? 'assistant' : turn.querySelector('[data-message-author-role="user"]') ? 'user' : null);
      if (role !== 'assistant' && role !== 'user') continue;
      const container = turn.closest('[data-turn-id-container]');
      const key = normalized(turn.getAttribute('data-turn-id') || container && container.getAttribute('data-turn-id-container'));
      if (!key || legacyKeys.has(role + ':' + key)) { valid = false; continue; }
      legacyKeys.add(role + ':' + key);
      const id = 'legacy:' + role + ':' + key;
      const content = turn.querySelector('[data-message-author-role="' + role + '"]') || turn;
      turns.push({ id, key, role, root: turn, content, text: role === 'user' ? authoredUserText(content) : null });
      known.add('legacy:user:' + key); known.add('legacy:assistant:' + key);
    }
    // Virtualized old turns still count in the baseline even when their content unmounts.
    for (const container of Array.from(document.querySelectorAll('[data-turn-id-container]'))) {
      if (container.closest('[data-turn-key]')) continue;
      const key = normalized(container.getAttribute('data-turn-id-container'));
      if (!key) { valid = false; continue; }
      known.add('legacy:user:' + key); known.add('legacy:assistant:' + key);
    }
    return { turns, known, valid };
  }
  function answerSnapshot(turn) {
    const progressSelector='[data-streaming-response-status], [data-testid^="cot-v5"]';
    // Activity markers themselves can be hidden; their parent owns the public
    // progress section. A final answer in its own search unit is separate.
    const activityContainers=Array.from(turn.root.querySelectorAll('[data-chatgpt-agent-turn-start]')).map(node=>node.parentElement);
    const firstStatus=Array.from(turn.root.querySelectorAll('[data-streaming-response-status]')).find(replyRendered);
    const commentary=element=>!!element.closest(progressSelector)
      || !element.closest('[data-content-search-unit-key], [data-chatgpt-search-unit-key]') && activityContainers.some(root=>root.contains(element))
      || !!firstStatus && !element.contains(firstStatus) && !!(element.compareDocumentPosition(firstStatus)&Node.DOCUMENT_POSITION_FOLLOWING);
    let candidates = transcriptAll(ANSWER, turn.root).filter(element => !element.closest('[data-user-message-bubble], [data-message-author-role="user"], [data-conversation-role="user"]')
      && (turn.content.contains(element) || !!(turn.content.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING)));
    if (turn.content.matches(ANSWER) && transcriptRendered(turn.content)) candidates.unshift(turn.content);
    candidates = candidates.filter(element => !element.parentElement || !element.parentElement.closest(ANSWER)
      || !turn.content.contains(element.parentElement.closest(ANSWER)));
    const excluded=candidates.filter(commentary);
    let answers=candidates.filter(element=>!excluded.includes(element)&&replyRendered(element));
    // Provider cards/embeds and plain replies need not have a Markdown wrapper.
    // Fall back only inside the positively identified assistant turn.
    const fallback = !answers.length && !candidates.some(element=>!excluded.includes(element))
      && replyRendered(turn.content) && !commentary(turn.content);
    if(fallback)answers=[turn.content];
    const lastAnswer = answers[answers.length - 1];
    if (!lastAnswer) return { text: '', complete: false, rawEnvelopeEligible: false };
    const footer = transcriptAll(FOOTER, turn.root).find(element => replyRendered(element) && !commentary(element)
      && !element.closest('[data-user-message-bubble], [data-message-author-role="user"], [data-conversation-role="user"]')
      && ((!lastAnswer.contains(element) && !!(lastAnswer.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING))
        || fallback && lastAnswer.contains(element)));
    const answer = { text: answers.map(element => renderedReplyText(element,fallback,excluded,progressSelector)).filter(Boolean).join('\n\n'), complete: !!footer, rawEnvelopeEligible: false };
    // Markdown rendering removes literal fences. Never turn a quoted example or
    // one block inside a larger reply into a control message: only the whole
    // response may supply a positively identified hoyoung-language block.
    if (answers.length === 1 && !(fallback && (excluded.length || turn.content.querySelector(progressSelector)))) {
      answer.rawEnvelopeEligible = bareProtocolEligible(answers[0]);
      Object.assign(answer, wholeStructuredEnvelope(answers[0]));
      if (answer.structuredEnvelopeUnavailable === 'structured_code_incomplete') answer.complete = false;
    }
    return answer;
  }
  function renderedReplyText(element,fallback,excluded=[],progressSelector='[data-streaming-response-status], [data-testid^="cot-v5"]') {
    let text=element.innerText||element.textContent||'';
    if(fallback) {
      const clone=element.cloneNode(true),originals=Array.from(element.querySelectorAll('*')),copies=Array.from(clone.querySelectorAll('*'));
      originals.forEach((node,index)=>{if(!replyRendered(node)||excluded.includes(node)||node.matches(progressSelector+',button,[role="button"],script,style,svg,[data-markdown-copy="exclude"],.turn-action-controls'))copies[index].remove();});
      for(const node of clone.querySelectorAll('br,p,div,pre,li,tr,h1,h2,h3,h4')) {
        if(node.tagName==='BR')node.replaceWith(document.createTextNode('\n'));
        else {node.before(document.createTextNode('\n'));node.after(document.createTextNode('\n'));}
      }
      text=clone.textContent.replace(/\n{3,}/g,'\n\n').trim();
    }
    const media=transcriptAll('img,video,audio,iframe,object,embed,canvas',element).filter(node=>replyRendered(node)&&!node.closest(progressSelector)&&!excluded.some(root=>root.contains(node))).map(node=>{
      const label=normalized(node.getAttribute('alt')||node.getAttribute('title')||node.getAttribute('aria-label'));
      const url=node.getAttribute('src')||node.getAttribute('data')||'';
      // Describe only exposed content. A cross-origin embed's private body is not readable.
      return '['+({IMG:'이미지',VIDEO:'영상',AUDIO:'오디오',IFRAME:'임베드',OBJECT:'임베드',EMBED:'임베드',CANVAS:'그래픽'}[node.tagName])
        +(label?': '+label:'')+']'+(/^https?:\/\//i.test(url)?' '+url:'');
    });
    return [text,...media].filter(Boolean).join('\n');
  }
  function bareProtocolEligible(answer) {
    // innerText loses quote/code/Markdown syntax. A rendered quotation must not
    // become a raw JSON command just because its visible characters form JSON.
    // Runtime still requires the whole valid object + current protocol/requestId.
    return transcriptAll('pre, code, .cm-content, blockquote, q, li, details, strong, em, b, i, s, del, mark, sub, sup, a, h1, h2, h3, h4, h5, h6, table, img, video, audio, canvas, iframe, object, embed, hr, [role="img"], input, textarea, select, button', answer).length === 0;
  }
  function wholeStructuredEnvelope(answer) {
    const surfaces=transcriptAll('[data-markdown-copy="code-block"]',answer);
    if(surfaces.length) {
      if(surfaces.length!==1||surfaces[0].closest('blockquote,q,li,details'))return {};
      const surface=surfaces[0],editors=transcriptAll('.cm-content[data-language]',surface);
      if(editors.length!==1||normalized(editors[0].getAttribute('data-language')).toLowerCase()!=='hoyoung')return {};
      const editor=editors[0],lines=Array.from(editor.children);
      if(!lines.length||lines.some(line=>!line.matches('.cm-line'))||surface.querySelector('.cm-gap'))
        return {structuredEnvelopeUnavailable:'structured_code_incomplete'};
      // CodeMirror keeps source text inside decorated line spans. Hidden lines
      // or decorations must not contribute unobserved protocol fields merely
      // because textContent includes them, even whitespace in JSON strings.
      // Wait for the complete rendered code,
      // as we already do for virtualized gaps, before completion or recovery.
      for(const element of editor.querySelectorAll('*'))
        if(!transcriptRendered(element)&&element.textContent)
          return {structuredEnvelopeUnavailable:'structured_code_incomplete'};
      const walker=document.createTreeWalker(answer,NodeFilter.SHOW_TEXT);
      for(let node=walker.nextNode();node;node=walker.nextNode())
        if(transcriptRendered(node.parentElement)&&normalized(node.textContent)&&!surface.contains(node))return {};
      for(const element of transcriptAll('img,video,audio,canvas,iframe,object,embed,hr,table,input,textarea,select,button,a,pre',answer))
        if(!surface.contains(element))return {};
      const text=lines.map(line=>line.textContent||'').join('\n');
      if(!text.trim())return {};
      if(text.length>MAX_STRUCTURED_ENVELOPE_CHARS)return {structuredEnvelopeUnavailable:'structured_envelope_too_large',structuredEnvelopeObservedChars:text.length};
      return {structuredEnvelope:text,structuredEnvelopeSource:'whole-language-hoyoung-code-block'};
    }
    const blocks = transcriptAll('pre', answer);
    if (blocks.length !== 1) return {};
    const pre = blocks[0];
    const quotation = pre.closest('blockquote, q, li, details');
    if (quotation && answer.contains(quotation)) return {};
    const codes = transcriptAll('code', pre);
    if (codes.length !== 1) return {};
    const code = codes[0];
    const languages = [];
    for (const element of [pre, code]) {
      const language = element.getAttribute('data-language');
      if (language !== null) languages.push(normalized(language).toLowerCase());
      for (const token of element.classList) {
        const match = /^(?:language|lang)-(.+)$/.exec(token);
        if (match) languages.push(match[1].toLowerCase());
      }
    }
    if (!languages.length || languages.some(language => language !== 'hoyoung')) return {};
    // A code-only reply may contain its language label and copy controls inside
    // the same pre. Unknown headers outside it, explanatory prose and non-text
    // attachments prevent extraction. Ordinary raw text is still preserved.
    for (const element of transcriptAll('img, video, audio, canvas, iframe, object, embed, hr, table, [role="img"], input, textarea, select, button, a', answer)) {
      if (!code.contains(element) && !(pre.contains(element) && element.closest('button'))) return {};
    }
    for (const element of code.querySelectorAll('*')) {
      if (!transcriptRendered(element) && element.textContent) return {};
    }
    const walker = document.createTreeWalker(answer, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (!transcriptRendered(node.parentElement) || code.contains(node) || !normalized(node.textContent)) continue;
      if (pre.contains(node) && (node.parentElement.closest('button') || normalized(node.textContent).toLowerCase() === 'hoyoung')) continue;
      return {};
    }
    const text = code.textContent || '';
    if (!text.trim()) return {};
    if (text.length > MAX_STRUCTURED_ENVELOPE_CHARS) return { structuredEnvelopeUnavailable: 'structured_envelope_too_large', structuredEnvelopeObservedChars: text.length };
    return { structuredEnvelope: text, structuredEnvelopeSource: 'whole-language-hoyoung-code-block' };
  }
  function remember(receipt) {
    seenRequestIds.add(receipt.requestId);
    receipts.set(receipt.requestId, receipt);
    while (receipts.size > 128) receipts.delete(receipts.keys().next().value);
  }
  function uncertain(code, metadata) {
    if (!active || active.phase === 'uncertain') return;
    const mayHaveBeenAccepted = !!active.clicked || !submissionStillUnobserved(active);
    active.phase = 'uncertain';
    active.uncertaintyCode = code;
    remember({ requestId: active.requestId, text: active.text, phase: 'uncertain', code });
    emitError(code, active.requestId, Object.assign({ mayHaveBeenAccepted, retryAllowed: false }, metadata || {}));
  }
  function responseParts(text) {
    const pieces = [];
    for (let start = 0; start < text.length;) {
      let end = Math.min(text.length, start + CAPTURE_PART_CHARS);
      // A UTF-8 native file must not replace an emoji split between surrogate halves.
      if (end < text.length && /[\uD800-\uDBFF]/.test(text.charAt(end - 1)) && /[\uDC00-\uDFFF]/.test(text.charAt(end))) end++;
      pieces.push(text.slice(start, end)); start = end;
    }
    return pieces;
  }
  function deliverCompletion(finished, answer, turnId, state) {
    const event = { type: 'completed', requestId: finished.requestId, code: finished.cancelRequested ? 'completed_after_cancel_request' : 'completed', text: answer.text, turnId, state, captureComplete: true, observedChars: answer.text.length, sourceFormat: 'visible-dom-text', renderedOnly: true, rawEnvelopeEligible: answer.rawEnvelopeEligible === true };
    for (const field of ['structuredEnvelope', 'structuredEnvelopeSource', 'structuredEnvelopeUnavailable', 'structuredEnvelopeObservedChars']) {
      if (answer[field] !== undefined) event[field] = answer[field];
    }
    if (JSON.stringify(event).length <= MAX_INLINE_EVENT_CHARS) return notify(event);
    if (answer.text.length > MAX_CAPTURE_CHARS) {
      uncertain('response_capture_overflow', { captureComplete: false, observedChars: answer.text.length, maximumCaptureChars: MAX_CAPTURE_CHARS, rawTextAvailableInPage: true });
      return false;
    }
    const parts = responseParts(answer.text);
    const captureId = finished.requestId + ':reply:' + turnId;
    for (let index = 0; index < parts.length; index++) {
      if (!notify({ type: 'progress', code: 'response_capture_part', requestId: finished.requestId, captureId, index, totalParts: parts.length, observedChars: answer.text.length, text: parts[index] })) {
        uncertain('response_capture_delivery_uncertain', { captureComplete: false, observedChars: answer.text.length, rawTextAvailableInPage: true });
        return false;
      }
    }
    delete event.text;
    event.captureId = captureId; event.totalParts = parts.length; event.captureEncoding = 'utf-8';
    if (!notify(event)) {
      uncertain('response_capture_delivery_uncertain', { captureComplete: false, observedChars: answer.text.length, rawTextAvailableInPage: true });
      return false;
    }
    finished.capture = { captureId, totalParts: parts.length, observedChars: answer.text.length, captureComplete: true };
    return true;
  }
  function tick() {
    if (disposed) return;
    if (cleaningSession) return;
    if(location.pathname===initialPath)suspendedRoute=false;
    const currentConversation=conversationIdFromPath(location.pathname);
    if(ownedConversationId&&currentConversation===ownedConversationId) {initialPath=location.pathname;suspendedRoute=false;}
    if (location.pathname !== initialPath && initialPath === '/' && active && active.clicked && active.freshConversation
        && currentConversation) {
      const snapshot = turnSnapshot();
      const users = snapshot.turns.filter(turn => turn.role === 'user' && !active.baseline.has(turn.id));
      if (snapshot.valid && users.length === 1 && users[0].text === normalized(active.text)) {
        initialPath = location.pathname;
        ownedConversationId = currentConversation;suspendedRoute=false;
      } else if (Date.now() - active.clickedAt < ACCEPT_TIMEOUT_MS) return;
    }
    if (document !== initialDocument || location.origin !== 'https://chatgpt.com') {
      dispose('navigation_uncertain'); return;
    }
    if(location.pathname!==initialPath) {
      // A same-document SPA route is not a foreign origin. Keep observation
      // alive so returning to the owned conversation or recovering its reply
      // remains possible; never send again or click an old page's controls.
      suspendedRoute=true;
      if(active)uncertain('navigation_uncertain');
      if(preferenceSelection)preferenceSelection.enabled=false;
      inspectAndEmit();return;
    }
    const quota = quotaBanner();
    if (quota) {
      quotaLatched = true;
      const knownNotSent = !!(active && !active.clicked && (/^(?:preparing(?:_attachments)?|awaiting_authorization)$/.test(active.phase) || active.knownNotSent === true));
      if (quota !== lastQuotaKey) {
        lastQuotaKey = quota;
        notify({ type: 'quota', code: 'quota_exhausted', requestId: active ? active.requestId : undefined, text: quota, state: readState(), retryAllowed: false, knownNotSent });
      }
      if (active) {
        active.phase = 'uncertain'; active.uncertaintyCode = 'quota_exhausted';
        active.knownNotSent = knownNotSent;
        remember({ requestId: active.requestId, text: active.text, phase: 'uncertain', code: 'quota_exhausted', knownNotSent });
      }
    }
    advanceConnectionPreparation();
    advancePreferenceSelection();
    inspectAndEmit();
    const needsInput = inputRequiredStatus();
    if (active && active.phase === 'accepted' && needsInput && needsInput !== lastInputRequiredKey) {
      lastInputRequiredKey = needsInput;
      notify({ type: 'progress', code: 'work_input_required', requestId: active.requestId, text: needsInput, state: readState(), queuedInputOnly: true });
    } else if (!needsInput) lastInputRequiredKey = '';
    if (!active || quotaLatched || active.phase === 'uncertain' || active.phase === 'preparing' || active.phase === 'preparing_attachments' || active.phase === 'awaiting_authorization') return;
    const snapshot = turnSnapshot();
    if (!snapshot.valid) { uncertain('turn_identity_unverified'); return; }
    const newUsers = snapshot.turns.filter(turn => turn.role === 'user' && !active.baseline.has(turn.id));
    const matches = newUsers.filter(turn => turn.text === normalized(active.text));
    if (matches.length > 1 || newUsers.some(turn => turn.text !== normalized(active.text))) { uncertain('turn_ambiguous'); return; }
    if (!active.acceptedUserId && matches.length === 1) {
      // A normal Work turn must have an owned conversation address before an
      // acceptance receipt can be used for future session deletion.
      if (active.freshConversation && !ownedConversationId) {
        if (Date.now() - active.clickedAt >= ACCEPT_TIMEOUT_MS) uncertain('conversation_id_unverified');
        return;
      }
      active.acceptedUserId = matches[0].id;
      active.acceptedUserKey = matches[0].key;
      active.phase = 'accepted';
      remember({ requestId: active.requestId, text: active.text, phase: 'accepted' });
      notify({ type: 'accepted', requestId: active.requestId, code: 'user_turn_confirmed', turnId: active.acceptedUserId, conversationId: ownedConversationId, freshConversation: active.freshConversation, uploadObserved: active.uploadObserved === true, attachmentNames: active.attachmentNames, uploadObservationScope: 'composer_reports_uploaded_before_user_turn_confirmation', state: readState() });
    }
    if (!active.acceptedUserId) {
      if (Date.now() - active.clickedAt >= ACCEPT_TIMEOUT_MS) uncertain('dispatch_uncertain');
      return;
    }
    const user = snapshot.turns.find(turn => turn.id === active.acceptedUserId);
    if (!user) return; // Virtualization alone does not undo the saved acknowledgement.
    const candidates = snapshot.turns.filter(turn => turn.role === 'assistant' && !active.baseline.has(turn.id)
      && (user.id.indexOf('group:') === 0 ? turn.key === user.key : !!(user.root.compareDocumentPosition(turn.root) & Node.DOCUMENT_POSITION_FOLLOWING)));
    if (candidates.length > 1) { uncertain('turn_ambiguous'); return; }
    if (!candidates.length) return;
    const answer = answerSnapshot(candidates[0]);
    if (answer.text !== active.lastText) {
      active.lastText = answer.text;
      active.settleKey = null;
      if (answer.text) notify({ type: 'progress', requestId: active.requestId, text: answer.text.slice(0, MAX_PROGRESS_PREVIEW_CHARS), turnId: candidates[0].id, state: readState(), previewOnly: answer.text.length > MAX_PROGRESS_PREVIEW_CHARS, observedChars: answer.text.length, captureComplete: false });
    }
    const state = readState();
    if (!answer.text || !answer.complete || state.busy || state.needsInput) { active.settleKey = null; return; }
    const settleKey = candidates[0].id + '\n' + answer.text + '\nrawEligible=' + answer.rawEnvelopeEligible + '\nstructured=' + (answer.structuredEnvelope || '') + '\nsource=' + (answer.structuredEnvelopeSource || answer.structuredEnvelopeUnavailable || '');
    if (active.settleKey !== settleKey) { active.settleKey = settleKey; active.settleAt = Date.now(); return; }
    if (Date.now() - active.settleAt < COMPLETION_SETTLE_MS) return;
    const finished = active;
    if (!deliverCompletion(finished, answer, candidates[0].id, state)) {
      if (active && active.phase !== 'uncertain') uncertain('response_capture_delivery_uncertain', { captureComplete: false, observedChars: answer.text.length, rawTextAvailableInPage: true });
      return;
    }
    active = null;
    remember({ requestId: finished.requestId, text: finished.text, phase: 'completed', response: finished.capture ? undefined : answer.text, capture: finished.capture, rawEnvelopeEligible: answer.rawEnvelopeEligible === true, structuredEnvelope: answer.structuredEnvelope, structuredEnvelopeSource: answer.structuredEnvelopeSource, structuredEnvelopeUnavailable: answer.structuredEnvelopeUnavailable, structuredEnvelopeObservedChars: answer.structuredEnvelopeObservedChars });
  }
  function inspectAndEmit() {
    const inspection = inspect();
    const key = JSON.stringify(inspection);
    if (key !== lastStateKey) {
      lastStateKey = key;
      notify({ type: 'state', code: inspection.code, state: inspection.state, requestId: inspection.activeRequestId || undefined, phase: inspection.phase });
    }
    return inspection;
  }
  function selectWork() {
    const inspection = inspect();
    if (disposed || location.origin !== 'https://chatgpt.com') return result('unsupported_origin');
    if (active || inspection.state.busy) return result('busy');
    if (inspection.state.authenticated !== true) return result(inspection.state.authenticated === false ? 'authentication_required' : 'authentication_unverified');
    const selectable = controls();
    if (!inspection.state.workSelected) {
      const work = selectable.filter(isWork);
      if (work.length !== 1 || !enabled(work[0])) return result('work_control_unverified');
      try { work[0].click(); } catch (_) { return result('work_selection_uncertain'); }
      return { ok: true, code: 'work_selection_pending', state: readState() };
    }
    return inspection;
  }
  function editorText(editor) { return editor.matches('textarea, input') ? normalized(editor.value) : renderedWords(editor,true); }
  function fillEditor(editor, text) {
    editor.focus();
    if (editor.matches('textarea, input')) {
      const prototype = editor.matches('textarea') ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(prototype, 'value').set;
      setter.call(editor, text);
      editor.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
      editor.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(editor);
      selection.removeAllRanges(); selection.addRange(range);
      if (!document.execCommand('insertText', false, text)) return false;
    }
    return editorText(editor) === normalized(text);
  }
  function validateAttachments(value) {
    if (value === undefined) return { ok: true, files: [] };
    if (!Array.isArray(value) || value.length > 8) return { ok: false, code: 'invalid_attachments' };
    const names = new Set();
    let total = 0;
    for (const item of value) {
      if (!item || typeof item.name !== 'string' || !item.name.trim() || item.name.length > 240
        || /[\r\n\x00/\\]/.test(item.name) || names.has(item.name)
        || typeof item.mime !== 'string' || !/^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(item.mime)
        || typeof item.base64 !== 'string' || item.base64.length > Math.ceil(10 * 1024 * 1024 / 3) * 4
        || item.base64.length % 4 !== 0 || /[^A-Za-z0-9+/=]/.test(item.base64)
        || !item.base64.length) return { ok: false, code: 'invalid_attachments' };
      const padding = item.base64.endsWith('==') ? 2 : item.base64.endsWith('=') ? 1 : 0;
      const firstPadding = item.base64.indexOf('=');
      if (firstPadding >= 0 && firstPadding !== item.base64.length - padding) return { ok: false, code: 'invalid_attachments' };
      const bytes = item.base64.length / 4 * 3 - padding;
      if (bytes > 10 * 1024 * 1024) return { ok: false, code: 'attachment_too_large' };
      total += bytes;
      if (total > 20 * 1024 * 1024) return { ok: false, code: 'attachments_too_large' };
      names.add(item.name);
    }
    return { ok: true, files: value };
  }
  function attachmentInput(editor, files) {
    const scope = composerScope(editor);
    if (!scope) return null;
    const inputs = Array.from(scope.querySelectorAll('input[data-testid="upload-photos-input"][type="file"], input[type="file"][multiple]:not([accept])'))
      .filter(element => element.isConnected && !element.disabled && (files.length < 2 || element.multiple));
    if (inputs.length !== 1) return null;
    const accept = (inputs[0].accept || '').split(',').map(value => value.trim().toLowerCase()).filter(Boolean);
    if (accept.length && files.some(file => !accept.some(type => type === '*/*' || type === file.mime.toLowerCase()
      || type.endsWith('/*') && file.mime.toLowerCase().startsWith(type.slice(0, -1))
      || type.startsWith('.') && file.name.toLowerCase().endsWith(type)))) return null;
    return inputs[0];
  }
  function attachFiles(input, files) {
    try {
      const transfer = new DataTransfer();
      for (const item of files) {
        const binary = atob(item.base64);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
        transfer.items.add(new File([bytes], item.name, { type: item.mime }));
      }
      input.files = transfer.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      return input.files && input.files.length === files.length && files.every((file, index) => input.files[index].name === file.name);
    } catch (_) { return false; }
  }
  function attachmentsReady(dispatch) {
    if (!dispatch.attachmentNames.length) return true;
    const scope = composerScope(dispatch.editor);
    if (!scope || all('[role="progressbar"], [aria-busy="true"]', scope).length) return false;
    return dispatch.attachmentNames.every(name => {
      const cards = all('[role="group"], .composer-attachment-surface:is(button, [role="button"])', scope)
        .filter(element => labelOf(element) === name);
      return cards.length === 1 && (cards[0].getAttribute('aria-busy') === 'false'
        || /^(?:complete|completed|ready|uploaded)$/.test(cards[0].getAttribute('data-state') || '')
        || all('[role="status"]', cards[0]).some(status => /^(?:Uploaded|Upload complete|Ready|업로드 완료|첨부 완료)$/i.test(textOf(status))));
    });
  }
  function submissionStillUnobserved(dispatch) {
    if (dispatch.clicked) return false;
    const snapshot = turnSnapshot();
    return snapshot.valid && !snapshot.turns.some(turn => turn.role === 'user' && !dispatch.baseline.has(turn.id));
  }
  function rejectAuthorization(code) {
    if (!active) return result(code, { retryAllowed: false });
    const requestId = active.requestId;
    if (!submissionStillUnobserved(active)) {
      uncertain(code, { knownNotSent: false, mayHaveBeenAccepted: true });
      return result(code, { requestId, retryAllowed: false });
    }
    if (active.authorizationTimer) clearTimeout(active.authorizationTimer);
    active.authorizationNonce = null; active.authorizationGranted = false;
    active.phase = 'uncertain'; active.knownNotSent = true; active.uncertaintyCode = code;
    remember({ requestId, text: active.text, phase: 'uncertain', code, knownNotSent: true });
    const metadata = { knownNotSent: true, mayHaveBeenAccepted: false, retryAllowed: false,
      composerMayContainDraft: true, attachmentUploadMayHaveOccurred: active.attachmentNames.length > 0 };
    emitError(code, requestId, metadata);
    return result(code, Object.assign({ requestId }, metadata));
  }
  function requestDispatchAuthorization(dispatch) {
    let nonce;
    try {
      const random = new Uint32Array(4); crypto.getRandomValues(random);
      nonce = Array.from(random, value => value.toString(16).padStart(8, '0')).join('');
    } catch (_) { rejectAuthorization('dispatch_authorization_nonce_unavailable'); return; }
    dispatch.authorizationPhase = dispatch.phase;
    dispatch.phase = 'awaiting_authorization'; dispatch.authorizationNonce = nonce;
    dispatch.authorizationDeadline = performance.now() + DISPATCH_AUTHORIZATION_TTL_MS;
    dispatch.authorizationTimer = setTimeout(() => {
      if (active === dispatch && dispatch.phase === 'awaiting_authorization' && dispatch.authorizationNonce === nonce)
        rejectAuthorization('dispatch_authorization_timeout');
    }, DISPATCH_AUTHORIZATION_TTL_MS);
    remember({ requestId: dispatch.requestId, text: dispatch.text, phase: 'awaiting_authorization' });
    if (!notify({ type: 'progress', code: 'dispatch_authorization_requested', requestId: dispatch.requestId, nonce,
      payloadDigest: dispatch.payloadDigest || '', authorizationTtlMs: DISPATCH_AUTHORIZATION_TTL_MS })) {
      if (active === dispatch && !dispatch.clicked) rejectAuthorization('dispatch_authorization_delivery_failed');
    }
  }
  function authorizeDispatch(requestId, nonce, allowed, payloadDigest) {
    if (disposed || document !== initialDocument || location.origin !== 'https://chatgpt.com' || location.pathname !== initialPath)
      return result('bridge_stale', { retryAllowed: false });
    if (!active || active.requestId !== requestId || active.phase !== 'awaiting_authorization'
      || !active.requiresNativeAuthorization || active.authorizationNonce !== nonce || typeof allowed !== 'boolean')
      return result('dispatch_authorization_mismatch', { retryAllowed: false });
    if (active.requiresNativePayloadDigest && (typeof payloadDigest !== 'string' || payloadDigest !== active.payloadDigest))
      return rejectAuthorization('dispatch_payload_digest_mismatch');
    if (performance.now() >= active.authorizationDeadline) return rejectAuthorization('dispatch_authorization_expired');
    if (!allowed) return rejectAuthorization('dispatch_authorization_denied');
    if (!submissionStillUnobserved(active)) return rejectAuthorization('dispatch_authorization_submission_unverified');
    if (active.authorizationTimer) clearTimeout(active.authorizationTimer);
    active.authorizationNonce = null; // Each nonce is consumed before any side effect.
    active.authorizationGranted = true; active.phase = active.authorizationPhase;
    clickWhenReady(requestId);
    return { ok: true, code: 'dispatch_authorized', state: readState(), requestId, knownNotSent: false, retryAllowed: false };
  }
  function clickWhenReady(requestId) {
    if (disposed || !active || active.requestId !== requestId || !/^preparing(?:_attachments)?$/.test(active.phase)) return;
    const state = readState();
    const code = readyCode(state, active.preferences);
    if (code !== 'ready' && !(code === 'busy' && active.attachmentNames.length)) { uncertain('selection_changed_before_send'); return; }
    if (!active.editor.isConnected || editorText(active.editor) !== normalized(active.text)) { uncertain('composer_changed_before_send'); return; }
    const buttons = sendButtons(active.editor);
    if (buttons.length !== 1) { uncertain('send_control_unverified'); return; }
    if (!attachmentsReady(active) || code === 'busy') {
      if (Date.now() - active.preparingAt >= ATTACHMENT_READY_TIMEOUT_MS) { uncertain('attachment_upload_unverified'); return; }
      setTimeout(function () { clickWhenReady(requestId); }, 100);
      return;
    }
    if (!enabled(buttons[0])) {
      if (Date.now() - active.preparingAt >= (active.attachmentNames.length ? ATTACHMENT_READY_TIMEOUT_MS : SEND_ENABLE_TIMEOUT_MS)) { uncertain('send_control_disabled'); return; }
      setTimeout(function () { clickWhenReady(requestId); }, 100);
      return;
    }
    if (active.requiresNativeAuthorization) {
      if (!active.authorizationGranted) { requestDispatchAuthorization(active); return; }
      if (performance.now() >= active.authorizationDeadline) { rejectAuthorization('dispatch_authorization_expired'); return; }
      active.authorizationGranted = false;
    }
    // The guard precedes the side effect. Even a throwing click must never be replayed.
    active.uploadObserved = active.attachmentNames.length > 0 && attachmentsReady(active);
    active.phase = 'awaiting_ack'; active.clicked = true; active.clickedAt = Date.now();
    remember({ requestId: active.requestId, text: active.text, phase: 'awaiting_ack' });
    try { buttons[0].click(); } catch (_) { uncertain('dispatch_uncertain'); return; }
    tick();
  }
  function send(payload) {
    if (disposed || document !== initialDocument || location.origin !== 'https://chatgpt.com') return result('bridge_stale', { retryAllowed: false });
    const requestId = payload && typeof payload.requestId === 'string' ? payload.requestId : undefined;
    const alreadyKnown = requestId && (seenRequestIds.has(requestId) || active && active.requestId === requestId);
    if (!payload || typeof payload.requestId !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(payload.requestId)
      || typeof payload.text !== 'string' || !payload.text.trim()) return alreadyKnown ? result('invalid_request') : preflightFailure('invalid_request', requestId);
    if (payload.text.length > MAX_PROMPT_CHARS) return alreadyKnown ? result('message_too_large') : preflightFailure('message_too_large', requestId);
    if (payload.requiresNativeAuthorization !== undefined && typeof payload.requiresNativeAuthorization !== 'boolean')
      return alreadyKnown ? result('invalid_request') : preflightFailure('invalid_request', requestId);
    if (payload.requiresNativePayloadDigest !== undefined && typeof payload.requiresNativePayloadDigest !== 'boolean')
      return alreadyKnown ? result('invalid_request') : preflightFailure('invalid_request', requestId);
    if (payload.requiresNativePayloadDigest === true && (payload.requiresNativeAuthorization !== true
      || typeof payload.nativePayloadDigest !== 'string' || !/^[a-f0-9]{64}$/.test(payload.nativePayloadDigest)))
      return alreadyKnown ? result('invalid_request') : preflightFailure('native_payload_digest_missing', requestId);
    const receipt = receipts.get(payload.requestId);
    const ownerRetryAuthorized = !!(receipt && receipt.phase === 'known_not_sent' && receipt.ownerRetryAuthorized);
    if (receipt) {
      if (receipt.text !== payload.text) return result('request_id_conflict');
      if (!ownerRetryAuthorized) return { ok: receipt.phase !== 'uncertain', code: receipt.phase === 'uncertain' ? 'dispatch_uncertain' : 'duplicate_request', state: readState(), requestId: payload.requestId, phase: receipt.phase, response: receipt.response, capture: receipt.capture, rawEnvelopeEligible: receipt.rawEnvelopeEligible === true, structuredEnvelope: receipt.structuredEnvelope, structuredEnvelopeSource: receipt.structuredEnvelopeSource, structuredEnvelopeUnavailable: receipt.structuredEnvelopeUnavailable, structuredEnvelopeObservedChars: receipt.structuredEnvelopeObservedChars, retryAllowed: false, knownNotSent: false };
    }
    // Keep small ID tombstones after dropping older reply text. A long-running page
    // can accept new requests without making an old dispatched ID replayable.
    if (seenRequestIds.has(payload.requestId) && !ownerRetryAuthorized) return result('request_receipt_archived', { requestId: payload.requestId, retryAllowed: false });
    if (active) return active.phase === 'uncertain'
      ? result('dispatch_uncertain', { requestId: active.requestId, retryAllowed: false })
      : preflightFailure('busy', payload.requestId, { activeRequestId: active.requestId, retryAllowed: false });
    const attachmentValidation = validateAttachments(payload.attachments);
    if (!attachmentValidation.ok) return preflightFailure(attachmentValidation.code, payload.requestId);
    const preferences = requiredPreferences(payload, configuredPreferences);
    if (!preferences) return preflightFailure('unsupported_preferences', payload.requestId);
    const state = readState();
    const code = readyCode(state, preferences);
    if (code !== 'ready') return preflightFailure(code, payload.requestId);
    const editor = composer();
    if (editorText(editor)) return preflightFailure('composer_not_empty', payload.requestId);
    const scope = composerScope(editor);
    if (all('[role="progressbar"], [aria-busy="true"]', scope).length) return preflightFailure('attachment_upload_unverified', payload.requestId);
    const files = attachmentValidation.files;
    const uploadInput = files.length ? attachmentInput(editor, files) : null;
    if (files.length && !uploadInput) return preflightFailure('attachment_control_unverified', payload.requestId);
    // Current mobile UI swaps the voice button for Send only after input. CoS
    // verifies the resulting control at clickWhenReady, before any submit.
    if (all(SEND, scope).length > 1) return preflightFailure('send_control_unverified', payload.requestId);
    const snapshot = turnSnapshot();
    if (!snapshot.valid) return preflightFailure('turn_identity_unverified', payload.requestId);
    active = { requestId: payload.requestId, text: payload.text, preferences, freshConversation: initialPath === '/' && snapshot.turns.length === 0, phase: files.length ? 'preparing_attachments' : 'preparing', preparingAt: Date.now(), editor, baseline: snapshot.known, clicked: false, lastText: '', attachmentNames: files.map(file => file.name), requiresNativeAuthorization: payload.requiresNativeAuthorization === true, requiresNativePayloadDigest: payload.requiresNativePayloadDigest === true, payloadDigest: payload.nativePayloadDigest || '', authorizationGranted: false };
    remember({ requestId: active.requestId, text: active.text, phase: 'preparing' });
    let filled = false;
    try { filled = fillEditor(editor, payload.text); } catch (_) { /* An editor implementation changed; do not guess another editing path. */ }
    if (!filled) {
      uncertain('composer_write_unverified');
      return result('composer_write_unverified', { requestId: payload.requestId, retryAllowed: false });
    }
    if (files.length && !attachFiles(uploadInput, files)) {
      uncertain('attachment_forwarding_uncertain');
      return result('attachment_forwarding_uncertain', { requestId: payload.requestId, retryAllowed: false });
    }
    setTimeout(function () { clickWhenReady(payload.requestId); }, 100);
    return { ok: true, code: 'dispatch_pending', state: readState(), requestId: payload.requestId, retryAllowed: false, knownNotSent: false };
  }
  function prepareOwnerRetry() {
    if (disposed || document !== initialDocument || location.origin !== 'https://chatgpt.com') return result('bridge_stale', { retryAllowed: false });
    if (quotaBanner()) return result('quota_exhausted', { retryAllowed: false });
    if (active && (active.clicked || !(/^(?:preparing(?:_attachments)?|awaiting_authorization)$/.test(active.phase) || active.knownNotSent === true))) {
      return result('outcome_uncertain', { requestId: active.requestId, retryAllowed: false });
    }
    if (active && !submissionStillUnobserved(active))
      return result('outcome_uncertain', { requestId: active.requestId, retryAllowed: false });
    let releasedRequestId;
    if (active) {
      const pending = active;
      if (pending.authorizationTimer) clearTimeout(pending.authorizationTimer);
      // Clear only our own staged draft. A different draft belongs to the owner.
      if (pending.editor.isConnected && editorText(pending.editor) === normalized(pending.text)) {
        let cleared = false;
        try { cleared = fillEditor(pending.editor, ''); } catch (_) { /* Preserve the uncertain editor state. */ }
        if (!cleared) return result('owner_retry_composer_unverified', { requestId: pending.requestId, retryAllowed: false });
      }
      active = null;
      releasedRequestId = pending.requestId;
      // Preserve the receipt and permit only an explicit owner-authorized replay
      // of this proven-not-sent request. Clicked outcomes never reach this branch.
      remember({ requestId: pending.requestId, text: pending.text, phase: 'known_not_sent', knownNotSent: true, ownerRetryAuthorized: true });
      emitError('cancelled_before_send', pending.requestId, { knownNotSent: true, mayHaveBeenAccepted: false, retryAllowed: false });
    }
    quotaLatched = false;
    lastQuotaKey = '';
    const inspection = inspectAndEmit();
    return { ok: true, code: 'owner_retry_enabled', state: inspection.state, requestId: releasedRequestId, quotaRecoveryVerified: false, knownNotSent: !!releasedRequestId };
  }
  function cancel() {
    if (!active) return result('nothing_to_cancel');
    if (!active.clicked && !submissionStillUnobserved(active)) {
      uncertain('cancellation_submission_unverified', { knownNotSent: false, mayHaveBeenAccepted: true });
      return result('cancellation_submission_unverified', { requestId: active.requestId, retryAllowed: false, knownNotSent: false });
    }
    if (/^(?:preparing(?:_attachments)?|awaiting_authorization)$/.test(active.phase) && !active.clicked) {
      const cancelled = active; active = null;
      if (cancelled.authorizationTimer) clearTimeout(cancelled.authorizationTimer);
      remember({ requestId: cancelled.requestId, text: cancelled.text, phase: 'cancelled' });
      emitError('cancelled_before_send', cancelled.requestId, { mayHaveBeenAccepted: false, retryAllowed: false });
      return { ok: true, code: 'cancelled_before_send', state: readState(), requestId: cancelled.requestId };
    }
    if (active.cancelRequested) return result('cancellation_pending', { requestId: active.requestId, retryAllowed: false });
    const buttons = stopButtons();
    if (buttons.length !== 1 || !enabled(buttons[0])) return result('cancellation_unverified', { requestId: active.requestId, retryAllowed: false });
    active.cancelRequested = true;
    try { buttons[0].click(); } catch (_) { uncertain('cancellation_uncertain'); return result('cancellation_uncertain', { retryAllowed: false }); }
    notify({ type: 'progress', requestId: active.requestId, code: 'cancellation_requested', state: readState() });
    return { ok: true, code: 'cancellation_requested', state: readState(), requestId: active.requestId, retryAllowed: false };
  }
  function dispose(code) {
    if (disposed) return;
    if (active && active.authorizationTimer) clearTimeout(active.authorizationTimer);
    if (active) emitError(code || 'navigation_uncertain', active.requestId, { mayHaveBeenAccepted: !!active.clicked || !submissionStillUnobserved(active), retryAllowed: false });
    disposed = true;disposalReason=code||'document_changed';
    if (observer) observer.disconnect();
    if (interval) clearInterval(interval);
    window.removeEventListener('pagehide', onPageHide);
    document.removeEventListener('click', invalidatePopupEvidence, true);
    document.removeEventListener('click', invalidateAccountEvidence, true);
  }
  function onPageHide() { dispose('navigation_uncertain'); }
  function restoreOwnedConversation(id) {
    if (active || cleaningSession || !/^[A-Za-z0-9_-]{8,128}$/.test(id) || conversationIdFromPath(location.pathname) !== id) return false;
    ownedConversationId = id; verifiedWorkSession = true;initialPath=location.pathname;suspendedRoute=false;return true;
  }
  function recoveryTextDigest(text) {
    if(recoveryDigests.has(text))return recoveryDigests.get(text);
    if(recoveryDigests.size>=200)recoveryDigests.delete(recoveryDigests.keys().next().value);
    recoveryDigests.set(text,null);
    try {crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)).then(bytes=>{
      recoveryDigests.set(text,Array.from(new Uint8Array(bytes),byte=>byte.toString(16).padStart(2,'0')).join(''));
    }).catch(()=>recoveryDigests.set(text,''));}catch(_){recoveryDigests.set(text,'');}
    return recoveryDigests.get(text);
  }
  function recoverResponse(requestIds) {
    // Read-only recovery of an already rendered result. No submit, picker,
    // deletion, clipboard, provider API or tool execution belongs here.
    const state=readState(),id=conversationIdFromPath(location.pathname);
    if(document!==initialDocument||location.origin!=='https://chatgpt.com'||!id||state.busy||state.needsInput
        ||state.authenticated===false||authenticationInvalidated) {
      recoveryObservation=null;return {ok:false,code:state.busy?'reply_recovery_waiting':'reply_recovery_open_conversation',state};
    }
    const requests=(Array.isArray(requestIds)?requestIds:[]).map(value=>typeof value==='string'?{requestId:value}:value)
      .filter(value=>value&&typeof value.requestId==='string'&&/^[A-Za-z0-9_.:-]{1,128}$/.test(value.requestId)).slice(0,100);
    const allowed=new Set(requests.map(value=>value.requestId));
    const snapshot=turnSnapshot(),matches=[];
    let hashing=false;
    if(snapshot.valid)for(const turn of snapshot.turns.filter(item=>item.role==='assistant')) {
      const answer=answerSnapshot(turn);if(!answer.complete||!answer.text||answer.text.length>MAX_INLINE_EVENT_CHARS)continue;
      const text=answer.structuredEnvelope||(answer.rawEnvelopeEligible?answer.text:'');
      let envelope;try{if(text&&text.length<=MAX_STRUCTURED_ENVELOPE_CHARS)envelope=JSON.parse(text);}catch(_){}
      if(envelope&&envelope.protocol==='hoyoung.v1'&&allowed.has(envelope.requestId)) {
        matches.push({requestId:envelope.requestId,turnId:turn.id,envelope:text,text:answer.text});continue;
      }
      const before=snapshot.turns.slice(0,snapshot.turns.indexOf(turn));
      const users=turn.id.startsWith('group:')?snapshot.turns.filter(item=>item.role==='user'&&item.key===turn.key)
        :before.filter(item=>item.role==='user').slice(-1);
      if(users.length!==1)continue;
      const user=users[0];
      for(const request of requests) {
        const exact=active&&active.clicked&&active.requestId===request.requestId&&!active.baseline.has(user.id)&&user.text===normalized(active.text);
        const digest=/^[a-f0-9]{64}$/.test(request.userTextDigest||'')?recoveryTextDigest(user.text):'';
        if(digest===null)hashing=true;
        if(exact||digest&&digest===request.userTextDigest)matches.push({requestId:request.requestId,turnId:turn.id,text:answer.text,
          requestBinding:exact?'active_user_exact':'user_text_sha256',userTextDigest:digest||''});
      }
    }
    if(matches.length!==1||hashing) {recoveryObservation=null;return {ok:false,code:hashing?'reply_recovery_settling':matches.length?'reply_recovery_ambiguous':'reply_recovery_open_conversation',state};}
    const match=matches[0],key=JSON.stringify([id,match.requestId,match.turnId,match.envelope,match.text]);
    if(!recoveryObservation||recoveryObservation.key!==key){recoveryObservation={key,requestId:match.requestId,conversationId:id,at:Date.now()};return {ok:false,code:'reply_recovery_settling',state};}
    if(Date.now()-recoveryObservation.at<COMPLETION_SETTLE_MS)return {ok:false,code:'reply_recovery_settling',state};
    return {ok:true,code:'reply_recovered',conversationId:id,captureComplete:true,...match,state};
  }
  function adoptRecoveredResponse(requestId,id) {
    if(!recoveryObservation||recoveryObservation.requestId!==requestId||recoveryObservation.conversationId!==id
      ||Date.now()-recoveryObservation.at<COMPLETION_SETTLE_MS||conversationIdFromPath(location.pathname)!==id
      ||active&&active.requestId!==requestId)return false;
    const text=active?active.text:receipts.has(requestId)?receipts.get(requestId).text:undefined;
    active=null;ownedConversationId=id;initialPath=location.pathname;suspendedRoute=false;verifiedWorkSession=true;
    preferenceSelection=null;preferenceSelectionFlight=null;preferenceSelectionUncertain=false;preferenceCloseFlight=null;
    remember({requestId,text,phase:'completed',code:'reply_recovered'});recoveryObservation=null;
    inspectAndEmit();return true;
  }
  async function deleteOwnedConversation(id) {
    if (active || readState().busy || !ownedConversationId || id !== ownedConversationId || cleaningSession)
      return result('conversation_cleanup_busy');
    cleaningSession = true;
    let event;
    try { event = await window.HoyoungDeleteConversation(id); }
    catch (_) { event = {type:'session_cleanup', code:'conversation_cleanup_unconfirmed', conversationId:id}; }
    notify(event);
    // Keep the old document blocked until native has persisted the deletion
    // receipt and explicitly loads a fresh document. Failure needs owner review.
    return event;
  }
  const api = { inspect, connectionDiagnostics, connect, selectWork, send, cancel, inspectAndEmit, prepareOwnerRetry, authorizeDispatch, configurePreferences, ensurePreferences, captureUsageObservation, restoreOwnedConversation, deleteOwnedConversation, recoverResponse, adoptRecoveredResponse };
  Object.defineProperty(api, '__document', { value: document });
  Object.defineProperty(api, '__dispose', { value: dispose });
  window.HoyoungWorkBridge = api;
  observer = new MutationObserver(function () {
    if (inspectionScheduled || disposed) return;
    inspectionScheduled = true;
    setTimeout(function () { inspectionScheduled = false; tick(); }, 0);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true });
  interval = setInterval(tick, 250);
  window.addEventListener('pagehide', onPageHide);
  document.addEventListener('click', invalidatePopupEvidence, true);
  document.addEventListener('click', invalidateAccountEvidence, true);
  tick();
})();
