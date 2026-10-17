/*
 * 调试工具专用：极简 CDP 客户端（Node 22+ 自带 WebSocket，零依赖）。
 * 只给 tools/dev 下的探针用，不属于产品本体。
 */

export class CDP {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    this.closed = false;
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id && this.pending.has(msg.id)) {
        const entry = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        clearTimeout(entry.timer);
        if (msg.error) entry.reject(new Error(msg.error.message || 'CDP 错误'));
        else entry.resolve(msg.result);
      }
    });
    ws.addEventListener('close', () => {
      this.closed = true;
      for (const [, entry] of this.pending) {
        clearTimeout(entry.timer);
        entry.reject(new Error('CDP 连接已断开'));
      }
      this.pending.clear();
    });
    ws.addEventListener('error', () => { this.closed = true; });
  }

  static async connect(url, timeoutMs = 10000) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('连接调试端口超时: ' + url)), timeoutMs);
      ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('无法连接调试端口: ' + url)); }, { once: true });
    });
    return new CDP(ws);
  }

  send(method, params = {}, timeoutMs = 30000) {
    if (this.closed) return Promise.reject(new Error('CDP 连接已关闭'));
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('CDP 调用超时: ' + method));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async eval(expression, { awaitPromise = true, timeoutMs = 20000 } = {}) {
    const res = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise,
      returnByValue: true,
      allowUnsafeEvalBlockedByCSP: true
    }, timeoutMs);
    if (res.exceptionDetails) {
      const d = res.exceptionDetails;
      const msg = (d.exception && (d.exception.description || d.exception.value)) || d.text || '页面脚本异常';
      throw new Error(String(msg).split('\n')[0]);
    }
    return res.result ? res.result.value : undefined;
  }

  close() {
    this.closed = true;
    try { this.ws.close(); } catch { /* 忽略 */ }
  }
}
