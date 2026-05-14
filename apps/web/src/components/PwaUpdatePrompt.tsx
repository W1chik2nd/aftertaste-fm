import { useEffect, useState } from "react";
import { RefreshCw, WifiOff, X } from "lucide-react";
import { useRegisterSW } from "virtual:pwa-register/react";
import { isBrowserOffline } from "../utils/network";

export function PwaUpdatePrompt() {
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [online, setOnline] = useState(() => !isBrowserOffline());
  const {
    needRefresh: [needRefresh],
    offlineReady: [offlineReady, setOfflineReady],
    updateServiceWorker
  } = useRegisterSW({
    onRegisterError(error) {
      console.warn("Service worker registration failed.", error);
    }
  });

  useEffect(() => {
    const updateOnline = () => setOnline(true);
    const updateOffline = () => setOnline(false);
    window.addEventListener("online", updateOnline);
    window.addEventListener("offline", updateOffline);
    return () => {
      window.removeEventListener("online", updateOnline);
      window.removeEventListener("offline", updateOffline);
    };
  }, []);

  if (needRefresh) {
    return (
      <PwaToast
        icon={<RefreshCw size={18} />}
        title="New version ready"
        message={updateError ?? "Refresh when the current moment is safe; playback will stay on this version until then."}
        actionLabel="Refresh"
        onAction={() => void applyUpdate(updateServiceWorker, setUpdateError)}
      />
    );
  }

  if (!online) {
    return (
      <PwaToast
        icon={<WifiOff size={18} />}
        title="Offline"
        message="Cached station data is still available. Live controls and audio streams need the server."
      />
    );
  }

  if (offlineReady) {
    return (
      <PwaToast
        icon={<WifiOff size={18} />}
        title="Ready offline"
        message="The app shell is cached for the next weak-network session."
        actionLabel="Dismiss"
        onAction={() => setOfflineReady(false)}
      />
    );
  }

  return null;
}

type PwaToastProps = {
  icon: React.ReactNode;
  title: string;
  message: string;
  actionLabel?: string;
  onAction?: () => void;
};

function PwaToast({ icon, title, message, actionLabel, onAction }: PwaToastProps) {
  return (
    <aside className="pwa-toast" aria-live="polite">
      {icon}
      <div className="pwa-toast-copy">
        <strong>{title}</strong>
        <p>{message}</p>
      </div>
      {actionLabel && onAction ? (
        <button type="button" className="pwa-toast-action" onClick={onAction} title={actionLabel}>
          {actionLabel === "Dismiss" ? <X size={16} /> : <RefreshCw size={16} />}
          <span>{actionLabel}</span>
        </button>
      ) : null}
    </aside>
  );
}

async function applyUpdate(
  updateServiceWorker: (reloadPage?: boolean) => Promise<void>,
  setUpdateError: (message: string | null) => void
) {
  setUpdateError(null);
  try {
    await updateServiceWorker(true);
  } catch (event) {
    console.warn("Could not activate the waiting service worker.", event);
    setUpdateError("Could not refresh yet. Try again after this track.");
  }
}
