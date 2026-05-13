#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { buildTasteProfile } from "./build-taste-profile.mjs";

const LISTENING_WEIGHT_SATURATION = 80;

const draftPath = process.argv[2];

if (!draftPath) {
  console.error("Usage: node scripts/build-evidence-analysis.mjs <tagged-draft.json>");
  process.exit(1);
}

const draft = JSON.parse(await fs.readFile(draftPath, "utf8"));
const lyricsPath = path.join(
  process.cwd(),
  "data/taste/lyrics",
  `${safeSlug(`netease-${draft.playlistId}-${draft.playlistName}`)}.lyrics.json`
);
const lyrics = await readJsonIfExists(lyricsPath);
const lyricsByTrackId = lyrics?.lyricsByTrackId ?? {};

const tracks = draft.tracks.map((track) => {
  const lyric = lyricsByTrackId[track.id] ?? null;
  return toEvidenceTrack(track, lyric);
});

const output = {
  version: 2,
  generatedAt: new Date().toISOString(),
  source: draft.source,
  playlistId: draft.playlistId,
  playlistName: draft.playlistName,
  analysisMode: "evidence-v2-lyrics-metadata",
  tracks
};

const outPath = path.join(process.cwd(), "data/taste/tracks.evidence.json");
await fs.writeFile(outPath, JSON.stringify(output, null, 2) + "\n");
await buildTasteProfile({ evidencePath: outPath, tasteDir: path.dirname(outPath) });
console.log(outPath);

function toEvidenceTrack(track, lyric) {
  const lyricThemes = inferLyricThemes(lyric);
  const language = languageFor(track, lyric);
  const evidence = {
    metadata: true,
    lyrics: Boolean(lyric),
    audioFeatures: false,
    userBehavior: Number.isFinite(Number(track.playCount)),
    manual: false,
    model: false
  };
  const moodTags = [];
  const contextTags = [
    { tag: "imported-netease", confidence: 1, evidence: ["metadata"] },
    { tag: "playlist-reykjavik", confidence: 0.95, evidence: ["metadata"] }
  ];
  const soundTags = [];
  const useTags = [];

  if (language.value !== "unknown") {
    contextTags.push({
      tag: language.value,
      confidence: language.confidence,
      evidence: language.evidence
    });
  }

  for (const theme of lyricThemes) {
    const item = { tag: theme, confidence: 0.78, evidence: ["lyrics"] };
    if (["late-night", "rainy"].includes(theme)) useTags.push(item);
    else moodTags.push(item);
  }

  const needsReview = !lyric || lyricThemes.length === 0;

  return {
    provider: track.provider,
    id: track.id,
    title: track.title,
    artist: track.artist,
    album: track.album,
    durationMs: track.durationMs,
    coverUrl: track.coverUrl,
    playCount: track.playCount ?? null,
    language,
    moodTags: dedupeTags(moodTags),
    contextTags: dedupeTags(contextTags),
    soundTags: dedupeTags(soundTags),
    useTags: dedupeTags(useTags),
    scores: inferScores(lyric, lyricThemes, track.playCount),
    evidence,
    lyricExcerpt: null,
    notes: buildNotes(lyric, lyricThemes, needsReview),
    needsReview
  };
}

function inferLyricThemes(lyric) {
  if (!lyric) return [];
  const text = lyric.toLowerCase();
  const tags = new Set();
  const includesAny = (items) => items.some((item) => text.includes(item.toLowerCase()));
  if (includesAny(["再见", "离开", "分开", "let go", "goodbye", "不见", "走后"])) tags.add("separation");
  if (includesAny(["想你", "想见", "miss", "回忆", "remember", "从前"])) tags.add("memory");
  if (includesAny(["爱", "love", "喜欢", "心动"])) tags.add("love");
  if (includesAny(["孤独", "寂寞", "lonely", "alone"])) tags.add("loneliness");
  if (includesAny(["雨", "rain"])) tags.add("rainy");
  if (includesAny(["夜", "night", "晚安"])) tags.add("late-night");
  if (includesAny(["快乐", "笑", "sun", "summer", "光"])) tags.add("bright");
  return [...tags];
}

function languageFor(track, lyric) {
  const lyricBody = stripLyricCredits(lyric);
  const cjkCount = countMatches(lyricBody, /[\u3400-\u9fff]/g);
  const latinCount = countMatches(lyricBody, /[a-zA-Z]/g);
  const titleArtist = `${track.title ?? ""} ${track.artist ?? ""}`;
  const hasCjkMetadata = /[\u3400-\u9fff]/.test(titleArtist);
  const hasHangulMetadata = /[\uac00-\ud7af]/.test(titleArtist);
  const hasKanaMetadata = /[\u3040-\u30ff]/.test(titleArtist);

  if (lyricBody && latinCount >= 80 && latinCount > cjkCount * 8) {
    return { value: "en", confidence: 0.88, evidence: ["lyrics", "metadata"] };
  }
  if (lyricBody && cjkCount >= 12 && cjkCount > latinCount * 0.18) {
    return {
      value: track.language && track.language !== "unknown" ? track.language : "zh-CN",
      confidence: 0.9,
      evidence: ["metadata", "lyrics"]
    };
  }
  if (hasHangulMetadata) return { value: "ko", confidence: 0.68, evidence: ["metadata"] };
  if (hasKanaMetadata) return { value: "ja", confidence: 0.68, evidence: ["metadata"] };
  if (!hasCjkMetadata && /[a-zA-Z]/.test(titleArtist)) {
    return { value: "en", confidence: lyricBody ? 0.7 : 0.55, evidence: lyricBody ? ["lyrics", "metadata"] : ["metadata"] };
  }
  if (track.language && track.language !== "unknown") {
    return { value: track.language, confidence: 0.62, evidence: ["metadata"] };
  }
  return { value: "unknown", confidence: 0.25, evidence: ["metadata"] };
}

function stripLyricCredits(lyric) {
  if (!lyric || typeof lyric !== "string") return "";
  return lyric
    .split(/\n+/)
    .map((line) => line.trim())
    .filter((line) => line && !/^(作词|作曲|编曲|制作人|词|曲|OP|SP|Producer|Composer|Lyricist)\b/i.test(line))
    .join("\n");
}

function countMatches(value, pattern) {
  return value.match(pattern)?.length ?? 0;
}

function inferScores(lyric, themes, playCount) {
  const has = (tag) => themes.includes(tag);
  const energy = has("bright") ? 0.56 : has("late-night") ? 0.34 : 0.45;
  const valence = has("separation") || has("loneliness") ? 0.32 : has("bright") || has("love") ? 0.58 : 0.5;
  const night = has("late-night") || has("memory") || has("loneliness") ? 0.72 : 0.5;
  const coding = has("late-night") && !has("separation") ? 0.58 : 0.42;
  const skipRisk = !lyric ? 0.35 : themes.length === 0 ? 0.28 : 0.18;
  const confidence = lyric ? 0.42 : 0.18;
  const listeningWeight = Math.min(1, Math.max(0, Number(playCount ?? 0) / LISTENING_WEIGHT_SATURATION));
  return {
    energy: evidenceScore(energy, confidence, lyric ? ["lyrics"] : ["metadata"]),
    valence: evidenceScore(valence, confidence, lyric ? ["lyrics"] : ["metadata"]),
    night: evidenceScore(night, confidence, lyric ? ["lyrics"] : ["metadata"]),
    coding: evidenceScore(coding, 0.25, lyric ? ["lyrics"] : ["metadata"]),
    skipRisk: evidenceScore(skipRisk, 0.25, lyric ? ["lyrics"] : ["metadata"]),
    danceability: evidenceScore(0.5, 0.0, []),
    acousticness: evidenceScore(0.5, 0.0, []),
    lyricDensity: evidenceScore(lyric ? 0.65 : 0.0, lyric ? 0.25 : 0.0, lyric ? ["lyrics"] : []),
    vocalPresence: evidenceScore(lyric ? 0.8 : 0.0, lyric ? 0.25 : 0.0, lyric ? ["lyrics"] : []),
    familiarity: evidenceScore(listeningWeight || 0.5, listeningWeight ? 0.72 : 0.0, listeningWeight ? ["user_behavior"] : []),
    intensity: evidenceScore(energy, confidence, lyric ? ["lyrics"] : ["metadata"])
  };
}

function evidenceScore(value, confidence, evidence) {
  return {
    value: Number(value.toFixed(2)),
    confidence,
    evidence
  };
}

function dedupeTags(tags) {
  const seen = new Map();
  for (const tag of tags) {
    const existing = seen.get(tag.tag);
    if (!existing || existing.confidence < tag.confidence) seen.set(tag.tag, tag);
  }
  return [...seen.values()].slice(0, 16);
}

function buildNotes(lyric, themes, needsReview) {
  const parts = [];
  if (lyric) parts.push(`Lyrics are available; detected lyric themes: ${themes.length ? themes.join(", ") : "none"}.`);
  else parts.push("No lyric evidence yet; keep confidence limited and review before relying on this track.");
  if (needsReview) parts.push("Needs review before being treated as high-confidence taste data.");
  return parts.join(" ");
}

async function readJsonIfExists(file) {
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch (error) {
    if (error?.code !== "ENOENT") console.warn(`Could not read JSON file ${file}: ${error.message}`);
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
