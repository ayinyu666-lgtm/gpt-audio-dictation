/*
 * v5：抓录音期间 composer 里的真实文本与麦克风权限状态。
 */
import { CDP } from './cdp.mjs';

const port = (process.argv.slice(2).indexOf('--port') >= 0) ? process.argv[process.argv.indexOf('--port') + 1] : '9224';
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

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(4500);

console.log('麦克风权限: ' + JSON.stringify(await ev(`navigator.permissions ? navigator.permissions.query({name:'microphone'}).then(p=>({state:p.state})).catch(e=>({err:String(e)})) : 'no api'`)));
console.log('直接试 getUserMedia: ' + JSON.stringify(await ev(`navigator.mediaDevices.getUserMedia({audio:true}).then(s=>({ok:true,tracks:s.getAudioTracks().map(t=>t.label+'/'+t.readyState)})).catch(e=>({ok:false,name:e.name,msg:e.message}))`)));
console.log('录音设备数: ' + JSON.stringify(await ev(`navigator.mediaDevices.enumerateDevices ? navigator.mediaDevices.enumerateDevices().then(d=>({n:d.length,audio:d.filter(x=>x.kind==='audioinput').map(x=>x.label||'(无标签)')})).catch(e=>({err:String(e)})) : 'no api'`)));

console.log('\n>>> 点「听写」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
for (let i = 1; i <= 10; i++) {
  await wait(700);
  const s = await ev(`(() => {
    const p = document.querySelector('div.ProseMirror[contenteditable="true"]');
    const pm = p ? (p.innerText||'').trim() : '';
    const ph = p ? (p.parentElement ? (p.parentElement.innerText||'').trim().slice(0,120) : '') : '';
    const btns = Array.from(document.querySelectorAll('button[aria-label]')).map(b=>b.getAttribute('aria-label')).filter(l=>/听写|发送|停止|转录|取消/.test(l));
    return { pm, ph, btns };
  })()`);
  console.log(`  t=${(i*0.7).toFixed(1)}s pm=${JSON.stringify(s.pm)} 占位=${JSON.stringify(s.ph)} 按钮=[${s.btns.join('|')}]`);
}
console.log('\n>>> 收尾');
console.log(JSON.stringify(await ev(`(() => { for (const l of ['取消听写','停止听写','取消转录']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return {clicked:l};} } return {none:true}; })()`)));
process.exit(0);
