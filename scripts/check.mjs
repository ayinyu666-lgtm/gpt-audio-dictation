import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const jsFiles = [
  'extension/background.js',
  'extension/page-hook.js',
  'extension/panel.js',
  'writer/writer.mjs',
  'tools/dev/cdp.mjs',
  'tools/dev/append-conversation.mjs',
  'tools/dev/newtab.mjs',
  'tools/dev/probe.mjs',
  'tools/dev/shot.mjs',
  'tools/dev/synthetic-page-test.js'
];

for (const relative of jsFiles) {
  const result = spawnSync(process.execPath, ['--check', path.join(root, relative)], {
    encoding: 'utf8'
  });
  if (result.status !== 0) {
    process.stderr.write(result.stderr || result.stdout);
    process.exit(result.status || 1);
  }
}

const manifest = JSON.parse(readFileSync(path.join(root, 'extension/manifest.json'), 'utf8'));
if (manifest.manifest_version !== 3) throw new Error('extension must use Manifest V3');
if (!manifest.version || !manifest.name) throw new Error('manifest name/version missing');

console.log(`checks passed: ${jsFiles.length} JavaScript files, manifest ${manifest.version}`);
