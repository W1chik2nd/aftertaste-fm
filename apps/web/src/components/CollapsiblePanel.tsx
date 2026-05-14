import { useId, useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";

type Props = {
  title: string;
  /** Optional short value shown on the right of the header (e.g. item count). */
  meta?: ReactNode;
  defaultOpen?: boolean;
  children: ReactNode;
};

/** Reusable disclosure panel — calm header with a chevron, content hidden until opened. */
export function CollapsiblePanel({ title, meta, defaultOpen = false, children }: Props) {
  const [open, setOpen] = useState(defaultOpen);
  const regionId = useId();

  return (
    <section className={`collapsible ${open ? "open" : ""}`}>
      <button
        type="button"
        className="collapsible-head"
        aria-expanded={open}
        aria-controls={regionId}
        onClick={() => setOpen((value) => !value)}
      >
        <ChevronDown className="collapsible-chevron" size={15} aria-hidden="true" />
        <span className="collapsible-title">{title}</span>
        {meta != null ? <span className="collapsible-meta">{meta}</span> : null}
      </button>
      {/* Kept mounted (just hidden) so aria-controls always points at a real element. */}
      <div className="collapsible-body" id={regionId} hidden={!open}>
        {children}
      </div>
    </section>
  );
}
