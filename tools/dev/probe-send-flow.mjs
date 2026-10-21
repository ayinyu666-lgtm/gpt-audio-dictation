/*
 * v8：新 UI 下「点发送」之后到底发生了什么 —— 轮询按钮/composer/URL/turn 数，
 * 确认「发送」是否等价于旧版「转录并发送」，以及收口判据（composer 是否清空）是否还成立。
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
  const all = Array.from(document.querySelectorAll('button[aria-label]'))
    .map(b => ({ l: b.getAttribute('aria-label'), d: !!b.disabled || b.getAttribute('aria-disabled')==='true' }))
    .filter(x => /听写|发送|停止|转录|取消/.test(x.l));
  const seen = new Set(); const btns = [];
  for (const b of all) { const k = b.l + b.d; if (!seen.has(k)) { seen.add(k); btns.push(b.l + (b.d?'(禁)':'')); } }
  return {
    href: location.href.replace('https://chatgpt.com',''),
    pm: pm ? (pm.innerText||'').trim() : '',
    turns: document.querySelectorAll('[class*="MarkdownRoot"]').length,
    userTurns: document.querySelectorAll('[class~="group/user-message"]').length,
    btns
  };
})()`;

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
console.log('待命: ' + JSON.stringify(await ev(SNAP)));

console.log('\n>>> 点「听写」，录 8 秒');
await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
for (let i = 1; i <= 8; i++) {
  await wait(1000);
  const s = await ev(SNAP);
  console.log(`  t=${i}s pm=${JSON.stringify(s.pm.slice(0,40))} 按钮=[${s.btns.join('|')}]`);
}

console.log('\n>>> 点「发送」，之后每 700ms 轮询');
await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(b) b.click(); return 1; })()`);
for (let i = 1; i <= 14; i++) {
  await wait(700);
  const s = await ev(SNAP);
  console.log(`  +${(i*0.7).toFixed(1)}s href=${s.href} pm=${JSON.stringify(s.pm.slice(0,40))} turns=${s.turns} user=${s.userTurns} 按钮=[${s.btns.join('|')}]`);
}
process.exit(0);
