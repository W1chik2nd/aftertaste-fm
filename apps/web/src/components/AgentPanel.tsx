import { FormEvent } from "react";
import { Loader2, MessageCircle, Send, Sparkles } from "lucide-react";
import type { AgentTrace } from "../types";

export type ChatMessage = { role: "user" | "agent"; text: string };

type Props = {
  messages: ChatMessage[];
  mood: string;
  setMood: (value: string) => void;
  busy: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onGenerate: () => void;
  agentTrace: AgentTrace | null;
};

export function AgentPanel({ messages, mood, setMood, busy, onSubmit, onGenerate, agentTrace }: Props) {
  return (
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

      <form className="composer" onSubmit={onSubmit}>
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

      <button className="generate-button" type="button" onClick={onGenerate} disabled={busy}>
        {busy ? <Loader2 className="spin" size={18} /> : <Sparkles size={18} />}
        Generate Today&apos;s Show
      </button>

      <AgentTracePanel trace={agentTrace} />
    </section>
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
