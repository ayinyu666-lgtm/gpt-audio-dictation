/*
 * v7：对比老会话与新会话的听写按钮链，确认是不是「新会话走新 UI、老会话走旧 UI」的灰度。
 * 老会话用 URL 直接导航；新会话导航到根。
 */
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const i = args.indexOf('--port');
const port = i >= 0 ? args[i + 1] : '9224';
const j = args.indexOf('--url');
const oldUrl = j >= 0 ? args[j + 1] : 'https://chatgpt.com/c/6ad8b657-2594-83ed-86db-a0232e8528fa';

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
  console.log('  有「转录并发送」? ' + JSON.stringify(await ev(`!!document.querySelector('button[aria-label="转录并发送"]')`)));
  console.log('  有「发送」? ' + JSON.stringify(await ev(`!!document.querySelector('button[aria-label="发送"]')`)));
  console.log('  有「停止听写」? ' + JSON.stringify(await ev(`!!document.querySelector('button[aria-label="停止听写"]')`)));
  console.log('  有「取消听写」? ' + JSON.stringify(await ev(`!!document.querySelector('button[aria-label="取消听写"]')`)));
  // 收口
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
await run('老会话(6ad8b657 Weichere Materialien)', oldUrl);
await run('老会话(6ad855a5 回忆翻译要求)', 'https://chatgpt.com/c/6ad855a5-2f0c-83ed-84d0-320572c8811c');
process.exit(0);
