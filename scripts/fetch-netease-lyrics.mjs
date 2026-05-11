#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";

const draftPath = process.argv[2];
const adapterBase = process.env.NETEASE_ADAPTER_BASE_URL ?? "http://localhost:8090";

if (!draftPath) {
  console.error("Usage: node scripts/fetch-netease-lyrics.mjs <tagged-draft.json>");
  process.exit(1);
}

const draft = JSON.parse(await fs.readFile(draftPath, "utf8"));
const root = process.cwd();
const outDir = path.join(root, "data/taste/lyrics");
await fs.mkdir(outDir, { recursive: true });

const slug = safeSlug(`netease-${draft.playlistId}-${draft.playlistName}`);
const outPath = path.join(outDir, `${slug}.lyrics.json`);
const existing = await readJsonIfExists(outPath);
const lyricsByTrackId = existing?.lyricsByTrackId ?? {};

for (const [index, track] of draft.tracks.entries()) {
  if (Object.prototype.hasOwnProperty.call(lyricsByTrackId, track.id) && lyricsByTrackId[track.id]) {
    continue;
  }

  try {
    const response = await fetch(`${adapterBase}/lyric?id=${encodeURIComponent(track.id)}`);
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
    const body = await response.json();
    lyricsByTrackId[track.id] = cleanupLyric(body.lyrics);
  } catch (error) {
    lyricsByTrackId[track.id] = null;
    console.warn(`lyric unavailable: ${track.title} (${track.id}) ${error.message}`);
  }

  if ((index + 1) % 25 === 0) {
    await write();
    console.log(`saved ${index + 1}/${draft.tracks.length}`);
  }
}

await write();
console.log(outPath);

async function write() {
  await fs.writeFile(
    outPath,
    JSON.stringify(
      {
        importedAt: new Date().toISOString(),
        source: draft.source,
        playlistId: draft.playlistId,
        playlistName: draft.playlistName,
        lyricsByTrackId
      },
      null,
      2
    ) + "\n"
  );
}

function cleanupLyric(value) {
  if (!value || typeof value !== "string") return null;
  const text = value
    .split(/\r?\n/)
    .map((line) => line.replace(/\[[^\]]+]/g, "").trim())
    .filter(Boolean)
    .join("\n");
  return text || null;
}

async function readJsonIfExists(file) {
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch {
    return null;
  }
}

function safeSlug(value) {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 96) || "playlist";
}
