/*
 * 隔离验证：提示词注入到底发几次、是不是在开录之前。
 *
 * 用本地 http 服务喂一个假页面（输入框 + 发送键 + 听写键），把真实的
 * extension/page-hook.js 灌进去跑，全程不碰真实 ChatGPT，也不发任何消息。
 *
 * 跑法：node tools/dev/probe-inject-dedupe.mjs
 * 期望：
 *   - 「/ 空白页 → 点开始」只注入 1 次，且注入发生在第一次点「听写」之前
 *   - 「会话 A → 会话 B」只注入 1 次
 *   - 「/ → /c/xxx」（自己发第一段造成的跳转）不注入
 *   - 冷却期内的重复会话变化不再注入
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CDP } from './cdp.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const HOOK = fs.readFileSync(path.join(__dirname, '..', '..', 'extension', 'page-hook.js'), 'utf8');

const PAGE = `<!doctype html><html><head><meta charset="utf-8"><title>synthetic</title></head>
<body>
  <div id="root">
    <div class="ProseMirror" contenteditable="true" role="textbox"></div>
  </div>
  <script>
    // 事件账本：谁在什么时候被点了、写了几次字
    window.__log = [];
    const root = document.getElementById('root');
    const composer = root.querySelector('.ProseMirror');
    const mk = (label, testid) => {
      const b = document.createElement('button');
      b.setAttribute('aria-label', label);
      if (testid) b.setAttribute('data-testid', testid);
      root.appendChild(b);
      return b;
    };
    // 待命态：只有「听写」
    let dictate = mk('听写');
    const send = mk('发送', 'send-button');
    send.style.display = 'none';
    let stopD = null;

    const setComposer = (t) => { composer.textContent = t; };
    window.__setComposer = setComposer;
    const origExec = document.execCommand.bind(document);
    document.execCommand = (cmd, ui, val) => {
      if (cmd === 'insertText' && val) {
        window.__log.push({ t: Date.now(), ev: 'insertText', text: String(val) });
        setComposer(String(val));
        return true;
      }
      if (cmd === 'selectAll') { window.__log.push({ t: Date.now(), ev: 'selectAll' }); return true; }
      if (cmd === 'delete') { setComposer(''); return true; }
      return origExec(cmd, ui, val);
    };

    send.onclick = () => {
      window.__log.push({ t: Date.now(), ev: 'clickSend', text: composer.innerText || '' });
      setComposer('');
      // 出来一个假的用户消息 + 假回答，让页面看起来像"发出去了"
      const u = document.createElement('div');
      u.className = 'group/user-message';
      u.textContent = 'sent';
      root.appendChild(u);
      const a = document.createElement('div');
      a.className = 'MarkdownRoot';
      a.textContent = 'answer';
      root.appendChild(a);
    };
    dictate.onclick = () => {
      window.__log.push({ t: Date.now(), ev: 'clickDictate' });
      dictate.remove();
      stopD = mk('停止听写');
      send.style.display = '';
      stopD.onclick = () => {
        window.__log.push({ t: Date.now(), ev: 'clickStopDictate' });
        if (stopD) { stopD.remove(); stopD = null; }
        send.style.display = 'none';
        dictate = mk('听写');
        dictate.onclick = arguments.callee;
      };
    };
    window.__composer = composer;
  </script>
</body></html>`;

const server = http.createServer((req, res) => {
  if (req.url === '/page-hook.js') {
    res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8' });
    res.end(HOOK);
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(PAGE);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const base = `http://127.0.0.1:${port}/`;

const args = process.argv.slice(2);
const pi = args.indexOf('--port');
const dbgPort = pi >= 0 ? args[pi + 1] : '9224';
// 自己开一个标签页，绝不复用别人正在用的页面（之前误把 ChatGPT 页面导航走了）
const created = await (await fetch(`http://127.0.0.1:${dbgPort}/json/new?about:blank`, { method: 'PUT' })).json();
const cdp = await CDP.connect(created.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
// 后台标签页的 setTimeout 会被浏览器限流到几十秒一次，探针里必须把页面拉到前台
try { await cdp.send('Page.bringToFront'); } catch { /* 忽略 */ }

async function ev(expr, awaitPromise = false) {
  const r = await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise, returnByValue: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text));
  return r.result.value;
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

await cdp.send('Page.navigate', { url: base });
await wait(1200);

// 灌真实 page-hook.js
await ev(`new Promise((res, rej) => {
  const s = document.createElement('script');
  s.src = '/page-hook.js';
  s.onload = () => res('ok');
  s.onerror = () => rej(new Error('load fail'));
  document.head.appendChild(s);
})`, true);
await wait(400);
console.log('page-hook 已加载:', await ev('!!window.__gadMain'));

// 配置：注入开、自动开始关（我们手动 start），挂个假 analyser 免得主循环报读不到电平
await ev(`(() => {
  const S = window.__gadMain;
  S.settings.autoInjectPrompt = true;
  S.settings.newSessionPrompt = '回忆关于会议记录/翻译相关的任务要求，根据要求开始任务。';
  S.settings.autoJumpNewSession = false;
  S.settings.saveEnabled = false;
  S.settings.autoStart = false;
  const fa = { fftSize: 4096, getFloatTimeDomainData(b) { for (let i = 0; i < b.length; i++) b[i] = 0; } };
  S.analyser = fa; S.buffer = new Float32Array(fa.fftSize); S.audioContext = { sampleRate: 48000 };
  return 'ok';
})()`);

async function report(tag) {
  const log = await ev('window.__log');
  const S = await ev(`(() => { const s = window.__gadMain; return { state: s.state, injectAt: s.injectAt, injectPendingAt: s.injectPendingAt, injecting: s.injecting, pending: !!s.injectPendingAt }; })()`);
  const inserts = log.filter((x) => x.ev === 'insertText');
  const sends = log.filter((x) => x.ev === 'clickSend');
  const dictates = log.filter((x) => x.ev === 'clickDictate');
  console.log(`\n== ${tag}`);
  console.log(`   insertText ${inserts.length} 次 / clickSend ${sends.length} 次 / clickDictate ${dictates.length} 次`);
  console.log(`   S = ${JSON.stringify(S)}`);
  if (inserts.length) {
    const first = inserts[0].t;
    const firstDictate = dictates.length ? dictates[0].t : null;
    console.log(`   首注入 → 首点听写：${firstDictate == null ? '（还没点过听写）' : (firstDictate - first) + 'ms'}`);
    console.log(`   注入文本第1条: ${JSON.stringify(inserts[0].text.slice(0, 30))}`);
  }
  return { log, S, inserts, sends, dictates };
}

async function waitForDictate(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const n = await ev(`window.__log.filter(x => x.ev === 'clickDictate').length`);
    if (n > 0) return true;
    await wait(250);
  }
  return false;
}

// 场景 1：空白新会话（/）→ 点开始
console.log('\n### 场景1 空白新会话 / + 点开始');
await ev(`window.__log.length = 0; window.__gadMain.api.start('test1'); 'ok'`);
// 后台标签页的定时器会被限流，轮询等「听写」真的被点，别用固定等待
await waitForDictate();
const r1 = await report('场景1');
const beforeDictate = r1.inserts.length ? (r1.dictates.length ? r1.dictates[0].t - r1.inserts[0].t : null) : null;
console.log(`   判定：注入 ${r1.inserts.length} 次（应为 1）；注入早于开录：${beforeDictate == null ? 'n/a' : beforeDictate > 0}`);

// 场景 2：/c/xxx 跳转（自己发第一段造成的）不应再注入
console.log('\n### 场景2 / → /c/aaa（自己发第一段）不应注入');
await ev(`window.__log.length = 0; history.pushState({}, '', '/c/aaa'); 'ok'`);
await wait(2000);
const r2 = await report('场景2');
console.log(`   判定：注入 ${r2.inserts.length} 次（应为 0）`);

// 场景 3：会话 A → 会话 B，应注入 1 次（冷却期已过）
console.log('\n### 场景3 /c/aaa → /c/bbb 应注入 1 次');
await ev(`window.__gadMain.injectAt = 0; window.__log.length = 0; history.pushState({}, '', '/c/bbb'); 'ok'`);
await wait(4500);
const r3 = await report('场景3');
console.log(`   判定：注入 ${r3.inserts.length} 次（应为 1）`);

// 场景 4：紧接着 bbb → ccc（冷却期内）不应再注入
console.log('\n### 场景4 /c/bbb → /c/ccc 冷却期内应 0 次');
await ev(`window.__log.length = 0; history.pushState({}, '', '/c/ccc'); 'ok'`);
await wait(2500);
const r4 = await report('场景4');
console.log(`   判定：注入 ${r4.inserts.length} 次（应为 0）`);

// 场景 5：退回空白页 → 应注入 1 次
console.log('\n### 场景5 /c/ccc → / 退回空白页应注入 1 次');
await ev(`window.__gadMain.injectAt = 0; window.__gadMain.injectPendingAt = 0; window.__log.length = 0; history.pushState({}, '', '/'); 'ok'`);
await wait(4500);
const r5 = await report('场景5');
console.log(`   判定：注入 ${r5.inserts.length} 次（应为 1）`);

console.log('\n=== 汇总 ===');
const checks = [
  ['场景1 空白页注入一次', r1.inserts.length === 1],
  ['场景1 注入早于开录', beforeDictate == null ? false : beforeDictate > 0],
  ['场景2 自发跳转不注入', r2.inserts.length === 0],
  ['场景3 会话切换注入一次', r3.inserts.length === 1],
  ['场景4 冷却期内不注入', r4.inserts.length === 0],
  ['场景5 退回空白页注入一次', r5.inserts.length === 1],
];
let pass = 0;
for (const [name, ok] of checks) { console.log(` ${ok ? 'PASS' : 'FAIL'}  ${name}`); if (ok) pass += 1; }
console.log(`合计 ${checks.length} 项，通过 ${pass} 项`);
cdp.close?.();
server.close();
process.exit(pass === checks.length ? 0 : 1);
