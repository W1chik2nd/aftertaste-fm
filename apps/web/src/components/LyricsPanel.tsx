import { useEffect, useRef, useState } from "react";
import type { LyricLine } from "../utils/lyrics";

type Props = {
  lines: LyricLine[];
  activeIndex: number;
  loading: boolean;
  trackTitle: string;
};

export function LyricsPanel({ lines, activeIndex, loading, trackTitle }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const activeRef = useRef<HTMLParagraphElement | null>(null);
  const [userScrolling, setUserScrolling] = useState(false);

  // Pause auto-scroll briefly when the user scrolls manually so we don't fight them.
  useEffect(() => {
    const node = containerRef.current;
    if (!node) return;
    let timeout: ReturnType<typeof setTimeout> | null = null;
    const handle = () => {
      setUserScrolling(true);
      if (timeout) clearTimeout(timeout);
      timeout = setTimeout(() => setUserScrolling(false), 4000);
    };
    node.addEventListener("wheel", handle, { passive: true });
    node.addEventListener("touchmove", handle, { passive: true });
    return () => {
      node.removeEventListener("wheel", handle);
      node.removeEventListener("touchmove", handle);
      if (timeout) clearTimeout(timeout);
    };
  }, []);

  useEffect(() => {
    if (userScrolling) return;
    activeRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [activeIndex, userScrolling]);

  if (loading) {
    return (
      <section className="lyrics-panel" aria-label={`Lyrics for ${trackTitle}`}>
        <span>Lyrics</span>
        <p className="lyrics-muted">Loading lyrics...</p>
      </section>
    );
  }

  if (!lines.length) {
    return (
      <section className="lyrics-panel" aria-label={`Lyrics for ${trackTitle}`}>
        <span>Lyrics</span>
        <p className="lyrics-muted">No synced lyrics available.</p>
      </section>
    );
  }

  return (
    <section className="lyrics-panel" aria-label={`Lyrics for ${trackTitle}`}>
      <span>Lyrics</span>
      <div className="lyrics-scroll" ref={containerRef}>
        {lines.map((line, index) => (
          <p
            key={line.id}
            ref={index === activeIndex ? activeRef : null}
            className={index === activeIndex ? "active" : ""}
          >
            {line.text}
          </p>
        ))}
      </div>
    </section>
  );
}
