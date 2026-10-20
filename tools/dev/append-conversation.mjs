/*
 * 把会话导出里"比文件末尾更新"的段落追加到已有 Markdown。
 * 用法： node append-conversation.mjs <export.json> <target.md>
 * 幂等：只追加时间严格晚于文件最后一个段落的块，重复运行不会重复写入。
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';

const [exportPath, mdPath] = process.argv.slice(2);
const data = JSON.parse(readFileSync(exportPath, 'utf8'));
const rows = data.rows || [];

const pad = (n) => String(n).padStart(2, '0');
const clock = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
const secOfDay = (d) => d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds();

// 配对：用户消息 → 它之后、下一条用户消息之前的回答
const blocks = [];
for (const r of rows) {
  if (r.role === 'user') blocks.push({ t: r.t, user: r.text, answers: [] });
  else if (blocks.length) blocks[blocks.length - 1].answers.push(r.text);
}

const existing = existsSync(mdPath) ? readFileSync(mdPath, 'utf8') : '';
let lastSec = -1;
const headLines = [...existing.matchAll(/^## (\d{2}):(\d{2}):(\d{2})/gm)];
if (headLines.length) {
  const m = headLines[headLines.length - 1];
  lastSec = Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]);
}

const fresh = blocks
  .map((b, i) => ({ b, i, d: new Date(b.t * 1000) }))
  .filter((x) => secOfDay(x.d) > lastSec);

if (!fresh.length) {
  console.log(JSON.stringify({ appended: 0, note: '没有比文件末尾更新的段落' }));
  process.exit(0);
}

const out = [];
for (const { b, i, d } of fresh) {
  const text = b.answers.join('\n\n').trim();
  out.push(`## ${clock(d)}　第 ${i + 1} 段`);
  out.push('');
  out.push('**原始转录**');
  out.push('');
  out.push(b.user.trim());
  out.push('');
  if (text) {
    out.push('**GPT 回答 / 处理结果**');
    out.push('');
    out.push(text);
    out.push('');
  }
  out.push('---');
  out.push('');
  out.push('');
}

const joiner = (existing && !existing.endsWith('\n')) ? '\n' : '';
writeFileSync(mdPath, joiner + out.join('\n'), { encoding: 'utf8', flag: 'a' });
console.log(JSON.stringify({
  appended: fresh.length,
  first: clock(fresh[0].d),
  last: clock(fresh[fresh.length - 1].d)
}, null, 2));
