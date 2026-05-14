import { useEffect, useState } from "react";
import { isBrowserOffline } from "../utils/network";

export function StatusStrip() {
  const [online, setOnline] = useState(() => !isBrowserOffline());

  useEffect(() => {
    const goOnline = () => setOnline(true);
    const goOffline = () => setOnline(false);
    window.addEventListener("online", goOnline);
    window.addEventListener("offline", goOffline);
    return () => {
      window.removeEventListener("online", goOnline);
      window.removeEventListener("offline", goOffline);
    };
  }, []);

  return (
    <footer className="status-strip" aria-label="Connection status">
      <span>Aftertaste FM.</span>
      <span className="status-strip-dots" aria-hidden="true" />
      <span>{online ? "Connected." : "Offline."}</span>
    </footer>
  );
}
