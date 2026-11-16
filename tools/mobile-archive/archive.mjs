import { createHash, randomUUID } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, linkSync, mkdirSync, openSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const VERSION = 1;
const MARKER = 'gad-mobile-archive-prototype-v1\n';
const hash = (s) => createHash('sha256').update(s).digest('hex');
const json = (value) => JSON.stringify(value) + '\n';
const fail = (message, code = 'invalid_input') => { const e = new Error(message); e.code = code; throw e; };
const plain = (v) => v && typeof v === 'object' && !Array.isArray(v);
const keys = (v, allowed, name) => {
  if (Object.keys(v).some((key) => !allowed.includes(key))) fail(`${name} 包含不支持的字段，未导入`);
};
const text = (v, name, max = 1000) => {
  if (typeof v !== 'string' || !v.trim() || v !== v.trim() || v.length > max || /[\x00-\x1f\x7f]/.test(v)) fail(`无效字段：${name}`);
  return v;
};

function validate(snapshot) {
  if (!plain(snapshot) || snapshot.schemaVersion !== VERSION) fail('快照 schemaVersion 必须为 1');
  keys(snapshot, ['schemaVersion', 'verification', 'source', 'conversationId', 'title', 'sessionStart', 'messages'], '快照');
  if (!plain(snapshot.verification) || snapshot.verification.complete !== true ||
      snapshot.verification.messagesStable !== true || snapshot.verification.answersComplete !== true) fail('必须明确声明完整、消息标识稳定且回答完成');
  keys(snapshot.verification, ['complete', 'messagesStable', 'answersComplete', 'method'], 'verification');
  const method = text(snapshot.verification.method, 'verification.method');
  const title = text(snapshot.title, 'title');
  const match = /^GAD｜([^｜]+)｜(\d{4}-\d{2}-\d{2}) (\d{2})-(\d{2})｜([A-Za-z0-9]{1,64})-(\d{2,})$/.exec(title);
  if (!match || match[1] !== match[1].trim() || /^0+$/.test(match[6])) fail('课程标题不符合固定命名规则');
  const [, course, day, hour, minute, classId, part] = match;
  text(course, 'course', 120);
  const start = text(snapshot.sessionStart, 'sessionStart');
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(start) ||
      !start.startsWith(`${day}T${hour}:${minute}:`) || !Number.isFinite(Date.parse(start))) fail('sessionStart 必须含时区，与标题中的本地开始时间一致');
  const calendar = new Date(`${day}T${hour}:${minute}:00Z`);
  if (!Number.isFinite(calendar.getTime()) || calendar.toISOString().slice(0, 16) !== `${day}T${hour}:${minute}`) fail('标题日期或时间无效');
  const conversationId = text(snapshot.conversationId, 'conversationId', 512);
  const source = text(snapshot.source, 'source', 512);
  if (!Array.isArray(snapshot.messages) || !snapshot.messages.length) fail('消息列表不能为空');
  const ids = new Set();
  const messages = snapshot.messages.map((m, i) => {
    if (!plain(m)) fail(`第 ${i + 1} 条消息无效`);
    keys(m, ['id', 'role', 'text'], `第 ${i + 1} 条消息`);
    const id = text(m.id, `messages[${i}].id`, 512);
    if (ids.has(id)) fail('同一会话中的消息 ID 重复');
    ids.add(id);
    if (!['user', 'assistant'].includes(m.role)) fail('只支持 user 和 assistant 消息');
    if (typeof m.text !== 'string' || !m.text.trim() || m.text.length > 4_000_000 || m.text.includes('\0')) fail('消息文本为空或无效');
    return { id, role: m.role, text: m.text };
  });
  return { schemaVersion: VERSION, verification: { complete: true, messagesStable: true, answersComplete: true, method }, source, conversationId, title, sessionStart: start, course, classId, part, messages };
}

// Write data to a new inode, flush it, and publish without replacing an existing
// file. A crash can leave an unreferenced temp file, never a partial final file.
function createImmutable(file, content) {
  mkdirSync(path.dirname(file), { recursive: true });
  const tmp = path.join(path.dirname(file), `.pending-${randomUUID()}`);
  let fd;
  try {
    fd = openSync(tmp, 'wx', 0o600);
    writeFileSync(fd, content, 'utf8');
    fsyncSync(fd);
    closeSync(fd); fd = undefined;
    try { linkSync(tmp, file); return 'created'; }
    catch (e) {
      if (e.code !== 'EEXIST') throw e;
      if (readFileSync(file, 'utf8') !== content) fail(`已存在不同内容：${file}`, 'conflict');
      return 'duplicate';
    }
  } finally {
    if (fd !== undefined) closeSync(fd);
    try { unlinkSync(tmp); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  }
}

export function init(root) {
  if (!root || !path.isAbsolute(root)) fail('必须提供绝对路径 --root');
  mkdirSync(root, { recursive: true });
  if (readdirSync(root).length) fail('初始化目录必须为空，避免碰触已有存档');
  createImmutable(path.join(root, '.mobile-archive-root'), MARKER);
}

function requireRoot(root) {
  if (!root || !path.isAbsolute(root)) fail('必须提供绝对路径 --root');
  const marker = path.join(root, '.mobile-archive-root');
  if (!existsSync(marker) || readFileSync(marker, 'utf8') !== MARKER) fail('目标不是已初始化的独立归档目录');
}

function locationFor(s) {
  // Hashes preserve stable identity even when filesystem-safe names collide.
  const slug = s.course.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/[. ]+$/g, '').slice(0, 40) || 'course';
  return path.join('courses', `${slug}-${hash(s.course).slice(0, 16)}`, `${s.sessionStart.slice(0, 10)}-${s.classId}-${hash(s.classId).slice(0, 16)}`, `conversation-${hash(s.source + '\0' + s.conversationId)}`);
}

function snapshotBody(s) {
  return json({ schemaVersion: s.schemaVersion, verification: s.verification, source: s.source,
    conversationId: s.conversationId, title: s.title, sessionStart: s.sessionStart, messages: s.messages });
}

export function submit(root, input) {
  requireRoot(root);
  const snapshot = validate(input);
  const body = snapshotBody(snapshot);
  const jobId = hash(body);
  const queued = createImmutable(path.join(root, 'queue', `${jobId}.json`), body);
  return { jobId, queued };
}

export function processJob(root, jobId, options = {}) {
  requireRoot(root);
  if (!/^[a-f0-9]{64}$/.test(jobId)) fail('无效任务 ID');
  const queueFile = path.join(root, 'queue', `${jobId}.json`);
  const body = readFileSync(queueFile, 'utf8');
  if (hash(body) !== jobId) fail('队列数据校验失败', 'corrupt_queue');
  const s = validate(JSON.parse(body));
  if (snapshotBody(s) !== body) fail('队列快照格式无效', 'corrupt_queue');
  const conversationKey = hash(s.source + '\0' + s.conversationId);
  const classData = json({ course: s.course, classId: s.classId, sessionStart: s.sessionStart });
  const route = locationFor(s);
  const manifest = json({ source: s.source, conversationId: s.conversationId, title: s.title,
    classId: s.classId, course: s.course, sessionStart: s.sessionStart, part: s.part,
    route, messages: s.messages.map((m, i) => ({ id: m.id, ordinal: i + 1, role: m.role, digest: hash(m.text) })) });
  // The immutable indexes bind a class and conversation globally inside this root.
  createImmutable(path.join(root, 'index', 'classes', `${hash(s.classId)}.json`), classData);
  const partNumber = s.part.replace(/^0+/, '');
  createImmutable(path.join(root, 'index', 'parts', `${hash(s.classId + '\0' + partNumber)}.json`),
    json({ classId: s.classId, part: partNumber, source: s.source, conversationId: s.conversationId }));
  createImmutable(path.join(root, 'index', 'conversations', `${conversationKey}.json`), manifest);
  createImmutable(path.join(root, route, 'conversation.json'), manifest);
  let created = 0, duplicate = 0;
  const unansweredTail = s.messages.at(-1).role === 'user';
  for (const [i, m] of s.messages.entries()) {
    const messageKey = hash(m.id);
    const replyNote = unansweredTail && i === s.messages.length - 1 ? '- 后续回答：快照中尚无\n' : '';
    const entry = `# ${s.course}\n\n- 会话：${s.conversationId}\n- 课次：${s.classId} / ${s.part}\n- 顺序：${i + 1}\n- 消息 ID：${m.id}\n- 角色：${m.role}\n${replyNote}\n---\n\n${m.text}\n`;
    const result = createImmutable(path.join(root, route, 'messages', `${messageKey}.md`), entry);
    if (result === 'created') created++; else duplicate++;
    if (options.afterMessage) options.afterMessage(i + 1);
  }
  const receipt = json({ jobId, conversationKey, route, count: s.messages.length, snapshotHash: jobId });
  createImmutable(path.join(root, 'receipts', `${jobId}.json`), receipt);
  return { jobId, route, count: s.messages.length, created, duplicate, status: 'complete',
    ...(unansweredTail ? { answerStatus: 'no_following_assistant_in_snapshot' } : {}) };
}

export function resume(root) {
  requireRoot(root);
  const dir = path.join(root, 'queue');
  if (!existsSync(dir)) return [];
  return readdirSync(dir).filter((name) => /^[a-f0-9]{64}\.json$/.test(name)).sort().map((name) => {
    const jobId = name.slice(0, -5);
    try { return processJob(root, jobId); }
    catch (e) { return { jobId, status: 'failed', code: e.code || 'error', error: e.message }; }
  });
}
