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

type LyricLine = {
  id: string;
  time: number | null;
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
  const [lyricsRaw, setLyricsRaw] = useState<string | null>(null);
  const [lyricsLoading, setLyricsLoading] = useState(false);

  const current = playback.currentItem;
  const lyricTrack = current?.track ?? null;
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

          <div className="model-note" aria-label="Model quality note">
            <Sparkles size={16} />
            <p>
              Best results need a model that follows JSON reliably across 30-50 candidate songs and can write natural
              radio copy. Smaller or cheaper models are fine for chat, but the show planner benefits from stronger
              instruction following.
            </p>
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

          {current?.track ? (
            <LyricsPanel
              lines={lyricLines}
              activeIndex={activeLyricIndex}
              loading={lyricsLoading}
              trackTitle={current.track.title}
            />
          ) : null}

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

function LyricsPanel({
  lines,
  activeIndex,
  loading,
  trackTitle
}: {
  lines: LyricLine[];
  activeIndex: number;
  loading: boolean;
  trackTitle: string;
}) {
  const activeRef = useRef<HTMLParagraphElement | null>(null);

  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [activeIndex]);

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
      <div className="lyrics-scroll">
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

function formatTime(value: number) {
  if (!Number.isFinite(value) || value <= 0) return "0:00";
  const minutes = Math.floor(value / 60);
  const seconds = Math.floor(value % 60);
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

function parseLyrics(raw: string | null): LyricLine[] {
  if (!raw) return [];
  const timed: LyricLine[] = [];
  const plain: string[] = [];
  const timePattern = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g;

  raw.split(/\r?\n/).forEach((line, lineIndex) => {
    const trimmed = line.trim();
    if (!trimmed || /^\[(ar|al|ti|by|offset|kana|tool):/i.test(trimmed)) return;

    const matches = [...trimmed.matchAll(timePattern)];
    const text = trimmed.replace(timePattern, "").trim();
    if (!matches.length) {
      if (text) plain.push(text);
      return;
    }
    if (!text) return;

    matches.forEach((match, matchIndex) => {
      const minutes = Number(match[1]);
      const seconds = Number(match[2]);
      const fraction = match[3] ? Number(`0.${match[3].padEnd(3, "0").slice(0, 3)}`) : 0;
      timed.push({
        id: `${lineIndex}-${matchIndex}-${minutes}-${seconds}`,
        time: minutes * 60 + seconds + fraction,
        text
      });
    });
  });

  if (timed.length) return timed.sort((left, right) => (left.time ?? 0) - (right.time ?? 0));
  return plain.map((text, index) => ({ id: `plain-${index}`, time: null, text }));
}

function activeLyricLineIndex(lines: LyricLine[], currentTime: number) {
  if (!lines.some((line) => line.time != null)) return -1;
  let active = 0;
  for (let index = 0; index < lines.length; index += 1) {
    const time = lines[index].time;
    if (time == null) continue;
    if (time <= currentTime + 0.25) active = index;
    else break;
  }
  return active;
}

export default App;
