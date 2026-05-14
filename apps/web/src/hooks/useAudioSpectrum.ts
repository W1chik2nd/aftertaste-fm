import { useEffect, useRef, useState } from "react";

export const WAVE_BARS = 28;

/**
 * Reads a real frequency spectrum off the main <audio> element via Web Audio.
 *
 * Returns `WAVE_BARS` magnitudes (0..1) while audio is playing, or `null` when
 * there is nothing usable to show — paused, not yet started, or the stream is
 * cross-origin (Netease CDN URLs) so the analyser is blocked and reads silence.
 * Callers fall back to a CSS animation when this is `null`.
 *
 * A media element can only be sourced once, so the AudioContext, source node and
 * analyser are created lazily on first play and kept in refs for the element's life.
 */
export function useAudioSpectrum(
  audioRef: React.RefObject<HTMLAudioElement>,
  isPlaying: boolean
): number[] | null {
  const contextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const sourceRef = useRef<MediaElementAudioSourceNode | null>(null);
  const frameRef = useRef<number | null>(null);
  const [levels, setLevels] = useState<number[] | null>(null);

  useEffect(() => {
    const audio = audioRef.current;
    if (!isPlaying || !audio) {
      if (frameRef.current != null) cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
      setLevels(null);
      return;
    }

    if (!contextRef.current) {
      try {
        const context = new AudioContext();
        const source = context.createMediaElementSource(audio);
        const analyser = context.createAnalyser();
        analyser.fftSize = 64;
        analyser.smoothingTimeConstant = 0.78;
        source.connect(analyser);
        analyser.connect(context.destination);
        contextRef.current = context;
        sourceRef.current = source;
        analyserRef.current = analyser;
      } catch (event) {
        console.warn("Web Audio spectrum unavailable.", event);
        return;
      }
    }

    const context = contextRef.current;
    const analyser = analyserRef.current;
    if (!context || !analyser) return;
    void context.resume();

    const bins = new Uint8Array(analyser.frequencyBinCount);
    const tick = () => {
      analyser.getByteFrequencyData(bins);
      setLevels(sampleBars(bins));
      frameRef.current = requestAnimationFrame(tick);
    };
    frameRef.current = requestAnimationFrame(tick);

    return () => {
      if (frameRef.current != null) cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
    };
  }, [audioRef, isPlaying]);

  return levels;
}

/** Down-samples raw FFT bins to WAVE_BARS magnitudes; returns null if all silent (cross-origin block). */
function sampleBars(bins: Uint8Array): number[] | null {
  const step = bins.length / WAVE_BARS;
  let peak = 0;
  const bars: number[] = [];
  for (let i = 0; i < WAVE_BARS; i += 1) {
    const value = bins[Math.floor(i * step)] ?? 0;
    peak = Math.max(peak, value);
    bars.push(value / 255);
  }
  return peak === 0 ? null : bars;
}
