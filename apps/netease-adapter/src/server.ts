import cors from "cors";
import express from "express";
import { loadEnv } from "./env";
import { mockPlaylist, mockStream, mockTracks } from "./mock";
import { NeteaseClient } from "./neteaseClient";
import { normalizePlaylist, normalizeStream, normalizeStreams, normalizeTrack } from "./normalize";

loadEnv();

const app = express();
const port = Number(process.env.ADAPTER_PORT ?? 8090);
const apiBase = process.env.NETEASE_API_BASE?.replace(/\/$/, "");
const mockMode = process.env.MOCK_NETEASE === "true";
const client = new NeteaseClient(apiBase, process.env.NETEASE_COOKIE, mockMode);

app.use(cors());
app.use(express.json());

app.get("/health", (_request, response) => {
  response.json({
    status: "ok",
    provider: "netease",
    mode: client.mode
  });
});

app.get("/search", async (request, response) => {
  const keywords = String(request.query.keywords ?? "");
  if (mockMode) {
    response.json(searchMock(keywords));
    return;
  }

  const raw = await client.search(keywords);
  const songs = raw.result?.songs ?? raw.songs ?? [];
  response.json(Array.isArray(songs) ? songs.map(normalizeTrack) : []);
});

app.get("/song/url", async (request, response) => {
  const id = String(request.query.id ?? "");
  const ids = id.split(",").map((value) => value.trim()).filter(Boolean);
  if (mockMode) {
    response.json(ids.length > 1 ? ids.map(mockStream) : mockStream(id));
    return;
  }

  const raw = await client.songUrl(id);
  response.json(ids.length > 1 ? normalizeStreams(raw, ids) : normalizeStream(raw, id));
});

app.get("/song/detail", async (request, response) => {
  const id = String(request.query.id ?? "");
  if (mockMode) {
    response.json(mockTracks.find((track) => track.id === id) ?? mockTracks[0]);
    return;
  }

  const raw = await client.songDetail(id);
  response.json(normalizeTrack(raw.songs?.[0] ?? raw));
});

app.get("/lyric", async (request, response) => {
  const id = String(request.query.id ?? "");
  if (mockMode) {
    response.json({ provider: "netease", trackId: id, lyrics: null });
    return;
  }

  const raw = await client.lyric(id);
  response.json({ provider: "netease", trackId: id, lyrics: raw.lrc?.lyric ?? null });
});

app.get("/playlist/detail", async (request, response) => {
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
});

app.get("/recommend/songs", async (_request, response) => {
  if (mockMode) {
    response.json(mockTracks);
    return;
  }

  const raw = await client.recommendSongs();
  const songs = raw.data?.dailySongs ?? raw.recommend ?? raw.songs ?? [];
  response.json(Array.isArray(songs) ? songs.map(normalizeTrack) : []);
});

app.use((error: unknown, _request: express.Request, response: express.Response, _next: express.NextFunction) => {
  console.error(error);
  response.status(502).json({
    error: "netease_adapter_error",
    message: error instanceof Error ? error.message : "Unknown adapter error"
  });
});

app.listen(port, () => {
  console.log(`netease-adapter listening on http://localhost:${port} (${client.mode})`);
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

  const tracks = [];
  for (const chunk of chunks) {
    const raw = await client.songDetail(chunk.join(","));
    const songs = Array.isArray(raw.songs) ? raw.songs : [];
    tracks.push(...songs.map(normalizeTrack));
  }
  return tracks;
}
