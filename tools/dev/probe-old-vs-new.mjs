/*
 * v8：对比老会话与新会话的听写按钮链。
 * 用法：node tools/dev/probe-old-vs-new.mjs [--port 9224] [--url https://chatgpt.com/c/<id>]
 * 不给 --url 时只用当前页面对比「新会话」与「当前会话」。
 */
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const i = args.indexOf('--port');
const port = i >= 0 ? args[i + 1] : '9224';
const j = args.indexOf('--url');
const oldUrl = j >= 0 ? args[j + 1] : '';

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
  let area = pm;
  for (let i = 0; i < 6 && area; i++) {
    if (area.querySelectorAll && area.querySelectorAll('button').length >= 2) break;
    area = area.parentElement;
  }
  const btns = area ? Array.from(area.querySelectorAll('button,[role=button]'))
    .map((e) => (e.getAttribute('aria-label') || e.innerText || '').trim()).filter(Boolean) : [];
  return { btns, pmLen: pm ? (pm.innerText || '').trim().length : -1 };
})()`;

async function run(tag, url) {
  console.log('\n===== ' + tag + ' : ' + url);
  await cdp.send('Page.navigate', { url });
  await wait(5500);
  const idle = await ev(SNAP);
  console.log('  待命按钮: [' + idle.btns.join(' | ') + ']  pmLen=' + idle.pmLen);
  const r = await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`);
  console.log('  点听写: ' + JSON.stringify(r));
  await wait(3000);
  const rec = await ev(SNAP);
  console.log('  录音按钮: [' + rec.btns.join(' | ') + ']  pmLen=' + rec.pmLen);
  for (const label of ['转录并发送', '发送', '停止听写', '取消听写']) {
    const has = await ev(`!!document.querySelector('button[aria-label="${label}"]')`);
    console.log('  有「' + label + '」? ' + has);
  }
  const fin = await ev(`(() => {
    for (const l of ['转录并发送','发送','停止听写','取消听写']) {
      const b = document.querySelector('button[aria-label="'+l+'"]');
      if (b) { b.click(); return { clicked: l }; }
    }
    return { none: true };
  })()`);
  await wait(3500);
  const after = await ev(SNAP);
  console.log('  收口点击: ' + JSON.stringify(fin) + '  之后按钮: [' + after.btns.join(' | ') + ']  href=' + await ev('location.href'));
}

await run('新会话', 'https://chatgpt.com/');
if (oldUrl) await run('指定会话', oldUrl);
else await run('当前页面会话', page.url);
process.exit(0);
