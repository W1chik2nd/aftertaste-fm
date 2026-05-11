import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const localApi = require("NeteaseCloudMusicApi");

type ApiResult = {
  status?: number;
  body?: unknown;
};

export type NeteaseClientMode = "mock" | "external-api" | "local-package";

export class NeteaseClient {
  readonly mode: NeteaseClientMode;

  constructor(
    private readonly apiBase?: string,
    private readonly cookie?: string,
    mockMode = false
  ) {
    this.mode = mockMode ? "mock" : apiBase ? "external-api" : "local-package";
  }

  async search(keywords: string) {
    return this.call("/search", { keywords });
  }

  async songUrl(id: string) {
    return this.call("/song/url", { id });
  }

  async songDetail(ids: string) {
    return this.call("/song/detail", { ids });
  }

  async lyric(id: string) {
    return this.call("/lyric", { id });
  }

  async playlistDetail(id: string) {
    return this.call("/playlist/detail", { id });
  }

  async recommendSongs() {
    return this.call("/recommend/songs", {});
  }

  private async call(path: string, query: Record<string, string>) {
    if (this.mode === "mock") {
      throw new Error("Netease client is in mock mode.");
    }

    if (this.mode === "external-api") {
      return this.callExternal(path, query);
    }

    return this.callLocal(path, query);
  }

  private async callExternal(path: string, query: Record<string, string>) {
    if (!this.apiBase) throw new Error("NETEASE_API_BASE is not configured.");
    const search = new URLSearchParams(query);
    const response = await fetch(`${this.apiBase}${path}?${search.toString()}`, {
      headers: this.cookie ? { cookie: this.cookie } : undefined
    });
    if (!response.ok) {
      throw new Error(`External Netease API returned ${response.status}`);
    }
    return response.json();
  }

  private async callLocal(path: string, query: Record<string, string>) {
    const fnName = path.replace(/^\//, "").replace(/\//g, "_");
    const fn = localApi[fnName];
    if (typeof fn !== "function") {
      throw new Error(`Local NeteaseCloudMusicApi does not expose ${fnName}`);
    }

    const result = (await fn({
      ...query,
      cookie: this.cookie
    })) as ApiResult;

    if (result.status && result.status >= 400) {
      throw new Error(`Local NeteaseCloudMusicApi returned ${result.status}`);
    }

    return result.body ?? result;
  }
}
