import type { Playlist, StreamUrl, Track } from "./types";

type AnyRecord = Record<string, any>;

export function normalizeTrack(raw: AnyRecord): Track {
  const artists = raw.ar ?? raw.artists ?? [];
  const artist = Array.isArray(artists)
    ? artists.map((item) => item.name).filter(Boolean).join(", ")
    : raw.artist ?? "Unknown artist";

  return {
    provider: "netease",
    id: String(raw.id ?? raw.songId ?? raw.trackId ?? ""),
    title: String(raw.name ?? raw.title ?? "Untitled"),
    artist: artist || "Unknown artist",
    album: raw.al?.name ?? raw.album?.name ?? raw.album ?? null,
    durationMs: Number(raw.dt ?? raw.duration ?? raw.durationMs) || null,
    coverUrl: raw.al?.picUrl ?? raw.album?.picUrl ?? raw.coverUrl ?? null,
    playCount: Number.isFinite(Number(raw.playCount)) ? Number(raw.playCount) : null
  };
}

export function normalizeStream(raw: AnyRecord, trackId: string): StreamUrl {
  const candidate = Array.isArray(raw.data) ? raw.data[0] : raw;
  const url = candidate?.url ?? null;
  const code = candidate?.code;
  const freeTrial = candidate?.freeTrialInfo ?? null;

  return {
    provider: "netease",
    trackId,
    url,
    expiresAt: candidate?.expi ? new Date(Date.now() + Number(candidate.expi) * 1000).toISOString() : null,
    quality: normalizeQuality(candidate?.level),
    reason: url ? "ok" : code === 403 || freeTrial ? "vip_required" : "login_required_or_unavailable"
  };
}

export function normalizeStreams(raw: AnyRecord, requestedIds: string[]): StreamUrl[] {
  const data = Array.isArray(raw.data) ? raw.data : [raw];
  const byId = new Map(data.map((item) => [String(item?.id ?? item?.songId ?? item?.trackId ?? ""), item]));
  return requestedIds.map((id) => normalizeStream(byId.get(id) ?? {}, id));
}

export function normalizePlaylist(raw: AnyRecord, id: string): Playlist {
  const playlist = raw.playlist ?? raw;
  const tracks = Array.isArray(playlist.tracks) ? playlist.tracks.map(normalizeTrack) : [];
  return {
    provider: "netease",
    id: String(playlist.id ?? id),
    name: String(playlist.name ?? "Untitled playlist"),
    description: playlist.description ?? null,
    coverUrl: playlist.coverImgUrl ?? playlist.coverUrl ?? null,
    tracks
  };
}

export function normalizeUserRecord(raw: AnyRecord, uid: string, type: string): Playlist {
  const records = type === "1" ? raw.weekData : raw.allData;
  const tracks = Array.isArray(records)
    ? records.map((record) => normalizeTrack({ ...(record.song ?? {}), playCount: record.playCount }))
    : [];
  return {
    provider: "netease",
    id: `user-record-${uid}-${type}`,
    name: type === "1" ? "Netease weekly listening ranking" : "Netease all-time listening ranking",
    description: "Songs from Netease listening ranking, weighted by playCount.",
    coverUrl: tracks[0]?.coverUrl ?? null,
    tracks
  };
}

function normalizeQuality(value: unknown): StreamUrl["quality"] {
  if (value === "standard" || value === "higher" || value === "exhigh" || value === "lossless") {
    return value;
  }
  return "unknown";
}
