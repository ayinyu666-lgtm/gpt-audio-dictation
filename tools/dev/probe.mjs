/*
 * 测试用探针：连上某个 CDP 端口的页面，执行一段表达式并打印结果。
 * 用法： node probe.mjs <port> "<expression>" [--js-file path] [--url 包含串] [--reload]
 */
import { readFileSync } from 'node:fs';
import { CDP } from './cdp.mjs';

const port = process.argv[2] || '9444';
const jsFileIdx = process.argv.indexOf('--js-file');
const expr = jsFileIdx > 0 ? readFileSync(process.argv[jsFileIdx + 1], 'utf8') : (process.argv[3] || 'document.title');
const urlHint = process.argv.includes('--url') ? process.argv[process.argv.indexOf('--url') + 1] : 'chatgpt';

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const pages = list.filter((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const page = pages.find((p) => (p.url + p.title).includes(urlHint)) || pages[0];
if (!page) {
  console.error('没有可用页面。现有：');
  for (const p of pages) console.error(' -', p.url);
  process.exit(1);
}
console.log('# page:', page.title, '|', page.url);
const cdp = await CDP.connect(page.webSocketDebuggerUrl);
try {
  await cdp.send('Runtime.enable');
  const goIdx = process.argv.indexOf('--go');
  if (goIdx > 0) {
    await cdp.send('Page.enable');
    await cdp.send('Page.navigate', { url: process.argv[goIdx + 1] });
    await new Promise((r) => setTimeout(r, 7000));
  }
  if (process.argv.includes('--reload')) {
    await cdp.send('Page.enable');
    await cdp.send('Page.reload', { ignoreCache: true });
    await new Promise((r) => setTimeout(r, 6000));
  }
  const v = await cdp.eval(expr, { timeoutMs: 30000 });
  console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 2));
} catch (e) {
  console.error('EVAL ERROR:', e.message);
  process.exitCode = 1;
} finally {
  cdp.close();
}
