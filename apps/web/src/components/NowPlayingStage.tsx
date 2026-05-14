import { AlertCircle, ChevronLeft, ChevronRight, Pause, Play } from "lucide-react";
import type { CSSProperties, ReactNode } from "react";
import type { PlaybackState, QueueItem } from "../types";
import { formatTime } from "../utils/format";

const MILLISECONDS_PER_SECOND = 1000;

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
  children?: ReactNode;
};

/** The hero of the Player view: what is on air, its lyrics, and the transport. */
export function NowPlayingStage({
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
  const coverUrl = current?.track?.coverUrl;
  return (
    <section className={`now-playing-stage ${coverUrl ? "with-cover" : ""}`} aria-label="Now playing">
      {coverUrl ? <img className="stage-backdrop-art" src={coverUrl} alt="" aria-hidden="true" /> : null}
      <div className="now-header">
        <div>
          <span>{statusLabel}</span>
          <h2>{playback.showTitle ?? "No show yet"}</h2>
          <p>{playback.segmentTitle ?? "Waiting for the first segment"}</p>
        </div>
        <span className={`live-dot ${playback.isPlaying ? "active" : ""}`} />
      </div>

      <div className="stage-content">
        {current ? <CurrentItem item={current} /> : <EmptyCurrent />}
      </div>

      <Transport
        isPlaying={playback.isPlaying}
        canPlayAudio={canPlayAudio}
        progressSeconds={progressSeconds}
        displayDuration={displayDuration}
        onSeek={onSeek}
        onTogglePlay={onTogglePlay}
        onPrevious={onPrevious}
        onNext={onNext}
      />

      <StageNotices error={error} canPlayAudio={canPlayAudio} current={current} />

      {children ? <div className="stage-after">{children}</div> : null}
    </section>
  );
}

function StageNotices({
  error,
  canPlayAudio,
  current
}: Pick<Props, "error" | "canPlayAudio" | "current">) {
  return (
    <>
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
    </>
  );
}

type TransportProps = {
  isPlaying: boolean;
  canPlayAudio: boolean;
  progressSeconds: number;
  displayDuration: number;
  onSeek: (seconds: number) => void;
  onTogglePlay: () => void;
  onPrevious: () => void;
  onNext: () => void;
};

function Transport({
  isPlaying,
  canPlayAudio,
  progressSeconds,
  displayDuration,
  onSeek,
  onTogglePlay,
  onPrevious,
  onNext
}: TransportProps) {
  const progressPercent = displayDuration ? Math.min(100, (progressSeconds / displayDuration) * 100) : 0;
  return (
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
        <button type="button" className="play-button" onClick={onTogglePlay} title={isPlaying ? "Pause" : "Play"}>
          {isPlaying ? <Pause size={26} /> : <Play size={26} />}
        </button>
        <button type="button" className="icon-button" onClick={onNext} title="Next">
          <ChevronRight size={23} />
        </button>
      </div>
    </div>
  );
}

function CurrentItem({ item }: { item: QueueItem }) {
  const track = item.track;
  if (!track) {
    return (
      <article className="host-readout">
        <span>{item.segmentTitle ?? "Chapter"} · {item.hostName ?? "Aftertaste"}</span>
        <blockquote>{item.hostScript}</blockquote>
      </article>
    );
  }

  const isHostLead = item.type === "host_voice";
  return (
    <article className={`track-readout ${isHostLead ? "chapter-readout" : ""}`}>
      <div className="cover-wrap stage-cover">
        {track.coverUrl ? <img src={track.coverUrl} alt="" /> : <div className="cover-fallback" />}
      </div>
      <div className="track-copy">
        <span>{isHostLead ? `${item.segmentTitle ?? "Chapter"} · ${item.hostName ?? "Aftertaste"}` : item.segmentTitle}</span>
        <h3>{track.title}</h3>
        <p>{track.artist}</p>
        <div className="track-meta-line">
          <small>{track.provider}</small>
          {track.durationMs ? <small>{formatTime(track.durationMs / MILLISECONDS_PER_SECOND)}</small> : null}
          {isHostLead ? <small>Host over lead</small> : null}
        </div>
        {isHostLead ? <blockquote>{item.hostScript}</blockquote> : null}
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
