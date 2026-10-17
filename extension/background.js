/*
 * 只做三件事，都不碰页面逻辑：
 *  1. 点扩展图标 → 通知当前标签页显示/隐藏悬浮面板
 *  2. 浏览器启动时（如果开了"自动打开 ChatGPT"）确保有一个 ChatGPT 标签页
 *  3. 本地接力：把页面侧收上来的文本和运行状态转发给本地小服务；
 *     失败结果原样交回面板，由面板保留队列并按可重试状态处理
 *
 * service worker 被回收不影响录音主链——所有判断和点击都在页面脚本里。
 */

const CHATGPT_MATCHES = ['https://chatgpt.com/*', 'https://chat.openai.com/*'];
const WRITER_URL = 'http://127.0.0.1:8799';

chrome.action.onClicked.addListener(async (tab) => {
  if (!tab || !tab.id) return;
  try {
    await chrome.tabs.sendMessage(tab.id, { type: 'togglePanel' });
  } catch {
    // 不是 ChatGPT 标签页，或内容脚本还没注入；忽略
  }
});

async function ensureChatgptTab() {
  const r = await chrome.storage.local.get('gad');
  const autoOpenTab = !!(r && r.gad && r.gad.autoOpenTab);
  if (!autoOpenTab) return;
  const tabs = await chrome.tabs.query({ url: CHATGPT_MATCHES });
  if (tabs.length) return;
  // 后台打开，不抢焦点
  await chrome.tabs.create({ url: 'https://chatgpt.com/', active: false });
}

chrome.runtime.onStartup.addListener(() => { ensureChatgptTab(); });
chrome.runtime.onInstalled.addListener(() => { ensureChatgptTab(); });

/** 往本地落盘服务发一次请求；内容写入磁盘可能稍慢，/save 单独给足时间。 */
async function postToWriter(path, payload, timeoutMs = 6000) {
  try {
    const res = await fetch(WRITER_URL + path, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(timeoutMs)
    });
    const data = await res.json().catch(() => ({}));
    return { ok: res.ok && data.ok !== false, status: res.status, ...data };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
}

// 一个监听器管三种消息：落盘转发、状态转发、探测本地服务
chrome.runtime.onMessage.addListener((msg, _sender, respond) => {
  if (!msg || !msg.type) return undefined;

  if (msg.type === 'relaySave') {
    (async () => {
      const r = await postToWriter('/save', msg.payload, 20000);
      respond(r.ok
        ? { ok: true, file: r.file, duplicate: !!r.duplicate, saveId: r.saveId || msg.payload?.saveId, status: r.status }
        : {
            ok: false,
            status: r.status,
            retryable: r.retryable !== false,
            code: r.code || '',
            saveId: r.saveId || msg.payload?.saveId,
            error: r.error || '本地落盘服务没在运行'
          });
    })();
    return true;
  }

  if (msg.type === 'relayTelemetry') {
    (async () => { await postToWriter('/telemetry', msg.payload); respond({ ok: true }); })();
    return true;
  }

  if (msg.type === 'writerPing') {
    (async () => {
      try {
        const res = await fetch(WRITER_URL + '/ping', { signal: AbortSignal.timeout(3000) });
        respond({ ok: true, ...(await res.json()) });
      } catch (e) {
        respond({ ok: false, error: String(e.message || e) });
      }
    })();
    return true;
  }

  if (msg.type === 'writerChooseRoot') {
    (async () => {
      try {
        const res = await fetch(WRITER_URL + '/choose-root', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: '{}',
          signal: AbortSignal.timeout(125000)
        });
        respond({ ok: res.ok, ...(await res.json().catch(() => ({}))) });
      } catch (e) {
        respond({ ok: false, error: String(e.message || e) });
      }
    })();
    return true;
  }

  if (msg.type === 'writerRootCandidates') {
    (async () => {
      try {
        const res = await fetch(WRITER_URL + '/root-candidates', { signal: AbortSignal.timeout(5000) });
        respond({ ok: res.ok, ...(await res.json().catch(() => ({}))) });
      } catch (e) {
        respond({ ok: false, error: String(e.message || e) });
      }
    })();
    return true;
  }

  if (msg.type === 'writerSetRoot') {
    (async () => {
      try {
        const res = await fetch(WRITER_URL + '/set-root', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ rootDir: msg.rootDir }),
          signal: AbortSignal.timeout(5000)
        });
        respond({ ok: res.ok, ...(await res.json().catch(() => ({}))) });
      } catch (e) {
        respond({ ok: false, error: String(e.message || e) });
      }
    })();
    return true;
  }

  if (msg.type === 'openChatgpt') {
    (async () => {
      try {
        const tab = await chrome.tabs.create({ url: 'https://chatgpt.com/', active: true });
        respond({ ok: true, tabId: tab && tab.id });
      } catch (e) {
        respond({ ok: false, error: String(e.message || e) });
      }
    })();
    return true;
  }

  if (msg.type === 'openChatgptNew') {
    chrome.tabs.create({ url: 'https://chatgpt.com/', active: true });
    return false;
  }

  return undefined;
});
