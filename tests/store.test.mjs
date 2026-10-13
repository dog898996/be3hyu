import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';

function fixture(t, options = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'assistant-store-'));
  const stores = [];
  const open = (extra = {}) => {
    const store = new Store(dir, { ...options, ...extra });
    stores.push(store);
    return store;
  };
  t.after(() => {
    for (const store of stores) { try { store.close(); } catch {} }
    rmSync(dir, { recursive: true, force: true });
  });
  return { dir, open, store: open() };
}

test('raw conversations, preferences, memories and learning survive reopen', (t) => {
  const { store, open } = fixture(t, { defaults: { personality: { tone: 'direct', language: 'ko' }, permissions: { shell: true } } });
  assert.equal(store.counts().memories, 0, 'no fabricated seeded personal facts');
  const message = store.addMessage({ channel: 'telegram', externalId: '123', role: 'user', text: '매일 아침 일정을 확인해줘.', threadId: 'dm-1' });
  const memory = store.upsertMemory({ key: 'routine.morning', content: '매일 아침 일정 확인을 요청했다.', kind: 'fact', confidence: 1, sourceMessageIds: [message.id] });
  store.updateSettings({ personality: { tone: 'casual' } }, 'user');
  store.addFeedback({ text: '핵심부터 짧게 말해줘.', sourceMessageId: message.id, memoryId: memory.id });
  store.addExperience({ summary: '아침 일정 확인', outcome: '완료', lesson: '일정 충돌을 먼저 확인한다.' });
  store.close();
  const reopened = open({ defaults: { personality: { tone: 'ignored' } } });
  assert.deepEqual(reopened.getSettings(), { personality: { tone: 'casual', language: 'ko' }, permissions: { shell: true } });
  assert.equal(reopened.listSettingsHistory().length, 2);
  assert.equal(reopened.listMessages()[0].text, message.text);
  assert.deepEqual(reopened.getMemory(memory.id).sourceMessageIds, [message.id]);
  assert.equal(reopened.listFeedback().length, 1);
  assert.equal(reopened.listExperiences()[0].lesson, '일정 충돌을 먼저 확인한다.');
});

test('contradictory updates supersede beliefs with evidence and retain both versions', (t) => {
  const { store } = fixture(t);
  const firstSource = store.addMessage({ role: 'user', text: '커피를 좋아해.' });
  const first = store.upsertMemory({ key: 'preference.coffee', content: '사용자는 커피를 좋아한다.', kind: 'fact', confidence: 1, sourceMessageIds: [firstSource.id] });
  const secondSource = store.addMessage({ role: 'user', text: '이제 커피 끊었어.' });
  const second = store.upsertMemory({ key: 'preference.coffee', content: '사용자는 커피를 끊었다.', kind: 'fact', confidence: 1, sourceMessageIds: [secondSource.id], observedAt: '2026-10-12T00:00:00.000Z' });
  assert.equal(second.version, 2);
  assert.equal(second.supersedesId, first.id);
  assert.equal(store.getMemory(first.id).state, 'superseded');
  assert.equal(store.getMemory(first.id).supersededById, second.id);
  assert.deepEqual(store.listMemories().map((record) => record.id), [second.id]);
  assert.equal(store.listMemories({ includeArchived: true }).length, 2);
  assert.equal(store.listMemories({ query: '커피를 끊' })[0].id, second.id, 'Hangul substring fallback retrieves inflected words');
  assert.equal(store.listMemories({ query: '" OR *' }).length, 0, 'FTS syntax from user input cannot change the query');
  store.archiveMemory(second.id);
  assert.equal(store.listMemories().length, 0);
  assert.equal(store.listMemories({ includeArchived: true }).length, 2);
});

test('forgetting deletes full belief history, linked sources, feedback, search index and artifact files', (t) => {
  const { store, dir, open } = fixture(t);
  const firstSource = store.addMessage({ role: 'user', text: '내 주소는 서울 어딘가.' });
  const otherSource = store.addMessage({ role: 'user', text: '내 주소는 부산 어딘가.' });
  const unrelated = store.addMessage({ role: 'user', text: '오늘 할일 확인해줘.' });
  const first = store.upsertMemory({ key: 'private.address', content: '서울 어딘가', kind: 'fact', sourceMessageIds: [firstSource.id] });
  const current = store.upsertMemory({ key: 'private.address', content: '부산 어딘가', kind: 'fact', sourceMessageIds: [otherSource.id] });
  store.addFeedback({ text: '주소 수정', sourceMessageId: otherSource.id, memoryId: current.id });
  const artifactPath = join(dir, 'address.txt');
  writeFileSync(artifactPath, '부산 어딘가');
  store.addArtifact({ path: artifactPath, memoryIds: [current.id], sourceMessageIds: [otherSource.id] });
  store.event('personalized.output', { sourceMessageId: otherSource.id, memoryId: first.id });
  const deleted = store.forgetMemory(current.id, { deleteSources: true });
  assert.equal(deleted.memories, 2);
  assert.equal(deleted.messages, 2);
  assert.equal(deleted.artifacts, 1);
  assert.equal(existsSync(artifactPath), false);
  assert.deepEqual(store.listMessages().map((message) => message.id), [unrelated.id]);
  assert.equal(store.listMemories({ includeArchived: true }).length, 0);
  assert.equal(store.searchMessages('주소').length, 0);
  assert.equal(store.listFeedback().length, 0);
  assert.equal(store.listArtifacts().length, 0);
  assert.equal(store.listEvents().some((event) => JSON.stringify(event.data).includes(otherSource.id)), false);
  store.close();
  assert.equal(open().listMemories({ includeArchived: true }).length, 0);
});

test('message ingestion and outbound queuing are durable and idempotent', (t) => {
  const { store, open } = fixture(t);
  const first = store.addMessage({ channel: 'telegram', externalId: 'update-1', role: 'user', text: '원문' });
  assert.equal(store.addMessage({ channel: 'telegram', externalId: 'update-1', role: 'user', text: '재전송으로 변경됨' }).id, first.id);
  assert.equal(store.listMessages()[0].text, '원문', 'raw message is append-only');
  assert.equal(store.getMessageByExternalId('telegram', 'update-1').id, first.id);
  assert.notEqual(store.addMessage({ channel: 'web', externalId: 'update-1', role: 'user', text: '다른 채널' }).id, first.id);
  const outbound = store.queueOutbound({ channel: 'telegram', threadId: 'dm', text: '완료했어.', idempotencyKey: 'task:1:complete' });
  assert.equal(store.queueOutbound({ channel: 'telegram', text: '다시 전송', idempotencyKey: 'task:1:complete' }).id, outbound.id);
  assert.equal(store.listPendingOutbound().length, 1);
  store.updateOutbound(outbound.id, { status: 'sending' });
  store.updateOutbound(outbound.id, { state: 'sent', externalId: 'provider-1' });
  assert.throws(() => store.updateOutbound(outbound.id, { state: 'pending' }), /cannot be resent/);
  store.close();
  const reopened = open();
  assert.equal(reopened.listPendingOutbound().length, 0);
  assert.equal(reopened.queueOutbound({ channel: 'telegram', text: 'duplicate after restart', idempotencyKey: 'task:1:complete' }).state, 'sent');
});

test('only one worker can claim a task, and leases fence stale completion after restart', (t) => {
  const { store, open } = fixture(t);
  const secondWorker = open();
  const task = store.addTask({ title: '아침 일정 확인', dedupKey: 'morning:2026-10-12' });
  assert.equal(store.addTask({ title: '중복 요청', dedupKey: 'morning:2026-10-12' }).id, task.id);
  const firstClaim = store.claimTask('worker-1');
  assert.equal(firstClaim.id, task.id);
  assert.equal(secondWorker.claimTask('worker-2'), null);
  assert.equal(secondWorker.recoverTasks().length, 0, 'live leases cannot be stolen by startup recovery');
  assert.throws(() => secondWorker.completeTask(task.id, '잘못된 완료', { leaseToken: 'wrong' }), /lease token/);
  // Simulate a crashed worker whose lease elapsed while the process was down.
  store.db.prepare('UPDATE tasks SET lease_until = ? WHERE id = ?').run('2000-01-01T00:00:00.000Z', task.id);
  assert.throws(() => store.completeTask(task.id, '늦은 완료', { leaseToken: firstClaim.leaseToken }), /expired/);
  assert.equal(secondWorker.recoverTasks().length, 1);
  const nextClaim = secondWorker.claimTask('worker-2');
  assert.equal(nextClaim.attempts, 2);
  assert.notEqual(nextClaim.leaseToken, firstClaim.leaseToken);
  assert.throws(() => store.completeTask(task.id, 'stale without explicit token'), /lease token/);
  assert.throws(() => store.completeTask(task.id, 'stale', { leaseToken: firstClaim.leaseToken }), /lease token/);
  secondWorker.completeTask(task.id, { checked: true }, { leaseToken: nextClaim.leaseToken, workerId: 'worker-2' });
  assert.equal(store.getTask(task.id).state, 'succeeded');
  assert.deepEqual(store.getTask(task.id).result, { checked: true });
});

test('task steering invalidates leases and paused continuous responsibilities block claims', (t) => {
  const { store } = fixture(t);
  const goal = store.addGoal({ title: '지속적으로 일정 관리', continuous: true });
  const task = store.addTask({ title: '회의 확인', goalId: goal.id });
  const claim = store.claimTask('worker');
  store.steerTask(task.id, { state: 'paused', metadata: { userReason: '보류' } });
  assert.throws(() => store.completeTask(task.id, '완료', { leaseToken: claim.leaseToken }), /not running/);
  store.steerTask(task.id, { state: 'queued', description: '우선순위 변경' });
  store.updateGoal(goal.id, { state: 'paused' });
  assert.equal(store.claimTask('worker'), null);
  store.updateGoal(goal.id, { state: 'active' });
  const resumed = store.claimTask('worker');
  assert.equal(resumed.description, '우선순위 변경');
  store.renewTaskLease(task.id, { leaseToken: resumed.leaseToken, leaseMs: 300_000 });
  assert.equal(store.getTask(task.id).state, 'running');
  store.failTask(task.id, new Error('외부 서비스 오류'), { leaseToken: resumed.leaseToken });
  assert.equal(store.getTask(task.id).error, '외부 서비스 오류');
});

test('failed memory evidence validation rolls back supersession and settings reject prototype keys', (t) => {
  const { store } = fixture(t);
  const memory = store.upsertMemory({ key: 'belief', content: '추론', kind: 'inference', confidence: 0.3 });
  assert.throws(() => store.upsertMemory({ key: 'belief', content: '근거가 없음', kind: 'fact', sourceMessageIds: ['nonexistent-message'] }), /FOREIGN KEY/);
  assert.equal(store.getMemory(memory.id).state, 'active');
  assert.equal(store.listMemories({ includeArchived: true }).length, 1);
  assert.throws(() => store.updateSettings(JSON.parse('{"__proto__":{"isAdmin":true}}')), /Invalid settings key/);
  assert.equal({}.isAdmin, undefined);
});

test('action receipts and checkpoints survive restart without replaying successful or uncertain effects', (t) => {
  const { store, open } = fixture(t);
  const task = store.addTask({ title: '파일 수정 후 DM 보내기', metadata: { delegated: true } });
  const saved = store.beginAction({ id: `${task.id}:0:0`, taskId: task.id, name: 'files.write', args: { path: 'report.txt', content: 'done' } });
  assert.equal(saved.created, true);
  store.finishAction(saved.id, { result: { path: 'report.txt' } });
  store.checkpointTask(task.id, { step: 1, history: [{ role: 'tool', content: 'saved' }] });
  const send = store.beginAction({ id: `${task.id}:1:0`, taskId: task.id, name: 'dm.send', args: { text: '완료했어.' } });
  store.close();
  const reopened = open();
  const receipt = reopened.beginAction({ id: saved.id, taskId: task.id, name: 'files.write', args: { path: 'report.txt', content: 'done' } });
  assert.equal(receipt.created, false);
  assert.equal(receipt.state, 'succeeded');
  assert.deepEqual(receipt.result, { path: 'report.txt' });
  assert.equal(reopened.getTask(task.id).metadata.delegated, true);
  assert.equal(reopened.getTask(task.id).metadata.checkpoint.step, 1);
  assert.deepEqual(reopened.recoverActions().map((action) => action.id), [send.id]);
  assert.equal(reopened.getAction(send.id).state, 'uncertain');
  assert.equal(reopened.beginAction({ id: send.id, taskId: task.id, name: 'dm.send', args: { text: '완료했어.' } }).created, false);
  assert.throws(() => reopened.beginAction({ id: saved.id, taskId: task.id, name: 'files.write', args: { path: 'different.txt' } }), /different arguments/);
  reopened.finishAction(send.id, { state: 'succeeded', result: { externalId: 'reconciled-with-provider' } });
  assert.equal(reopened.listActions({ taskId: task.id }).length, 2);
  assert.throws(() => reopened.finishAction(saved.id, { state: 'failed' }), /cannot change state/);
});

test('quota pause and known retry timing persist independently of user settings', (t) => {
  const { store, open } = fixture(t, { defaults: { autonomous: true } });
  const quota = { exhausted: true, detectedAt: '2026-10-12T00:00:00.000Z', retryAt: '2026-10-12T03:00:00.000Z', resetKnown: true, reason: 'model quota exceeded' };
  assert.equal(store.getRuntimeState('quota'), null);
  store.setRuntimeState('quota', quota);
  store.close();
  const reopened = open();
  assert.deepEqual(reopened.getRuntimeState('quota'), quota);
  assert.deepEqual(reopened.getSettings(), { autonomous: true });
  reopened.setRuntimeState('quota', { ...quota, exhausted: false });
  assert.equal(reopened.getRuntimeState('quota').exhausted, false);
});

test('crashed delivery becomes uncertain and cannot be automatically resent', (t) => {
  const { store, open } = fixture(t);
  const outbound = store.queueOutbound({ channel: 'instagram', threadId: 'dm', text: '완료했어.', idempotencyKey: 'ambiguous-send' });
  store.updateOutbound(outbound.id, { state: 'sending' });
  store.close();
  const reopened = open();
  assert.equal(reopened.recoverOutbound(), 1);
  assert.equal(reopened.listOutbound()[0].state, 'uncertain');
  assert.equal(reopened.listPendingOutbound(100, { includeFailed: true }).length, 0);
  assert.equal(reopened.queueOutbound({ channel: 'instagram', text: 'duplicate', idempotencyKey: 'ambiguous-send' }).state, 'uncertain');
  reopened.updateOutbound(outbound.id, { state: 'confirmed', externalId: 'provider-confirmed-existing-send' });
  assert.equal(reopened.listOutbound()[0].state, 'sent');
});

test('deleting shared raw evidence also removes dependent memories and their registered artifacts', (t) => {
  const { store } = fixture(t);
  const source = store.addMessage({ role: 'user', text: '개인 주소와 개인 사무실이 같아.' });
  const first = store.upsertMemory({ key: 'private.home', content: '개인 주소', kind: 'fact', sourceMessageIds: [source.id] });
  const dependent = store.upsertMemory({ key: 'private.office', content: '개인 사무실', kind: 'inference', sourceMessageIds: [source.id] });
  store.addArtifact({ kind: 'draft', content: '개인 사무실 주소', memoryIds: [dependent.id] });
  store.event('dependent.memory.used', { memoryId: dependent.id });
  const deleted = store.forgetMemory(first.id, { deleteSources: true });
  assert.equal(deleted.memories, 2);
  assert.equal(deleted.artifacts, 1);
  assert.equal(store.getMemory(dependent.id), null);
  assert.equal(store.listArtifacts().length, 0);
  assert.equal(store.listEvents().some((event) => event.data.memoryId === dependent.id), false);
});

test('provider receipt and external delivery ID remain durable in the outbox', (t) => {
  const { store, open } = fixture(t);
  const outbound = store.queueOutbound({ channel: 'instagram', threadId: 'dm', text: '완료했어.', idempotencyKey: 'verified-send' });
  const receipt = { verified: true, externalId: 'ig-item-123' };
  store.updateOutbound(outbound.id, { state: 'sent', receipt });
  store.close();
  const saved = open().listOutbound()[0];
  assert.equal(saved.externalId, 'ig-item-123');
  assert.deepEqual(saved.receipt, receipt);
});

test('forgetting a raw source removes derived tasks, action arguments, replies and learning records', (t) => {
  const { store, open } = fixture(t);
  const source = store.addMessage({ role: 'user', text: '개인 주소를 이용해 편지를 써줘.' });
  const memory = store.upsertMemory({ key: 'private.address', content: '개인 주소', kind: 'fact', sourceMessageIds: [source.id] });
  const task = store.addTask({ title: '개인 주소 편지', description: source.text, metadata: { sourceMessageIds: [source.id] } });
  const action = store.beginAction({ id: `${task.id}:0:0`, taskId: task.id, name: 'files.write', args: { content: source.text } });
  store.finishAction(action.id, { result: { content: source.text } });
  store.addExperience({ taskId: task.id, summary: '개인 주소 편지 작성', lesson: '개인 주소 활용' });
  store.addFeedback({ taskId: task.id, text: '개인 주소 수정 필요' });
  const reply = store.addMessage({ role: 'assistant', text: '주소를 편지에 추가했어.', metadata: { taskId: task.id } });
  const replyMemory = store.upsertMemory({ key: 'private.letter', content: '주소가 담긴 편지', kind: 'fact', sourceMessageIds: [reply.id] });
  store.queueOutbound({ channel: 'instagram', text: reply.text, idempotencyKey: `reply:${task.id}:0` });
  const unrelated = store.addTask({ title: '다른 할일' });
  const search = store.beginAction({ id: `${unrelated.id}:0:0`, taskId: unrelated.id, name: 'memory', args: { action: 'list' } });
  store.finishAction(search.id, { result: { memoryId: memory.id, content: memory.content } });
  store.checkpointTask(unrelated.id, { step: 1, history: [{ role: 'tool', content: JSON.stringify({ memoryId: memory.id, content: memory.content }) }] });
  const deleted = store.forgetMemory(memory.id, { deleteSources: true });
  assert.equal(deleted.tasks, 1);
  assert.equal(deleted.messages, 2);
  assert.equal(deleted.outbound, 1);
  assert.equal(store.getTask(task.id), null);
  assert.equal(store.getAction(action.id), null);
  assert.equal(store.getMemory(replyMemory.id), null);
  assert.equal(store.listExperiences().length, 0);
  assert.equal(store.listFeedback().length, 0);
  assert.deepEqual(store.getAction(search.id).result, { redacted: true });
  assert.deepEqual(store.getTask(unrelated.id).metadata.checkpoint.history, []);
  store.close();
  const reopened = open();
  assert.equal(reopened.getTask(task.id), null);
  assert.equal(reopened.getTask(unrelated.id).title, '다른 할일');
  assert.equal(reopened.listMessages().length, 0);
});

test('new DM read receipts deduplicate, survive reopen, and never reset a confirmed read', (t) => {
  const { store, open } = fixture(t);
  const message = store.addMessage({ channel: 'instagram', externalId: 'incoming-1', role: 'user', text: '새 DM', threadId: 'thread-1' });
  const read = store.queueRead({ messageId: message.id, threadId: 'thread-1', externalId: 'incoming-1', accountUsername: '@Assistant_Account' });
  assert.equal(read.state, 'pending');
  assert.equal(read.accountUsername, 'assistant_account');
  assert.equal('text' in read, false, 'read queue does not duplicate message text');
  assert.equal(store.queueRead({ messageId: message.id, threadId: 'thread-1', externalId: 'incoming-1', accountUsername: 'assistant_account' }).id, read.id);
  assert.equal(store.listReads().length, 1);
  store.updateRead(read.id, { state: 'reading' });
  assert.equal(store.getRead(read.id).attempts, 1);
  store.updateRead(read.id, { state: 'reading' });
  assert.equal(store.getRead(read.id).attempts, 1, 'one attempt per entry into reading');
  const receipt = { confirmed: true, externalId: 'incoming-1', seenAt: '2026-10-12T02:00:00.000Z' };
  store.updateRead(read.id, { status: 'confirmed', receipt });
  store.close();
  const reopened = open();
  assert.deepEqual(reopened.getRead(read.id).receipt, receipt);
  assert.ok(reopened.getRead(read.id).confirmedAt);
  assert.equal(reopened.listPendingReads({ accountUsername: 'assistant_account' }).length, 0);
  assert.equal(reopened.recoverReads(), 0);
  assert.equal(reopened.queueRead({ messageId: message.id, threadId: 'thread-1', externalId: 'incoming-1', accountUsername: 'assistant_account' }).state, 'confirmed');
  assert.throws(() => reopened.updateRead(read.id, { state: 'pending' }), /cannot be requeued/);
});

test('read retry dates and account matching exclude dormant failures and future retries', (t) => {
  const { store } = fixture(t);
  const add = (externalId, accountUsername = 'assistant') => {
    const message = store.addMessage({ channel: 'instagram', externalId, role: 'user', text: '[사진]', threadId: 'thread' });
    return store.queueRead({ messageId: message.id, threadId: 'thread', externalId, accountUsername });
  };
  const pending = add('pending');
  const failed = add('failed');
  store.updateRead(failed.id, { state: 'failed', error: 'cannot navigate', nextAttemptAt: null });
  const unverified = add('unverified');
  store.updateRead(unverified.id, { state: 'unverified', receipt: { confirmed: false }, nextAttemptAt: null });
  const future = add('future');
  store.updateRead(future.id, { state: 'failed', nextAttemptAt: '2026-10-12T03:00:00.000Z' });
  const due = add('due');
  store.updateRead(due.id, { state: 'unverified', nextAttemptAt: new Date('2026-10-12T01:00:00.000Z').getTime() });
  add('other-account', 'someone_else');
  const clock = new Date('2026-10-12T02:00:00.000Z').getTime();
  assert.deepEqual(store.listPendingReads({ now: clock, accountUsername: '@ASSISTANT' }).map((read) => read.id), [pending.id, due.id]);
  assert.equal(store.getRead(failed.id).error, 'cannot navigate');
  assert.deepEqual(store.getRead(unverified.id).receipt, { confirmed: false });
  assert.equal(store.listPendingReads({ now: '2026-10-12T04:00:00.000Z', accountUsername: 'assistant' }).length, 3);
  assert.throws(() => store.updateRead(future.id, { nextAttemptAt: 'invalid date' }), /ISO date/);
});

test('interrupted reads recover for safe retry and source forgetting cascades their durable queue', (t) => {
  const { store, open } = fixture(t);
  const message = store.addMessage({ channel: 'instagram', externalId: 'read-source', role: 'user', text: '개인 정보', threadId: 'thread' });
  const memory = store.upsertMemory({ key: 'private.read-source', content: '개인 정보', kind: 'fact', sourceMessageIds: [message.id] });
  const read = store.queueRead({ messageId: message.id, threadId: 'thread', externalId: 'read-source', accountUsername: 'assistant' });
  store.updateRead(read.id, { state: 'reading', receipt: { opened: true }, error: 'browser process lost' });
  store.close();
  const reopened = open();
  assert.equal(reopened.recoverReads(), 1);
  const recovered = reopened.listPendingReads()[0];
  assert.equal(recovered.id, read.id);
  assert.equal(recovered.state, 'pending');
  assert.equal(recovered.attempts, 1);
  assert.equal(recovered.error, 'browser process lost');
  assert.deepEqual(recovered.receipt, { opened: true });
  reopened.updateRead(read.id, { state: 'reading' });
  assert.equal(reopened.getRead(read.id).attempts, 2);
  reopened.forgetMemory(memory.id, { deleteSources: true });
  assert.equal(reopened.getRead(read.id), null);
  assert.equal(reopened.listReads().length, 0);
});

test('owner thread reads take priority without confirming older messages in the same thread', (t) => {
  const { store } = fixture(t);
  const visitorThread = 'https://www.instagram.com/direct/t/visitor/';
  const ownerThread = 'https://www.instagram.com/direct/t/owner/';
  const add = (externalId, threadId) => {
    const message = store.addMessage({ channel: 'instagram', externalId, role: 'user', text: '[사진]', threadId });
    return store.queueRead({ messageId: message.id, threadId, externalId, accountUsername: 'assistant' });
  };
  const visitor = add('visitor', visitorThread);
  const olderOwner = add('owner-old', ownerThread);
  const newerOwner = add('owner-new', ownerThread);
  assert.equal(store.listPendingReads({ limit: 1 })[0].id, visitor.id);
  assert.equal(store.listPendingReads({ accountUsername: 'assistant', priorityThread: ownerThread, limit: 1 })[0].id, olderOwner.id);
  store.updateRead(newerOwner.id, { state: 'confirmed', receipt: { externalId: 'owner-new', confirmed: true } });
  assert.equal(store.getRead(olderOwner.id).state, 'pending', 'confirmation remains attached to the individual external message');
  assert.equal(store.listPendingReads({ priorityThread: ownerThread, limit: 1 })[0].id, olderOwner.id);
});

test('settings revisions apply once atomically and preserve later manual edits after restart', (t) => {
  const { store, open } = fixture(t, { defaults: { model: { effort: 'high' }, autonomy: { enabled: true } } });
  const onceKey = 'requirements-revision:medium-and-seen';
  const patch = { model: { effort: 'medium' }, channels: { instagram: { markSeen: true } } };
  const applied = store.updateSettings(patch, 'requirements-import', { onceKey });
  assert.equal(applied.model.effort, 'medium');
  assert.equal(store.getRuntimeState(onceKey), true);
  assert.equal(store.listSettingsHistory().length, 2);
  store.updateSettings({ model: { effort: 'low' }, channels: { instagram: { markSeen: false } } }, 'user');
  const eventCount = store.listEvents().length;
  store.close();
  const reopened = open();
  const preserved = reopened.updateSettings(patch, 'requirements-import', { onceKey });
  assert.equal(preserved.model.effort, 'low');
  assert.equal(preserved.channels.instagram.markSeen, false);
  assert.equal(reopened.listSettingsHistory().length, 3, 'repeat does not create a settings version');
  assert.equal(reopened.listEvents().length, eventCount, 'repeat does not create a settings event');
  // Failure writing the marker rolls back the settings version and event too.
  const realSetRuntimeState = reopened.setRuntimeState;
  reopened.setRuntimeState = () => { throw new Error('simulated marker persistence failure'); };
  assert.throws(() => reopened.updateSettings({ model: { effort: 'high' } }, 'requirements-import', { onceKey: 'requirements-revision:next' }), /marker persistence failure/);
  reopened.setRuntimeState = realSetRuntimeState;
  assert.equal(reopened.getSettings().model.effort, 'low');
  assert.equal(reopened.getRuntimeState('requirements-revision:next'), null);
  assert.equal(reopened.listSettingsHistory().length, 3);
  assert.equal(reopened.listEvents().length, eventCount);
});
