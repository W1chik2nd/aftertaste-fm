import { useEffect, useRef, useState } from "react";

export const WAVE_BARS = 28;

/** FFT window. 256 → 128 frequency bins, enough to give each bar real detail. */
const FFT_SIZE = 256;
/** Lower than the WebAudio default (0.8) so bars react quickly instead of lagging. */
const SMOOTHING = 0.6;
/** Fraction of the FFT bins worth showing — the top end is near-silent for music. */
const USABLE_BIN_FRACTION = 0.72;
/** Perceptual curve (<1) that lifts quiet detail so bars actually move. */
const LOUDNESS_CURVE = 0.62;
/** Extra gain applied across the bands, ramping up to this at the treble end, so
 *  high bars still visibly bounce instead of sitting flat under the loud bass. */
const HIGH_BAND_GAIN = 0.9;
const BYTE_MAX = 255;

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
  // Held only to keep the source node alive for the element's life — it can never
  // be created again, and a dropped reference could let it be garbage-collected.
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
        analyser.fftSize = FFT_SIZE;
        analyser.smoothingTimeConstant = SMOOTHING;
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
      // The element's audio is routed through this context, so a suspended
      // context means silence — keep nudging it back while we should be playing.
      if (context.state === "suspended") void context.resume();
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

/**
 * Down-samples raw FFT bins to WAVE_BARS magnitudes. Each bar gets an equal,
 * contiguous slice of the usable bins (skipping the DC bin) so every bar covers
 * a distinct frequency range and moves with the music — a log scale instead
 * crowds the low end, leaving most bars pinned to the steady bass. A treble
 * tilt and a loudness curve then keep the higher, quieter bands visible.
 * Returns null if every bar is silent — the cross-origin block reads as zero.
 */
function sampleBars(bins: Uint8Array): number[] | null {
  const usableBins = Math.max(WAVE_BARS, Math.floor(bins.length * USABLE_BIN_FRACTION));
  const binsPerBar = usableBins / WAVE_BARS;
  let peak = 0;
  const bars: number[] = [];
  for (let i = 0; i < WAVE_BARS; i += 1) {
    const start = Math.floor(i * binsPerBar) + 1;
    const end = Math.max(start + 1, Math.floor((i + 1) * binsPerBar) + 1);
    let sum = 0;
    for (let bin = start; bin < end; bin += 1) sum += bins[bin] ?? 0;
    const average = sum / (end - start);
    peak = Math.max(peak, average);
    const tilt = 1 + (i / WAVE_BARS) * HIGH_BAND_GAIN;
    bars.push(Math.min(1, Math.pow((average / BYTE_MAX) * tilt, LOUDNESS_CURVE)));
  }
  return peak === 0 ? null : bars;
}
