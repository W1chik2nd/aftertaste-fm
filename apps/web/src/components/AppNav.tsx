import { Download, Library, Radio, Settings } from "lucide-react";
import type { ReactNode } from "react";

export type ViewId = "player" | "library" | "import" | "settings";

const items: Array<{ id: ViewId; label: string; icon: ReactNode }> = [
  { id: "player", label: "Player", icon: <Radio size={19} /> },
  { id: "library", label: "Library", icon: <Library size={19} /> },
  { id: "import", label: "Import", icon: <Download size={19} /> },
  { id: "settings", label: "Settings", icon: <Settings size={19} /> }
];

type Props = {
  activeView: ViewId;
  onChange: (view: ViewId) => void;
};

export function AppNav({ activeView, onChange }: Props) {
  return (
    <nav className="app-nav" aria-label="Primary">
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          className={activeView === item.id ? "active" : ""}
          onClick={() => onChange(item.id)}
          title={item.label}
        >
          {item.icon}
          <span>{item.label}</span>
        </button>
      ))}
    </nav>
  );
}
