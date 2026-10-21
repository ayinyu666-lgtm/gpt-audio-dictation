/*
 * 决定性实验 v3：让 GPT 生成一段很长的答案（预计 20 秒以上），
 * 全程每 300ms 记录「生成中 / 听写是否可点」，看生成期间「听写」到底能不能点。
 * 这决定了"空窗"能不能被压缩到比 GPT 回答时间更短。
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
  const ans = document.querySelectorAll('[class*="MarkdownRoot"]');
  return {
    dExist: !!d, dDis: dis(d),
    gen: !!document.querySelector('button[aria-label="停止"]'),
    ansLen: ans.length ? (ans[ans.length-1].innerText || '').trim().length : 0
  };
})()`;

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
await wait(2000);
await ev(`(() => { const pm=document.querySelector('div.ProseMirror[contenteditable="true"]'); if(!pm) return 0;
  pm.focus(); document.execCommand('insertText', false, 'Schreibe einen ausfuehrlichen Aufsatz von mindestens 800 Woertern ueber die Geschichte der chemischen Industrie in Deutschland. Bitte sehr detailliert und lang.'); return 1; })()`);
await wait(500);
const t0 = Date.now();
await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(b) b.click(); return 1; })()`);
console.log('已发送长答案请求，每 300ms 采样（最多 60s）：\n');
let prevAns = 0;
let stillGrowingAt = -1;
for (let i = 0; i < 200; i++) {
  const s = await ev(SNAP);
  const el = (Date.now() - t0) / 1000;
  const growing = s.ansLen > prevAns;
  if (growing) stillGrowingAt = el;
  prevAns = s.ansLen;
  console.log(`  +${el.toFixed(1)}s 生成中=${s.gen ? 'Y' : '-'} 听写=${s.dExist ? (s.dDis ? '禁' : '★可点') : '无'} 答案字数=${s.ansLen}${growing ? ' ↑' : ''}`);
  if (!s.gen && el > 3 && !growing && s.ansLen > 200) break;
  if (el > 60) break;
  await wait(300);
}
console.log('\n最后一次答案还在增长: +' + stillGrowingAt.toFixed(1) + 's');
await ev(`(() => { for (const l of ['停止听写','取消听写']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return l;} } return 0; })()`);
process.exit(0);
