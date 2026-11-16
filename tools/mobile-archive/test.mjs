import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { init, submit, processJob, resume } from './archive.mjs';

const tmp = mkdtempSync(path.join(tmpdir(), 'gad-mobile-archive-test-'));
try {
  const root = path.join(tmp, 'archive');
  const snapshot = {
    schemaVersion: 1,
    verification: { complete: true, messagesStable: true, answersComplete: true, method: 'fixture independently verified' },
    source: 'fixture', conversationId: 'conversation-1', title: 'GAD｜回收技术｜2026-10-30 09-00｜A7K2Q9-01',
    sessionStart: '2026-10-30T09:00:00+01:00',
    messages: [
      { id: 'u1', role: 'user', text: '重复的发言' }, { id: 'a1', role: 'assistant', text: '回答一' },
      { id: 'u2', role: 'user', text: '重复的发言' }, { id: 'a2', role: 'assistant', text: '回答二' }
    ]
  };
  init(root);
  assert.throws(() => init(root), /必须为空/);
  assert.throws(() => submit(tmp, snapshot), /不是已初始化/);
  for (const mutate of [
    (s) => { s.verification.complete = false; },
    (s) => { s.messages[2].id = 'u1'; },
    (s) => { s.messages[0].attachment = '无法处理的附件'; },
    (s) => { s.verification.answersComplete = false; },
    (s) => { s.title = s.title.replace('10-30', '02-30'); s.sessionStart = s.sessionStart.replace('10-30', '02-30'); },
    (s) => { s.sessionStart = '2026-10-30T09:00:00'; }
  ]) {
    const bad = structuredClone(snapshot); mutate(bad);
    assert.throws(() => submit(root, bad));
  }
  const job = submit(root, snapshot);
  assert.throws(() => processJob(root, job.jobId, { afterMessage: (i) => { if (i === 2) throw new Error('模拟中断'); } }), /模拟中断/);
  const resumed = resume(root);
  assert.equal(resumed[0].status, 'complete');
  assert.equal(resumed[0].created, 2);
  assert.equal(resumed[0].duplicate, 2);
  const messageDir = path.join(root, resumed[0].route, 'messages');
  assert.equal(readdirSync(messageDir).filter((x) => x.endsWith('.md')).length, 4);
  assert.equal(submit(root, snapshot).queued, 'duplicate');
  const repeated = processJob(root, job.jobId);
  assert.equal(repeated.created, 0); assert.equal(repeated.duplicate, 4);
  const changed = structuredClone(snapshot); changed.messages[1].text = '修改后的回答';
  const conflict = submit(root, changed);
  assert.throws(() => processJob(root, conflict.jobId), (e) => e.code === 'conflict');
  const moved = structuredClone(snapshot); moved.title = moved.title.replace('回收技术', '其他课程');
  assert.throws(() => processJob(root, submit(root, moved).jobId), (e) => e.code === 'conflict');
  const second = structuredClone(snapshot); second.conversationId = 'conversation-2'; second.title = second.title.replace('-01', '-02');
  const secondResult = processJob(root, submit(root, second).jobId);
  assert.equal(path.dirname(secondResult.route), path.dirname(repeated.route));
  assert.notEqual(secondResult.route, repeated.route);
  const duplicatePart = structuredClone(second); duplicatePart.conversationId = 'conversation-3';
  assert.throws(() => processJob(root, submit(root, duplicatePart).jobId), (e) => e.code === 'conflict');
  const consecutive = structuredClone(snapshot);
  consecutive.conversationId = 'consecutive';
  consecutive.title = consecutive.title.replace('-01', '-03');
  consecutive.messages = [
    { id: 'cu1', role: 'user', text: '第一段' },
    { id: 'cu2', role: 'user', text: '紧接着的第二段' },
    { id: 'ca1', role: 'assistant', text: '回答甲' },
    { id: 'ca2', role: 'assistant', text: '回答乙' },
    { id: 'cu3', role: 'user', text: '尚无后续回答' }
  ];
  const consecutiveJob = submit(root, consecutive);
  assert.throws(() => processJob(root, consecutiveJob.jobId, { afterMessage: (i) => { if (i === 2) throw new Error('模拟中断'); } }), /模拟中断/);
  const continued = processJob(root, consecutiveJob.jobId);
  assert.equal(continued.created, 3);
  assert.equal(continued.duplicate, 2);
  assert.equal(continued.answerStatus, 'no_following_assistant_in_snapshot');
  const consecutiveDir = path.join(root, continued.route, 'messages');
  for (const [i, message] of consecutive.messages.entries()) {
    const filename = createHash('sha256').update(message.id).digest('hex') + '.md';
    const entry = readFileSync(path.join(consecutiveDir, filename), 'utf8');
    assert.ok(entry.includes(`- 顺序：${i + 1}\n`));
    assert.ok(entry.includes(message.text));
    assert.equal(entry.includes('- 后续回答：快照中尚无'), i === consecutive.messages.length - 1);
  }
  const consecutiveRetry = processJob(root, consecutiveJob.jobId);
  assert.equal(consecutiveRetry.created, 0);
  assert.equal(consecutiveRetry.duplicate, 5);
  assert.equal(consecutiveRetry.answerStatus, 'no_following_assistant_in_snapshot');
  for (const [part, role] of [['04', 'assistant'], ['05', 'user']]) {
    const single = structuredClone(snapshot);
    single.conversationId = `single-${role}`;
    single.title = single.title.replace('-01', `-${part}`);
    single.messages = [{ id: `single-${role}`, role, text: '单条消息' }];
    const result = processJob(root, submit(root, single).jobId);
    assert.equal(result.created, 1);
    assert.equal(result.answerStatus, role === 'user' ? 'no_following_assistant_in_snapshot' : undefined);
  }
  assert.ok(resume(root).some((item) => item.status === 'failed' && item.code === 'conflict'));
  const cliRoot = path.join(tmp, 'cli-root');
  const fixture = path.join(tmp, 'fixture.json'); writeFileSync(fixture, JSON.stringify(snapshot));
  const cli = fileURLToPath(new URL('./cli.mjs', import.meta.url));
  const run = (...args) => spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8' });
  assert.equal(run('init', '--root', cliRoot).status, 0);
  const firstCli = run('import', '--root', cliRoot, '--snapshot', fixture);
  assert.equal(firstCli.status, 0, firstCli.stderr);
  assert.equal(JSON.parse(firstCli.stdout).created, 4);
  const secondCli = run('import', '--root', cliRoot, '--snapshot', fixture);
  assert.equal(secondCli.status, 0, secondCli.stderr);
  assert.equal(JSON.parse(secondCli.stdout).duplicate, 4);
  assert.ok(readFileSync(path.join(cliRoot, 'receipts', `${JSON.parse(firstCli.stdout).jobId}.json`), 'utf8'));
  console.log('mobile archive tests passed');
} finally {
  const base = path.resolve(tmpdir());
  const target = path.resolve(tmp);
  if (!target.startsWith(base + path.sep) || !path.basename(target).startsWith('gad-mobile-archive-test-')) {
    throw new Error('拒绝清理不在预期临时目录中的测试路径');
  }
  rmSync(target, { recursive: true, force: true });
}
