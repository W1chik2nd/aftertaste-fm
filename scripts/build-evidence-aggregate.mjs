#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { buildTasteProfile } from "./build-taste-profile.mjs";

const EVIDENCE_SCHEMA_VERSION = 2;

const tasteDir = path.resolve(process.argv[2] ?? "data/taste");
const tracksDir = path.join(tasteDir, "tracks");
const outPath = path.join(tasteDir, "tracks.evidence.json");

const tracks = await readTrackFiles(tracksDir);
const aggregate = {
  version: EVIDENCE_SCHEMA_VERSION,
  generatedAt: new Date().toISOString(),
  source: "data/taste/tracks",
  playlistId: "library",
  playlistName: "Aftertaste Library",
  analysisMode: "evidence-v2-per-track",
  tracks
};

await writeAtomic(outPath, JSON.stringify(aggregate, null, 2) + "\n");
await buildTasteProfile({ evidencePath: outPath, tasteDir });
console.log(outPath);

async function readTrackFiles(root) {
  if (!(await exists(root))) return [];
  const files = await collectJsonFiles(root);
  const parsed = [];
  for (const file of files) {
    parsed.push(JSON.parse(await fs.readFile(file, "utf8")));
  }
  return parsed.sort((a, b) =>
    `${a.artist ?? ""}\u0000${a.title ?? ""}\u0000${a.id ?? ""}`.localeCompare(
      `${b.artist ?? ""}\u0000${b.title ?? ""}\u0000${b.id ?? ""}`
    )
  );
}

async function collectJsonFiles(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const absolute = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...await collectJsonFiles(absolute));
    } else if (entry.isFile() && entry.name.endsWith(".json")) {
      files.push(absolute);
    }
  }
  return files;
}

async function exists(file) {
  try {
    await fs.access(file);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

async function writeAtomic(file, content) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  const part = `${file}.part`;
  await fs.writeFile(part, content);
  await fs.rename(part, file);
}
