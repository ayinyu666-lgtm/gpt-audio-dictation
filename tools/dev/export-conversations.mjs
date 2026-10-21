/*
 * 导出指定 ChatGPT 会话的完整内容（在已登录页面里 fetch，需要 live 登录态）。
 * 用法：
 *   node tools/dev/export-conversations.mjs --port 9224 --ids <id1>,<id2> --out exports/conversations.json
 * 只做只读抓取 + 落盘，不改页面、不发送消息。
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const get = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] ? args[i + 1] : def;
};
const port = get('port', '9224');
const ids = get('ids', '').split(',').map((s) => s.trim()).filter(Boolean);
const outPath = resolve(get('out', 'exports/conversations.json'));
const urlHint = get('url', 'chatgpt.com');

const wantList = args.includes('--list');

if (!ids.length && !wantList) {
  console.error('需要 --ids <会话id,逗号分隔> 或 --list');
  process.exit(1);
}

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && (t.url || '').includes(urlHint));
if (!page) {
  console.error('没有找到 ChatGPT 页面，当前页面：', list.map((t) => t.url).join(' | '));
  process.exit(1);
}

const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Runtime.enable');

async function evaluate(expr) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: expr,
    awaitPromise: true,
    returnByValue: true,
  });
  if (r.exceptionDetails) {
    throw new Error(r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description || ''));
  }
  return r.result.value;
}

const token = await evaluate(`(async () => {
  const r = await fetch('/api/auth/session', { credentials: 'include' });
  if (!r.ok) return { error: 'session_http_' + r.status };
  const j = await r.json();
  return { token: j.accessToken || null };
})()`);

if (!token || !token.token) {
  console.error('拿不到 accessToken：', JSON.stringify(token));
  process.exit(1);
}
console.log('accessToken 长度', token.token.length);

// --list：列出最近会话（用于查 id）
if (wantList) {
  const listData = await evaluate(`(async () => {
    const r = await fetch('/backend-api/conversations?offset=0&limit=40&order=updated', {
      credentials: 'include',
      headers: { Authorization: 'Bearer ${token.token}' },
    });
    if (!r.ok) return { error: 'http_' + r.status };
    return { json: await r.json() };
  })()`);
  if (listData.error) {
    console.error('会话列表失败:', listData.error);
    process.exit(1);
  }
  console.log(JSON.stringify((listData.json.items || []).map((i) => ({ id: i.id, title: i.title, updated: i.update_time })), null, 1));
  process.exit(0);
}

const results = [];
for (const id of ids) {
  const data = await evaluate(`(async () => {
    const r = await fetch('/backend-api/conversation/${id}', {
      credentials: 'include',
      headers: { Authorization: 'Bearer ${token.token}' },
    });
    if (!r.ok) return { id: '${id}', error: 'http_' + r.status };
    return { id: '${id}', json: await r.json() };
  })()`);
  if (data.error) {
    console.error('会话 ' + id + ' 失败:', data.error);
    results.push({ id, error: data.error });
    continue;
  }
  results.push({ id, json: data.json });
  const mapping = data.json.mapping || {};
  const nodes = Object.values(mapping);
  const userTurns = nodes.filter((n) => n.message && n.message.author && n.message.author.role === 'user');
  const assistantTurns = nodes.filter((n) => n.message && n.message.author && n.message.author.role === 'assistant');
  console.log(id, 'title=', data.json.title, 'nodes=', nodes.length, 'user=', userTurns.length, 'assistant=', assistantTurns.length);
}

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(results, null, 2), 'utf8');
console.log('已写出', outPath);
process.exit(0);
