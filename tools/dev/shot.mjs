/*
 * 测试用截图： node shot.mjs <port> <out.png> [--url 包含串] [--size 1300x900] [--js "表达式"]
 */
import { writeFileSync } from 'node:fs';
import { CDP } from './cdp.mjs';

const port = process.argv[2] || '9444';
const out = process.argv[3] || 'shot.png';
const arg = (name) => (process.argv.includes(name) ? process.argv[process.argv.indexOf(name) + 1] : null);
const urlHint = arg('--url') || 'chatgpt';
const size = arg('--size');
const js = arg('--js');

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const pages = list.filter((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const page = pages.find((p) => (p.url + p.title).includes(urlHint)) || pages[0];
if (!page) { console.error('no page'); process.exit(1); }

const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
if (size) {
  const [w, h] = size.split('x').map(Number);
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false });
}
if (js) {
  await new Promise((r) => setTimeout(r, 300));
  const v = await cdp.eval(js);
  console.log('# js:', typeof v === 'string' ? v : JSON.stringify(v));
}
await new Promise((r) => setTimeout(r, 900));
const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
writeFileSync(out, Buffer.from(shot.data, 'base64'));
console.log('# saved', out, size || '');
if (size) await cdp.send('Emulation.clearDeviceMetricsOverride');
cdp.close();
