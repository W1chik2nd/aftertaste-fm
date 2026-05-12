import { FormEvent, useEffect, useMemo, useState } from "react";
import { Radio } from "lucide-react";
import { radioApi } from "./api";
import type {
  AgentTrace,
  HealthResponse,
  PlanResponse,
  PlaybackState,
  SettingsResponse
} from "./types";
import { AgentPanel, type ChatMessage } from "./components/AgentPanel";
import { PlaybackPanel } from "./components/PlaybackPanel";
import { QueuePanel } from "./components/QueuePanel";
import { StatusStrip } from "./components/StatusStrip";
import { LyricsPanel } from "./components/LyricsPanel";
import { usePlayer, emptyPlayback } from "./hooks/usePlayer";
import { activeLyricLineIndex, parseLyrics } from "./utils/lyrics";
import { weatherLabel } from "./utils/format";

function App() {
  const player = usePlayer();
  const { playback, setPlayback, audioRef, voiceRef, mainUrl, voiceUrl,
    progressSeconds, durationSeconds, setProgressSeconds, setDurationSeconds,
    advanceToNext, seek, error, setError } = player;

  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [settings, setSettings] = useState<SettingsResponse | null>(null);
  const [adapterStatus, setAdapterStatus] = useState("unknown");
  const [agentTrace, setAgentTrace] = useState<AgentTrace | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      role: "agent",
      text: "Tell me what the room needs. I will turn it into a few radio segments, not a song-by-song lecture."
    }
  ]);
  const [mood, setMood] = useState("");
  const [locationInput, setLocationInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [lyricsRaw, setLyricsRaw] = useState<string | null>(null);
  const [lyricsLoading, setLyricsLoading] = useState(false);

  const current = playback.currentItem;
  const lyricTrack = current?.track ?? null;
  const statusLabel = playback.isPlaying
    ? current?.type === "host_voice"
      ? voiceUrl
        ? "Speaking over the lead"
        : "Playing the lead"
      : "Playing"
    : "Paused";
  const upcoming = useMemo(
    () => playback.queue.slice(playback.currentIndex + 1, playback.currentIndex + 7),
    [playback.currentIndex, playback.queue]
  );
  const lyricLines = useMemo(() => parseLyrics(lyricsRaw), [lyricsRaw]);
  const activeLyricIndex = useMemo(
    () => activeLyricLineIndex(lyricLines, progressSeconds),
    [lyricLines, progressSeconds]
  );

  useEffect(() => {
    void refreshStatus();
    void radioApi.clearPlayback().then(setPlayback).catch(() => setPlayback(emptyPlayback));
    void radioApi.settings().then((value) => {
      setSettings(value);
      setLocationInput(value.weatherLocation ?? "");
    }).catch(() => undefined);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLyricsRaw(null);
    setLyricsLoading(false);

    if (!lyricTrack?.id) return;

    setLyricsLoading(true);
    void radioApi.lyrics(lyricTrack.id)
      .then((response) => {
        if (!cancelled) setLyricsRaw(response.lyrics?.trim() || null);
      })
      .catch(() => {
        if (!cancelled) setLyricsRaw(null);
      })
      .finally(() => {
        if (!cancelled) setLyricsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [lyricTrack?.id]);

  async function run(action: () => Promise<PlaybackState | PlanResponse>) {
    setBusy(true);
    setError(null);
    try {
      const result = await action();
      if ("playback" in result) {
        setPlayback(result.playback);
        setAgentTrace(result.agentTrace ?? null);
        setMessages((currentMessages) => [
          ...currentMessages,
          {
            role: "agent",
            text: result.agentTrace?.summary ?? `I built ${result.showPlan.title}.`
          }
        ]);
      } else {
        setPlayback(result);
      }
    } catch (event) {
      setError(event instanceof Error ? event.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  async function refreshStatus() {
    const [server, adapter] = await Promise.allSettled([
      radioApi.health(),
      radioApi.adapterHealth()
    ]);

    if (server.status === "fulfilled") setHealth(server.value);
    if (adapter.status === "fulfilled") setAdapterStatus(adapter.value.status ?? "ok");
    if (adapter.status === "rejected") setAdapterStatus("offline");
  }

  async function saveLocation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const location = locationInput.trim();
    if (!location) return;
    setBusy(true);
    setError(null);
    try {
      const nextSettings = await radioApi.setLocation(location);
      setSettings(nextSettings);
      setLocationInput(nextSettings.weatherLocation ?? location);
      setMessages((currentMessages) => [
        ...currentMessages,
        {
          role: "agent",
          text: nextSettings.weather
            ? `Weather context set: ${weatherLabel(nextSettings)}. I’ll use it when shaping the next show.`
            : "Location saved, but I could not refresh weather yet."
        }
      ]);
    } catch (event) {
      setError(event instanceof Error ? event.message : "Could not update weather location.");
    } finally {
      setBusy(false);
    }
  }

  async function submitMood(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = mood.trim();
    if (!message) return;
    setMessages((currentMessages) => [...currentMessages, { role: "user", text: message }]);
    setMood("");
    await sendAgentMessage(message);
  }

  async function sendAgentMessage(message: string) {
    setBusy(true);
    setError(null);
    try {
      const response = await radioApi.agentChat(message);
      setMessages((currentMessages) => [...currentMessages, { role: "agent", text: response.message }]);
      if (response.command && response.command !== "now") {
        await radioApi.now().then(setPlayback);
      }
      if (response.shouldPlan) {
        await run(() => radioApi.chat(message));
      }
    } catch (event) {
      setError(event instanceof Error ? event.message : "Chat failed.");
    } finally {
      setBusy(false);
    }
  }

  async function generateToday() {
    setMessages((currentMessages) => [...currentMessages, { role: "user", text: "Generate today's show." }]);
    await run(radioApi.planToday);
  }

  const canPlayAudio = Boolean(mainUrl);
  const displayDuration =
    durationSeconds || (current?.type === "track" && current.track?.durationMs ? current.track.durationMs / 1000 : 0);

  return (
    <main className="shell">
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

      <header className="app-header" aria-label="Aftertaste FM">
        <div className="brand">
          <Radio size={18} />
          <span>Aftertaste FM</span>
        </div>
        <div className="topbar-status">
          <span>{health?.provider ?? "mock"} provider</span>
          <span>{playback.hostLanguage}</span>
        </div>
      </header>

      <section className="workspace" aria-label="Radio workspace">
        <AgentPanel
          messages={messages}
          mood={mood}
          setMood={setMood}
          busy={busy}
          onSubmit={submitMood}
          onGenerate={() => void generateToday()}
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
          onSeek={seek}
          onTogglePlay={() => void run(playback.isPlaying ? radioApi.pause : radioApi.play)}
          onPrevious={() => void run(radioApi.previous)}
          onNext={() => void advanceToNext()}
        >
          {current?.track ? (
            <LyricsPanel
              lines={lyricLines}
              activeIndex={activeLyricIndex}
              loading={lyricsLoading}
              trackTitle={current.track.title}
            />
          ) : null}
        </PlaybackPanel>

        <QueuePanel queueLength={playback.queue.length} upcoming={upcoming} />
      </section>

      <StatusStrip
        health={health}
        adapterStatus={adapterStatus}
        settings={settings}
        locationInput={locationInput}
        setLocationInput={setLocationInput}
        onSaveLocation={saveLocation}
        busy={busy}
      />
    </main>
  );
}

export default App;
