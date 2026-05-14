import { useEffect, useMemo, useState } from "react";
import { radioApi } from "../api";
import { activeLyricLineIndex, parseLyrics } from "../utils/lyrics";

/**
 * Loads lyrics for the current track and derives the active line from playback progress.
 * Split out of App.tsx so the orchestrator stays focused on view + playback wiring.
 */
export function useLyrics(trackId: string | null | undefined, progressSeconds: number) {
  const [lyricsRaw, setLyricsRaw] = useState<string | null>(null);
  const [lyricsLoading, setLyricsLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLyricsRaw(null);
    setLyricsLoading(false);
    if (!trackId) return;

    async function loadLyrics(id: string) {
      setLyricsLoading(true);
      try {
        const response = await radioApi.lyrics(id);
        if (!cancelled) setLyricsRaw(response.lyrics?.trim() || null);
      } catch (event) {
        console.warn("Could not load lyrics.", event);
        if (!cancelled) setLyricsRaw(null);
      } finally {
        if (!cancelled) setLyricsLoading(false);
      }
    }

    void loadLyrics(trackId);
    return () => {
      cancelled = true;
    };
  }, [trackId]);

  const lyricLines = useMemo(() => parseLyrics(lyricsRaw), [lyricsRaw]);
  const activeLyricIndex = useMemo(
    () => activeLyricLineIndex(lyricLines, progressSeconds),
    [lyricLines, progressSeconds]
  );

  return { lyricLines, activeLyricIndex, lyricsLoading };
}
