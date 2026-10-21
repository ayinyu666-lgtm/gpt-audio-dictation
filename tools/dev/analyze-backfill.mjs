/*
 * 分析：把导出的会话 JSON 里的 user 轮次，与磁盘上已落盘的段落做指纹比对，找出漏落的。
 * 只读，不写盘。
 *
 * 用法：node tools/dev/analyze-backfill.mjs <conversations.json> <归档根目录> [第二个归档根目录]
 * 可选环境变量 DATE_PREFIX=2026-10-21 只统计某一天的轮次（ISO 前缀匹配）。
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const EXPORT = process.argv[2] || 'conversations.json';
const ROOT = process.argv[3] || 'archive-root';
const DABAN = process.argv[4] || '';

const DATE_PREFIX = process.env.DATE_PREFIX || '';
const data = JSON.parse(readFileSync(EXPORT, 'utf8'));

function nodeText(n) {
  const m = n.message;
  if (!m || !m.content) return '';
  const c = m.content;
  if (typeof c.text === 'string') return c.text;
  if (Array.isArray(c.parts)) return c.parts.filter((p) => typeof p === 'string').join('\n');
  if (Array.isArray(c.content)) return c.content.map((x) => x.text || '').join('\n');
  return '';
}

// 收集今天的 user 轮次
const turns = [];
for (const conv of data) {
  if (!conv.json || !conv.json.mapping) continue;
  const nodes = Object.values(conv.json.mapping);
  for (const n of nodes) {
    const m = n.message;
    if (!m || !m.author || m.author.role !== 'user') continue;
    const t = nodeText(n).trim();
    if (!t) continue;
    const ts = m.create_time ? new Date(m.create_time * 1000) : null;
    if (!ts) continue;
    // 只要今天(2026-10-21)柏林时间的
    const iso = ts.toISOString();
    if (DATE_PREFIX && !iso.startsWith(DATE_PREFIX)) continue;
    turns.push({ convId: conv.id, convTitle: conv.json.title, ts, text: t });
  }
}
turns.sort((a, b) => a.ts - b.ts);
console.log('今天 user 轮次总数:', turns.length);

// 收集磁盘上已有段落
function collect(dir) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir)) {
    if (!f.endsWith('.md')) continue;
    const s = readFileSync(join(dir, f), 'utf8');
    // 匹配 ## HH:MM:SS 段 之后的 **原始转录** 块
    const re = /## (\d\d:\d\d:\d\d)　第 \d+ 段[\s\S]*?\*\*原始转录\*\*\n\n([\s\S]*?)\n\n\*\*GPT 回答/g;
    let m;
    while ((m = re.exec(s))) out.push({ file: join(dir, f), time: m[1], transcript: m[2].trim() });
  }
  return out;
}

const archived = [];
for (const entry of readdirSync(ROOT, { withFileTypes: true })) {
  if (!entry.isDirectory()) continue;
  archived.push(...collect(join(ROOT, entry.name, '原始录音')));
  archived.push(...collect(join(ROOT, entry.name)));
}
if (DABAN) archived.push(...collect(join(DABAN, '原始录音')));
console.log('磁盘已有段数:', archived.length);

function norm(s) {
  return s.replace(/\s+/g, '').toLowerCase().slice(0, 120);
}
const archSet = new Set(archived.map((a) => norm(a.transcript)));

const missing = turns.filter((t) => !archSet.has(norm(t.text)));
console.log('漏落轮次:', missing.length);
console.log('');
// 按会话分组
const byConv = {};
for (const t of missing) {
  (byConv[t.convId] ||= []).push(t);
}
for (const [id, arr] of Object.entries(byConv)) {
  const hhmm = arr.map((t) => t.ts.toLocaleTimeString('de-DE', { timeZone: 'Europe/Berlin', hour12: false }).slice(0, 8));
  console.log(id, arr[0].convTitle, '漏', arr.length, ' 时段', hhmm[0], '→', hhmm[hhmm.length - 1]);
  console.log('  样例:', JSON.stringify(arr[0].text.slice(0, 100)));
}
