import type { FormEvent, ReactNode } from "react";
import type { AgentTrace, PlaybackState, QueueItem } from "../../types";
import { AgentPanel, type ChatMessage } from "../AgentPanel";
import { PlaybackPanel } from "../PlaybackPanel";
import { QueuePanel } from "../QueuePanel";

type Props = {
  messages: ChatMessage[];
  mood: string;
  setMood: (value: string) => void;
  busy: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onGenerate: () => void;
  agentTrace: AgentTrace | null;
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

export function PlayerView({
  messages,
  mood,
  setMood,
  busy,
  onSubmit,
  onGenerate,
  agentTrace,
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
    <section className="workspace" aria-label="Radio workspace">
      <AgentPanel
        messages={messages}
        mood={mood}
        setMood={setMood}
        busy={busy}
        onSubmit={onSubmit}
        onGenerate={onGenerate}
        agentTrace={agentTrace}
      />

      <PlaybackPanel
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
      </PlaybackPanel>

      <QueuePanel queueLength={playback.queue.length} upcoming={upcoming} />
    </section>
  );
}
