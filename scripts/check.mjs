import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const jsFiles = [
  'extension/background.js',
  'extension/page-hook.js',
  'extension/panel.js',
  'extension/archive-import.js',
  'writer/writer.mjs',
  'tools/mobile-archive/archive.mjs',
  'tools/mobile-archive/cli.mjs',
  'tools/mobile-archive/browser-client.mjs',
  'tools/mobile-archive/export-from-browser.mjs',
  'tools/mobile-archive/local-export.mjs',
  'tools/mobile-archive/local-export.test.mjs',
  'tools/mobile-archive/test.mjs'
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
