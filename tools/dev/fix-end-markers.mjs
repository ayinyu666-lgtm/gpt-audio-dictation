/* 一次性修补：合并脚本里被 shell 吃掉前缀的 gad-save-end 标记。 */
import { readFileSync, writeFileSync } from 'node:fs';
const p = 'E:/AI/工作目录/硕士-系统控制-大作业/原始录音/2026-10-21 周三 17-00.md';
let s = readFileSync(p, 'utf8');
// 形如 "s-https:__chatgpt..._2 -->" 的孤立尾部 -> 补回 "<!-- gad-save-end:"
s = s.replace(/^(s-https:[^\n]*?)\s*-->$/gm, (m, inner) => '<!-- gad-save-end:' + inner.trim() + ' -->');
// 头部去掉多余空行
s = s.replace(/^\n+/, '');
writeFileSync(p, s, 'utf8');
const bad = (s.match(/^s-https:/gm) || []).length;
const ends = (s.match(/^<!-- gad-save-end:/gm) || []).length;
const starts = (s.match(/^<!-- gad-save-start:/gm) || []).length;
console.log(JSON.stringify({ remainingBad: bad, starts, ends }));
