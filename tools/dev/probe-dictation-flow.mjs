/*
 * 只读+最小交互探测：点一次「听写」，然后按时间轴记录底部按钮的 aria 集合变化。
 * 只在探测页做，不碰用户的页面。会真的开始一次录音（几秒后手动取消）。
 * 用法：node tools/dev/probe-dictation-flow.mjs [--port 9224] [--hold 6000]
 */
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const get = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const port = get('port', '9224');
const hold = Number(get('hold', '6000'));

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && (t.url || '').includes('chatgpt.com'));
if (!page) { console.error('没找到 ChatGPT 页面'); process.exit(1); }
console.log('页面:', page.title);

const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Runtime.enable');
async function ev(expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result.value;
}

const SNAP = `(() => {
  const q = (s) => Array.from(document.querySelectorAll(s));
  const inf = (el) => ({
    aria: el.getAttribute('aria-label') || '',
    testid: el.getAttribute('data-testid') || '',
    text: (el.innerText || el.textContent || '').trim().slice(0, 40),
    disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
  });
  const all = q('button,[role=button]').map(inf);
  const kw = ['听写','转录','发送','停止','取消','录音','语音','麦克风','dictat','transcri','send','stop','cancel','record','voice','mic','audio'];
  const rel = all.filter((b) => kw.some((k) => (b.aria+' '+b.text+' '+b.testid).toLowerCase().includes(k)));
  return {
    ts: Date.now(),
    rel,
    canvas: q('canvas').length,
    composerText: (document.querySelector('div.ProseMirror[contenteditable="true"]')||{}).innerText?.trim().slice(0,60) || '',
  };
})()`;

console.log('--- 点击前 ---');
const before = await ev(SNAP);
console.log(JSON.stringify(before.rel, null, 1));

// 点听写
const clicked = await ev(`(() => {
  const b = document.querySelector('button[aria-label="听写"]');
  if (!b) return { ok: false, reason: 'no_dictate_button' };
  b.click();
  return { ok: true };
})()`);
console.log('点击听写:', JSON.stringify(clicked));

const marks = [];
const t0 = Date.now();
while (Date.now() - t0 < hold) {
  await new Promise((r) => setTimeout(r, 800));
  const s = await ev(SNAP);
  const key = JSON.stringify(s.rel);
  const last = marks[marks.length - 1];
  if (!last || last.key !== key) {
    marks.push({ key, at: Date.now() - t0, rel: s.rel, canvas: s.canvas, composerText: s.composerText });
  }
}

console.log('');
console.log('=== 按钮集合变化（每次变化打印一次） ===');
marks.forEach((m, i) => {
  console.log('[' + i + '] +' + m.at + 'ms  canvas=' + m.canvas + '  composer=' + JSON.stringify(m.composerText));
  m.rel.forEach((b) => console.log('     ', JSON.stringify(b)));
});

// 收尾：尝试取消
const cancelled = await ev(`(() => {
  const labels = ['取消听写','停止听写','取消转录','停止录音','取消'];
  for (const l of labels) {
    const b = document.querySelector('button[aria-label="' + l + '"]');
    if (b) { b.click(); return { ok: true, clicked: l }; }
  }
  return { ok: false, tried: labels };
})()`);
console.log('');
console.log('收尾:', JSON.stringify(cancelled));
process.exit(0);
