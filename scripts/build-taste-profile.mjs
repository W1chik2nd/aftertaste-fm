#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

export async function buildTasteProfile({
  evidencePath = path.join(process.cwd(), "data/taste/tracks.evidence.json"),
  tasteDir = path.join(process.cwd(), "data/taste")
} = {}) {
  const analysis = JSON.parse(await fs.readFile(evidencePath, "utf8"));
  const tracks = analysis.tracks ?? [];
  await fs.mkdir(tasteDir, { recursive: true });

  const languageCounts = countBy(tracks, (track) => track.language?.value ?? "unknown");
  const artistCounts = countBy(tracks, (track) => track.artist ?? "unknown");
  const moodTags = countTags(tracks, "moodTags");
  const contextTags = countTags(tracks, "contextTags");
  const soundTags = countTags(tracks, "soundTags");
  const useTags = countTags(tracks, "useTags");
  const allTags = mergeCounts(moodTags, contextTags, soundTags, useTags);
  const averages = averageScores(tracks);
  const preferredTags = pickPreferredTags({ moodTags, soundTags, useTags, contextTags });
  const avoidTags = pickAvoidTags(tracks);
  const artistAliases = buildArtistAliases(tracks);
  const rules = buildRules(preferredTags, avoidTags, { moodTags, soundTags, useTags, contextTags }, artistAliases);
  const profile = renderProfile({
    analysis,
    tracks,
    languageCounts,
    artistCounts,
    moodTags,
    contextTags,
    soundTags,
    useTags,
    allTags,
    averages,
    preferredTags,
    avoidTags
  });

  const profilePath = path.join(tasteDir, "profile.md");
  const rulesPath = path.join(tasteDir, "rules.json");
  await fs.writeFile(profilePath, profile);
  await fs.writeFile(rulesPath, JSON.stringify(rules, null, 2) + "\n");
  return { profilePath, rulesPath };
}

function renderProfile({
  analysis,
  tracks,
  languageCounts,
  artistCounts,
  moodTags,
  contextTags,
  soundTags,
  useTags,
  allTags,
  averages,
  preferredTags,
  avoidTags
}) {
  const generatedAt = new Date().toISOString();
  const trackCount = tracks.length;
  const needsReview = tracks.filter((track) => track.needsReview).length;
  const modelEvidence = tracks.filter((track) => track.evidence?.model).length;
  const lyricEvidence = tracks.filter((track) => track.evidence?.lyrics).length;

  return `# Aftertaste FM Taste Profile

Generated at: ${generatedAt}

Source: ${analysis.playlistName ?? "imported library"} (${analysis.playlistId ?? "unknown"})
Tracks analyzed: ${trackCount}
Analysis mode: ${analysis.analysisMode ?? "unknown"}

## Runtime Summary

This file is generated from \`tracks.evidence.json\` and is intended for the runtime LLM planner. It should summarize durable taste signals, not store lyrics or one-off commentary.

- Evidence coverage: ${lyricEvidence}/${trackCount} tracks with lyrics, ${modelEvidence}/${trackCount} tracks with model analysis.
- Review queue: ${needsReview}/${trackCount} tracks still marked \`needsReview\`.
- Default candidate tags: ${preferredTags.length ? preferredTags.join(", ") : "none yet"}.
- Avoid by default: ${avoidTags.length ? avoidTags.join(", ") : "none yet"}.

## Language Mix

${formatCounts(languageCounts, "language")}

## Core Artists

${formatCounts(artistCounts, "artist", 15)}

## Strongest Tags

Mood:
${formatCounts(moodTags, "tag", 14)}

Sound:
${formatCounts(soundTags, "tag", 14)}

Use:
${formatCounts(useTags, "tag", 14)}

Context:
${formatCounts(contextTags, "tag", 14)}

All tags:
${formatCounts(allTags, "tag", 20)}

## Average Scores

${Object.entries(averages)
  .map(([name, value]) => `- ${name}: ${value.toFixed(2)}`)
  .join("\n")}

## Planner Guidance

- Use \`tracks.evidence.json\` as the source of truth for track-level selection.
- Prefer high-confidence tags and scores; treat low-confidence tracks as candidates for review.
- Keep runtime prompts compact: selected candidates, profile summary, rules, and no full lyrics.
- Build segments from groups of tracks. Do not make the host speak before every song.
- Use \`avoidTags\` as default friction, not a permanent ban. User intent can override it.
`;
}

function buildRules(preferredTags, avoidTags, buckets, artistAliases) {
  const has = (tag) =>
    (buckets.moodTags.get(tag) ?? 0) +
      (buckets.soundTags.get(tag) ?? 0) +
      (buckets.useTags.get(tag) ?? 0) +
      (buckets.contextTags.get(tag) ?? 0) >
    0;

  const moodAliases = {
    quiet: ["soft", "calm", "late-night", "acoustic", "ambient"].filter(has),
    coding: ["coding", "focus", "background", "low-lyric-density", "late-night"].filter(has),
    "less sad": ["warm", "comfort", "hopeful", "bright", "romantic"].filter(has),
    rainy: ["rainy", "late-night", "wistful", "melancholic"].filter(has),
    commute: ["commute", "driving", "walking", "midtempo", "city-memory"].filter(has),
    energetic: ["energy-lift", "workout", "party", "edm", "dance"].filter(has),
    nostalgic: ["nostalgia", "nostalgia-session", "classic", "karaoke-memory", "city-memory"].filter(has)
  };

  return {
    version: 1,
    defaultCandidateLimit: 72,
    segmentTrackCount: 3,
    preferredTags,
    avoidTags,
    moodAliases: Object.fromEntries(Object.entries(moodAliases).filter(([, tags]) => tags.length > 0)),
    artistAliases
  };
}

function buildArtistAliases(tracks) {
  const artistCounts = countBy(tracks, (track) => track.artist ?? "");
  const aliases = new Map();
  for (const [artist, count] of artistCounts.entries()) {
    if (!artist || count < 2) continue;
    for (const part of splitArtists(artist)) {
      const key = normalizeForMatch(part);
      if (key.length < 3) continue;
      const existing = aliases.get(key) ?? new Set();
      existing.add(part);
      aliases.set(key, existing);
    }
  }

  return Object.fromEntries(
    [...aliases.entries()]
      .map(([alias, values]) => [alias, [...values].sort()])
      .filter(([, values]) => values.length > 0)
      .sort(([a], [b]) => a.localeCompare(b))
  );
}

function splitArtists(artist) {
  return artist
    .split(/,|，|&|、|\bfeat\.\b|\bft\.\b|\band\b/i)
    .map((value) => value.trim())
    .filter(Boolean);
}

function normalizeForMatch(value) {
  return value.toLowerCase().replace(/[\s.'’\-_/()（）·]+/g, "");
}

function pickPreferredTags({ moodTags, soundTags, useTags, contextTags }) {
  void contextTags;
  const ranked = [
    ...topEntries(useTags, 8),
    ...topEntries(moodTags, 8),
    ...topEntries(soundTags, 8)
  ]
    .map(([tag]) => tag)
    .filter(isRuntimePreferenceTag);
  return [...new Set(ranked)].slice(0, 12);
}

function isRuntimePreferenceTag(tag) {
  if (!tag || tag === "unknown") return false;
  if (tag.startsWith("playlist-")) return false;
  if (tag.startsWith("imported-")) return false;
  if (/^[a-z]{2}(-[A-Z]{2})?$/.test(tag)) return false;
  return true;
}

function pickAvoidTags(tracks) {
  const stats = new Map();
  for (const track of tracks) {
    const score = track.scores ?? {};
    const skipRisk = score.skipRisk?.value ?? 0;
    const energy = score.energy?.value ?? 0.5;
    const intensity = score.intensity?.value ?? energy;
    const risk = skipRisk * 0.6 + Math.max(0, energy - 0.7) * 0.25 + Math.max(0, intensity - 0.7) * 0.25;
    for (const tag of tagsFor(track)) {
      const item = stats.get(tag) ?? { count: 0, risk: 0 };
      item.count += 1;
      item.risk += risk;
      stats.set(tag, item);
    }
  }

  return [...stats.entries()]
    .map(([tag, item]) => ({ tag, count: item.count, risk: item.risk / item.count }))
    .filter((item) => item.count >= 2 && item.risk >= 0.24)
    .sort((a, b) => b.risk - a.risk || b.count - a.count)
    .slice(0, 10)
    .map((item) => item.tag);
}

function tagsFor(track) {
  return ["moodTags", "contextTags", "soundTags", "useTags"].flatMap((bucket) =>
    (track[bucket] ?? []).filter((item) => (item.confidence ?? 0) >= 0.35).map((item) => item.tag)
  );
}

function countBy(items, selector) {
  const counts = new Map();
  for (const item of items) {
    const key = selector(item);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

function countTags(tracks, bucket) {
  const counts = new Map();
  for (const track of tracks) {
    for (const tag of track[bucket] ?? []) {
      if ((tag.confidence ?? 0) < 0.35) continue;
      counts.set(tag.tag, (counts.get(tag.tag) ?? 0) + 1);
    }
  }
  return counts;
}

function mergeCounts(...maps) {
  const counts = new Map();
  for (const map of maps) {
    for (const [key, value] of map.entries()) {
      counts.set(key, (counts.get(key) ?? 0) + value);
    }
  }
  return counts;
}

function averageScores(tracks) {
  const sums = new Map();
  const counts = new Map();
  for (const track of tracks) {
    for (const [name, score] of Object.entries(track.scores ?? {})) {
      if (typeof score?.value !== "number") continue;
      sums.set(name, (sums.get(name) ?? 0) + score.value);
      counts.set(name, (counts.get(name) ?? 0) + 1);
    }
  }
  return Object.fromEntries([...sums.entries()].map(([name, sum]) => [name, sum / counts.get(name)]));
}

function formatCounts(counts, label, limit = 12) {
  const lines = topEntries(counts, limit).map(([name, count]) => `- ${name}: ${count}`);
  return lines.length ? lines.join("\n") : `- no ${label} signals yet`;
}

function topEntries(counts, limit) {
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, limit);
}

const isMain = import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  const evidencePath = process.argv[2] ?? path.join(process.cwd(), "data/taste/tracks.evidence.json");
  const tasteDir = process.argv[3] ?? path.dirname(evidencePath);
  buildTasteProfile({ evidencePath, tasteDir })
    .then(({ profilePath, rulesPath }) => {
      console.log(profilePath);
      console.log(rulesPath);
    })
    .catch((error) => {
      console.error(error.message);
      process.exit(1);
    });
}
