import fs from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const root = path.resolve(import.meta.dirname, '..');
const directory = path.join(root, 'work', 'downloads');
const list = process.argv[2] ? path.resolve(process.argv[2]) : path.join(root, 'scripts', 'dependencies.json');
const packages = JSON.parse(fs.readFileSync(list, 'utf8'));
fs.mkdirSync(directory, { recursive: true });

async function download(pkg) {
  const name = pkg.name.replaceAll(';', '-');
  const target = path.join(directory, name + '.zip');
  if (fs.existsSync(target)) {
    console.log('cached ' + name);
    return;
  }
  const staging = target + '.part';
  console.log('downloading ' + name);
  const partialSize = fs.existsSync(staging) ? fs.statSync(staging).size : 0;
  const response = await fetch(pkg.url, {
    signal: AbortSignal.timeout(600000),
    headers: partialSize ? { Range: 'bytes=' + partialSize + '-' } : {},
  });
  if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + pkg.url);
  await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(staging, {
    flags: response.status === 206 && partialSize ? 'a' : 'w',
  }));
  if (pkg.size && fs.statSync(staging).size !== pkg.size) throw new Error('Archive size differs: ' + name);
  fs.renameSync(staging, target);
  console.log('downloaded ' + name);
}

// Two streams keep disk and network pressure bounded while downloading the NDK.
const queue = packages.slice();
await Promise.all([0, 1].map(async () => {
  while (queue.length) {
    const pkg = queue.shift();
    for (let attempt = 1; ; attempt++) {
      try { await download(pkg); break; }
      catch (error) {
        if (attempt >= 3) throw error;
        console.log('retry ' + pkg.name + ': ' + error.message);
        await new Promise(resolve => setTimeout(resolve, 1500));
      }
    }
  }
}));
