import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { exportConversation } from './local-export.mjs';

const temp = mkdtempSync(path.join(tmpdir(), 'gad-local-export-'));
const outputDir = path.join(temp, 'out');
const node = (id, parent, role, parts, extra = {}) => ({
  id: `node-${id}`, parent,
  message: { id, author: { role }, content: { parts }, create_time: 1793700000, ...extra }
});
const payload = (id = 'conv-1234567890abcdef') => ({
  id, title: '课堂记录 2026-11-02 09:15', create_time: 1793700000, current_node: 'n3',
  mapping: {
    n0: node('m0', null, 'user', ['第一条提问']),
    n1: node('m1', 'n0', 'assistant', ['第一条回答']),
    n2: node('m2', 'n1', 'user', ['末尾追问']),
    n3: node('m3', 'n2', 'user', [{ content_type: 'text', parts: [{ text: '连续用户消息里的转写' }] }])
  }
});

try {
  const input = payload();
  const first = exportConversation({ payload: input, course: '德语课', outputDir });
  assert.equal(first.completeness.complete, true);
  assert.equal(first.counts.messages, 4);
  assert.equal(first.counts.user, 3);
  assert.equal(first.counts.assistant, 1);
  assert.equal(first.unansweredTail, true);
  assert.match(path.basename(first.paths.markdown), /^2026-11-02 周一 \d{2}-\d{2} \[conv-1234567\]\.md$/);
  const firstMarkdown = readFileSync(first.paths.markdown, 'utf8');
  assert.ok(firstMarkdown.includes('第一条提问'));
  assert.ok(firstMarkdown.includes('第一条回答'));
  assert.ok(firstMarkdown.includes('连续用户消息里的转写'));
  assert.ok(firstMarkdown.includes('末尾未回复：是'));
  assert.deepEqual(JSON.parse(readFileSync(first.paths.snapshot, 'utf8')), input);

  const again = exportConversation({ payload: input, course: '德语课', outputDir });
  assert.deepEqual(again.paths, first.paths);
  assert.equal(readdirSync(outputDir).filter((name) => name.endsWith('.md')).length, 1);

  const changed = structuredClone(input);
  changed.mapping.n3.message.content.parts[0].parts[0].text = '增量后的转写内容';
  const incremental = exportConversation({ payload: changed, course: '德语课', outputDir });
  assert.equal(incremental.version, 2);
  assert.notEqual(incremental.paths.snapshot, first.paths.snapshot);
  assert.ok(readFileSync(first.paths.markdown, 'utf8').includes('连续用户消息里的转写'));
  assert.ok(readFileSync(incremental.paths.markdown, 'utf8').includes('增量后的转写内容'));

  const noName = payload('no-name-123456');
  noName.title = '没有时间的会话';
  noName.create_time = Date.parse('2026-11-04T00:30:00Z') / 1000;
  const fallback = exportConversation({ payload: noName, outputDir });
  assert.match(path.basename(fallback.paths.markdown), /^2026-11-04 周三 01-30 \[no-name-1234\]\.md$/);
  assert.ok(readFileSync(fallback.paths.markdown, 'utf8').includes('来源：create_time'));
  assert.ok(readFileSync(fallback.paths.markdown, 'utf8').includes('课程：未提供'));

  const broken = payload('broken-123456');
  broken.mapping.n1.parent = 'missing-parent';
  const brokenResult = exportConversation({ payload: broken, outputDir });
  assert.equal(brokenResult.completeness.complete, false);
  assert.ok(brokenResult.completeness.problems.some((issue) => issue.includes('parent_link_broken')));
  assert.ok(readFileSync(brokenResult.paths.markdown, 'utf8').includes('父链完整：否'));

  const loop = payload('loop-1234567890');
  loop.mapping.n0.parent = 'n2';
  const loopResult = exportConversation({ payload: loop, outputDir });
  assert.equal(loopResult.completeness.complete, false);
  assert.ok(loopResult.completeness.problems.some((issue) => issue.includes('parent_cycle')));

  const audio = payload('audio-1234567890');
  audio.mapping.n3.message.content.parts = [
    { content_type: 'audio_transcription', parts: [{ text: 'nested transcription' }] },
    { content_type: 'audio', audio: 'opaque-audio-pointer' }
  ];
  const audioResult = exportConversation({ payload: audio, outputDir });
  assert.equal(audioResult.counts.audioWithoutText, 1);
  assert.ok(readFileSync(audioResult.paths.markdown, 'utf8').includes('nested transcription'));

  const pointer = payload('pointer-1234567890');
  pointer.mapping.n3.message.content.parts = [{ content_type: 'audio', asset_pointer: 'audio_asset_1' }];
  const pointerResult = exportConversation({ payload: pointer, outputDir });
  assert.equal(pointerResult.counts.audioWithoutText, 1);
  assert.equal(pointerResult.completeness.complete, true);
  assert.ok(readFileSync(pointerResult.paths.markdown, 'utf8').includes('音频转写缺失'));
  assert.ok(readFileSync(pointerResult.paths.markdown, 'utf8').includes('audio_asset_1'));

  const noAssistant = payload('no-assistant-123');
  noAssistant.mapping = {
    u0: node('u0', null, 'user', ['仅有用户消息']),
    u1: node('u1', 'u0', 'user', ['没有助手回复'])
  };
  noAssistant.current_node = 'u1';
  const noAssistantResult = exportConversation({ payload: noAssistant, outputDir });
  assert.equal(noAssistantResult.completeness.complete, true);
  assert.equal(noAssistantResult.completeness.assistantMessagesMissing, true);
  assert.equal(noAssistantResult.unansweredTail, true);

  const abnormal = payload('status-1234567890');
  abnormal.mapping.n1.message.status = 'in_progress';
  const abnormalResult = exportConversation({ payload: abnormal, outputDir });
  assert.equal(abnormalResult.completeness.complete, true);
  assert.equal(abnormalResult.counts.messageStatusAnomalies, 1);
  assert.ok(readFileSync(abnormalResult.paths.markdown, 'utf8').includes('消息状态异常：assistant/m1: in_progress'));

  const manual = payload('manual-1234567890');
  manual.title = '2026-11-04 08:00 人工文件冲突';
  const manualName = '2026-11-04 周三 08-00 [manual-12345].md';
  writeFileSync(path.join(outputDir, manualName), '人工内容，不能覆盖');
  const manualResult = exportConversation({ payload: manual, outputDir });
  assert.notEqual(manualResult.paths.markdown, path.join(outputDir, manualName));
  assert.equal(readFileSync(path.join(outputDir, manualName), 'utf8'), '人工内容，不能覆盖');
  assert.ok(readFileSync(manualResult.paths.markdown, 'utf8').includes('第一条提问'));

  assert.throws(() => exportConversation({ payload: payload('bad-course'), course: '../课程', outputDir }), /有效的课程名/);
  assert.throws(() => exportConversation({ payload: payload('bad-timezone'), outputDir, timeZone: 'Invalid/Zone' }), /无效时区/);
  const undated = payload('undated-123456');
  undated.title = '没有时间';
  delete undated.create_time;
  assert.throws(() => exportConversation({ payload: undated, outputDir }), /无法确定会话时间/);

  const snapshotFiles = readdirSync(path.join(outputDir, '.mobile-archive', 'snapshots'), { recursive: true });
  assert.ok(snapshotFiles.some((name) => String(name).endsWith('.json')));
  console.log('mobile local export tests passed');
} finally {
  const expectedRoot = path.resolve(tmpdir());
  const actual = path.resolve(temp);
  if (!actual.startsWith(expectedRoot + path.sep) || !path.basename(actual).startsWith('gad-local-export-')) throw new Error('拒绝清理测试目录以外的路径');
  rmSync(actual, { recursive: true, force: true });
}
