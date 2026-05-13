import cors from "cors";
import express from "express";
import { loadEnv } from "./env";
import { mockPlaylist, mockStream, mockTracks, mockUserRecord } from "./mock";
import { NeteaseClient } from "./neteaseClient";
import { normalizePlaylist, normalizeStreams, normalizeTrack, normalizeUserRecord } from "./normalize";

loadEnv();

const app = express();
const port = Number(process.env.ADAPTER_PORT ?? 8090);
const apiBase = process.env.NETEASE_API_BASE?.replace(/\/$/, "");
const mockMode = process.env.MOCK_NETEASE === "true";
const client = new NeteaseClient(apiBase, process.env.NETEASE_COOKIE, mockMode);

const allowedOrigins = (process.env.ADAPTER_ALLOWED_ORIGINS ?? "http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080")
  .split(",")
  .map((value) => value.trim())
  .filter(Boolean);

app.use(cors({
  origin: (origin, callback) => {
    if (!origin || allowedOrigins.includes(origin)) callback(null, true);
    else callback(new Error(`Origin ${origin} not allowed`));
  }
}));
app.use(express.json({ limit: "1mb" }));

const asyncHandler = <T extends express.Request>(fn: (req: T, res: express.Response) => Promise<unknown>) =>
  (req: express.Request, res: express.Response, next: express.NextFunction) => {
    Promise.resolve(fn(req as T, res)).catch(next);
  };

app.get("/health", (_request, response) => {
  response.json({
    status: "ok",
    provider: "netease",
    mode: client.mode
  });
});

app.get("/search", asyncHandler(async (request, response) => {
  const keywords = String(request.query.keywords ?? "").slice(0, 200);
  if (mockMode) {
    response.json(searchMock(keywords));
    return;
  }
  const raw = await client.search(keywords);
  const songs = raw.result?.songs ?? raw.songs ?? [];
  response.json(Array.isArray(songs) ? songs.map(normalizeTrack) : []);
}));

app.get("/song/url", asyncHandler(async (request, response) => {
  const id = String(request.query.id ?? "");
  const ids = id.split(",").map((value) => value.trim()).filter(Boolean);
  if (!ids.length) {
    response.json([]);
    return;
  }
  if (mockMode) {
    response.json(ids.map(mockStream));
    return;
  }
  const raw = await client.songUrl(ids.join(","));
  response.json(normalizeStreams(raw, ids));
}));

app.get("/song/detail", asyncHandler(async (request, response) => {
  const id = String(request.query.id ?? "");
  if (mockMode) {
    response.json(mockTracks.find((track) => track.id === id) ?? mockTracks[0]);
    return;
  }
  const raw = await client.songDetail(id);
  response.json(normalizeTrack(raw.songs?.[0] ?? raw));
}));

app.get("/lyric", asyncHandler(async (request, response) => {
  const id = String(request.query.id ?? "");
  if (mockMode) {
    response.json({ provider: "netease", trackId: id, lyrics: null });
    return;
  }
  const raw = await client.lyric(id);
  response.json({ provider: "netease", trackId: id, lyrics: raw.lrc?.lyric ?? null });
}));

app.get("/playlist/detail", asyncHandler(async (request, response) => {
  const id = String(request.query.id ?? "");
  if (mockMode) {
    response.json(mockPlaylist(id));
    return;
  }
  const raw = await client.playlistDetail(id);
  const playlist = normalizePlaylist(raw, id);
  const rawPlaylist = raw.playlist ?? raw;
  const trackIds = Array.isArray(rawPlaylist.trackIds)
    ? rawPlaylist.trackIds.map((item: { id?: unknown }) => String(item.id)).filter(Boolean)
    : [];

  if (trackIds.length > playlist.tracks.length) {
    const detailTracks = await fetchTrackDetails(trackIds);
    response.json({ ...playlist, tracks: detailTracks.length ? detailTracks : playlist.tracks });
    return;
  }
  response.json(playlist);
}));

app.get("/user/record", asyncHandler(async (request, response) => {
  const uid = String(request.query.uid ?? "").trim();
  const type = String(request.query.type ?? "0").trim() || "0";
  if (!uid) {
    response.status(400).json({ error: "missing_uid", message: "uid is required." });
    return;
  }
  if (mockMode) {
    response.json(mockUserRecord(uid, type));
    return;
  }
  const raw = await client.userRecord(uid, type);
  response.json(normalizeUserRecord(raw, uid, type));
}));

app.get("/recommend/songs", asyncHandler(async (_request, response) => {
  if (mockMode) {
    response.json(mockTracks);
    return;
  }
  const raw = await client.recommendSongs();
  const songs = raw.data?.dailySongs ?? raw.recommend ?? raw.songs ?? [];
  response.json(Array.isArray(songs) ? songs.map(normalizeTrack) : []);
}));

app.use((error: unknown, _request: express.Request, response: express.Response, _next: express.NextFunction) => {
  const safe = error instanceof Error ? { name: error.name, message: error.message } : { message: "Unknown adapter error" };
  console.error("[netease-adapter]", safe.name ?? "Error", safe.message);
  response.status(502).json({ error: "netease_adapter_error", message: safe.message });
});

app.listen(port, "127.0.0.1", () => {
  console.log(`netease-adapter listening on http://127.0.0.1:${port} (${client.mode})`);
});

function searchMock(keywords: string) {
  const query = keywords.trim().toLowerCase();
  if (!query) return mockTracks;
  return mockTracks.filter((track) =>
    [track.title, track.artist, track.album].some((value) => value?.toLowerCase().includes(query))
  );
}

async function fetchTrackDetails(trackIds: string[]) {
  const chunks: string[][] = [];
  for (let index = 0; index < trackIds.length; index += 200) {
    chunks.push(trackIds.slice(index, index + 200));
  }
  const tracks: ReturnType<typeof normalizeTrack>[] = [];
  for (const chunk of chunks) {
    const raw = await client.songDetail(chunk.join(","));
    const songs = Array.isArray(raw.songs) ? raw.songs : [];
    tracks.push(...songs.map(normalizeTrack));
  }
  return tracks;
}
