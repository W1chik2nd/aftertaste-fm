import { useCallback, useEffect, useRef, useState } from "react";
import { radioApi } from "../api";
import type { PlaybackState } from "../types";
import { mainMediaUrl, voiceOverlayUrl } from "../utils/media";
import { OFFLINE_MEDIA_MESSAGE, isBrowserOffline } from "../utils/network";

const emptyPlayback: PlaybackState = {
  currentItem: null,
  currentIndex: 0,
  queue: [],
  showTitle: null,
  segmentTitle: null,
  isPlaying: false,
  progressMs: 0,
  durationMs: null,
  hostLanguage: "en-US"
};

type UsePlayerResult = {
  playback: PlaybackState;
  setPlayback: (state: PlaybackState) => void;
  audioRef: React.RefObject<HTMLAudioElement>;
  voiceRef: React.RefObject<HTMLAudioElement>;
  progressSeconds: number;
  durationSeconds: number;
  setProgressSeconds: (value: number) => void;
  setDurationSeconds: (value: number) => void;
  mainUrl: string | undefined;
  voiceUrl: string | undefined;
  advanceToNext: () => Promise<void>;
  seek: (seconds: number) => void;
  error: string | null;
  setError: (message: string | null) => void;
};

export function usePlayer(): UsePlayerResult {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const voiceRef = useRef<HTMLAudioElement | null>(null);
  const advancingRef = useRef(false);
  const [playback, setPlayback] = useState<PlaybackState>(emptyPlayback);
  const [progressSeconds, setProgressSeconds] = useState(0);
  const [durationSeconds, setDurationSeconds] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const current = playback.currentItem;
  const mainUrl = mainMediaUrl(current);
  const voiceUrl = voiceOverlayUrl(current);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    setProgressSeconds(0);
    setDurationSeconds(0);
    audio.pause();
    audio.currentTime = 0;

    if (!mainUrl) {
      audio.removeAttribute("src");
      audio.load();
      return;
    }
    audio.src = mainUrl;
    audio.load();
  }, [current?.id, mainUrl]);

  useEffect(() => {
    const voice = voiceRef.current;
    if (!voice) return;
    if (!voiceUrl) {
      voice.removeAttribute("src");
      voice.load();
      return;
    }
    voice.src = voiceUrl;
    voice.currentTime = 0;
    voice.load();
  }, [current?.id, voiceUrl]);

  useEffect(() => {
    const audio = audioRef.current;
    const voice = voiceRef.current;

    if (!playback.isPlaying) {
      audio?.pause();
      voice?.pause();
      return;
    }

    if (mainUrl && audio) {
      audio.volume = voiceUrl ? 0.16 : 1;
      if (audio.ended || audio.currentTime >= (audio.duration || Infinity)) {
        audio.currentTime = 0;
      }
      void audio.play().catch((event) => {
        console.warn("Audio playback failed.", event);
        setError(isBrowserOffline() ? OFFLINE_MEDIA_MESSAGE : "Audio could not start. The item may be unavailable.");
      });
    }
    if (voiceUrl && voice) {
      voice.volume = 1;
      void voice.play().catch((event) => {
        console.warn("Host voice playback failed.", event);
        setError(isBrowserOffline() ? OFFLINE_MEDIA_MESSAGE : "Host voice could not start, but the lead track can keep playing.");
      });
    }
  }, [current?.id, mainUrl, playback.isPlaying, voiceUrl]);

  const advanceToNext = useCallback(async () => {
    if (advancingRef.current) return;
    advancingRef.current = true;
    setError(null);
    try {
      audioRef.current?.pause();
      voiceRef.current?.pause();
      if (voiceRef.current) voiceRef.current.currentTime = 0;
      const nextState = await radioApi.next();
      setPlayback(nextState);
    } catch (event) {
      setError(event instanceof Error ? event.message : "Could not advance playback.");
    } finally {
      advancingRef.current = false;
    }
  }, []);

  const seek = useCallback((seconds: number) => {
    setProgressSeconds(seconds);
    if (audioRef.current) audioRef.current.currentTime = seconds;
  }, []);

  return {
    playback,
    setPlayback,
    audioRef,
    voiceRef,
    progressSeconds,
    durationSeconds,
    setProgressSeconds,
    setDurationSeconds,
    mainUrl,
    voiceUrl,
    advanceToNext,
    seek,
    error,
    setError
  };
}

export { emptyPlayback };
