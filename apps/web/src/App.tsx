import { FormEvent, useEffect, useMemo, useState } from "react";
import { Radio } from "lucide-react";
import { radioApi } from "./api";
import type { AgentTrace, HealthResponse, PlanResponse, PlaybackState, SettingsResponse } from "./types";
import { type ChatMessage } from "./components/AgentPanel";
import { AppAudio } from "./components/AppAudio";
import { LyricsPanel } from "./components/LyricsPanel";
import { AppNav } from "./components/AppNav";
import { ImportView } from "./components/views/ImportView";
import { LibraryView } from "./components/views/LibraryView";
import { PlayerView } from "./components/views/PlayerView";
import { SettingsView } from "./components/views/SettingsView";
import { usePlayer, emptyPlayback } from "./hooks/usePlayer";
import { useStoredView } from "./hooks/useStoredView";
import { activeLyricLineIndex, parseLyrics } from "./utils/lyrics";
import { weatherLabel } from "./utils/format";

function App() {
  const player = usePlayer();
  const {
    playback,
    setPlayback,
    audioRef,
    voiceRef,
    mainUrl,
    voiceUrl,
    progressSeconds,
    durationSeconds,
    setProgressSeconds,
    setDurationSeconds,
    advanceToNext,
    seek,
    error,
    setError
  } = player;

  const [activeView, setActiveView] = useStoredView();
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [settings, setSettings] = useState<SettingsResponse | null>(null);
  const [adapterStatus, setAdapterStatus] = useState("unknown");
  const [libraryRevision, setLibraryRevision] = useState(0);
  const [agentTrace, setAgentTrace] = useState<AgentTrace | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: "agent", text: "Tell me what the room needs. I will turn it into a few radio segments, not a song-by-song lecture." }
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
    void initialize();
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLyricsRaw(null);
    setLyricsLoading(false);
    if (!lyricTrack?.id) return;

    async function loadLyrics(trackId: string) {
      setLyricsLoading(true);
      try {
        const response = await radioApi.lyrics(trackId);
        if (!cancelled) setLyricsRaw(response.lyrics?.trim() || null);
      } catch (event) {
        console.warn("Could not load lyrics.", event);
        if (!cancelled) setLyricsRaw(null);
      } finally {
        if (!cancelled) setLyricsLoading(false);
      }
    }

    void loadLyrics(lyricTrack.id);
    return () => {
      cancelled = true;
    };
  }, [lyricTrack?.id]);

  async function initialize() {
    await refreshStatus();
    await refreshSettings();
    try {
      setPlayback(await radioApi.now());
    } catch (event) {
      console.warn("Could not restore playback on startup.", event);
      setPlayback(emptyPlayback);
    }
  }

  async function refreshSettings() {
    try {
      const value = await radioApi.settings();
      setSettings(value);
      setLocationInput(value.weatherLocation ?? "");
    } catch (event) {
      setError(event instanceof Error ? event.message : "Could not load settings.");
    }
  }

  async function refreshStatus() {
    const [server, adapter] = await Promise.allSettled([radioApi.health(), radioApi.adapterHealth()]);
    if (server.status === "fulfilled") setHealth(server.value);
    setAdapterStatus(adapter.status === "fulfilled" ? (adapter.value.status ?? "ok") : "offline");
  }

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
          { role: "agent", text: result.agentTrace?.summary ?? `I built ${result.showPlan.title}.` }
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
        setPlayback(await radioApi.now());
      }
      if (response.shouldPlan) {
        await run(() => radioApi.chat(message, response.routingIntent));
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

  const displayDuration = durationSeconds || (current?.type === "track" && current.track?.durationMs ? current.track.durationMs / 1000 : 0);

  return (
    <main className="shell">
      <AppAudio
        audioRef={audioRef}
        voiceRef={voiceRef}
        playback={playback}
        setProgressSeconds={setProgressSeconds}
        setDurationSeconds={setDurationSeconds}
        advanceToNext={advanceToNext}
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

      <div className="app-frame">
        <AppNav activeView={activeView} onChange={setActiveView} />
        {activeView === "player" ? (
          <PlayerView
            messages={messages}
            mood={mood}
            setMood={setMood}
            busy={busy}
            onSubmit={submitMood}
            onGenerate={() => void generateToday()}
            agentTrace={agentTrace}
            playback={playback}
            statusLabel={statusLabel}
            current={current}
            error={error}
            canPlayAudio={Boolean(mainUrl)}
            progressSeconds={progressSeconds}
            displayDuration={displayDuration}
            onSeek={seek}
            onTogglePlay={() => void run(playback.isPlaying ? radioApi.pause : radioApi.play)}
            onPrevious={() => void run(radioApi.previous)}
            onNext={() => void advanceToNext()}
            upcoming={upcoming}
          >
            {current?.track ? (
              <LyricsPanel
                lines={lyricLines}
                activeIndex={activeLyricIndex}
                loading={lyricsLoading}
                trackTitle={current.track.title}
              />
            ) : null}
          </PlayerView>
        ) : null}
        {activeView === "library" ? (
          <LibraryView
            onError={setError}
            refreshSignal={libraryRevision}
            onLibraryChanged={() => setLibraryRevision((rev) => rev + 1)}
          />
        ) : null}
        {activeView === "import" ? (
          <ImportView onError={setError} onLibraryChanged={() => setLibraryRevision((rev) => rev + 1)} />
        ) : null}
        {activeView === "settings" ? (
          <SettingsView
            health={health}
            adapterStatus={adapterStatus}
            settings={settings}
            locationInput={locationInput}
            setLocationInput={setLocationInput}
            onSaveLocation={saveLocation}
            onRefreshStatus={() => { void refreshStatus(); void refreshSettings(); }}
            busy={busy}
          />
        ) : null}
      </div>

      {error && activeView !== "player" ? <div className="global-error">{error}</div> : null}
    </main>
  );
}

export default App;
