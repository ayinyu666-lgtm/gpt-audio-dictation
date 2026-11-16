#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { init, submit, processJob, resume } from './archive.mjs';

function usage() {
  throw new Error('用法：node cli.mjs init --root <绝对路径> | import --root <绝对路径> --snapshot <JSON路径> | resume --root <绝对路径>');
}
try {
  const args = process.argv.slice(2);
  const command = args.shift();
  const options = {};
  while (args.length) {
    const key = args.shift();
    if (!['--root', '--snapshot'].includes(key) || !args.length || options[key]) usage();
    options[key] = args.shift();
  }
  if (!options['--root'] || !path.isAbsolute(options['--root'])) usage();
  const root = options['--root'];
  let result;
  if (command === 'init' && !options['--snapshot']) {
    init(root); result = { status: 'initialized', root };
  } else if (command === 'import' && options['--snapshot']) {
    const input = JSON.parse(readFileSync(options['--snapshot'], 'utf8'));
    const job = submit(root, input);
    result = { ...processJob(root, job.jobId), queued: job.queued };
  } else if (command === 'resume' && !options['--snapshot']) {
    result = resume(root);
    if (result.some((item) => item.status !== 'complete')) process.exitCode = 1;
  } else usage();
  console.log(JSON.stringify(result, null, 2));
} catch (e) {
  console.error(JSON.stringify({ status: 'failed', code: e.code || 'error', error: e.message }));
  process.exitCode = 1;
}
