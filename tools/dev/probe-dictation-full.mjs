/*
 * 完整按钮时间轴探测（只在探测页跑，会真录几秒音然后取消）。
 * 记录：点听写前 → 录音中 → 点「停止听写」→ 点「转录并发送」→ 之后
 * 每一步都打印 composer 区域全部按钮 + 全局新出现的 overlay/对话框。
 */
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const get = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const port = get('port', '9224');

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
  const inf = (el) => ({
    a: el.getAttribute('aria-label') || '',
    t: (el.innerText || el.textContent || '').trim().slice(0, 30),
    d: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
  });
  // composer 区域：找包含 ProseMirror 的 form 或最外层容器
  const pm = document.querySelector('div.ProseMirror[contenteditable="true"]');
  let area = pm;
  for (let i = 0; i < 6 && area; i++) {
    if (area.querySelectorAll && area.querySelectorAll('button').length >= 2) break;
    area = area.parentElement;
  }
  const compBtns = area ? Array.from(area.querySelectorAll('button,[role=button]')).map(inf) : [];
  // 全局含关键词的按钮（去重）
  const kw = ['听写','转录','发送','停止','取消','录音','语音','麦克风','dictat','transcri','send','stop','cancel','record','voice','mic','audio'];
  const seen = new Set(); const rel = [];
  for (const b of document.querySelectorAll('button,[role=button]')) {
    const i = inf(b);
    if (!kw.some((k) => (i.a + ' ' + i.t).toLowerCase().includes(k))) continue;
    const key = i.a + '|' + i.t + '|' + i.d;
    if (seen.has(key)) continue; seen.add(key); rel.push(i);
  }
  // 弹层/对话框
  const overlays = Array.from(document.querySelectorAll('[role=dialog],[role=alertdialog],[role=menu],[data-radix-popper-content-wrapper]'))
    .map((e) => ({ role: e.getAttribute('role'), text: (e.innerText || '').trim().slice(0, 80) }))
    .filter((o) => o.text);
  return {
    compBtns, rel, overlays,
    canvas: document.querySelectorAll('canvas').length,
    pmText: pm ? (pm.innerText || '').trim().slice(0, 80) : '',
  };
})()`;

const show = (tag, s) => {
  console.log('');
  console.log('### ' + tag + '  canvas=' + s.canvas + '  pm=' + JSON.stringify(s.pmText));
  console.log('  composer 区按钮:');
  s.compBtns.forEach((b) => console.log('    ', JSON.stringify(b)));
  console.log('  全局相关按钮:');
  s.rel.forEach((b) => console.log('    ', JSON.stringify(b)));
  if (s.overlays.length) { console.log('  弹层:'); s.overlays.forEach((o) => console.log('    ', JSON.stringify(o))); }
};

const click = (label) => ev(`(() => {
  const b = document.querySelector('button[aria-label="${label}"]');
  if (!b) return { ok: false };
  b.click(); return { ok: true };
})()`);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

show('0 初始', await ev(SNAP));

console.log('\n>>> 点「听写」');
console.log(JSON.stringify(await click('听写')));
await wait(1200);
show('1 点听写后 1.2s', await ev(SNAP));
await wait(2500);
show('2 录音中 3.7s', await ev(SNAP));

console.log('\n>>> 点「停止听写」');
console.log(JSON.stringify(await click('停止听写')));
await wait(1500);
show('3 停止听写后', await ev(SNAP));

console.log('\n>>> 点「转录并发送」');
console.log(JSON.stringify(await click('转录并发送')));
for (const ms of [800, 1500, 2500, 4000]) {
  await wait(ms);
  show('4 转录并发送 +' + ms, await ev(SNAP));
}

// 收尾清理
console.log('\n>>> 收尾');
console.log(JSON.stringify(await ev(`(() => {
  for (const l of ['取消听写','停止听写','取消转录','取消']) {
    const b = document.querySelector('button[aria-label="' + l + '"]');
    if (b) { b.click(); return { clicked: l }; }
  }
  return { none: true };
})()`)));
process.exit(0);
