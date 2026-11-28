import { build } from 'esbuild';
import { mkdir, copyFile, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const output = 'build/app-assets/web';
await mkdir(output, { recursive: true });
await build({ entryPoints: ['src/reading.mjs'], bundle: true, format: 'iife', globalName: 'Reading',
  outfile: 'build/app-assets/reading.js', target: 'es2020', legalComments: 'eof' });
for (const name of ['shell', 'parent', 'programs']) {
  await build({ entryPoints: [`src/${name}.mjs`], bundle: true, format: 'iife',
    globalName: name === 'programs' ? 'PlayerPrograms' : undefined,
    outfile: `${output}/${name}.js`, target: 'es2020', legalComments: 'eof' });
}
for (const file of ['index.html', 'chat.css', 'message.css', 'parent.html']) await copyFile(`src/${file}`, `${output}/${file}`);
for (const [name, path] of [['jquery', 'jquery/dist/jquery.js'], ['lodash', 'lodash/lodash.js'],
  ['vue', 'vue/dist/vue.global.prod.js'], ['vue-router', 'vue-router/dist/vue-router.global.prod.js']])
  await copyFile(`node_modules/${path}`, `${output}/${name}.js`);
await build({ entryPoints: ['src/libraries.mjs'], bundle: true, format: 'iife', outfile: `${output}/libraries.js`, target: 'es2020' });
const names = ['shell.js', 'parent.js', 'programs.js', 'index.html', 'chat.css', 'message.css', 'parent.html', 'jquery.js', 'lodash.js', 'vue.js', 'vue-router.js', 'libraries.js'];
const hashes = Object.fromEntries(await Promise.all(names.map(async name => [name, createHash('sha256').update(await readFile(`${output}/${name}`)).digest('hex')])));
await writeFile(`${output}/manifest.json`, JSON.stringify({ profile: 'player-web-1', files: hashes }, null, 2));
let licenses = '';
for (const [name, file] of [['jquery', 'LICENSE.txt'], ['lodash', 'LICENSE'], ['vue', 'LICENSE'], ['@vue/shared', 'LICENSE'], ['vue-router', 'LICENSE'],
  ['zod', 'LICENSE'], ['yaml', 'LICENSE'], ['showdown', 'LICENSE'], ['parse5', 'LICENSE'], ['parse5/node_modules/entities', 'LICENSE'],
  ['dompurify', 'LICENSE'], ['acorn', 'LICENSE'],
  ['postcss', 'LICENSE'], ['postcss-value-parser', 'LICENSE'], ['nanoid', 'LICENSE'], ['picocolors', 'LICENSE'], ['source-map-js', 'LICENSE']]) {
  licenses += `\n\n${name}\n${await readFile(`node_modules/${name}/${file}`, 'utf8')}`;
}
await writeFile(`${output}/LICENSES.txt`, licenses);
