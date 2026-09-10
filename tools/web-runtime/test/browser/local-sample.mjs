import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';

// Optional, unmodified local originals; no community text is copied into fixtures.
export async function localSample(hash) {
  const root = new URL('../../../../source/', import.meta.url);
  for (const name of await readdir(root).catch(() => [])) {
    if (!/\.(json|png)$/i.test(name)) continue;
    const bytes = await readFile(new URL(encodeURIComponent(name), root));
    if (createHash('sha256').update(bytes).digest('hex') !== hash) continue;
    if (name.endsWith('.json')) { const card = JSON.parse(bytes); return card.data ?? card; }
    let result;
    for (let offset = 8; offset + 12 <= bytes.length;) {
      const size = bytes.readUInt32BE(offset), type = bytes.toString('ascii', offset + 4, offset + 8);
      const chunk = bytes.subarray(offset + 8, offset + 8 + size); offset += size + 12;
      if (type !== 'tEXt') continue;
      const split = chunk.indexOf(0), key = chunk.toString('ascii', 0, split);
      if (key === 'chara' || key === 'ccv3') {
        const card = JSON.parse(Buffer.from(chunk.subarray(split + 1).toString(), 'base64'));
        result = card.data ?? card;
      }
    }
    return result;
  }
}
