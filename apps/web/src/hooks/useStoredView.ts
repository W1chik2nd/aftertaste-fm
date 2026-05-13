import { useEffect, useState } from "react";
import type { ViewId } from "../components/AppNav";

const ACTIVE_VIEW_STORAGE_KEY = "aftertaste.activeView";
const viewIds: ViewId[] = ["player", "library", "import", "settings"];

export function useStoredView(): [ViewId, (view: ViewId) => void] {
  const [activeView, setActiveView] = useState<ViewId>(() => readStoredView());

  useEffect(() => {
    try {
      window.localStorage.setItem(ACTIVE_VIEW_STORAGE_KEY, activeView);
    } catch (event) {
      console.warn("Could not store active view.", event);
    }
  }, [activeView]);

  return [activeView, setActiveView];
}

function readStoredView(): ViewId {
  if (typeof window === "undefined") return "player";
  try {
    const stored = window.localStorage.getItem(ACTIVE_VIEW_STORAGE_KEY);
    return isViewId(stored) ? stored : "player";
  } catch (event) {
    console.warn("Could not read active view.", event);
    return "player";
  }
}

function isViewId(value: string | null): value is ViewId {
  return value !== null && viewIds.some((viewId) => viewId === value);
}
