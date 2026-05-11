import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Languages,
  Loader2,
  MessageCircle,
  Pause,
  Play,
  Radio,
  RefreshCw,
  Send,
  Server,
  Sparkles,
  Wifi,
  CloudSun
} from "lucide-react";
import { radioApi, resolveMediaUrl } from "./api";
import type { AgentTrace, HealthResponse, PlaybackState, PlanResponse, QueueItem, SettingsResponse } from "./types";

const emptyPlayback: PlaybackState = {
  currentItem: null,
  currentIndex: 0,
  queue: [],
  showTitle: null,
  segmentTitle: null,
  isPlaying: false,
  progressMs: 0,
  durationMs: null,
  hostLanguage: "en-US"
};

type ChatMessage = {
  role: "user" | "agent";
  text: string;
};

function App() {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const voiceRef = useRef<HTMLAudioElement | null>(null);
  const advancingRef = useRef(false);
  const [playback, setPlayback] = useState<PlaybackState>(emptyPlayback);
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
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [progressSeconds, setProgressSeconds] = useState(0);
  const [durationSeconds, setDurationSeconds] = useState(0);

  const current = playback.currentItem;
  const mainMediaUrl = resolveMediaUrl(
    current?.type === "track" ? current.track?.streamUrl : current?.track?.streamUrl ?? current?.ttsUrl
  );
  const voiceOverlayUrl = resolveMediaUrl(
    current?.type === "host_voice" && current.track?.streamUrl ? current.ttsUrl : null
  );
  const statusLabel = playback.isPlaying
    ? current?.type === "host_voice"
      ? voiceOverlayUrl
        ? "Speaking over the lead"
        : "Playing the lead"
      : "Playing"
    : "Paused";
  const upcoming = useMemo(
    () => playback.queue.slice(playback.currentIndex + 1, playback.currentIndex + 7),
    [playback.currentIndex, playback.queue]
  );

  useEffect(() => {
    void refreshStatus();
    void radioApi.now().then(setPlayback).catch(() => undefined);
    void radioApi.settings().then((value) => {
      setSettings(value);
      setLocationInput(value.weatherLocation ?? "");
    }).catch(() => undefined);
  }, []);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    setProgressSeconds(0);
    setDurationSeconds(0);
    audio.pause();
    audio.currentTime = 0;

    if (!mainMediaUrl) {
      audio.removeAttribute("src");
      audio.load();
      return;
    }

    audio.src = mainMediaUrl;
    audio.load();
  }, [current?.id, mainMediaUrl]);

  useEffect(() => {
    const voice = voiceRef.current;
    if (!voice) return;

    if (!voiceOverlayUrl) {
      voice.removeAttribute("src");
      voice.load();
      return;
    }

    voice.src = voiceOverlayUrl;
    voice.currentTime = 0;
    voice.load();
  }, [current?.id, voiceOverlayUrl]);

  useEffect(() => {
    const audio = audioRef.current;
    const voice = voiceRef.current;

    if (!playback.isPlaying) {
      audio?.pause();
      voice?.pause();
      return;
    }

    if (mainMediaUrl && audio) {
      audio.volume = voiceOverlayUrl ? 0.16 : 1;
      if (audio.ended || audio.currentTime >= (audio.duration || Infinity)) {
        audio.currentTime = 0;
      }
      void audio.play().catch(() => setError("Audio could not start. The item may be unavailable."));
    }
    if (voiceOverlayUrl && voice) {
      voice.volume = 1;
      void voice.play().catch(() => setError("Host voice could not start, but the lead track can keep playing."));
    }
  }, [current?.id, mainMediaUrl, playback.isPlaying, voiceOverlayUrl]);

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

  async function advanceToNext(keepQuiet = true) {
    if (advancingRef.current) return;
    advancingRef.current = true;
    setError(null);
    try {
      audioRef.current?.pause();
      voiceRef.current?.pause();
      if (voiceRef.current) voiceRef.current.currentTime = 0;
      const nextState = await radioApi.next();
      setPlayback(nextState);
      if (!keepQuiet) {
        setMessages((currentMessages) => [...currentMessages, { role: "agent", text: "Skipping to the next item." }]);
      }
    } catch (event) {
      setError(event instanceof Error ? event.message : "Could not advance playback.");
    } finally {
      advancingRef.current = false;
    }
  }

  async function refreshStatus() {
    const [server, adapter] = await Promise.allSettled([
      radioApi.health(),
      fetch("http://localhost:8090/health").then((response) => response.json())
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

    const quickIntent = classifyQuickIntent(message);
    if (quickIntent === "next") {
      await advanceToNext(false);
      return;
    }
    if (quickIntent === "previous") {
      await run(radioApi.previous);
      return;
    }
    if (quickIntent === "pause") {
      await run(radioApi.pause);
      return;
    }
    if (quickIntent === "play") {
      await run(radioApi.play);
      return;
    }
    if (quickIntent === "now") {
      setMessages((currentMessages) => [
        ...currentMessages,
        {
          role: "agent",
          text: current
            ? `On air: ${current.type === "host_voice" ? current.track?.title ?? current.segmentTitle : current.track?.title} ${current.track?.artist ? `by ${current.track.artist}` : ""}.`
            : "Nothing is on air yet. Generate a show first."
        }
      ]);
      return;
    }

    if (isPlanningRequest(message)) {
      await run(() => radioApi.chat(message));
      return;
    }

    await replyToOrdinaryChat(message);
  }

  async function replyToOrdinaryChat(message: string) {
    setBusy(true);
    setError(null);
    try {
      const response = await radioApi.agentChat(message);
      setMessages((currentMessages) => [...currentMessages, { role: "agent", text: response.message }]);
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

  const canPlayAudio = Boolean(mainMediaUrl);
  const displayDuration =
    durationSeconds || (current?.type === "track" && current.track?.durationMs ? current.track.durationMs / 1000 : 0);
  const progressPercent = displayDuration ? Math.min(100, (progressSeconds / displayDuration) * 100) : 0;

  return (
    <main className="shell">
      <audio
        ref={audioRef}
        onLoadedMetadata={(event) => setDurationSeconds(event.currentTarget.duration || 0)}
        onTimeUpdate={(event) => setProgressSeconds(event.currentTarget.currentTime || 0)}
        onEnded={() => {
          setProgressSeconds(0);
          void advanceToNext(true);
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
        <section className="agent-panel" aria-label="AI radio agent">
          <div className="agent-intro">
            <p className="eyebrow">private ai radio</p>
            <h1>What should the station feel like?</h1>
            <p>Describe the room. Aftertaste turns it into a hosted radio segment, then lets the music run.</p>
          </div>

          <div className="section-heading">
            <span>Radio Agent</span>
            <strong>{agentTrace?.mode ?? "ready"}</strong>
          </div>

          <div className="conversation" aria-label="Agent conversation">
            {messages.map((message, index) => (
              <div className={`bubble ${message.role}`} key={`${message.role}-${index}`}>
                <span>{message.role === "user" ? "You" : "Aftertaste"}</span>
                <p>{message.text}</p>
              </div>
            ))}
          </div>

          <form className="composer" onSubmit={submitMood}>
            <MessageCircle size={18} />
            <input
              value={mood}
              onChange={(event) => setMood(event.target.value)}
              placeholder="less sad, but still soft"
              aria-label="Mood instruction"
            />
            <button type="submit" disabled={busy || !mood.trim()} title="Tune with this prompt">
              {busy ? <Loader2 className="spin" size={18} /> : <Send size={18} />}
            </button>
          </form>

          <button className="generate-button" type="button" onClick={() => void generateToday()} disabled={busy}>
            {busy ? <Loader2 className="spin" size={18} /> : <Sparkles size={18} />}
            Generate Today&apos;s Show
          </button>

          <AgentTracePanel trace={agentTrace} />
        </section>

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
                style={{ "--progress": `${progressPercent}%` } as React.CSSProperties}
                onChange={(event) => {
                  const next = Number(event.currentTarget.value);
                  setProgressSeconds(next);
                  if (audioRef.current) audioRef.current.currentTime = next;
                }}
              />
              <span>{formatTime(displayDuration)}</span>
            </div>

            <div className="controls" aria-label="Playback controls">
              <button type="button" className="icon-button" onClick={() => void run(radioApi.previous)} title="Previous">
                <ChevronLeft size={23} />
              </button>
              <button
                type="button"
                className="play-button"
                onClick={() => void run(playback.isPlaying ? radioApi.pause : radioApi.play)}
                title={playback.isPlaying ? "Pause" : "Play"}
              >
                {playback.isPlaying ? <Pause size={26} /> : <Play size={26} />}
              </button>
              <button type="button" className="icon-button" onClick={() => void advanceToNext(false)} title="Next">
                <ChevronRight size={23} />
              </button>
            </div>
          </div>
        </section>

        <aside className="queue-panel" aria-label="Queue">
          <div className="section-heading">
            <span>Queue</span>
            <strong>{playback.queue.length} items</strong>
          </div>
          <ol className="queue-list">
            {upcoming.length ? (
              upcoming.map((item) => <QueueRow key={item.id} item={item} />)
            ) : (
              <li className="queue-empty">No upcoming items yet.</li>
            )}
          </ol>
        </aside>
      </section>

      <section className="settings-strip" aria-label="Settings and status">
        <StatusCell icon={<Server size={18} />} label="radio-server" value={health?.status ?? "unknown"} />
        <StatusCell icon={<Wifi size={18} />} label="provider" value={health?.provider ?? "mock"} />
        <StatusCell icon={<RefreshCw size={18} />} label="netease-adapter" value={adapterStatus} />
        <StatusCell icon={<CloudSun size={18} />} label="weather" value={weatherLabel(settings)} />
        <StatusCell icon={<Languages size={18} />} label="host language" value="English" />
        <form className="location-cell" onSubmit={saveLocation}>
          <CloudSun size={18} />
          <label htmlFor="weather-location">location</label>
          <input
            id="weather-location"
            value={locationInput}
            onChange={(event) => setLocationInput(event.target.value)}
            placeholder="Leeds"
          />
          <button type="submit" disabled={busy || !locationInput.trim()} title="Save weather location">
            Save
          </button>
        </form>
      </section>
    </main>
  );
}

function AgentTracePanel({ trace }: { trace: AgentTrace | null }) {
  const context = trace?.contextWindow ?? [
    "taste profile: waiting",
    "routine: waiting",
    "provider candidates: waiting"
  ];
  const strategy = trace?.recommendationStrategy ?? [
    "The agent will group tracks into segments.",
    "Host copy stays between groups, not before every song."
  ];

  return (
    <div className="trace-panel" aria-label="Agent reasoning">
      <div className="trace-signals">
        {(trace?.signals ?? [{ label: "mode", value: "mock-first" }]).slice(0, 4).map((signal) => (
          <div key={`${signal.label}-${signal.value}`}>
            <span>{signal.label}</span>
            <strong>{signal.value}</strong>
          </div>
        ))}
      </div>

      <div className="trace-columns">
        <TraceList title="Context" items={context} />
        <TraceList title="Strategy" items={strategy} />
      </div>
    </div>
  );
}

function TraceList({ title, items }: { title: string; items: string[] }) {
  return (
    <div>
      <h3>{title}</h3>
      <ul>
        {items.slice(0, 4).map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
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

function QueueRow({ item }: { item: QueueItem }) {
  const hostTitle = item.track ? `${item.segmentTitle} · ${item.track.title}` : item.segmentTitle;
  const hostDetail = item.track ? `${item.track.artist} · host over lead` : item.hostScript;
  return (
    <li className={item.type === "host_voice" ? "queue-host" : ""}>
      <span>{item.type === "host_voice" ? "Host" : "Track"}</span>
      <div>
        <strong>{item.type === "host_voice" ? hostTitle : item.track?.title}</strong>
        <small>{item.type === "host_voice" ? hostDetail : item.track?.artist}</small>
      </div>
    </li>
  );
}

function StatusCell({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="status-cell">
      {icon}
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function weatherLabel(settings: SettingsResponse | null) {
  const weather = settings?.weather;
  if (!weather) return settings?.weatherLocation ? "waiting" : "not set";
  return `${weather.locationName} · ${weather.condition}, ${Math.round(weather.temperatureC)}C`;
}

type QuickIntent = "next" | "previous" | "pause" | "play" | "now" | null;

function classifyQuickIntent(message: string): QuickIntent {
  const text = message.trim().toLowerCase();
  if (/^(next|skip|skip this|下一首|换歌|跳过)$/.test(text)) return "next";
  if (/^(previous|prev|back|上一首|回上一首)$/.test(text)) return "previous";
  if (/^(pause|stop|暂停|停一下)$/.test(text)) return "pause";
  if (/^(play|resume|continue|继续|播放)$/.test(text)) return "play";
  if (/^(what'?s playing|what is playing|now playing|这是什么|现在放什么|正在放什么)$/.test(text)) return "now";
  return null;
}

function isPlanningRequest(message: string) {
  const text = message.toLowerCase();
  return [
    "play ",
    "give me",
    "i want",
    "i'd like",
    "more ",
    "less ",
    "something",
    "song",
    "songs",
    "music",
    "playlist",
    "show",
    "radio",
    "english",
    "chinese",
    "中文",
    "英文",
    "安静",
    "悲伤",
    "开心",
    "换一组",
    "来点",
    "想听",
    "推荐"
  ].some((token) => text.includes(token));
}

function formatTime(value: number) {
  if (!Number.isFinite(value) || value <= 0) return "0:00";
  const minutes = Math.floor(value / 60);
  const seconds = Math.floor(value % 60);
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export default App;
