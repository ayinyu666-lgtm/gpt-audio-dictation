import { readFileSync } from 'node:fs';
import path from 'node:path';

// Attach only to the explicitly selected, already-running dedicated browser.
// Authentication stays inside the page; cookies and tokens never leave it.
export async function connectBrowser({ profile, port } = {}) {
  port = Number(port || readFileSync(path.join(profile, 'DevToolsActivePort'), 'utf8').split(/\r?\n/)[0]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('无效浏览器端口');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const page = targets.find(t => t.type === 'page' && /^https:\/\/chatgpt\.com(?:\/|$)/.test(t.url));
  if (!page) throw new Error('请在独立浏览器中打开 ChatGPT');
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let serial = 0;
  const pending = new Map();
  socket.onmessage = ({ data }) => {
    const response = JSON.parse(data);
    if (pending.has(response.id)) {
      const { resolve, reject, timer } = pending.get(response.id);
      clearTimeout(timer); pending.delete(response.id);
      response.error ? reject(new Error(response.error.message)) : resolve(response.result);
    }
  };
  async function evaluate(expression) {
    const id = ++serial;
    const result = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('浏览器读取超时')); }, 60000);
      pending.set(id, { resolve, reject, timer });
      socket.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }));
    });
    if (result.exceptionDetails) throw new Error('页面读取失败：' + (result.exceptionDetails.exception?.description || result.exceptionDetails.text));
    return result.result.value;
  }
  return {
    evaluate,
    async get(apiPath) {
      if (!/^\/backend-api\/(conversation\/[-a-zA-Z0-9]+|conversations\?[^#]*)$/.test(apiPath)) throw new Error('只允许读取会话');
      return evaluate(`(async () => {
        const sessionResponse = await fetch('/api/auth/session', { credentials: 'include' });
        if (!sessionResponse.ok) throw new Error('登录状态不可用');
        const session = await sessionResponse.json();
        if (!session.accessToken) throw new Error('请在独立浏览器中登录 ChatGPT');
        const response = await fetch(${JSON.stringify(apiPath)}, { credentials: 'include', headers: { Authorization: 'Bearer ' + session.accessToken } });
        if (!response.ok) throw new Error('会话读取 HTTP ' + response.status);
        return response.json();
      })()`);
    },
    close() { socket.close(); }
  };
}
