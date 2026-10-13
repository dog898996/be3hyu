'use strict';

(async () => {
  const { PendingMessageQueue } = await import('/message-queue.js');
  const $ = id => document.getElementById(id);
  const state = { data: null, connected: false, syncing: false, authRequired: false, dirtySettings: false, dirtyConnections: false, view: 'chat', goalFilter: 'all', memoryFilter: 'active', activityFilter: 'all', renderedMessages: '', browserBusy: false, browserOwner: 'chatgpt', pendingButtons: new Set(), modelLogin: null, loginTimer: null, loginPolling: false, composerIds: new Map() };
  const personaName = () => state.data?.settings?.persona?.name?.trim() || '호영이';
  const viewNames = { overview: '개요', chat: '대화', goals: '목표와 작업', memory: '메모리', connections: '연결', activity: '활동 기록', settings: '맞춤 설정' };
  const capabilities = {
    browser: ['브라우저', '웹 탐색, 로그인된 서비스 사용'], terminal: ['터미널', '명령 실행, 프로그램과 자동화'], files: ['파일', '문서, 프로젝트, 파일 읽기와 쓰기'], memory: ['메모리', '기억 조회, 학습, 수정, 잊기'], goals: ['목표와 작업', '목표 생성, 계획, 실행'], messages: ['메시지', '연결된 채널에서 대화와 메시지 전송'], settings: ['맞춤 설정', '권한과 행동 지침 변경'], agents: ['에이전트', '작업 분담, 새 능력과 도구 확장']
  };
  const kindNames = { fact: '확인된 사실', inference: '합리적 추론', preference: '사용자 선호', uncertain: '불확실한 주장', opinion: '주관적 의견' };
  const goalStatuses = { active: '진행 중', pending: '대기', running: '실행 중', completed: '완료', done: '완료', paused: '일시 중지', failed: '실패', cancelled: '취소' };
  const taskStatuses = { ...goalStatuses, succeeded: '완료', queued: '실행 대기', waiting: '재시도 대기', working: '실행 중', waiting_for_model: '모델 대기', waiting_for_quota: '한도 회복 대기', blocked: '연결 대기', error: '오류' };
  const text = (id, value) => { $(id).textContent = value ?? '—'; };
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const badge = (id, label, style = 'neutral') => { $(id).textContent = label; $(id).className = `badge ${style}`; };
  const array = value => Array.isArray(value) ? value : [];
  const dateValue = value => { const date = new Date(value); return value && Number.isFinite(date.getTime()) ? date : null; };
  const dateTime = value => { const date = dateValue(value); return date ? date.toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : '—'; };
  const shortTime = value => { const date = dateValue(value); if (!date) return '—'; const seconds = Math.max(0, (Date.now() - date.getTime()) / 1000); return seconds < 60 ? '방금' : seconds < 3600 ? `${Math.floor(seconds / 60)}분 전` : seconds < 86400 ? `${Math.floor(seconds / 3600)}시간 전` : date.toLocaleDateString('ko-KR', { month: 'numeric', day: 'numeric' }); };
  const isCompleted = item => ['completed', 'done', 'succeeded'].includes(item.status);
  const isArchived = item => item.archived === true || item.state === 'archived';
  const logMessage = log => log.data?.message || log.message || log.title || String(log.kind || '활동 기록');
  const logCategory = log => { const type = `${log.kind || ''} ${log.level || ''}`; return /error|fail/i.test(type) ? 'error' : /config|setting|permission/i.test(type) ? 'settings' : 'action'; };
  const sourceLabel = memory => { const provenance = memory.provenance || memory.source || memory.evidence; if (typeof provenance === 'string') return provenance; if (Array.isArray(provenance) && provenance.length) return provenance.map(p => typeof p === 'string' ? p : p.text || p.label || p.title || p.type || '').filter(Boolean).join(' · '); if (provenance && !Array.isArray(provenance) && typeof provenance === 'object') return provenance.label || provenance.title || provenance.type || ''; const ids = array(memory.sourceMessageIds); return ids.length ? `대화 기록 ${ids.length}개 · ${ids.join(', ')}` : '근거 미기록'; };
  const empty = (title, detail, icon = '◎') => `<div class="empty-state"><span>${icon}</span><h3>${escape(title)}</h3><p>${escape(detail)}</p></div>`;

  function toast(message, isError = false) {
    const node = document.createElement('div'); node.className = `toast${isError ? ' error' : ''}`; node.textContent = message; $('toast-region').append(node); setTimeout(() => node.remove(), isError ? 9000 : 5000);
  }

  let messageStorage;
  try { messageStorage = window.localStorage; } catch {}
  const messageQueue = new PendingMessageQueue({
    storage: messageStorage,
    send: payload => api('/api/chat', 'POST', payload),
    canSend: () => state.connected && !state.authRequired && navigator.onLine !== false,
    onChange: () => { renderMessages(); renderConnection(); },
    onAccepted: (entry, response) => {
      const input = entry.draftKey && $(entry.draftKey);
      if (input && state.composerIds.get(entry.draftKey) === entry.clientMessageId) {
        if (input.value.trim() === entry.message) input.value = '';
        state.composerIds.delete(entry.draftKey);
      }
      const saved = response?.message;
      if (saved && state.data && !array(state.data.messages).some(message => message.id === saved.id)) state.data.messages = [saved, ...array(state.data.messages)];
      renderMessages();
    }
  });

  async function api(path, method = 'GET', payload) {
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), path.startsWith('/api/browser') ? 90000 : 25000);
    try {
      const response = await fetch(path, { method, headers: payload === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' }, body: payload === undefined ? undefined : JSON.stringify(payload), signal: controller.signal, cache: 'no-store' });
      const contentType = response.headers.get('content-type') || ''; const data = contentType.includes('application/json') ? await response.json() : {};
      if (!response.ok || data.ok === false || data.error) { const error = new Error(typeof data.error === 'string' ? data.error : data.error?.message || data.message || `서버 응답 오류 (${response.status})`); error.status = response.status; throw error; }
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('서버 응답 시간이 초과되었습니다. 상태를 새로고침해 확인하세요.');
      if (error instanceof TypeError) throw new Error('서버와 연결할 수 없습니다. 서버 실행과 네트워크 상태를 확인하세요.');
      throw error;
    } finally { clearTimeout(timer); }
  }

  async function perform(button, action, message) {
    if (button && state.pendingButtons.has(button)) return null;
    if (button) { button.disabled = true; state.pendingButtons.add(button); }
    try { const result = await action(); if (message) toast(message); await sync(); return result; }
    catch (error) { toast(error.message, true); return null; }
    finally { if (button) { state.pendingButtons.delete(button); button.disabled = !state.connected || (button.id === 'model-login-start' && state.modelLogin?.state === 'pending'); } }
  }

  function goTo(view, updateHash = true) {
    if (!viewNames[view]) view = 'chat'; state.view = view;
    document.querySelectorAll('.view').forEach(node => node.classList.toggle('active', node.id === `view-${view}`));
    document.querySelectorAll('[data-view]').forEach(node => node.classList.toggle('active', node.dataset.view === view));
    text('view-label', viewNames[view]); $('mobile-menu').setAttribute('aria-expanded', 'false'); document.querySelector('.sidebar').classList.remove('open');
    if (updateHash && location.hash !== `#${view}`) history.replaceState(null, '', `#${view}`);
    if (view === 'chat') $('chat-messages').scrollTop = $('chat-messages').scrollHeight;
  }

  function renderConnection() {
    $('offline-banner').hidden = state.connected;
    const pending = messageQueue.list();
    text('offline-banner', state.authRequired ? '원격 서버 접근 보호를 확인해야 합니다. 대기 메시지는 기기에 보존하며 인증 후 같은 ID로 전송합니다.' : `앱과 서버의 연결이 끊겼습니다. 클라우드 작업의 현재 상태는 재연결 후 확인합니다.${pending.length ? ` 전송 대기 메시지 ${pending.length}개를 기기에 보존했습니다.` : ''} 연결이 복구되면 자동으로 확인합니다.`);
    $('connection-pill').innerHTML = `<span class="status-dot ${state.connected ? '' : 'red'}"></span>${state.connected ? '서버 연결됨' : '서버 연결 끊김'}`;
    document.querySelectorAll('form:not(#auth-form) button[type="submit"]').forEach(button => { const isChat = button.closest('#chat-form, #quick-chat-form'); button.disabled = (!isChat && !state.connected) || state.pendingButtons.has(button); });
    document.querySelectorAll('#settings-form input, #settings-form select, #settings-form textarea, #connections-form input, #connections-form select, #connections-form textarea, #browser-input-form input, #goal-form input, #goal-form select, #goal-form textarea, #memory-form input, #memory-form select, #memory-form textarea').forEach(input => { input.disabled = !state.connected; });
    $('chat-message').disabled = false; $('quick-message').disabled = false;
    $('chat-form').querySelector('.composer-footer > span').textContent = pending.length ? `기기에 보존된 전송 대기 ${pending.length}개 · 연결 후 접수 확인` : 'Enter 전송 · Shift + Enter 줄바꿈';
    text('quick-channel-note', pending.length ? `전송 대기 ${pending.length}개를 기기에 보존했습니다.` : '이 메시지는 전용 앱에서 서버에 전달됩니다.');
    ['engine-control', 'show-goal-form', 'add-memory', 'allow-all', 'chatgpt-login', 'browser-refresh', 'browser-enter', 'browser-tab', 'browser-escape', 'check-quota', 'model-login-start'].forEach(id => { $(id).disabled = !state.connected || state.pendingButtons.has($(id)) || (id === 'model-login-start' && state.modelLogin?.state === 'pending'); });
    if (!state.connected) { text('sidebar-status', '앱 연결 끊김'); $('sidebar-dot').className = 'status-dot red'; text('sidebar-heartbeat', '클라우드 상태 재확인 대기'); badge('engine-badge', '연결 끊김', 'red'); text('engine-title', '앱이 서버에 연결할 수 없어요'); text('engine-detail', '앱 연결을 끊어도 작업 중지 명령은 보내지 않습니다. 다시 연결하면 클라우드의 현재 작업 상태를 확인합니다.'); badge('provider-badge', '미확인'); }
  }

  function renderStatus() {
    if (!state.data) return;
    const settings = state.data.settings || {}; const runtime = state.data.status || state.data.runtime || {}; const model = runtime.model || {}; const enabled = settings.autonomy?.enabled === true; const quota = runtime.quota || {}; const modelReason = model.reason || model.message || '';
    const running = enabled && runtime.state !== 'paused';
    const models = { 'chatgpt-browser': 'ChatGPT 웹', webzonku: 'Webzonku', 'codex-cli': 'Codex CLI' };
    const statusNames = { waiting_for_model: ['모델 연결을 기다리고 있어요', '구독 인증과 모델 준비가 완료되면 목표를 이어서 실행합니다.', '모델 대기', 'amber'], working: ['다음 행동을 이어가고 있어요', '목표와 현재 맥락에 따라 실제 작업을 수행하고 있습니다.', '실행 중', 'mint'], paused: ['잠시 멈춰 있어요', '목표와 기억을 보존하고 있습니다. 재개하면 다음 행동을 이어갑니다.', '일시 중지', 'neutral'], error: ['실행 중 확인이 필요한 문제가 있어요', '활동 기록에서 오류 원인과 연결 상태를 확인하세요.', '확인 필요', 'red'], idle: ['다음 할 일을 살펴보고 있어요', '이벤트와 목표를 확인하며 다음 행동을 준비합니다.', '대기 중', 'mint'], waiting_for_quota: ['모델 한도 회복을 기다리고 있어요', '작업과 대화를 보존하고 새 메시지는 앱에 저장합니다.', '한도 대기', 'amber'] };
    const current = quota.exhausted ? statusNames.waiting_for_quota : !enabled ? statusNames.paused : statusNames[runtime.state] || ['서버 상태를 확인했어요', '실제 작업과 연결 상태를 아래에서 확인하세요.', '상태 확인', 'neutral'];
    text('engine-title', current[0]); text('engine-detail', current[1]); badge('engine-badge', current[2], current[3]); text('sidebar-status', current[2]); $('sidebar-dot').className = `status-dot ${current[3] === 'mint' ? '' : current[3] === 'red' ? 'red' : 'amber'}`;
    text('sidebar-heartbeat', `${settings.autonomy?.heartbeatSeconds ?? 60}초 하트비트 · 이벤트 반응`);
    const next = dateValue(runtime.nextDecisionAt); text('next-heartbeat', running && next ? (next > new Date() ? `${Math.ceil((next.getTime() - Date.now()) / 1000)}초 후` : '곧 확인') : running ? '이벤트 · 주기 확인' : '일시 중지');
    text('last-activity', shortTime(runtime.lastDecisionAt)); text('model-mini', model.ready ? '준비됨' : model.reachable ? '응답 확인 중' : '연결 대기');
    $('engine-control').innerHTML = running ? '<span>Ⅱ</span> 자율 실행 일시 중지' : '<span>▷</span> 자율 실행 재개'; $('engine-control').dataset.action = running ? 'pause' : 'resume';
    text('provider-summary', `${models[settings.model?.provider] || '모델'} · ${modelReason || (model.ready ? '모델 준비됨' : '연결 확인 필요')}`); badge('provider-badge', model.ready ? '준비됨' : '연결 필요', model.ready ? 'mint' : 'neutral'); badge('model-detail-badge', model.ready ? '준비됨' : model.reachable ? '확인 중' : '연결 필요', model.ready ? 'mint' : 'neutral');
    text('model-connection-detail', modelReason || '모델의 실제 응답과 로그인 상태를 확인하세요.'); text('browser-profile-status', runtime.browserReady ? '서버의 지속 브라우저 프로필을 사용할 수 있습니다.' : '브라우저가 준비되지 않았습니다. 서버 환경과 활동 기록을 확인하세요.');
    const observedModel = array(model.modelLabels || model.observedModelLabels).join(' · ') || model.observedModel;
    const verifiedModel = model.verified === true && model.actualModel;
    text('actual-model-label', verifiedModel ? `확인된 실행 조합: ${model.actualModel} · ${model.actualEffort || '추론 수준 미확인'} · ${model.actualServiceTier === 'priority' ? 'Fast (priority)' : model.actualServiceTier || '속도 미확인'}` : settings.model?.provider === 'chatgpt-browser' && observedModel ? `실제 브라우저에서 관측한 모델: ${observedModel}` : '실제 실행 조합: 아직 확인되지 않았습니다.');
    const preference = settings.model?.preferences || {}; const preferenceApplied = model.verified === true && (model.preferenceApplied === true || runtime.preferenceApplied === true);
    text('model-preference-status', `${preference.preferredModel || '모델 미지정'} · ${preference.reasoningEffort || '추론 미지정'} · ${preference.speed || '속도 미지정'}. ${preferenceApplied ? '실제 연결에서 적용을 확인했습니다.' : settings.model?.allowFallback ? '희망 설정의 실제 적용은 미확인입니다. 주인이 현재 선택 모델 사용을 허용했습니다.' : '실제 적용이 아직 확인되지 않았습니다. 원하는 설정이 없으면 임의로 낮추지 않고 주인에게 확인합니다.'}`);
    badge('chat-model-state', model.ready ? '모델 준비됨' : quota.exhausted ? '한도 회복 대기' : '모델 연결 필요', model.ready && !quota.exhausted ? 'mint' : 'amber');
    text('chat-autonomy', enabled ? '사용' : '중지'); text('chat-self-config', settings.permissions?.agentCanChange ? '허용' : '사용자만 변경'); text('chat-channel', '전용 앱 대화');
    $('quota-banner').hidden = !quota.exhausted;
    if (quota.exhausted) { const recovery = quota.resetKnown && quota.retryAt ? `확인된 재확인 시각: ${dateTime(quota.retryAt)}.` : '한도 초기화 시각은 확인되지 않았습니다.'; text('quota-detail', `${quota.reason || '모델이 실제 사용 한도를 알렸습니다.'} 작업과 대화를 보존하고 새 메시지는 앱에 저장합니다. ${recovery}`); }
  }

  function fillSettings() {
    const settings = state.data?.settings || {};
    const name = personaName(); document.title = `${name} · 자율비서`; text('brand-name', name); text('overview-agent-name', name); text('chat-assistant-name', `${name}와 대화`); text('footer-agent-name', name);
    if (!state.dirtySettings) {
      $('autonomy-enabled').checked = settings.autonomy?.enabled === true; $('heartbeat-seconds').value = settings.autonomy?.heartbeatSeconds ?? 60; $('agent-can-change').checked = settings.permissions?.agentCanChange === true;
      $('conversation-channel').value = 'web'; $('custom-instructions').value = settings.persona?.instructions || ''; $('persona-name').value = name;
      const stored = settings.permissions?.capabilities || {};
      $('capability-list').innerHTML = Object.entries(capabilities).map(([name, [label, description]]) => `<label class="capability-row"><span><strong class="capability-name">${label}</strong><small class="capability-description">${description}</small></span><input class="switch" type="checkbox" data-capability="${name}" ${stored[name] === true || (stored[name] === undefined && settings.permissions?.default === 'allow') ? 'checked' : ''}></label>`).join('');
      badge('settings-save-state', '서버에 저장됨', 'neutral');
    }
    if (!state.dirtyConnections) {
      const model = settings.model || {};
      $('model-provider').value = model.provider || 'codex-cli'; $('model-endpoint').value = model.endpoint || ''; $('model-name').value = model.model || ''; $('chatgpt-url').value = model.url || 'https://chatgpt.com/?temporary-chat=true';
      $('preferred-model').value = model.preferences?.preferredModel || 'gpt-6.1-sol'; $('reasoning-effort').value = model.preferences?.reasoningEffort || 'medium'; $('model-speed').value = model.preferences?.speed || 'fast'; $('model-allow-fallback').checked = model.allowFallback === true;
      updateProviderFields();
    }
    updateInstructionCount(); updatePermissionsBadge();
  }

  function updateProviderFields() { const provider = $('model-provider').value; $('endpoint-field').hidden = provider !== 'webzonku'; $('native-login-panel').hidden = provider !== 'codex-cli'; $('chatgpt-login').hidden = provider !== 'chatgpt-browser'; $('chatgpt-url-field').hidden = provider !== 'chatgpt-browser'; $('model-name-field').hidden = provider !== 'chatgpt-browser'; $('browser-panel').hidden = provider !== 'chatgpt-browser' || !$('advanced-model-settings').open; }
  function updateInstructionCount() { text('instructions-count', `${$('custom-instructions').value.length.toLocaleString('ko-KR')}자`); }
  function updatePermissionsBadge() { const switches = [...document.querySelectorAll('[data-capability]')]; const count = switches.filter(input => input.checked).length; badge('permissions-badge', count === switches.length && count > 0 ? '모두 허용' : `${count} / ${switches.length} 허용`, count === switches.length && count > 0 ? 'mint' : 'amber'); }

  function renderGoals() {
    const allGoals = array(state.data?.goals); const activeGoals = allGoals.filter(goal => !isCompleted(goal) && goal.status !== 'cancelled'); const tasks = array(state.data?.tasks);
    text('stat-goals', activeGoals.length); text('stat-completed', tasks.filter(isCompleted).length); text('nav-goal-count', activeGoals.length); text('goal-count-label', activeGoals.length); text('goals-total', `${allGoals.length}개의 목표`); text('tasks-total', `${tasks.length}개의 작업`);
    $('overview-goals').innerHTML = activeGoals.slice(0, 3).map(goal => `<div class="overview-goal"><span class="goal-symbol">◎</span><div><strong>${escape(goal.title || goal.description || '목표')}</strong><p>${escape(goal.description || '완료 조건을 추가해 실행 방향을 정해주세요.')}</p></div><span class="badge ${goal.status === 'running' ? 'mint' : 'neutral'}">${escape(goalStatuses[goal.status] || '대기')}</span></div>`).join('') || '<div class="empty-inline">아직 진행 중인 목표가 없습니다. 목표를 추가하면 비서가 다음 행동을 준비합니다.</div>';
    const goals = allGoals.filter(goal => state.goalFilter === 'all' || (state.goalFilter === 'completed' ? isCompleted(goal) : !isCompleted(goal)));
    $('goals-list').innerHTML = goals.map(goal => `<article class="goal-card"><div class="goal-card-top"><span class="goal-symbol">◎</span><span class="badge ${isCompleted(goal) ? 'mint' : goal.status === 'failed' ? 'red' : 'neutral'}">${escape(goalStatuses[goal.status] || goal.status || '대기')}</span></div><h3>${escape(goal.title || '목표')}</h3><p>${escape(goal.description || '추가 맥락 없음')}</p><div class="goal-card-footer"><span>${dateTime(goal.createdAt)} · ${Number(goal.priority) > 0 ? '높은 우선순위' : Number(goal.priority) < 0 ? '낮은 우선순위' : '보통 우선순위'}</span><button class="text-button" data-goal-action="${isCompleted(goal) ? 'active' : 'completed'}" data-goal-id="${escape(goal.id)}">${isCompleted(goal) ? '다시 진행 ↗' : '완료 처리 ✓'}</button></div></article>`).join('') || empty('등록된 목표가 없습니다', '목표를 추가하고 필요한 결과와 완료 조건을 알려주세요.');
    $('tasks-list').innerHTML = tasks.map(task => `<div class="task-row"><span class="task-dot"></span><div class="task-body"><strong>${escape(task.title || task.description || task.type || '작업')}</strong>${task.result || task.error ? `<p>${escape(typeof task.result === 'string' ? task.result : task.error || task.result?.summary || '')}</p>` : ''}<div class="task-tools"><button class="text-button" data-task-action="edit" data-task-id="${escape(task.id)}">지시 수정</button>${isCompleted(task) ? '' : `<button class="text-button" data-task-action="${task.status === 'paused' || task.status === 'failed' ? 'queued' : 'paused'}" data-task-id="${escape(task.id)}">${task.status === 'paused' || task.status === 'failed' ? '다시 진행 ↗' : '멈춤 Ⅱ'}</button>`}</div></div><span class="badge ${isCompleted(task) ? 'mint' : ['failed', 'error'].includes(task.status) ? 'red' : 'neutral'}">${escape(taskStatuses[task.status] || task.status || '대기')}</span></div>`).join('') || '<div class="empty-inline">아직 생성된 작업이 없습니다. 비서가 목표를 실행하면 실제 작업이 이곳에 표시됩니다.</div>';
  }

  function renderMemories() {
    const memories = array(state.data?.memories); text('stat-memories', memories.filter(memory => !isArchived(memory)).length);
    const query = $('memory-search').value.toLowerCase().trim(); const filtered = memories.filter(memory => (state.memoryFilter === 'all' || (state.memoryFilter === 'archived' ? isArchived(memory) : !isArchived(memory))) && `${memory.content} ${sourceLabel(memory)} ${memory.key || ''}`.toLowerCase().includes(query));
    $('memory-list').innerHTML = filtered.map(memory => `<article class="memory-card"><div class="memory-card-top"><span class="badge ${memory.kind === 'fact' ? 'mint' : memory.kind === 'inference' || memory.kind === 'uncertain' ? 'amber' : 'neutral'}">${escape(kindNames[memory.kind] || memory.kind || '기억')}</span><time>${dateTime(memory.updatedAt || memory.createdAt)}</time></div><p class="memory-card-content">${escape(memory.content)}</p><div class="memory-source"><strong>근거</strong> · ${escape(sourceLabel(memory))}${typeof memory.confidence === 'number' ? `<br>확신 수준 · ${Math.round(memory.confidence * 100)}%` : ''}</div><div class="card-actions"><button class="text-button" data-memory-action="edit" data-memory-id="${escape(memory.id)}">수정</button><button class="text-button" data-memory-action="${isArchived(memory) ? 'restore' : 'archive'}" data-memory-id="${escape(memory.id)}">${isArchived(memory) ? '복원' : '보관'}</button><button class="text-button forget-button" data-memory-action="forget" data-memory-id="${escape(memory.id)}">잊기</button></div></article>`).join('') || empty(query ? '검색 결과가 없습니다' : state.memoryFilter === 'archived' ? '보관한 기억이 없습니다' : '아직 저장된 기억이 없습니다', query ? '다른 단어나 근거로 검색해보세요.' : '대화와 작업에서 맥락이 쌓이면 실제 기억이 표시됩니다. 직접 추가할 수도 있습니다.', '⌘');
  }

  function activityHtml(log, full = false) { const category = logCategory(log); return `<div class="activity-item ${category}"><span class="activity-indicator"></span><div class="activity-body"><strong>${escape(logMessage(log))}</strong>${full && log.data?.reason ? `<p>${escape(log.data.reason)}</p>` : ''}${full ? `<p>${escape(log.kind || 'activity')}</p>` : ''}</div><time class="activity-time">${full ? dateTime(log.createdAt) : shortTime(log.createdAt)}</time></div>`; }
  function renderActivity() {
    const logs = array(state.data?.logs); text('logs-total', `${logs.length}개의 기록`);
    $('overview-activity').innerHTML = logs.slice(0, 3).map(log => activityHtml(log)).join('') || '<div class="empty-inline">아직 활동 기록이 없습니다. 실제 실행과 변경 내역이 이곳에 표시됩니다.</div>';
    const filtered = logs.filter(log => state.activityFilter === 'all' || logCategory(log) === state.activityFilter);
    $('activity-list').innerHTML = filtered.map(log => activityHtml(log, true)).join('') || empty('표시할 활동 기록이 없습니다', '서버에서 실행한 작업과 설정 변경을 기록합니다.', '≋');
  }

  function renderMessages() {
    const messages = array(state.data?.messages).slice().reverse(); const pending = messageQueue.list(); const signature = JSON.stringify([personaName(), messages.map(message => [message.id, message.text, message.role]), pending]);
    if (signature === state.renderedMessages) return; state.renderedMessages = signature;
    const waiting = { pending: '기기에 보존됨 · 서버 접수 확인 대기', auth_required: '기기에 보존됨 · 원격 서버 접근 확인 필요', conflict: '기기에 보존됨 · 메시지 ID 충돌, 확인 필요', rejected: '기기에 보존됨 · 서버가 접수를 거절함' };
    $('chat-messages').innerHTML = messages.map(message => `<article class="chat-bubble ${message.role === 'user' ? 'user' : message.role === 'system' ? 'system' : 'assistant'}"><div class="message-meta"><span>${message.role === 'user' ? message.metadata?.authority === 'third_party' ? `외부 상대 ${escape(message.metadata?.senderUsername || '')}` : '주인' : message.role === 'system' ? '진행 상태' : escape(personaName())}${message.channel === 'instagram' || message.channel === 'instagram-dm' ? ' · Instagram DM' : message.channel === 'space-import' ? ' · 이전 대화' : ' · 웹'}</span><time>${dateTime(message.createdAt)}</time></div><p>${escape(message.text || message.content || '')}</p></article>`).join('') + pending.map(entry => `<article class="chat-bubble user"><div class="message-meta"><span>${waiting[entry.state]}</span><time>${dateTime(entry.createdAt)}</time></div><p>${escape(entry.message)}</p></article>`).join('') || empty('대화는 여기서 시작합니다', '목표와 맥락을 알려주세요. 연결이 끊기면 기기에 보존하고, 재연결 후 서버 접수를 확인합니다.', '▤');
    $('chat-messages').scrollTop = $('chat-messages').scrollHeight;
  }

  function officialLoginUrl(value) {
    try { const url = new URL(value); return url.protocol === 'https:' && ['auth.openai.com', 'openai.com'].includes(url.hostname) && !url.username && !url.password ? url.href : null; } catch { return null; }
  }

  function renderModelLogin(login) {
    const raw = login || { state: 'idle' };
    const restoredAuth=raw.state==='idle'&&state.data?.settings?.model?.provider==='codex-cli'&&state.data?.status?.model?.authenticated===true;
    const mapped=restoredAuth?'succeeded':raw.state==='completed'?(raw.authenticated===true?'succeeded':raw.needsHealthCheck?'pending':'failed'):({starting:'pending',awaiting_authorization:'pending',cancelled:'idle'})[raw.state]||raw.state;
    state.modelLogin={...raw,state:mapped};const current=state.modelLogin;
    const labels = { idle: ['연결 확인', 'neutral'], pending: ['로그인 대기', 'amber'], succeeded: ['로그인 완료', 'mint'], failed: ['로그인 실패', 'red'] }; const label = labels[current.state] || labels.idle;
    badge('model-login-badge', label[0], label[1]);
    text('model-login-message', current.message || (current.state === 'pending' ? current.userCode ? '공식 로그인 페이지에 기기 코드를 입력하세요. 완료 상태를 자동으로 확인합니다.' : '공식 로그인 주소와 기기 코드를 준비하고 있습니다. 잠시 뒤 자동으로 표시합니다.' : current.state === 'succeeded' ? 'Codex 구독 인증을 완료했습니다. 실제 모델 준비 여부는 연결 상태에서 확인합니다.' : current.state === 'failed' ? `구독 인증을 완료하지 못했습니다. 다시 연결할 수 있습니다.${current.errorCode ? ` (${current.errorCode})` : ''}` : '모델 사용을 위한 최초 1회 구독 인증입니다. 연결 버튼으로 공식 OpenAI 주소와 기기 코드를 확인하세요.'));
    const url = officialLoginUrl(current.verificationUrl); const code = typeof current.userCode === 'string' ? current.userCode : '';
    $('device-code-box').hidden = current.state !== 'pending' || !url || !code;
    if (url) { $('model-login-url').href = url; text('model-login-url', `${url} ↗`); } else { $('model-login-url').removeAttribute('href'); if (current.state === 'pending' && current.verificationUrl) text('model-login-message', '서버가 반환한 주소를 공식 OpenAI 로그인 주소로 확인할 수 없습니다. 로그인 주소를 확인하세요.'); }
    text('model-login-code', code || '—'); text('model-login-expiry', current.expiresAt ? `코드 만료 시각 ${dateTime(current.expiresAt)}` : '완료 상태를 자동으로 확인합니다.');
    text('model-login-start', current.state === 'pending' ? '구독 인증 기다리는 중…' : current.state === 'succeeded' ? 'Codex 구독 다시 연결 ↗' : 'Codex 구독 연결 (최초 1회) ↗');
    $('model-login-start').disabled = !state.connected || current.state === 'pending' || state.pendingButtons.has($('model-login-start'));
    if (current.state === 'pending' && !state.loginTimer) state.loginTimer = setTimeout(pollModelLogin, 3000);
    if (current.state !== 'pending' && state.loginTimer) { clearTimeout(state.loginTimer); state.loginTimer = null; }
  }

  async function pollModelLogin() {
    state.loginTimer = null; if (state.authRequired || state.loginPolling || state.modelLogin?.state !== 'pending') return; state.loginPolling = true;
    try { const result = await api('/api/model/login'); renderModelLogin(result.modelLogin || result.login || result); if (state.modelLogin.state === 'succeeded') { toast('Codex 구독 인증을 완료했습니다. 실제 모델 연결 상태를 확인하고 있습니다.'); await sync(); } }
    catch (error) { if (error.status === 401 || error.status === 403) requireAuthentication(); text('model-login-message', `${error.message} 로그인 진행 상태를 다시 확인합니다.`); }
    finally { state.loginPolling = false; if (!state.authRequired && state.modelLogin?.state === 'pending' && !state.loginTimer) state.loginTimer = setTimeout(pollModelLogin, 3000); }
  }

  function requireAuthentication() {
    state.connected = false; state.authRequired = true;
    try { messageQueue.pauseForAuthentication(); } catch (error) { toast(error.message, true); }
    if (state.loginTimer) { clearTimeout(state.loginTimer); state.loginTimer = null; }
    if (!$('auth-dialog').open) $('auth-dialog').showModal();
  }

  async function flushPending() {
    try {
      const result = await messageQueue.flush();
      if (result.blocked === 'authentication') requireAuthentication();
      else if (result.blocked === 'connection') { state.connected = false; renderConnection(); }
      return result;
    } catch (error) { toast(error.message, true); return { blocked: 'storage', error }; }
  }

  async function sync({ force = false } = {}) {
    if (state.syncing || (state.authRequired && !force) || navigator.onLine === false) return; state.syncing = true;
    try { const data = await api('/api/state'); if (!data.settings) throw new Error('서버에서 올바른 비서 상태를 받지 못했습니다.'); state.data = data; state.connected = true; state.authRequired = false; messageQueue.reconcile(data.messages); messageQueue.confirmConnection(); fillSettings(); renderStatus(); renderGoals(); renderMemories(); renderActivity(); renderMessages(); renderModelLogin(data.modelLogin || state.modelLogin || { state: 'idle' }); text('last-sync', `최근 동기화 ${new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })}`); }
    catch (error) { state.connected = false; text('last-sync', error.status === 401 || error.status === 403 ? '원격 서버 접근 보호 확인 필요' : '서버 동기화 실패'); if (error.status === 401 || error.status === 403) requireAuthentication(); if (!state.data) text('engine-detail', error.message); }
    finally { state.syncing = false; renderConnection(); }
    if (state.connected) await flushPending();
  }

  async function sendMessage(form, input, navigate = false) {
    const message = input.value.trim(); if (!message) return;
    const button = form.querySelector('button[type="submit"]'); if (state.pendingButtons.has(button)) return;
    state.pendingButtons.add(button); button.disabled = true;
    try {
      const existing = messageQueue.list().find(entry => entry.clientMessageId === state.composerIds.get(input.id) && entry.message === message);
      const entry = existing || messageQueue.enqueue(message, { draftKey: input.id });
      state.composerIds.set(input.id, entry.clientMessageId);
      if (navigate) goTo('chat');
      const result = await flushPending();
      if (!messageQueue.list().some(item => item.clientMessageId === entry.clientMessageId)) { toast('메시지를 클라우드 작업 큐에 전달했습니다. 진행 상태를 대화에서 확인하세요.'); await sync(); }
      else { const pending = messageQueue.list().find(item => item.clientMessageId === entry.clientMessageId); const rejected = result.blocked === 'rejected' || ['conflict', 'rejected'].includes(pending?.state); toast(result.blocked === 'authentication' ? '메시지를 기기에 보존했습니다. 원격 서버 접근 확인 후 같은 ID로 전송합니다.' : rejected ? '서버가 접수를 거절했습니다. 메시지는 기기에 보존했으니 내용을 확인하세요.' : '메시지를 기기에 보존했습니다. 연결이 복구되면 같은 ID로 접수를 확인합니다.', rejected); }
    } catch (error) { toast(error.message, true); }
    finally { state.pendingButtons.delete(button); renderConnection(); }
  }

  function openMemory(memory) {
    $('memory-id').value = memory?.id || ''; $('memory-kind').value = memory?.kind || 'fact'; $('memory-content').value = memory?.content || ''; $('memory-source').value = memory ? sourceLabel(memory) : '사용자 직접 입력'; text('memory-dialog-title', memory ? '기억 편집' : '새 기억 추가'); $('memory-dialog').showModal();
  }

  async function refreshBrowser() {
    const result = await api('/api/browser/screenshot', 'POST', { owner: state.browserOwner }); const src = result.screenshot || result.screenshotUrl || result.url;
    if (!src || typeof src !== 'string') throw new Error('브라우저 화면 주소를 받지 못했습니다.');
    const image = $('browser-screenshot'); image.src = `${src}${src.includes('?') ? '&' : '?'}t=${Date.now()}`; image.hidden = false; $('browser-empty').hidden = true;
    if (result.pageUrl || result.currentUrl) text('browser-current-url', result.pageUrl || result.currentUrl);
  }

  async function browserAction(payload, button) {
    if (state.browserBusy) return; state.browserBusy = true; if (button) { button.disabled = true; state.pendingButtons.add(button); }
    if (payload.action === 'chatgpt-login') { state.browserOwner = 'chatgpt'; $('browser-owner').value = state.browserOwner; }
    try { const result = await api('/api/browser', 'POST', { owner: state.browserOwner, ...payload }); if (result.pageUrl || result.currentUrl) text('browser-current-url', result.pageUrl || result.currentUrl); await refreshBrowser(); }
    catch (error) { toast(error.message, true); }
    finally { state.browserBusy = false; if (button) { state.pendingButtons.delete(button); button.disabled = !state.connected; } }
  }

  document.querySelectorAll('[data-view], [data-goto]').forEach(button => button.addEventListener('click', () => goTo(button.dataset.view || button.dataset.goto)));
  window.addEventListener('hashchange', () => goTo(location.hash.slice(1), false));
  $('mobile-menu').addEventListener('click', () => { const opened = document.querySelector('.sidebar').classList.toggle('open'); $('mobile-menu').setAttribute('aria-expanded', String(opened)); });
  document.addEventListener('click', event => { if (innerWidth <= 720 && !event.target.closest('.sidebar') && !event.target.closest('#mobile-menu')) { document.querySelector('.sidebar').classList.remove('open'); $('mobile-menu').setAttribute('aria-expanded', 'false'); } });
  $('refresh-state').addEventListener('click', () => sync({ force: true })); $('refresh-activity').addEventListener('click', () => sync({ force: true }));
  $('engine-control').addEventListener('click', event => perform(event.currentTarget, () => api('/api/control', 'POST', { action: event.currentTarget.dataset.action }), event.currentTarget.dataset.action === 'pause' ? '자율 실행을 일시 중지했습니다.' : '자율 실행을 재개했습니다.'));
  $('check-quota').addEventListener('click', event => perform(event.currentTarget, () => api('/api/control', 'POST', { action: 'check-quota' }), '한도 회복 확인을 요청했습니다. 확인 결과는 실제 모델 상태에 반영됩니다.'));
  $('quick-chat-form').addEventListener('submit', event => { event.preventDefault(); sendMessage(event.currentTarget, $('quick-message'), true); });
  $('chat-form').addEventListener('submit', event => { event.preventDefault(); sendMessage(event.currentTarget, $('chat-message')); });
  $('chat-message').addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chat-form').requestSubmit(); } });
  $('show-goal-form').addEventListener('click', () => { $('goal-form-panel').hidden = false; $('goal-title').focus(); }); $('cancel-goal-form').addEventListener('click', () => { $('goal-form-panel').hidden = true; });
  $('goal-form').addEventListener('submit', async event => { event.preventDefault(); const result = await perform(event.submitter, () => api('/api/goals', 'POST', { title: $('goal-title').value.trim(), description: $('goal-description').value.trim(), priority: ({high:20,normal:0,low:-10})[$('goal-priority').value] }), '목표를 서버에 저장했습니다.'); if (result) { event.target.reset(); $('goal-form-panel').hidden = true; } });
  $('goal-filters').addEventListener('click', event => { const button = event.target.closest('[data-goal-filter]'); if (!button) return; state.goalFilter = button.dataset.goalFilter; $('goal-filters').querySelectorAll('button').forEach(item => item.classList.toggle('active', item === button)); renderGoals(); });
  $('goals-list').addEventListener('click', event => { const button = event.target.closest('[data-goal-action]'); if (button && state.connected) perform(button, () => api(`/api/goals/${encodeURIComponent(button.dataset.goalId)}`, 'PATCH', { status: button.dataset.goalAction }), '목표 상태를 변경했습니다.'); });
  $('tasks-list').addEventListener('click', event => { const button = event.target.closest('[data-task-action]'); if (!button || !state.connected) return; const task = array(state.data?.tasks).find(item => String(item.id) === button.dataset.taskId); if (!task) return; let patch = { state: button.dataset.taskAction }; if (button.dataset.taskAction === 'edit') { const description = prompt('작업 지시를 수정하세요. 실행 중인 작업은 현재 행동을 멈추고 수정 내용을 반영합니다.', task.description || ''); if (description === null || !description.trim()) return; patch = { description: description.trim(), state: 'queued' }; } perform(button, () => api(`/api/tasks/${encodeURIComponent(task.id)}`, 'PATCH', patch), '작업 지시를 서버에 반영했습니다.'); });
  $('memory-search').addEventListener('input', renderMemories);
  $('memory-filters').addEventListener('click', event => { const button = event.target.closest('[data-memory-filter]'); if (!button) return; state.memoryFilter = button.dataset.memoryFilter; $('memory-filters').querySelectorAll('button').forEach(item => item.classList.toggle('active', item === button)); renderMemories(); });
  $('add-memory').addEventListener('click', () => openMemory());
  $('memory-list').addEventListener('click', event => { const button = event.target.closest('[data-memory-action]'); if (!button || !state.connected) return; const memory = array(state.data?.memories).find(item => String(item.id) === button.dataset.memoryId); if (!memory) return; const action = button.dataset.memoryAction; if (action === 'edit') return openMemory(memory); if (action === 'forget' && !confirm('이 기억의 모든 버전과 근거 대화, 함께 연결된 관련 기억과 파일을 삭제할까요? 삭제 후 복구할 수 없습니다.')) return; perform(button, () => api(`/api/memories/${encodeURIComponent(memory.id)}`, action === 'forget' ? 'DELETE' : 'PATCH', action === 'forget' ? undefined : { state: action === 'archive' ? 'archived' : 'active', archived: action === 'archive' }), action === 'forget' ? '기억을 삭제했습니다.' : action === 'archive' ? '기억을 보관했습니다.' : '기억을 복원했습니다.'); });
  ['close-memory-dialog', 'cancel-memory-dialog'].forEach(id => $(id).addEventListener('click', () => $('memory-dialog').close()));
  $('memory-form').addEventListener('submit', async event => { event.preventDefault(); const id = $('memory-id').value; const result = await perform(event.submitter, () => api(id ? `/api/memories/${encodeURIComponent(id)}` : '/api/memories', id ? 'PATCH' : 'POST', { content: $('memory-content').value.trim(), kind: $('memory-kind').value, source: $('memory-source').value.trim(), evidence: [{type:'user',text:$('memory-source').value.trim()}] }), '기억을 저장했습니다.'); if (result) $('memory-dialog').close(); });
  $('activity-filters').addEventListener('click', event => { const button = event.target.closest('[data-activity-filter]'); if (!button) return; state.activityFilter = button.dataset.activityFilter; $('activity-filters').querySelectorAll('button').forEach(item => item.classList.toggle('active', item === button)); renderActivity(); });
  $('settings-form').addEventListener('input', () => { state.dirtySettings = true; badge('settings-save-state', '저장하지 않은 변경', 'amber'); updateInstructionCount(); updatePermissionsBadge(); });
  $('allow-all').addEventListener('click', () => { document.querySelectorAll('[data-capability]').forEach(input => { input.checked = true; }); state.dirtySettings = true; badge('settings-save-state', '저장하지 않은 변경', 'amber'); updatePermissionsBadge(); });
  $('settings-form').addEventListener('submit', async event => {
    event.preventDefault(); const heartbeat = Number($('heartbeat-seconds').value); if (!Number.isInteger(heartbeat) || heartbeat < 1 || heartbeat > 86400) return toast('하트비트는 1~86400초의 정수로 입력하세요.', true);
    const permissionValues = Object.fromEntries([...document.querySelectorAll('[data-capability]')].map(input => [input.dataset.capability, input.checked]));
    const result = await perform(event.submitter, async () => { const value = await api('/api/settings', 'PUT', { persona: { name: $('persona-name').value.trim(), instructions: $('custom-instructions').value }, autonomy: { enabled: $('autonomy-enabled').checked, heartbeatSeconds: heartbeat }, permissions: { default: Object.values(permissionValues).every(Boolean) ? 'allow' : 'deny', agentCanChange: $('agent-can-change').checked, capabilities: permissionValues }, channels: { default: $('conversation-channel').value } }); state.dirtySettings = false; return value; }, '맞춤 지침과 권한을 서버에 저장했습니다.'); if (result) badge('settings-save-state', '서버에 저장됨');
  });
  $('connections-form').addEventListener('input', () => { state.dirtyConnections = true; }); $('model-provider').addEventListener('change', updateProviderFields);
  $('advanced-model-settings').addEventListener('toggle', updateProviderFields);
  $('model-login-start').addEventListener('click', event => perform(event.currentTarget, async () => { const result = await api('/api/model/login', 'POST', {}); renderModelLogin(result.modelLogin || result.login || result); return result; }));
  $('copy-model-code').addEventListener('click', async () => { const code = state.modelLogin?.userCode; if (!code) return; try { await navigator.clipboard.writeText(code); toast('공개 기기 코드를 복사했습니다.'); } catch { toast('자동 복사를 사용할 수 없습니다. 화면의 기기 코드를 직접 복사하세요.', true); } });
  $('connections-form').addEventListener('submit', async event => {
    event.preventDefault();
    await perform(event.submitter, async () => { const value = await api('/api/settings', 'PUT', { model: { provider: $('model-provider').value, model: $('model-name').value.trim(), url: $('chatgpt-url').value.trim() || 'https://chatgpt.com/?temporary-chat=true', endpoint: $('model-endpoint').value.trim(), allowFallback: $('model-allow-fallback').checked, preferences: {preferredModel:$('preferred-model').value.trim(), reasoningEffort:$('reasoning-effort').value, speed:$('model-speed').value} } }); state.dirtyConnections = false; return value; }, '모델 설정을 서버에 저장했습니다. 구독 인증 상태를 확인하세요.');
  });
  $('chatgpt-login').addEventListener('click', async event => { await browserAction({ action: 'chatgpt-login' }, event.currentTarget); $('browser-panel').scrollIntoView({ behavior: 'smooth', block: 'start' }); });
  $('browser-refresh').addEventListener('click', event => perform(event.currentTarget, refreshBrowser));
  $('browser-owner').addEventListener('change', async event => { state.browserOwner = event.currentTarget.value; await perform($('browser-refresh'), refreshBrowser); });
  $('browser-screenshot').addEventListener('click', event => { if (!state.connected || state.browserBusy) return; const rect = event.currentTarget.getBoundingClientRect(); const x = Math.round((event.clientX - rect.left) * event.currentTarget.naturalWidth / rect.width); const y = Math.round((event.clientY - rect.top) * event.currentTarget.naturalHeight / rect.height); browserAction({ action: 'click', x, y }); });
  $('browser-input-form').addEventListener('submit', event => { event.preventDefault(); const value = $('browser-input').value; $('browser-input').value = ''; if (value) browserAction({ action: 'type', text: value }, event.submitter); });
  [['browser-enter', 'Enter'], ['browser-tab', 'Tab'], ['browser-escape', 'Escape']].forEach(([id, key]) => $(id).addEventListener('click', event => browserAction({ action: 'press', key }, event.currentTarget)));
  $('browser-screenshot').addEventListener('error', () => { $('browser-screenshot').hidden = true; $('browser-empty').hidden = false; $('browser-empty').innerHTML = empty('브라우저 화면을 불러오지 못했습니다', '화면 새로고침으로 다시 시도하세요. 브라우저 준비 상태도 확인하세요.', '↗'); });
  $('auth-form').addEventListener('submit', async event => { event.preventDefault(); const token = $('auth-token').value; $('auth-token').value = ''; event.submitter.disabled = true; text('auth-error', ''); try { await api('/api/session', 'POST', { token }); $('auth-dialog').close(); state.authRequired = false; await sync(); } catch (error) { text('auth-error', error.message); } finally { event.submitter.disabled = false; } });
  for (const entry of messageQueue.list()) if (entry.draftKey) { const input = $(entry.draftKey); if (input) { input.value = entry.message; state.composerIds.set(entry.draftKey, entry.clientMessageId); } }
  if (messageQueue.loadError) toast(messageQueue.loadError.message, true);
  window.addEventListener('offline', () => { state.connected = false; renderConnection(); });
  window.addEventListener('online', () => sync());
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') sync(); });
  text('today-label', new Date().toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'long' }));
  goTo(location.hash.slice(1), false); renderConnection(); sync(); setInterval(sync, 10000); setInterval(() => { if (state.connected && state.data) renderStatus(); }, 1000);
})();
