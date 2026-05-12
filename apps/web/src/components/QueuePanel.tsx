import type { QueueItem } from "../types";

type Props = {
  queueLength: number;
  upcoming: QueueItem[];
};

export function QueuePanel({ queueLength, upcoming }: Props) {
  return (
    <aside className="queue-panel" aria-label="Queue">
      <div className="section-heading">
        <span>Queue</span>
        <strong>{queueLength} items</strong>
      </div>
      <ol className="queue-list">
        {upcoming.length ? (
          upcoming.map((item) => <QueueRow key={item.id} item={item} />)
        ) : (
          <li className="queue-empty">No upcoming items yet.</li>
        )}
      </ol>
    </aside>
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
