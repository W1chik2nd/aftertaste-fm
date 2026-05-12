import { AlertCircle, ChevronLeft, ChevronRight, Pause, Play } from "lucide-react";
import type { CSSProperties } from "react";
import type { PlaybackState, QueueItem } from "../types";
import { formatTime } from "../utils/format";

type Props = {
  playback: PlaybackState;
  statusLabel: string;
  current: QueueItem | null | undefined;
  error: string | null;
  canPlayAudio: boolean;
  progressSeconds: number;
  displayDuration: number;
  onSeek: (seconds: number) => void;
  onTogglePlay: () => void;
  onPrevious: () => void;
  onNext: () => void;
  children?: React.ReactNode;
};

export function PlaybackPanel({
  playback,
  statusLabel,
  current,
  error,
  canPlayAudio,
  progressSeconds,
  displayDuration,
  onSeek,
  onTogglePlay,
  onPrevious,
  onNext,
  children
}: Props) {
  const progressPercent = displayDuration ? Math.min(100, (progressSeconds / displayDuration) * 100) : 0;
  return (
    <section className="playback-panel" aria-label="Playback">
      <div className="now-header">
        <div>
          <span>{statusLabel}</span>
          <h2>{playback.showTitle ?? "No show yet"}</h2>
          <p>{playback.segmentTitle ?? "Waiting for the first segment"}</p>
        </div>
        <span className={`live-dot ${playback.isPlaying ? "active" : ""}`} />
      </div>

      {current ? <CurrentItem item={current} /> : <EmptyCurrent />}

      {children}

      {error ? (
        <div className="inline-error">
          <AlertCircle size={16} />
          {error}
        </div>
      ) : null}

      {!canPlayAudio && current ? (
        <div className="unavailable">
          <AlertCircle size={16} />
          {current.type === "host_voice"
            ? "Host audio is text-only for this chapter."
            : `Stream unavailable: ${current.track?.unavailableReason ?? "unknown"}.`}
        </div>
      ) : null}

      {canPlayAudio && current?.type === "host_voice" && current.track && !current.ttsUrl ? (
        <div className="unavailable">
          <AlertCircle size={16} />
          Host voice is unavailable, so the chapter lead will play clean.
        </div>
      ) : null}

      <div className="transport">
        <div className="progress-wrap" aria-label="Playback progress">
          <span>{formatTime(progressSeconds)}</span>
          <input
            aria-label="Seek playback"
            type="range"
            min="0"
            max={displayDuration || 0}
            step="1"
            value={displayDuration ? Math.min(progressSeconds, displayDuration) : 0}
            disabled={!canPlayAudio || !displayDuration}
            style={{ "--progress": `${progressPercent}%` } as CSSProperties}
            onChange={(event) => onSeek(Number(event.currentTarget.value))}
          />
          <span>{formatTime(displayDuration)}</span>
        </div>

        <div className="controls" aria-label="Playback controls">
          <button type="button" className="icon-button" onClick={onPrevious} title="Previous">
            <ChevronLeft size={23} />
          </button>
          <button
            type="button"
            className="play-button"
            onClick={onTogglePlay}
            title={playback.isPlaying ? "Pause" : "Play"}
          >
            {playback.isPlaying ? <Pause size={26} /> : <Play size={26} />}
          </button>
          <button type="button" className="icon-button" onClick={onNext} title="Next">
            <ChevronRight size={23} />
          </button>
        </div>
      </div>
    </section>
  );
}

function CurrentItem({ item }: { item: QueueItem }) {
  if (item.type === "host_voice") {
    const lead = item.track;
    return (
      <article className={lead ? "track-readout chapter-readout" : "host-readout"}>
        {lead ? (
          <div className="cover-wrap">
            {lead.coverUrl ? <img src={lead.coverUrl} alt="" /> : <div className="cover-fallback" />}
          </div>
        ) : null}
        <div>
          <span>{item.segmentTitle ?? "Chapter"} · {item.hostName ?? "Aftertaste"}</span>
          {lead ? <h3>{lead.title}</h3> : null}
          {lead ? <p>{lead.artist}</p> : null}
          <blockquote>{item.hostScript}</blockquote>
        </div>
      </article>
    );
  }

  const track = item.track;
  return (
    <article className="track-readout">
      <div className="cover-wrap">
        {track?.coverUrl ? <img src={track.coverUrl} alt="" /> : <div className="cover-fallback" />}
      </div>
      <div>
        <span>{item.segmentTitle}</span>
        <h3>{track?.title ?? "Untitled track"}</h3>
        <p>{track?.artist ?? "Unknown artist"}</p>
        <small>{track?.provider ?? "unknown provider"}</small>
      </div>
    </article>
  );
}

function EmptyCurrent() {
  return (
    <article className="empty-current">
      <h3>Nothing is on air.</h3>
      <p>Start with a plain-language prompt and the agent will build the first program.</p>
    </article>
  );
}
