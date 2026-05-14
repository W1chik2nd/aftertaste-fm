import type { FormEvent, ReactNode } from "react";
import type { AgentTrace, PlaybackState, QueueItem } from "../../types";
import { AgentDock, type ChatMessage } from "../AgentDock";
import { AgentTracePanel } from "../AgentTracePanel";
import { ClockHero } from "../ClockHero";
import { CollapsiblePanel } from "../CollapsiblePanel";
import { NowPlayingStage } from "../NowPlayingStage";
import { QueueList } from "../QueueList";

type Props = {
  messages: ChatMessage[];
  mood: string;
  setMood: (value: string) => void;
  busy: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onGenerate: () => void;
  agentTrace: AgentTrace | null;
  onAir: boolean;
  spectrumLevels: number[] | null;
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
  upcoming: QueueItem[];
  children?: ReactNode;
};

/**
 * Two-column player layout. The left rail (1/3) stacks the station clock over
 * the chat dock; the right column (2/3) is the player itself — the now-playing
 * stage, the queue, and the agent trace. The two columns stretch to equal height.
 */
export function PlayerView({
  messages,
  mood,
  setMood,
  busy,
  onSubmit,
  onGenerate,
  agentTrace,
  onAir,
  spectrumLevels,
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
  upcoming,
  children
}: Props) {
  return (
    <section className="player-view" aria-label="Radio player">
      <div className="player-rail">
        <ClockHero onAir={onAir} levels={spectrumLevels} />
        <AgentDock
          messages={messages}
          mood={mood}
          setMood={setMood}
          busy={busy}
          onSubmit={onSubmit}
          onGenerate={onGenerate}
        />
      </div>

      <div className="player-main">
        <NowPlayingStage
          playback={playback}
          statusLabel={statusLabel}
          current={current}
          error={error}
          canPlayAudio={canPlayAudio}
          progressSeconds={progressSeconds}
          displayDuration={displayDuration}
          onSeek={onSeek}
          onTogglePlay={onTogglePlay}
          onPrevious={onPrevious}
          onNext={onNext}
        >
          {children}
        </NowPlayingStage>

        <CollapsiblePanel title="Queue" meta={`${playback.queue.length} items`} defaultOpen>
          <QueueList upcoming={upcoming} />
        </CollapsiblePanel>

        <CollapsiblePanel title="Agent details" meta={agentTrace?.mode ?? "ready"}>
          <AgentTracePanel trace={agentTrace} />
        </CollapsiblePanel>
      </div>
    </section>
  );
}
