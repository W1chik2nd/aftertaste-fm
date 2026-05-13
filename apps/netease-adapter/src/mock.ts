import type { Playlist, StreamUrl, Track } from "./types";

const MOCK_PLAY_COUNT_START = 96;
const MOCK_PLAY_COUNT_STEP = 13;

export const mockTracks: Track[] = [
  {
    provider: "netease",
    id: "ne-mock-001",
    title: "Quiet Tab Left Open",
    artist: "Aftertaste Mock",
    album: "Adapter Smoke Test",
    durationMs: 218000,
    coverUrl: "https://images.unsplash.com/photo-1493246507139-91e8fad9978e?auto=format&fit=crop&w=900&q=80"
  },
  {
    provider: "netease",
    id: "ne-mock-002",
    title: "深夜缓存",
    artist: "海边调试员",
    album: "Local Weather",
    durationMs: 241000,
    coverUrl: "https://images.unsplash.com/photo-1483412033650-1015ddeb83d1?auto=format&fit=crop&w=900&q=80"
  },
  {
    provider: "netease",
    id: "ne-mock-003",
    title: "No Announcement Needed",
    artist: "Small Signal",
    album: "Three Songs Together",
    durationMs: 204000,
    coverUrl: "https://images.unsplash.com/photo-1515405295579-ba7b45403062?auto=format&fit=crop&w=900&q=80"
  },
  {
    provider: "netease",
    id: "ne-mock-004",
    title: "雨停在二环外",
    artist: "无声地图",
    album: "Window Side",
    durationMs: 230000,
    coverUrl: "https://images.unsplash.com/photo-1500534314209-a25ddb2bd429?auto=format&fit=crop&w=900&q=80"
  },
  {
    provider: "netease",
    id: "ne-mock-005",
    title: "Kind Edges",
    artist: "Juniper Tape",
    album: "A Little Less Blue",
    durationMs: 227000,
    coverUrl: "https://images.unsplash.com/photo-1519681393784-d120267933ba?auto=format&fit=crop&w=900&q=80"
  },
  {
    provider: "netease",
    id: "ne-mock-006",
    title: "The Last Build Before Sleep",
    artist: "North Exit",
    album: "Warm Boot",
    durationMs: 212000,
    coverUrl: "https://images.unsplash.com/photo-1495567720989-cebdbdd97913?auto=format&fit=crop&w=900&q=80"
  }
];

export function mockStream(trackId: string): StreamUrl {
  return {
    provider: "netease",
    trackId,
    url: null,
    expiresAt: null,
    quality: "unknown",
    reason: "unavailable"
  };
}

export function mockPlaylist(id: string): Playlist {
  return {
    provider: "netease",
    id,
    name: "Aftertaste Mock Netease Shelf",
    description: "Normalized mock playlist returned by the adapter when Netease is not configured.",
    coverUrl: mockTracks[0]?.coverUrl ?? null,
    tracks: mockTracks
  };
}

export function mockUserRecord(uid: string, type: string): Playlist {
  return {
    provider: "netease",
    id: `user-record-${uid}-${type}`,
    name: type === "1" ? "Mock weekly listening ranking" : "Mock all-time listening ranking",
    description: "Mock listening ranking for local import testing.",
    coverUrl: mockTracks[0]?.coverUrl ?? null,
    tracks: mockTracks.map((track, index) => ({
      ...track,
      playCount: MOCK_PLAY_COUNT_START - index * MOCK_PLAY_COUNT_STEP
    }))
  };
}
