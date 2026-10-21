/*
 * 决定性实验 v2：GPT 生成答案期间「听写」按钮是 disabled 的，点不动。
 * 那么在 disabled 期间反复尝试，第一次真正可点是什么时刻？点了之后多久进入录音？
 * 用 80ms 高频轮询，量出「生成结束 → 听写可点 → 进入录音」这三点的精确时刻。
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

// 全部状态一次取回，减少往返
const SNAP = `(() => {
  const d = document.querySelector('button[aria-label="听写"]');
  const dis = (b) => b ? (!!b.disabled || b.getAttribute('aria-disabled') === 'true') : null;
  return {
    dExist: !!d, dDis: dis(d),
    stopD: !!document.querySelector('button[aria-label="停止听写"]'),
    gen: !!document.querySelector('button[aria-label="停止"]')
  };
})()`;

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
await wait(2000);
await ev(`(() => { const pm=document.querySelector('div.ProseMirror[contenteditable="true"]'); if(!pm) return 0;
  pm.focus(); document.execCommand('insertText', false, 'Nenne drei Unterschiede zwischen Destillation und Rektifikation, kurz.'); return 1; })()`);
await wait(500);
const t0 = Date.now();
await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(b) b.click(); return 1; })()`);

let tGenEnd = -1, tFirstClickable = -1, tEntered = -1, prevGen = true;
for (let i = 0; i < 500; i++) {
  const s = await ev(SNAP);
  const el = (Date.now() - t0) / 1000;
  if (prevGen && !s.gen && tGenEnd < 0) tGenEnd = el;
  prevGen = s.gen;
  if (s.dExist && !s.dDis && tFirstClickable < 0) tFirstClickable = el;
  if (s.stopD) { tEntered = el; break; }
  if (tFirstClickable >= 0 && !s.stopD && s.dExist && !s.dDis) {
    await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
    const a = await ev(SNAP);
    if (a.stopD) { tEntered = el; break; }
  }
  if (el > 30) break;
  await wait(80);
}
console.log(JSON.stringify({
  生成结束: tGenEnd < 0 ? null : +tGenEnd.toFixed(2),
  听写首次可点: tFirstClickable < 0 ? null : +tFirstClickable.toFixed(2),
  进入录音: tEntered < 0 ? null : +tEntered.toFixed(2)
}, null, 1));
await ev(`(() => { for (const l of ['停止听写','取消听写']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return l;} } return 0; })()`);
process.exit(0);
