import type { RefObject } from "react";
import type { PlaybackState } from "../types";

type Props = {
  audioRef: RefObject<HTMLAudioElement>;
  voiceRef: RefObject<HTMLAudioElement>;
  playback: PlaybackState;
  setProgressSeconds: (value: number) => void;
  setDurationSeconds: (value: number) => void;
  advanceToNext: () => Promise<void>;
};

export function AppAudio({
  audioRef,
  voiceRef,
  playback,
  setProgressSeconds,
  setDurationSeconds,
  advanceToNext
}: Props) {
  return (
    <>
      <audio
        ref={audioRef}
        onLoadedMetadata={(event) => setDurationSeconds(event.currentTarget.duration || 0)}
        onTimeUpdate={(event) => setProgressSeconds(event.currentTarget.currentTime || 0)}
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
      />
    </>
  );
}
