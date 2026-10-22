/*
 * 从 ChatGPT 会话里把用户转录 + GPT 回答导出来，按落盘格式写成 Markdown。
 *
 * 用途：扩展没落盘时，事后从会话本身抢救内容。
 * 跑法：node tools/dev/recover-conversation.mjs <conversationId> [--out 目录] [--dry]
 */
import fs from 'node:fs';
import path from 'node:path';
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const convId = args.find((a) => !a.startsWith('--'));
if (!convId) {
  console.error('用法：node tools/dev/recover-conversation.mjs <conversationId> [--out 目录] [--dry]');
  process.exit(2);
}
const outIdx = args.indexOf('--out');
const outDir = outIdx >= 0 ? args[outIdx + 1] : '';
const dry = args.includes('--dry');
const dbgPort = (() => {
  const i = args.indexOf('--port');
  return i >= 0 ? args[i + 1] : '9224';
})();

const created = await (await fetch(`http://127.0.0.1:${dbgPort}/json/new?about:blank`, { method: 'PUT' })).json();
const cdp = await CDP.connect(created.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
try { await cdp.send('Page.bringToFront'); } catch { /* 忽略 */ }

const ev = async (expr, awaitPromise = false) => {
  const r = await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise, returnByValue: true });
  if (r.exceptionDetails) {
    throw new Error(JSON.stringify(r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text));
  }
  return r.result.value;
};
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
const COMPOSER_JS = '!!document.querySelector(\'div.ProseMirror[contenteditable="true"]\')';
for (let i = 0; i < 40; i += 1) {
  await wait(500);
  try { if (await ev(COMPOSER_JS)) break; } catch { /* 页面还在加载 */ }
}

const data = await ev(`(async () => {
  const s = await (await fetch('/api/auth/session', { credentials: 'include' })).json();
  if (!s || !s.accessToken) return { error: '未登录或取不到 accessToken' };
  const r = await fetch('/backend-api/conversation/${convId}', {
    credentials: 'include',
    headers: { Authorization: 'Bearer ' + s.accessToken }
  });
  if (!r.ok) return { error: 'HTTP ' + r.status };
  const j = await r.json();
  const nodes = Object.values(j.mapping || {}).filter((n) => n && n.message);
  nodes.sort((a, b) => (a.message.create_time || 0) - (b.message.create_time || 0));
  const msgs = [];
  for (const n of nodes) {
    const m = n.message;
    const role = m.author && m.author.role;
    if (role !== 'user' && role !== 'assistant') continue;
    const text = (m.content && Array.isArray(m.content.parts) ? m.content.parts : [])
      .filter((x) => typeof x === 'string').join(String.fromCharCode(10)).trim();
    if (!text) continue;
    msgs.push({ role, text, t: m.create_time || null });
  }
  return { title: j.title || '', msgs, createTime: j.create_time || null };
})()`, true);

if (data.error) {
  console.error('导出失败：' + data.error);
  cdp.close?.();
  process.exit(1);
}

const msgs = data.msgs || [];
console.log('会话标题:', data.title);
console.log('消息数:', msgs.length, '（user ' + msgs.filter((m) => m.role === 'user').length +
  ' / assistant ' + msgs.filter((m) => m.role === 'assistant').length + '）');

// 按"用户消息 + 紧随其后的助手回答"配对成段
const pairs = [];
for (let i = 0; i < msgs.length; i += 1) {
  if (msgs[i].role !== 'user') continue;
  let ans = '';
  for (let k = i + 1; k < msgs.length && msgs[k].role !== 'user'; k += 1) {
    ans += (ans ? '\n\n' : '') + msgs[k].text;
  }
  pairs.push({ user: msgs[i].text, assistant: ans, t: msgs[i].t });
}
console.log('配对段数:', pairs.length);

const berlin = (epochSec) => {
  const d = epochSec ? new Date(epochSec * 1000) : new Date();
  return new Date(d.toLocaleString('sv-SE', { timeZone: 'Europe/Berlin' }).replace(' ', 'T') + 'Z');
};
const pad = (n) => String(n).padStart(2, '0');
const lines = [];
for (let i = 0; i < pairs.length; i += 1) {
  const when = berlin(pairs[i].t);
  const hhmmss = pad(when.getUTCHours()) + ':' + pad(when.getUTCMinutes()) + ':' + pad(when.getUTCSeconds());
  lines.push('## ' + hhmmss + '　第 ' + (i + 1) + ' 段');
  lines.push('');
  lines.push(pairs[i].user);
  lines.push('');
  if (pairs[i].assistant) {
    lines.push(pairs[i].assistant);
    lines.push('');
  }
}
const body = lines.join('\n');

if (dry) {
  console.log('\n--- 前 40 行 ---');
  console.log(body.split('\n').slice(0, 40).join('\n'));
  cdp.close?.();
  process.exit(0);
}

if (!outDir) {
  console.error('没有给 --out，内容只在上面打印过；加 --out 目录 才会落盘');
  cdp.close?.();
  process.exit(0);
}

fs.mkdirSync(outDir, { recursive: true });
const first = berlin(pairs.length ? pairs[0].t : null);
const wd = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][first.getUTCDay()];
const name = first.getUTCFullYear() + '-' + pad(first.getUTCMonth() + 1) + '-' + pad(first.getUTCDate()) +
  ' ' + wd + ' ' + pad(first.getUTCHours()) + '-' + pad(first.getUTCMinutes()) + '.md';
const file = path.join(outDir, name);
const header = '# ' + (process.env.RECOVER_COURSE || '未分类') + '\n\n';
fs.writeFileSync(file, header + body + '\n', 'utf8');
console.log('已写入:', file, '(' + fs.statSync(file).size + ' 字节)');
cdp.close?.();
