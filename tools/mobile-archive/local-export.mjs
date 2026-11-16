import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六'];
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const asObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

function fail(message, code = 'invalid_input') {
  const error = new Error(message);
  error.code = code;
  throw error;
}
function zonedParts(date, timeZone) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
  }).formatToParts(date);
  return Object.fromEntries(parts.filter((part) => part.type !== 'literal').map(({ type, value }) => [type, value]));
}

function timestampFromCreateTime(value) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    const milliseconds = Math.abs(value) < 100_000_000_000 ? value * 1000 : value;
    const date = new Date(milliseconds);
    return Number.isFinite(date.getTime()) ? date : null;
  }
  if (typeof value === 'string' && value.trim()) {
    const milliseconds = Date.parse(value);
    return Number.isFinite(milliseconds) ? new Date(milliseconds) : null;
  }
  return null;
}

function timeFromName(title, timeZone) {
  if (typeof title !== 'string') return null;
  const match = /(?<!\d)(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?:[^\d\nT]{0,12}|T)(\d{1,2})[:\-](\d{2})(?!\d)/.exec(title);
  if (!match) return null;
  const [, year, month, day, hour, minute] = match;
  const values = [Number(year), Number(month), Number(day), Number(hour), Number(minute)];
  const [y, m, d, h, min] = values;
  const check = new Date(Date.UTC(y, m - 1, d, h, min));
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== m - 1 || check.getUTCDate() !== d || h > 23 || min > 59) return null;
  // Parse named wall time in the requested zone (including Berlin DST) without
  // depending on the machine's local timezone.
  let guess = Date.UTC(y, m - 1, d, h, min);
  for (let i = 0; i < 3; i++) {
    const p = zonedParts(new Date(guess), timeZone);
    const represented = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day), Number(p.hour), Number(p.minute));
    const desired = Date.UTC(y, m - 1, d, h, min);
    if (represented === desired) return new Date(guess);
    guess += desired - represented;
  }
  const p = zonedParts(new Date(guess), timeZone);
  if (+p.year === y && +p.month === m && +p.day === d && +p.hour === h && +p.minute === min) return new Date(guess);
  return null;
}

function namedTimestampFromMessage(text, timeZone) {
  const marker = /本会话命名为\s*[:：]\s*([^\r\n]+)/.exec(text);
  return marker ? timeFromName(marker[1], timeZone) : null;
}

function chooseTimestamp(payload, timeZone, visibleUserTexts) {
  for (const text of visibleUserTexts.slice(0, 2)) {
    const named = namedTimestampFromMessage(text, timeZone);
    if (named) return { date: named, source: 'message_naming' };
  }
  for (const title of [payload.title, payload.name]) {
    const named = timeFromName(title, timeZone);
    if (named) return { date: named, source: 'title' };
  }
  const created = timestampFromCreateTime(payload.create_time);
  if (created) return { date: created, source: 'create_time' };
  fail('无法确定会话时间：命名中没有可识别时间，且 create_time 无效');
}

function messageText(parts) {
  const fragments = [];
  const audio = [];
  const addText = (text, audioContext) => {
    if (!text) return;
    fragments.push(text);
    if (audioContext) audioContext.transcription.push(text);
  };
  const visit = (value, audioContext = null) => {
    if (typeof value === 'string') {
      addText(value, audioContext);
      return;
    }
    if (Array.isArray(value)) {
      for (const item of value) visit(item, audioContext);
      return;
    }
    if (!asObject(value)) return;
    const type = [value.content_type, value.type, value.modality].filter((x) => typeof x === 'string').join(' ').toLowerCase();
    const isTranscript = /audio[_ -]?transcription|transcript/.test(type);
    const hasAudioPointer = Object.keys(value).some((key) => /audio|asset_pointer|audio_url/i.test(key));
    const isAudio = hasAudioPointer || (!isTranscript && /audio|speech|voice/.test(type));
    let context = audioContext;
    if (isAudio) {
      const ref = {};
      for (const [key, item] of Object.entries(value)) {
        if (/audio|asset_pointer|transcript|text|type|modality/i.test(key) && (typeof item === 'string' || typeof item === 'number')) ref[key] = item;
      }
      context = { ref, transcription: [] };
      audio.push(context);
    }
    // Visit parts once; text in an audio wrapper's own subtree belongs to that
    // audio segment and cannot be masked by unrelated message text.
    if (typeof value.text === 'string') addText(value.text, context || (isTranscript ? audioContext : null));
    const directTranscript = typeof value.transcript === 'string' ? value.transcript
      : typeof value.transcription === 'string' ? value.transcription : null;
    if (directTranscript && directTranscript !== value.text) addText(directTranscript, context || audioContext);
    if (Array.isArray(value.parts)) visit(value.parts, context);
  };
  visit(parts);
  const text = fragments.join('\n').trim();
  for (const segment of audio) segment.hasTranscript = segment.transcription.some((part) => part.trim().length > 0);
  return {
    text,
    audio: audio.map(({ ref, hasTranscript }) => ({ ref, hasTranscript })),
    hasUntranscribedAudio: audio.some((segment) => !segment.hasTranscript)
  };
}

const HIDDEN_ASSISTANT_CHANNELS = new Set(['analysis', 'justify', 'confidence', 'delegate_summary', 'juicepred']);

function isHiddenMessage(message, node) {
  const metadata = message.metadata ?? node.metadata ?? {};
  if (metadata.is_visually_hidden_from_conversation === true) return true;
  if (message.author?.role !== 'assistant') return false;
  const channelValue = message.channel ?? metadata.channel;
  const channel = typeof channelValue === 'string' ? channelValue : channelValue?.name;
  return typeof channel === 'string' && HIDDEN_ASSISTANT_CHANNELS.has(channel.toLowerCase());
}

function conversationIdOf(payload) {
  const hasId = payload.id !== undefined && payload.id !== null;
  const hasConversationId = payload.conversation_id !== undefined && payload.conversation_id !== null;
  const id = hasId ? payload.id : null;
  const conversationId = hasConversationId ? payload.conversation_id : null;
  if ((hasId && (typeof id !== 'string' || !id.trim())) ||
      (hasConversationId && (typeof conversationId !== 'string' || !conversationId.trim()))) {
    fail('payload 的 id/conversation_id 必须是非空字符串');
  }
  if (id && conversationId && id !== conversationId) fail('payload.id 与 payload.conversation_id 不一致', 'identity_mismatch');
  const result = id || conversationId;
  if (!result) fail('payload 必须提供 id 或 conversation_id');
  return result;
}

function followCurrentBranch(payload) {
  const mapping = payload.mapping;
  const nodes = [];
  const problems = [];
  if (!asObject(mapping)) return { nodes, complete: false, problems: ['mapping_missing_or_invalid'] };
  if (typeof payload.current_node !== 'string' || !payload.current_node) {
    return { nodes, complete: false, problems: ['current_node_missing_or_invalid'] };
  }
  let nodeId = payload.current_node;
  const seen = new Set();
  while (true) {
    if (seen.has(nodeId)) {
      problems.push(`parent_cycle_at:${nodeId}`);
      break;
    }
    seen.add(nodeId);
    if (!Object.hasOwn(mapping, nodeId) || !asObject(mapping[nodeId])) {
      problems.push(`parent_link_broken_at:${nodeId}`);
      break;
    }
    const node = mapping[nodeId];
    nodes.push({ nodeId, node });
    if (node.parent === null) break;
    if (typeof node.parent !== 'string' || !node.parent) {
      problems.push(`parent_link_invalid_at:${nodeId}`);
      break;
    }
    nodeId = node.parent;
  }
  nodes.reverse();
  return { nodes, complete: problems.length === 0, problems };
}

function safeText(value) {
  return String(value).replaceAll('\0', '\\0');
}

function renderMarkdown({ payload, course, time, messages, chain, statusAnomalies, audioWithoutText, ignoredRoles, unansweredTail, version }) {
  const p = zonedParts(time.date, time.timeZone);
  const day = `${p.year}-${p.month}-${p.day}`;
  const weekday = WEEKDAYS[new Date(Date.UTC(+p.year, +p.month - 1, +p.day)).getUTCDay()];
  const clock = `${p.hour}-${p.minute}`;
  const rawTitle = payload.title ?? payload.name;
  const title = typeof rawTitle === 'string' && rawTitle.trim() ? rawTitle.trim() : '（无会话标题）';
  const lines = [
    `# ${safeText(title)}`, '',
    `- 会话 ID：${safeText(payload.id)}`,
    `- 课程：${course ? safeText(course) : '未提供'}`,
    `- 日期：${day}（周${weekday}）`,
    `- 时间：${p.hour}:${p.minute}（${time.timeZone}；来源：${time.source}）`,
    `- 父链完整：${chain.complete ? '是' : '否'}`,
    `- 消息数：${messages.length}（user ${messages.filter((m) => m.role === 'user').length}，assistant ${messages.filter((m) => m.role === 'assistant').length}）`,
    `- 末尾未回复：${unansweredTail ? '是（current_node 对应 user 消息）' : '否'}`,
    `- 消息状态异常：${statusAnomalies.length ? statusAnomalies.map((x) => `${x.role}/${x.messageId}: ${x.status}`).join('；') : '无'}`,
    `- 无文字音频：${audioWithoutText.length ? `${audioWithoutText.length} 条；音频指针未能还原为文字，见下方消息` : '无'}`,
    `- 未归档角色节点：${ignoredRoles}`,
    `- 导出版本：${version}`,
    '',
    '> 完整性只描述是否沿 current_node 父链到达根节点；助手是否缺失、状态是否异常、音频是否有转写分别报告。',
    ''
  ];
  if (chain.problems.length) lines.push('## 父链问题', '', ...chain.problems.map((problem) => `- ${safeText(problem)}`), '');
  for (const [index, message] of messages.entries()) {
    lines.push(`## ${index + 1}. ${message.role === 'user' ? '用户' : '助手'}${message.status ? `（${safeText(message.status)}）` : ''}`, '', `消息 ID：${safeText(message.id)}`, '');
    if (message.text) lines.push(message.text, '');
    else lines.push('（此消息没有可提取的文字内容）', '');
    if (message.hasUntranscribedAudio) {
      lines.push('**音频转写缺失：** 此消息包含音频内容/指针，但快照中没有可用文字转写，不能还原音频内容。', '');
      for (const pointer of message.audio) lines.push(`- 音频信息：\`${JSON.stringify(pointer)}\``);
      lines.push('');
    }
  }
  return { markdown: lines.join('\n'), baseName: `${day} 周${weekday} ${clock} [${String(payload.id).slice(0, 12)}]` };
}

function writeNew(file, content) {
  mkdirSync(path.dirname(file), { recursive: true });
  try {
    writeFileSync(file, content, { encoding: 'utf8', flag: 'wx' });
    return true;
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
    return false;
  }
}

function readJsonIfExists(file) {
  if (!existsSync(file)) return null;
  try { return JSON.parse(readFileSync(file, 'utf8')); } catch { return null; }
}

export function exportConversation({ payload, course, outputDir, timeZone = 'Europe/Berlin' } = {}) {
  if (!asObject(payload) || !asObject(payload.mapping)) fail('payload 必须包含 mapping');
  const id = conversationIdOf(payload);
  payload = { ...payload, id };
  if (typeof outputDir !== 'string' || !outputDir.trim()) fail('必须提供 outputDir');
  if (course !== undefined && course !== null && (typeof course !== 'string' || !course.trim() || /[<>:"/\\|?*\x00-\x1f]/.test(course))) fail('course 必须是有效的课程名；不会自动推测课程');
  try { new Intl.DateTimeFormat('en', { timeZone }).format(new Date()); } catch { fail(`无效时区：${timeZone}`); }
  const root = path.resolve(outputDir);
  mkdirSync(root, { recursive: true });

  const chain = followCurrentBranch(payload);
  const messages = [];
  const statusAnomalies = [];
  const audioWithoutText = [];
  let ignoredRoles = 0;
  const seenMessageIds = new Set();
  for (const { nodeId, node } of chain.nodes) {
    const message = node.message;
    if (!asObject(message)) continue;
    const role = message.author?.role;
    if (role !== 'user' && role !== 'assistant') { if (role) ignoredRoles++; continue; }
    if (isHiddenMessage(message, node)) { ignoredRoles++; continue; }
    const id = typeof message.id === 'string' && message.id ? message.id : nodeId;
    if (seenMessageIds.has(id)) {
      chain.complete = false;
      chain.problems.push(`duplicate_message_id:${id}`);
      continue;
    }
    seenMessageIds.add(id);
    const extracted = messageText(message.content?.parts ?? message.content?.text ?? []);
    const status = typeof message.status === 'string' ? message.status : null;
    if (status && status !== 'finished_successfully') statusAnomalies.push({ messageId: id, role, status });
    const item = { id, role, status, ...extracted };
    messages.push(item);
    if (item.hasUntranscribedAudio) audioWithoutText.push({ messageId: id, pointers: item.audio });
  }

  const time = chooseTimestamp(payload, timeZone, messages.filter(m => m.role === 'user').map(m => m.text));
  time.timeZone = timeZone;
  const unansweredTail = messages.at(-1)?.role === 'user';
  const completeness = {
    complete: chain.complete,
    chainComplete: chain.complete,
    problems: [...chain.problems],
    assistantMessagesPresent: messages.some((m) => m.role === 'assistant'),
    assistantMessagesMissing: !messages.some((m) => m.role === 'assistant'),
    statusAnomalies,
    audioWithoutText: audioWithoutText.map((item) => item.messageId)
  };

  const raw = JSON.stringify(payload, null, 2) + '\n';
  const contentHash = sha256(raw);
  const conversationKey = sha256(payload.id);
  const stateDir = path.join(root, '.mobile-archive');
  const snapshotPath = path.join(stateDir, 'snapshots', conversationKey, `${contentHash}.json`);
  mkdirSync(path.dirname(snapshotPath), { recursive: true });
  if (!existsSync(snapshotPath) && !writeNew(snapshotPath, raw)) {
    const stored = readFileSync(snapshotPath, 'utf8');
    if (stored !== raw) fail('快照路径已存在不同内容，拒绝覆盖', 'conflict');
  }

  const historyDir = path.join(stateDir, 'receipts', conversationKey);
  mkdirSync(historyDir, { recursive: true });
  const prior = readJsonIfExists(path.join(historyDir, `${contentHash}.json`));
  let version = prior?.version;
  if (!Number.isInteger(version)) {
    const versions = [];
    for (let i = 1; ; i++) {
      const receipt = readJsonIfExists(path.join(historyDir, `${i}.json`));
      if (!receipt) break;
      versions.push(i);
    }
    version = versions.length + 1;
  }
  const rendered = renderMarkdown({ payload, course, time, messages, chain, statusAnomalies, audioWithoutText, ignoredRoles, unansweredTail, version });
  const mdHash = sha256(rendered.markdown);
  let markdownPath = prior?.markdownPath;
  if (!markdownPath || !existsSync(markdownPath) || readFileSync(markdownPath, 'utf8') !== rendered.markdown) {
    let suffix = version === 1 ? '' : ` v${version}`;
    let filename = `${rendered.baseName}${suffix}.md`;
    markdownPath = path.join(root, filename);
    let collision = 2;
    while (!writeNew(markdownPath, rendered.markdown)) {
      if (readFileSync(markdownPath, 'utf8') === rendered.markdown) break;
      filename = `${rendered.baseName}${suffix}（冲突${collision}）.md`;
      markdownPath = path.join(root, filename);
      collision++;
    }
  }

  const receipt = {
    conversationId: payload.id,
    contentHash,
    markdownHash: mdHash,
    version,
    paths: { markdown: markdownPath, snapshot: snapshotPath },
    counts: {
      messages: messages.length,
      user: messages.filter((m) => m.role === 'user').length,
      assistant: messages.filter((m) => m.role === 'assistant').length,
      messageStatusAnomalies: statusAnomalies.length,
      audioWithoutText: audioWithoutText.length,
      ignoredRoles
    },
    completeness,
    unansweredTail
  };
  const receiptByHash = path.join(historyDir, `${contentHash}.json`);
  const receiptByVersion = path.join(historyDir, `${version}.json`);
  const receiptText = JSON.stringify(receipt, null, 2) + '\n';
  if (!existsSync(receiptByHash)) writeNew(receiptByHash, receiptText);
  if (!existsSync(receiptByVersion)) writeNew(receiptByVersion, receiptText);
  return receipt;
}
