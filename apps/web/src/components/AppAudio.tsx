import type { RefObject } from "react";
import type { PlaybackState } from "../types";
import { OFFLINE_MEDIA_MESSAGE, isBrowserOffline } from "../utils/network";

const STREAM_LOAD_ERROR = "Audio stream failed to load. The server may be offline or the item unavailable.";
const VOICE_LOAD_ERROR = "Host voice failed to load, but the lead track can keep playing.";

type Props = {
  audioRef: RefObject<HTMLAudioElement>;
  voiceRef: RefObject<HTMLAudioElement>;
  playback: PlaybackState;
  setProgressSeconds: (value: number) => void;
  setDurationSeconds: (value: number) => void;
  advanceToNext: () => Promise<void>;
  onError: (message: string) => void;
};

export function AppAudio({
  audioRef,
  voiceRef,
  playback,
  setProgressSeconds,
  setDurationSeconds,
  advanceToNext,
  onError
}: Props) {
  return (
    <>
      <audio
        ref={audioRef}
        onLoadedMetadata={(event) => setDurationSeconds(event.currentTarget.duration || 0)}
        onTimeUpdate={(event) => setProgressSeconds(event.currentTarget.currentTime || 0)}
        onError={() => onError(isBrowserOffline() ? OFFLINE_MEDIA_MESSAGE : STREAM_LOAD_ERROR)}
        onEnded={() => {
          setProgressSeconds(0);
          void advanceToNext();
        }}
      />
      <audio
        ref={voiceRef}
        onPlay={() => {
          if (audioRef.current) audioRef.current.volume = 0.16;
        }}
        onEnded={() => {
          if (audioRef.current) audioRef.current.volume = 1;
        }}
        onPause={() => {
          if (audioRef.current && !playback.isPlaying) audioRef.current.volume = 1;
        }}
        onError={() => onError(isBrowserOffline() ? OFFLINE_MEDIA_MESSAGE : VOICE_LOAD_ERROR)}
      />
    </>
  );
}
