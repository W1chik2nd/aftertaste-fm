import { useEffect, useState } from "react";
import { WAVE_BARS } from "../hooks/useAudioSpectrum";

const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

type Props = {
  onAir: boolean;
  /** Real frequency spectrum (0..1 per bar) when available, else null for the CSS fallback. */
  levels: number[] | null;
};

export function ClockHero({ onAir, levels }: Props) {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  const hours = String(now.getHours()).padStart(2, "0");
  const minutes = String(now.getMinutes()).padStart(2, "0");
  const weekday = now.toLocaleDateString(undefined, { weekday: "long" });
  const day = String(now.getDate()).padStart(2, "0");
  const dateLabel = `${weekday} · ${day} ${MONTHS[now.getMonth()]} ${now.getFullYear()}`;

  return (
    <section className="clock-hero" aria-label="Station clock">
      <span className="clock-hero-brand">Aftertaste FM</span>
      <div className="clock-hero-time" role="timer" aria-label={`${hours}:${minutes}`}>
        <span>{hours}</span>
        <span className="clock-hero-colon" aria-hidden="true">:</span>
        <span>{minutes}</span>
      </div>
      <span className="clock-hero-date">{dateLabel}</span>
      <span className={`clock-hero-status ${onAir ? "on" : "off"}`}>
        <span className="clock-hero-dot" aria-hidden="true" />
        {onAir ? "On Air" : "Off Air"}
      </span>
      <Waveform onAir={onAir} levels={levels} />
    </section>
  );
}

function Waveform({ onAir, levels }: Props) {
  // Real spectrum when the analyser has data; otherwise the synthesized CSS animation.
  if (levels) {
    return (
      <div className="clock-hero-wave live" aria-hidden="true">
        {levels.map((level, index) => (
          <span key={index} style={{ height: `${Math.max(8, level * 100)}%` }} />
        ))}
      </div>
    );
  }
  return (
    <div className={`clock-hero-wave ${onAir ? "playing" : ""}`} aria-hidden="true">
      {Array.from({ length: WAVE_BARS }, (_, index) => (
        <span key={index} style={{ animationDelay: `${(index % 7) * 0.11}s` }} />
      ))}
    </div>
  );
}
