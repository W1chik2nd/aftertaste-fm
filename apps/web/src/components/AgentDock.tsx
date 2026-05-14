import { useState, type FormEvent } from "react";
import { ChevronDown, Loader2, MessageCircle, Send, Sparkles } from "lucide-react";

export type ChatMessage = { role: "user" | "agent"; text: string };

type Props = {
  messages: ChatMessage[];
  mood: string;
  setMood: (value: string) => void;
  busy: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onGenerate: () => void;
};

/**
 * The integrated agent console. Conversation is visible by default, while the
 * header can collapse it when the listener wants a quieter rail.
 */
export function AgentDock({ messages, mood, setMood, busy, onSubmit, onGenerate }: Props) {
  const [historyOpen, setHistoryOpen] = useState(true);
  const exchangeCount = messages.filter((message) => message.role === "user").length;

  return (
    <section className="agent-dock" aria-label="AI radio agent">
      <button
        type="button"
        className={`agent-dock-toggle ${historyOpen ? "open" : ""}`}
        aria-expanded={historyOpen}
        onClick={() => setHistoryOpen((value) => !value)}
      >
        <MessageCircle size={15} aria-hidden="true" />
        <span>Aftertaste Agent</span>
        <span className="agent-dock-count">{exchangeCount ? `${exchangeCount} sent` : "ready"}</span>
        <ChevronDown className="collapsible-chevron" size={15} aria-hidden="true" />
      </button>

      {historyOpen ? <Conversation messages={messages} /> : null}

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
    </section>
  );
}

function Conversation({ messages }: { messages: ChatMessage[] }) {
  return (
    <div className="conversation" aria-label="Agent conversation">
      {messages.map((message, index) => (
        <div className={`bubble ${message.role}`} key={`${message.role}-${index}`}>
          <span>{message.role === "user" ? "You" : "Aftertaste"}</span>
          <p>{message.text}</p>
        </div>
      ))}
    </div>
  );
}
