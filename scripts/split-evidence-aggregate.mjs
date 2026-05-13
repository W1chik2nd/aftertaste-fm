#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";

const evidencePath = path.resolve(process.argv[2] ?? "data/taste/tracks.evidence.json");
const tasteDir = path.dirname(evidencePath);
const analysis = JSON.parse(await fs.readFile(evidencePath, "utf8"));
const tracks = analysis.tracks ?? [];

for (const track of tracks) {
  if (!track.provider || !track.id) continue;
  const outPath = path.join(tasteDir, "tracks", safeFileStem(track.provider), `${safeFileStem(track.id)}.json`);
  await writeAtomic(outPath, JSON.stringify(track, null, 2) + "\n");
}

console.log(`wrote ${tracks.length} per-track evidence files`);

async function writeAtomic(file, content) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  const part = `${file}.part`;
  await fs.writeFile(part, content);
  await fs.rename(part, file);
}

function safeFileStem(value) {
  return String(value)
    .toLowerCase()
    .replace(/[^a-z0-9\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff\uac00-\ud7af]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 96) || "item";
}
