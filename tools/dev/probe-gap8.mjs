/*
 * 定点验证：在「听写」刚变可点的那一瞬间点下去，多久能真正进入录音（「停止听写」出现）？
 * 每 50ms 采样，记录 可点 → 点击 → 停止听写出现 的间隔。
 */
import { CDP } from './cdp.mjs';

const port = '9224';
const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && (t.url || '').includes('chatgpt.com'));
const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
async function ev(e) {
  const r = await cdp.send('Runtime.evaluate', { expression: e, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result.value;
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const SNAP = `(() => {
  const d = document.querySelector('button[aria-label="听写"]');
  const dis = (b) => b ? (!!b.disabled || b.getAttribute('aria-disabled') === 'true') : null;
  return { dExist: !!d, dDis: dis(d), stopD: !!document.querySelector('button[aria-label="停止听写"]') };
})()`;

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
await wait(2000);
await ev(`(() => { const pm=document.querySelector('div.ProseMirror[contenteditable="true"]'); if(!pm) return 0;
  pm.focus(); document.execCommand('insertText', false, 'Sag kurz Hallo auf Deutsch.'); return 1; })()`);
await wait(400);
const t0 = Date.now();
await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(b) b.click(); return 1; })()`);

let tClickable = -1, tClicked = -1, tEntered = -1;
for (let i = 0; i < 900; i++) {
  const s = await ev(SNAP);
  const el = (Date.now() - t0) / 1000;
  if (s.dExist && !s.dDis && tClickable < 0) tClickable = el;
  if (tClickable >= 0 && tClicked < 0 && s.dExist && !s.dDis && !s.stopD) {
    await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
    tClicked = el;
    // 点完立刻高频盯「停止听写」
    for (let k = 0; k < 60; k++) {
      const a = await ev(SNAP);
      if (a.stopD) { tEntered = (Date.now() - t0) / 1000; break; }
      await wait(50);
    }
    break;
  }
  await wait(50);
}
console.log(JSON.stringify({
  听写可点: tClickable < 0 ? null : +tClickable.toFixed(2),
  点击时刻: tClicked < 0 ? null : +tClicked.toFixed(2),
  进入录音: tEntered < 0 ? null : +tEntered.toFixed(2),
  点击到进入: (tClicked < 0 || tEntered < 0) ? null : +(tEntered - tClicked).toFixed(2)
}, null, 1));
await ev(`(() => { for (const l of ['停止听写','取消听写']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return l;} } return 0; })()`);
process.exit(0);
