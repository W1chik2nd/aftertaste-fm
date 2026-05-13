export type Track = {
  provider: "netease";
  id: string;
  title: string;
  artist: string;
  album: string | null;
  durationMs: number | null;
  coverUrl: string | null;
  playCount?: number | null;
};

export type StreamUrl = {
  provider: "netease";
  trackId: string;
  url: string | null;
  expiresAt: string | null;
  quality: "standard" | "higher" | "exhigh" | "lossless" | "unknown";
  reason: "ok" | "vip_required" | "login_required_or_unavailable" | "unavailable" | "region_blocked" | "unknown";
};

export type Playlist = {
  provider: "netease";
  id: string;
  name: string;
  description: string | null;
  coverUrl: string | null;
  tracks: Track[];
};
