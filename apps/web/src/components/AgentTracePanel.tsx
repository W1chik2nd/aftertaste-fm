import type { AgentTrace } from "../types";

/** The agent's reasoning trace — signals plus context/strategy. Lives behind a collapsible. */
export function AgentTracePanel({ trace }: { trace: AgentTrace | null }) {
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
