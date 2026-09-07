import { build } from 'esbuild';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = dirname(fileURLToPath(import.meta.url));
export const output = resolve(root, 'build');
const manifest = JSON.parse(await readFile(resolve(root, 'upstream-lock.json'), 'utf8'));
for (const file of manifest.files) {
    const target = resolve(output, 'upstream', file.target);
    let bytes;
    try { bytes = await readFile(target); } catch { /* Fetch missing pinned source. */ }
    if (!bytes || createHash('sha256').update(bytes).digest('hex') !== file.sha256) {
        const response = await fetch(file.url, { signal: AbortSignal.timeout(30_000) });
        if (!response.ok) throw new Error(`Upstream download failed: ${response.status} ${file.target}`);
        bytes = Buffer.from(await response.arrayBuffer());
        if (createHash('sha256').update(bytes).digest('hex') !== file.sha256) {
            throw new Error(`Upstream source hash mismatch: ${file.target}`);
        }
        await mkdir(dirname(target), { recursive: true });
        await writeFile(target, bytes);
    }
}
const upstream = resolve(output, 'upstream/mvu');
const buildOptions = {
    absWorkingDir: root,
    entryPoints: ['entry.ts'],
    outfile: resolve(output, 'mvu.js'),
    bundle: true,
    platform: 'browser',
    format: 'iife',
    globalName: 'MvuProbe',
    target: 'es2022',
    nodePaths: [resolve(root, 'node_modules')],
    alias: {
        '@/store': resolve(root, 'shims/store.ts'),
        '@/i18n': resolve(root, 'shims/i18n.ts'),
        '@': resolve(upstream, 'src'),
        '@util': resolve(upstream, 'util'),
        'mvu-zod': resolve(output, 'upstream/mvu-zod/mvu_zod.js'),
    },
    plugins: [{
        name: 'pinned-zod-imports',
        setup(builder) {
            const modules = new Map([
                ['https://testingcf.jsdelivr.net/npm/compare-versions@6.1.1/+esm', 'compare-versions'],
                ['https://testingcf.jsdelivr.net/npm/json5@2.2.3/+esm', 'json5'],
                ['https://testingcf.jsdelivr.net/npm/jsonrepair@3.15.0/+esm', 'jsonrepair'],
                ['https://testingcf.jsdelivr.net/npm/zod/v4/core/+esm', 'zod/v4/core'],
                ['https://testingcf.jsdelivr.net/npm/klona@2.0.6/+esm', 'klona'],
            ]);
            builder.onResolve({ filter: /^https?:/ }, async args => {
                const mapped = modules.get(args.path);
                if (!mapped) throw new Error(`Unmapped upstream import: ${args.path}`);
                return builder.resolve(mapped, { resolveDir: root, kind: args.kind });
            });
        },
    }],
};
await build(buildOptions);
const androidAssets = resolve(output, 'android-assets/mvu');
await mkdir(androidAssets, { recursive: true });
await build({
    ...buildOptions,
    entryPoints: ['quickjs-entry.ts'],
    outfile: resolve(androidAssets, 'runtime.js'),
    globalName: 'PlayerMvu',
    minify: true,
});
await writeFile(resolve(androidAssets, 'state-card.json'), await readFile(resolve(root, 'fixtures/state-card.json')));
// The upstream helper has a separate license: include original notices in local test assets.
for (const name of ['mvu', 'mvu-zod']) {
    await writeFile(resolve(androidAssets, `${name}-LICENSE`), await readFile(resolve(output, 'upstream', name, 'LICENSE')));
}
const runtimeBytes = await readFile(resolve(androidAssets, 'runtime.js'));
await writeFile(resolve(androidAssets, 'acorn-LICENSE'), await readFile(resolve(root, 'node_modules/acorn/LICENSE')));
await writeFile(resolve(androidAssets, 'provenance.json'), JSON.stringify({
    mvuCommit: manifest.mvuCommit,
    zodCommit: manifest.zodCommit,
    bundleSha256: createHash('sha256').update(runtimeBytes).digest('hex'),
    bundleBytes: runtimeBytes.length,
}, null, 2) + '\n');
console.log(`Built pinned MVU ${manifest.mvuCommit} with MVU Zod ${manifest.zodCommit}`);
const ejsAssets = resolve(output, 'app-assets/ejs');
await mkdir(ejsAssets, { recursive: true });
await build({ absWorkingDir: root, entryPoints: ['ejs-entry.mjs'], outfile: resolve(ejsAssets, 'runtime.js'),
    bundle: true, platform: 'browser', format: 'iife', globalName: 'PlayerEjs', target: 'es2022', minify: true });
await writeFile(resolve(ejsAssets, 'ejs-LICENSE'), await readFile(resolve(root, 'node_modules/ejs/LICENSE')));
await writeFile(resolve(ejsAssets, 'lodash-LICENSE'), await readFile(resolve(root, 'node_modules/lodash/LICENSE')));
await writeFile(resolve(ejsAssets, 'provenance.json'), JSON.stringify({ ejs: '3.1.10',
    hostReferenceCommit: 'd6f520d149aba146305b0b781ddd691d449c28d2',
    bundleSha256: createHash('sha256').update(await readFile(resolve(ejsAssets, 'runtime.js'))).digest('hex'),
}, null, 2) + '\n');
// Application assets contain only the framework and notices, never sample/card programs.
const appAssets = resolve(output, 'app-assets/mvu');
await mkdir(appAssets, { recursive: true });
for (const name of ['runtime.js', 'provenance.json', 'mvu-LICENSE', 'mvu-zod-LICENSE', 'acorn-LICENSE']) {
    await writeFile(resolve(appAssets, name), await readFile(resolve(androidAssets, name)));
}
