/*
 * DOM-only ChatGPT Work transport for Hoyoung's Android WebView.
 * Composer, stable turn identities and answer/footer observation are adapted from
 * miuuyy/codex-chatgpt-web (MIT), pinned 0b6087c5307c7a58adda9c420358f3f70d2035f5.
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
  let ownedConversationId = '';
  let verifiedWorkSession = false;
  let cleaningSession = false;
  const cos = window.HoyoungCoSDom;
  const COMPOSER = '[data-testid="prompt-textarea"], #prompt-textarea, [contenteditable="true"][data-lexical-editor="true"], form[data-chatgpt-composer] [data-composer-markdown][contenteditable="true"][role="textbox"]';
  const SEND = '[data-testid="send-button"], button[type="submit"]';
  const STOP = '[data-testid="stop-button"], button[aria-label="Stop generating"], button[aria-label="응답 생성 중지"]';
  const ANSWER = '.markdown, [data-markdown-text-style="assistant-message"], .puik-root.not-markdown > [class*="_DilResponseRoot"]';
  const FOOTER = 'button[data-testid="copy-turn-action-button"], .turn-action-controls button';
  const TRANSCRIPT = '[data-turn-key], [data-testid^="conversation-turn-"], [data-turn-id-container], [data-message-author-role], [data-conversation-role], [data-user-message-bubble]';
  const CONTROL = 'button, [role="button"], [role="tab"], [role="switch"], [role="checkbox"], [role="menuitemcheckbox"], [role="menuitemradio"], [role="radio"], [role="option"], [role="menuitem"], a[aria-current]';
  const MODEL_CONTROL = 'button[data-testid="model-switcher-dropdown-button"], button[data-codex-intelligence-trigger="true"], [data-model-picker-view-toggle="true"]';
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
  const MAX_PREFERENCE_SELECTION_CLICKS = 6;
  let preferenceSelection = null;
  let preferenceSelectionFlight = null;
  let preferenceSelectionUncertain = false;
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
  const receipts = new Map();
  const seenRequestIds = new Set();

  function normalized(value) {
    return String(value || '').normalize('NFKC').replace(/[\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/g, '').replace(/\s+/g, ' ').trim();
  }
  function textOf(element) { return normalized(element && (element.innerText || element.textContent)); }
  function labelOf(element) { return normalized(element && (element.getAttribute('aria-label') || element.getAttribute('title') || element.innerText || element.textContent)); }
  function visible(element) {
    if (!element || !element.isConnected) return false;
    for (let parent = element; parent; parent = parent.parentElement) {
      if (parent.matches('[hidden], [inert], [aria-hidden="true"]')) return false;
      const style = getComputedStyle(parent);
      if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
    }
    return element.getClientRects().length > 0;
  }
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
    return cos ? cos.composer() : null;
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
    return controls().filter(element => !element.closest('[role="menu"], [role="listbox"]')
      && (element.matches(MODEL_CONTROL) || element.matches('button[data-tone="neutral"][aria-haspopup="menu"]')
        && !!element.closest('form, [data-chatgpt-composer]') && !!parseCombinedModel(labelOf(element))));
  }
  function ownedMenus(triggers) {
    if (triggers.length !== 1) return [];
    const trigger = triggers[0];
    const ids = normalized([trigger.getAttribute('aria-controls'), trigger.getAttribute('aria-owns')].filter(Boolean).join(' ')).split(' ').filter(Boolean);
    return all('[role="menu"], [role="listbox"]').filter(menu => !menu.closest(TRANSCRIPT)
      && (menu.id && ids.includes(menu.id) || trigger.id && normalized(menu.getAttribute('aria-labelledby')).split(' ').includes(trigger.id)));
  }
  function fastToggles() {
    const menus = ownedMenus(modelTriggers());
    return controls().filter(element => /^Fast$|^빠름$/i.test(labelOf(element))
      && element.matches('[role="switch"], [role="checkbox"], [role="menuitemcheckbox"], button[aria-pressed]')
      && (menus.some(menu => menu.contains(element)) || element.closest('[role="group"][aria-label="Speed"], [role="group"][aria-label="속도"]'))
      && /^(?:true|false)$/.test(element.getAttribute('aria-checked') || element.getAttribute('aria-pressed') || ''));
  }
  function fastToggleValue(element) { return (element.getAttribute('aria-checked') || element.getAttribute('aria-pressed')) === 'true' ? 'fast' : 'standard'; }
  function invalidatePopupEvidence(event) {
    if (event.target instanceof Element && modelTriggers().some(trigger => trigger.contains(event.target))) observedSpeed = null;
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
    const model = uniqueValue(modelControls.concat(chosen.filter(element => isDimensionEvidence(element, 'model'))).map(element => parseModel(labelOf(element))));
    const currentControls = chosen.concat(modelControls, triggers.effort, triggers.speed);
    const combined = modelControls.map(element => parseCombinedModel(labelOf(element))).filter(Boolean);
    const effort = uniqueValue(combined.map(item => item.effort).concat(currentControls.filter(element => isDimensionEvidence(element, 'effort')).map(element => {
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
    const popupSpeed = toggles.some(element => element.closest('[role="menu"], [role="listbox"]'))
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
    if (!state.workSelected) return 'work_unverified';
    if (cleaningSession) return 'conversation_cleanup_pending';
    if (state.conversationOwned !== true) return 'unowned_conversation';
    if (state.busy || state.quota || state.needsInput) return state.quota ? 'quota_exhausted' : state.needsInput ? 'input_required' : 'busy';
    const editor = composer(), scope = composerScope(editor);
    if (!editor || !scope) return 'composer_unverified';
    if (editorText(editor)) return 'composer_has_draft';
    if (all('[data-testid="attachment"], [data-file-name], [role="group"][aria-label], [role="progressbar"], progress', scope).length
      || Array.from(scope.querySelectorAll('input[type="file"]')).some(input => input.files && input.files.length)) return 'composer_has_attachments';
    // Missing/contradictory selected UI evidence is never replaced by the desired
    // preference, a model offer or arbitrary transcript text.
    if (!state.model || !state.effort) return 'preference_selection_unverified';
    return 'safe';
  }
  function optionValue(element, dimension) {
    const label = labelOf(element);
    return dimension === 'model' ? parseModel(label) : dimension === 'effort' ? parseEffort(label) : parseSpeed(label);
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
    return dedicated.length || dimension === 'model' ? dedicated : modelTriggers().filter(element => !!parseCombinedModel(labelOf(element)) && /^(?:menu|listbox|true)$/.test(element.getAttribute('aria-haspopup') || ''));
  }
  function clickPreference(element, dimension, value, opening) {
    const selection = preferenceSelection;
    if (!selection || !selection.enabled || selection.clicks >= MAX_PREFERENCE_SELECTION_CLICKS || !enabled(element)) {
      if (selection) selection.status = 'preference_selection_limit';
      return;
    }
    selection.clicks++;
    preferenceSelectionFlight = { dimension, value, opening, deadline: performance.now() + PREFERENCE_SELECTION_TIMEOUT_MS };
    selection.status = 'preference_selection_pending';
    try { element.click(); }
    catch (_) { preferenceSelectionUncertain = true; selection.status = 'preference_selection_uncertain'; }
  }
  function advancePreferenceSelection() {
    const selection = preferenceSelection;
    if (!selection || !selection.enabled || !preferenceConfigurationValid) return;
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
      if (!flight.opening && state[flight.dimension] === flight.value) {
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
        }
        return;
      } else return;
    }
    const dimension = ['model', 'effort', 'speed'].find(name => state[name] !== configuredPreferences[name]);
    if (!dimension) { selection.status = 'verified'; return; }
    if (selection.failed || selection.clicks >= MAX_PREFERENCE_SELECTION_CLICKS) { selection.status = 'preference_selection_limit'; return; }
    const requested = configuredPreferences[dimension], choices = preferenceOptions(dimension, requested);
    if (choices.length === 1 && enabled(choices[0])) { clickPreference(choices[0], dimension, requested, false); return; }
    if (choices.length > 1) { selection.failed = true; selection.status = 'preference_option_ambiguous'; return; }
    const triggers = preferenceTriggers(dimension);
    if (triggers.length !== 1 || !enabled(triggers[0])) { selection.failed = true; selection.status = 'preference_option_unavailable'; return; }
    clickPreference(triggers[0], dimension, requested, true);
  }
  function ensurePreferences(payload) {
    const preferences = applyPreferences(payload);
    if (!preferences) { if (preferenceSelection) preferenceSelection.enabled = false; return inspectAndEmit(); }
    const key = JSON.stringify(preferences);
    if (!preferenceSelection || preferenceSelection.key !== key) preferenceSelection = { key, clicks: 0, enabled: true, failed: false, status: 'pending' };
    else preferenceSelection.enabled = true;
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
  function readState() {
    const selectable = controls();
    const editor = composer();
    const loggedOut = selectable.some(element => /^(?:Log in|Login|Sign in|로그인)$/i.test(labelOf(element)));
    const accountControl = selectable.some(element => element.matches('[data-testid="user-menu-button"]')
      || /^(?:Open (?:profile|account|user) menu|(?:프로필|계정|사용자) 메뉴 열기)$/i.test(labelOf(element)));
    // A mobile sidebar may keep the signed-in account control mounted but hidden.
    // Only the account-specific marker counts; arbitrary hidden labels do not.
    const responsiveAccount = Array.from(document.querySelectorAll('button[data-testid="user-menu-button"]'))
      .some(element => !element.closest(TRANSCRIPT));
    // A guest composer alone is not proof of an authenticated subscription.
    const authenticated = loggedOut ? false : editor && (accountControl || responsiveAccount) ? true : null;
    const temporaryBanner = all('header [role="status"], main > [role="status"], header h1, main > h1')
      .some(element => isTemporary(element) && !element.closest(TRANSCRIPT));
    const selectedWork = selectable.some(element => isWork(element) && selected(element));
    const selectedChat = selectable.some(element => /^(?:Chat|Chat mode|채팅|Chat 모드)$/i.test(labelOf(element)) && selected(element));
    if (selectedChat) verifiedWorkSession = false;
    if (selectedWork && location.pathname === initialPath) verifiedWorkSession = true;
    const ownPath = ownedConversationId && location.pathname === '/c/' + ownedConversationId;
    const fresh = location.pathname === '/' && turnSnapshot().turns.length === 0;
    return Object.assign({
      authenticated,
      workSelected: selectedWork || !!(ownPath && verifiedWorkSession && !selectedChat),
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
    if (!preferenceConfigurationValid) return 'unsupported_preferences';
    if (state.authenticated === false) return 'authentication_required';
    if (state.authenticated !== true) return 'authentication_unverified';
    if (!state.workSelected) return 'work_unverified';
    if (cleaningSession) return 'conversation_cleanup_pending';
    if (state.conversationOwned !== true) return 'unowned_conversation';
    if (preferenceSelectionFlight) return preferenceSelectionUncertain ? 'preference_selection_uncertain' : 'preference_selection_pending';
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
    const code = readyCode(state);
    return { ok: code === 'ready', code, state, requiredPreferences: Object.assign({}, configuredPreferences),
      preferenceSelection: preferenceSelection ? { status: preferenceSelection.status, clicks: preferenceSelection.clicks, enabled: preferenceSelection.enabled } : null,
      activeRequestId: active ? active.requestId : null, phase: active ? active.phase : null };
  }

  function turnSnapshot() {
    const turns = [];
    const known = new Set();
    const groupKeys = new Set();
    let valid = true;
    for (const group of Array.from(document.querySelectorAll('[data-turn-key]'))) {
      if (group.parentElement && group.parentElement.closest('[data-turn-key]')) continue;
      const key = normalized(group.getAttribute('data-turn-key'));
      if (!key || groupKeys.has(key)) { valid = false; continue; }
      groupKeys.add(key);
      known.add('group:user:' + key); known.add('group:assistant:' + key);
      const userRoots = all('[data-user-message-bubble]', group);
      const assistantRoots = all('[data-conversation-role="assistant"], [data-chatgpt-agent-turn-start]', group)
        .filter(element => !element.parentElement || !element.parentElement.closest('[data-conversation-role="assistant"]'));
      if (userRoots.length > 1) valid = false;
      if (userRoots.length === 1) {
        const user = userRoots[0];
        const content = all('[data-search-result-target]', user);
        turns.push({ id: 'group:user:' + key, key, role: 'user', root: group, content: user, text: textOf(content.length === 1 ? content[0] : user) });
      }
      if (assistantRoots.length) turns.push({ id: 'group:assistant:' + key, key, role: 'assistant', root: group, content: assistantRoots[assistantRoots.length - 1] });
    }
    const legacyKeys = new Set();
    for (const turn of Array.from(document.querySelectorAll('[data-testid^="conversation-turn-"]'))) {
      if (turn.closest('[data-turn-key]') || turn.parentElement && turn.parentElement.closest('[data-testid^="conversation-turn-"]')) continue;
      const role = turn.getAttribute('data-turn') || turn.getAttribute('data-message-author-role')
        || (turn.querySelector('[data-message-author-role="assistant"]') ? 'assistant' : turn.querySelector('[data-message-author-role="user"]') ? 'user' : null);
      if (role !== 'assistant' && role !== 'user') continue;
      const container = turn.closest('[data-turn-id-container]');
      const key = normalized(turn.getAttribute('data-turn-id') || container && container.getAttribute('data-turn-id-container'));
      if (!key || legacyKeys.has(role + ':' + key)) { valid = false; continue; }
      legacyKeys.add(role + ':' + key);
      const id = 'legacy:' + role + ':' + key;
      const content = turn.querySelector('[data-message-author-role="' + role + '"]') || turn;
      turns.push({ id, key, role, root: turn, content, text: role === 'user' ? textOf(content) : null });
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
    let answers = all(ANSWER, turn.root).filter(element => !element.closest('[data-user-message-bubble], [data-message-author-role="user"], [data-conversation-role="user"]')
      && (turn.content.contains(element) || !!(turn.content.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING)));
    if (turn.content.matches(ANSWER) && visible(turn.content)) answers.unshift(turn.content);
    answers = answers.filter(element => !element.parentElement || !element.parentElement.closest(ANSWER)
      || !turn.content.contains(element.parentElement.closest(ANSWER)));
    const lastAnswer = answers[answers.length - 1];
    if (!lastAnswer) return { text: '', complete: false, rawEnvelopeEligible: false };
    const footer = all(FOOTER, turn.root).find(element => !element.closest('[data-user-message-bubble], [data-message-author-role="user"], [data-conversation-role="user"]')
      && !lastAnswer.contains(element) && !!(lastAnswer.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING));
    const answer = { text: answers.map(element => element.innerText || element.textContent || '').filter(Boolean).join('\n\n'), complete: !!footer, rawEnvelopeEligible: false };
    // Markdown rendering removes literal fences. Never turn a quoted example or
    // one block inside a larger reply into a control message: only the whole
    // response may supply a positively identified hoyoung-language block.
    if (answers.length === 1) {
      answer.rawEnvelopeEligible = bareProtocolEligible(answers[0]);
      Object.assign(answer, wholeStructuredEnvelope(answers[0]));
    }
    return answer;
  }
  function bareProtocolEligible(answer) {
    // innerText loses quote/code/Markdown syntax. A rendered quotation must not
    // become a raw JSON command just because its visible characters form JSON.
    // Runtime still requires the whole valid object + current protocol/requestId.
    return all('pre, code, blockquote, q, li, details, strong, em, b, i, s, del, mark, sub, sup, a, h1, h2, h3, h4, h5, h6, table, img, video, audio, canvas, iframe, object, embed, hr, [role="img"], input, textarea, select, button', answer).length === 0;
  }
  function wholeStructuredEnvelope(answer) {
    const blocks = all('pre', answer);
    if (blocks.length !== 1) return {};
    const pre = blocks[0];
    const quotation = pre.closest('blockquote, q, li, details');
    if (quotation && answer.contains(quotation)) return {};
    const codes = all('code', pre);
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
    for (const element of all('img, video, audio, canvas, iframe, object, embed, hr, table, [role="img"], input, textarea, select, button, a', answer)) {
      if (!code.contains(element) && !(pre.contains(element) && element.closest('button'))) return {};
    }
    for (const element of code.querySelectorAll('*')) {
      if (!visible(element) && normalized(element.textContent)) return {};
    }
    const walker = document.createTreeWalker(answer, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (!visible(node.parentElement) || code.contains(node) || !normalized(node.textContent)) continue;
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
    active.phase = 'uncertain';
    active.uncertaintyCode = code;
    remember({ requestId: active.requestId, text: active.text, phase: 'uncertain', code });
    emitError(code, active.requestId, Object.assign({ mayHaveBeenAccepted: !!active.clicked, retryAllowed: false }, metadata || {}));
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
    if (location.pathname !== initialPath && initialPath === '/' && active && active.clicked && active.freshConversation
        && /^\/c\/[A-Za-z0-9_-]{8,128}$/.test(location.pathname)) {
      const snapshot = turnSnapshot();
      const users = snapshot.turns.filter(turn => turn.role === 'user' && !active.baseline.has(turn.id));
      if (snapshot.valid && users.length === 1 && users[0].text === normalized(active.text)) {
        initialPath = location.pathname;
        ownedConversationId = initialPath.substring(3);
      } else if (Date.now() - active.clickedAt < ACCEPT_TIMEOUT_MS) return;
    }
    if (document !== initialDocument || location.origin !== 'https://chatgpt.com' || location.pathname !== initialPath) {
      dispose('navigation_uncertain'); return;
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
  function editorText(editor) { return editor.matches('textarea, input') ? normalized(editor.value) : textOf(editor); }
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
    if (/^(?:preparing(?:_attachments)?|awaiting_authorization)$/.test(active.phase) && !active.clicked) {
      if (!submissionStillUnobserved(active)) {
        uncertain('cancellation_submission_unverified', { knownNotSent: false, mayHaveBeenAccepted: true });
        return result('cancellation_submission_unverified', { requestId: active.requestId, retryAllowed: false });
      }
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
    disposed = true;
    if (observer) observer.disconnect();
    if (interval) clearInterval(interval);
    window.removeEventListener('pagehide', onPageHide);
    document.removeEventListener('click', invalidatePopupEvidence, true);
  }
  function onPageHide() { dispose('navigation_uncertain'); }
  function restoreOwnedConversation(id) {
    if (active || cleaningSession || !/^[A-Za-z0-9_-]{8,128}$/.test(id) || location.pathname !== '/c/' + id) return false;
    ownedConversationId = id; verifiedWorkSession = true; return true;
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
  const api = { inspect, selectWork, send, cancel, inspectAndEmit, prepareOwnerRetry, authorizeDispatch, configurePreferences, ensurePreferences, captureUsageObservation, restoreOwnedConversation, deleteOwnedConversation };
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
  tick();
})();
