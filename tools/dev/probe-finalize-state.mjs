/*
 * v9：精确抓「收口瞬间」的按钮 aria-label / innerText / disabled，以及 composer 文本变化。
 * 每 250ms 采样，覆盖 点发送 → 完成 的全过程。
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
  const pm = document.querySelector('div.ProseMirror[contenteditable="true"]');
  const out = [];
  for (const b of document.querySelectorAll('button,[role=button]')) {
    const al = b.getAttribute('aria-label') || '';
    const tx = (b.innerText || b.textContent || '').trim();
    if (!/听写|发送|停止|转录|取消|完成/.test(al + tx)) continue;
    out.push({
      al, tx: tx.slice(0, 20),
      d: !!b.disabled || b.getAttribute('aria-disabled') === 'true',
      inComposer: !!(pm && (b === pm || pm.contains(b) || (pm.parentElement && pm.parentElement.contains(b))))
    });
  }
  const seen = new Set(); const u = [];
  for (const o of out) { const k = o.al + '|' + o.tx + '|' + o.d + '|' + o.inComposer; if (!seen.has(k)) { seen.add(k); u.push(o); } }
  return { pm: pm ? (pm.innerText || '').trim() : '', btns: u };
})()`;
const fmt = (s) => 'pm=' + JSON.stringify(s.pm.slice(0, 30)) + '  ' + s.btns.map((b) => `[${b.al || b.tx}${b.d ? '/禁' : ''}${b.inComposer ? '/composer' : ''}]`).join(' ');

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
console.log('待命: ' + fmt(await ev(SNAP)));

console.log('\n>>> 点听写，先录 10 秒');
await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
for (let i = 0; i < 4; i++) { await wait(2500); console.log('  录' + ((i + 1) * 2.5) + 's: ' + fmt(await ev(SNAP))); }

console.log('\n>>> 点发送，每 250ms 采样 40 次');
await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(b) b.click(); return 1; })()`);
for (let i = 1; i <= 40; i++) {
  await wait(250);
  const s = await ev(SNAP);
  console.log(`  +${(i * 0.25).toFixed(2)}s ` + fmt(s));
}
console.log('href: ' + await ev('location.href'));
process.exit(0);
