#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { buildTasteProfile } from "./build-taste-profile.mjs";

await loadDotEnv(path.join(process.cwd(), ".env"));

const args = parseArgs(process.argv.slice(2));
const draftPath = args._[0];
const apiKey = process.env.OPENAI_API_KEY;
const model = args.model ?? process.env.OPENAI_ANALYSIS_MODEL ?? process.env.OPENAI_MODEL ?? "gpt-5.2";
const batchSize = Number(args["batch-size"] ?? process.env.ANALYSIS_BATCH_SIZE ?? 12);
const limit = args.limit ? Number(args.limit) : null;

if (!draftPath || args.help) {
  console.error("Usage: node scripts/analyze-playlist-openai.mjs <tagged-draft.json> [--batch-size 12] [--limit 20] [--out data/taste/tracks.evidence.json]");
  process.exit(args.help ? 0 : 1);
}

if (!apiKey) {
  console.error("OPENAI_API_KEY is required for OpenAI playlist analysis.");
  process.exit(1);
}

const draft = JSON.parse(await fs.readFile(draftPath, "utf8"));
const slug = safeSlug(`netease-${draft.playlistId}-${draft.playlistName}`);
const lyricsPath = path.join(process.cwd(), "data/taste/lyrics", `${slug}.lyrics.json`);
const lyrics = await readJsonIfExists(lyricsPath);
const lyricsByTrackId = lyrics?.lyricsByTrackId ?? {};
const selectedTracks = limit ? draft.tracks.slice(0, limit) : draft.tracks;
const outPath = path.resolve(args.out ?? "data/taste/tracks.evidence.json");
const partialPath = `${outPath}.partial`;
const analyzed = [];

for (let start = 0; start < selectedTracks.length; start += batchSize) {
  const batch = selectedTracks.slice(start, start + batchSize);
  const batchResult = await analyzeBatch(batch);
  const byId = new Map(batchResult.tracks.map((track) => [track.id, track]));

  for (const track of batch) {
    const analysis = byId.get(track.id);
    analyzed.push(toEvidenceTrack(track, analysis, lyricsByTrackId[track.id] ?? null));
  }

  await write(partialPath, analyzed);
  console.log(`analyzed ${Math.min(start + batch.length, selectedTracks.length)}/${selectedTracks.length}`);
}

await write(outPath, analyzed);
await buildTasteProfile({ evidencePath: outPath, tasteDir: path.dirname(outPath) });
console.log(outPath);

async function analyzeBatch(batch) {
  const input = batch.map((track) => ({
    id: track.id,
    title: track.title,
    artist: track.artist,
    album: track.album,
    durationMs: track.durationMs,
    provider: track.provider,
    lyrics: truncateLyrics(lyricsByTrackId[track.id])
  }));

  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      "OpenAI-Beta": "responses=v1"
    },
    body: JSON.stringify({
      model,
      input: [
        {
          role: "system",
          content: analysisSystemPrompt()
        },
        {
          role: "user",
          content: JSON.stringify({
            playlist: {
              provider: "netease",
              id: draft.playlistId,
              name: draft.playlistName,
              source: draft.source
            },
            tracks: input
          })
        }
      ],
      text: {
        format: responseFormatSchema()
      },
      max_output_tokens: 5000
    })
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`OpenAI analysis failed: ${response.status} ${response.statusText}\n${text}`);
  }

  const body = await response.json();
  const outputText = extractOutputText(body);
  if (!outputText) throw new Error("OpenAI response did not contain output_text.");
  return JSON.parse(outputText);
}

function analysisSystemPrompt() {
  return `
You are Aftertaste FM's offline music analyst.
Analyze tracks for a private AI radio using a general taxonomy, not a taxonomy tailored to this playlist.

You may use title, artist, album, provider metadata, playlist context, and lyrics.
You do not have audio. Be conservative about sonic features unless metadata/lyrics clearly support them.
Prefer unknown or low confidence over pretending.
Do not quote lyrics. Do not write long prose. Use lowercase kebab-case tags.

Tag buckets:
- moodTags: emotional or lyrical themes, e.g. tender, wistful, melancholic, heartbreak, longing, nostalgia, serene, warm, lonely, tense, angry, defiant, euphoric, playful, sensual, hopeful, grief, comfort, romantic, bittersweet, dark, bright.
- contextTags: catalog identity or external meaning, e.g. mandopop, cantopop, western-pop, k-pop, j-pop, chinese-indie, city-memory, karaoke-memory, classic, new-release, live, cover, soundtrack, game-soundtrack, anime, mainstream, deep-cut, playlist-anchor, transition-track, outlier.
- soundTags: genre, arrangement, instrumentation, production, vocal style, e.g. ballad, rnb, hip-hop, rap, folk, indie-rock, alt-rock, pop-rock, edm, house, synth-pop, bedroom-pop, lo-fi, acoustic, piano, guitar-led, orchestral, jazz, classical, metal, punk, trap, ambient, instrumental, vocal-pop, duet.
- useTags: practical listening situations, e.g. late-night, coding, focus, study, commute, driving, walking, rainy, sleep, morning, workout, party, karaoke, background, foreground, recovery, comfort-listening, social, solo, wind-down, energy-lift, nostalgia-session.

Scores are 0-1 with confidence:
energy, valence, night, coding, skipRisk, danceability, acousticness, lyricDensity, vocalPresence, familiarity, intensity.

Evidence strings should come from: metadata, lyrics, playlist_context, model_inference.
Set needsReview true if lyrics are missing, language is uncertain, score confidence is low, or the track has ambiguous/insufficient evidence.
Return JSON only.
`.trim();
}

function toEvidenceTrack(track, analysis, lyric) {
  const fallback = fallbackAnalysis(track, lyric);
  const item = analysis ?? fallback;
  return {
    provider: track.provider,
    id: track.id,
    title: track.title,
    artist: track.artist,
    album: track.album,
    durationMs: track.durationMs,
    coverUrl: track.coverUrl,
    language: item.language ?? fallback.language,
    moodTags: cleanTags(item.moodTags),
    contextTags: cleanTags(item.contextTags),
    soundTags: cleanTags(item.soundTags),
    useTags: cleanTags(item.useTags),
    scores: fillScores(item.scores ?? {}),
    evidence: {
      metadata: true,
      lyrics: Boolean(lyric),
      audioFeatures: false,
      userBehavior: false,
      manual: false,
      model: Boolean(analysis)
    },
    lyricExcerpt: null,
    notes: typeof item.notes === "string" ? item.notes.slice(0, 500) : null,
    needsReview: Boolean(item.needsReview)
  };
}

function fallbackAnalysis(track, lyric) {
  const language = lyric && /[\u3400-\u9fff]/.test(lyric) ? "zh-CN" : "unknown";
  return {
    language: evidenceString(language, lyric ? 0.55 : 0.25, lyric ? ["lyrics"] : ["metadata"]),
    moodTags: [],
    contextTags: [
      { tag: "imported-netease", confidence: 1, evidence: ["metadata"] }
    ],
    soundTags: [],
    useTags: [],
    scores: {},
    notes: "Fallback analysis; OpenAI did not return a track-level result.",
    needsReview: true
  };
}

function fillScores(scores) {
  const names = ["energy", "valence", "night", "coding", "skipRisk", "danceability", "acousticness", "lyricDensity", "vocalPresence", "familiarity", "intensity"];
  return Object.fromEntries(names.map((name) => [name, evidenceNumber(scores[name], name === "skipRisk" ? 0.2 : 0.5)]));
}

function cleanTags(tags) {
  if (!Array.isArray(tags)) return [];
  return tags
    .filter((tag) => tag && typeof tag.tag === "string")
    .map((tag) => ({
      tag: kebab(tag.tag).slice(0, 60),
      confidence: clampNumber(tag.confidence, 0, 1, 0.3),
      evidence: cleanEvidence(tag.evidence)
    }))
    .filter((tag) => tag.tag)
    .slice(0, 12);
}

function evidenceString(value, confidence, evidence) {
  return {
    value: typeof value === "string" && value ? value.slice(0, 32) : "unknown",
    confidence: clampNumber(confidence, 0, 1, 0),
    evidence: cleanEvidence(evidence)
  };
}

function evidenceNumber(value, fallback) {
  if (value && typeof value === "object") {
    return {
      value: clampNumber(value.value, 0, 1, fallback),
      confidence: clampNumber(value.confidence, 0, 1, 0),
      evidence: cleanEvidence(value.evidence)
    };
  }
  return { value: fallback, confidence: 0, evidence: [] };
}

function cleanEvidence(evidence) {
  const allowed = new Set(["metadata", "lyrics", "playlist_context", "model_inference"]);
  if (!Array.isArray(evidence)) return [];
  return [...new Set(evidence.filter((item) => allowed.has(item)))];
}

function responseFormatSchema() {
  const evidenceValueString = {
    type: "object",
    additionalProperties: false,
    required: ["value", "confidence", "evidence"],
    properties: {
      value: { type: "string" },
      confidence: { type: "number" },
      evidence: { type: "array", items: { type: "string" } }
    }
  };
  const evidenceValueNumber = {
    type: "object",
    additionalProperties: false,
    required: ["value", "confidence", "evidence"],
    properties: {
      value: { type: "number" },
      confidence: { type: "number" },
      evidence: { type: "array", items: { type: "string" } }
    }
  };
  const tag = {
    type: "object",
    additionalProperties: false,
    required: ["tag", "confidence", "evidence"],
    properties: {
      tag: { type: "string" },
      confidence: { type: "number" },
      evidence: { type: "array", items: { type: "string" } }
    }
  };
  const scoreProperties = Object.fromEntries(
    ["energy", "valence", "night", "coding", "skipRisk", "danceability", "acousticness", "lyricDensity", "vocalPresence", "familiarity", "intensity"]
      .map((name) => [name, evidenceValueNumber])
  );
  return {
    type: "json_schema",
    name: "aftertaste_playlist_analysis",
    strict: true,
    schema: {
      type: "object",
      additionalProperties: false,
      required: ["tracks"],
      properties: {
        tracks: {
          type: "array",
          items: {
            type: "object",
            additionalProperties: false,
            required: ["id", "language", "moodTags", "contextTags", "soundTags", "useTags", "scores", "notes", "needsReview"],
            properties: {
              id: { type: "string" },
              language: evidenceValueString,
              moodTags: { type: "array", items: tag },
              contextTags: { type: "array", items: tag },
              soundTags: { type: "array", items: tag },
              useTags: { type: "array", items: tag },
              scores: {
                type: "object",
                additionalProperties: false,
                required: Object.keys(scoreProperties),
                properties: scoreProperties
              },
              notes: { type: "string" },
              needsReview: { type: "boolean" }
            }
          }
        }
      }
    }
  };
}

async function write(file, tracks) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(
    file,
    JSON.stringify(
      {
        version: 2,
        generatedAt: new Date().toISOString(),
        source: draft.source,
        playlistId: draft.playlistId,
        playlistName: draft.playlistName,
        analysisMode: "openai-general-music-evidence-v1",
        tracks
      },
      null,
      2
    ) + "\n"
  );
}

function extractOutputText(element) {
  if (!element || typeof element !== "object") return null;
  if (typeof element.output_text === "string") return element.output_text;
  if (element.type === "output_text" && typeof element.text === "string") return element.text;
  if (Array.isArray(element)) {
    for (const child of element) {
      const found = extractOutputText(child);
      if (found) return found;
    }
  } else {
    for (const child of Object.values(element)) {
      const found = extractOutputText(child);
      if (found) return found;
    }
  }
  return null;
}

function truncateLyrics(value) {
  if (!value || typeof value !== "string") return null;
  return value.split(/\n+/).slice(0, 80).join("\n").slice(0, 6000);
}

function parseArgs(items) {
  const parsed = { _: [] };
  for (let index = 0; index < items.length; index += 1) {
    const item = items[index];
    if (!item.startsWith("--")) {
      parsed._.push(item);
      continue;
    }
    const key = item.slice(2);
    const next = items[index + 1];
    if (!next || next.startsWith("--")) {
      parsed[key] = true;
    } else {
      parsed[key] = next;
      index += 1;
    }
  }
  return parsed;
}

async function loadDotEnv(file) {
  try {
    const content = await fs.readFile(file, "utf8");
    for (const line of content.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const match = trimmed.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
      if (!match || process.env[match[1]]) continue;
      process.env[match[1]] = match[2].replace(/^["']|["']$/g, "");
    }
  } catch {
    // .env is optional.
  }
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

function kebab(value) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, "-")
    .replace(/[^a-z0-9\u4e00-\u9fa5-]+/g, "")
    .replace(/-+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.min(max, Math.max(min, number));
}
