import { useCallback, useEffect, useRef, useState } from "react";
import { checkpointKey, decodeCheckpoint } from "./checkpoint";
import type { Checkpoint } from "./checkpoint";

export function useCheckpoint() {
  const [checkpoint, setCheckpoint] = useState<Checkpoint | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const lastStored = useRef<string | null>(null);
  const stale = useRef(false);

  const warnStale = useCallback(() => {
    stale.current = true;
    setWarning("Another tab changed this draft. Reload to use that version. Edits here won’t autosave.");
  }, []);

  useEffect(() => {
    function changed(event: StorageEvent) {
      if ((event.key === checkpointKey || event.key === null) && event.newValue !== lastStored.current) {
        warnStale();
      }
    }

    const timer = window.setTimeout(() => {
      try {
        const raw = window.localStorage.getItem(checkpointKey);
        const restored = raw === null ? null : decodeCheckpoint(raw);
        if (raw !== null && restored === null) {
          window.localStorage.removeItem(checkpointKey);
          setWarning("The previous quote expired or could not be read.");
        } else {
          lastStored.current = raw;
          setCheckpoint(restored);
        }
      } catch {
        setWarning("This browser’s storage is unavailable. You can continue in this tab.");
      } finally {
        setReady(true);
      }
    }, 0);

    window.addEventListener("storage", changed);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("storage", changed);
    };
  }, [warnStale]);

  const persist = useCallback((value: Checkpoint | null) => {
    setCheckpoint(value);
    if (stale.current) return;
    try {
      const raw = window.localStorage.getItem(checkpointKey);
      if (raw !== lastStored.current) {
        warnStale();
        return;
      }
      const serialized = value === null ? null : JSON.stringify(value);
      if (serialized === null) window.localStorage.removeItem(checkpointKey);
      else window.localStorage.setItem(checkpointKey, serialized);
      lastStored.current = serialized;
    } catch {
      setWarning("This browser’s storage is unavailable. You can continue in this tab.");
    }
  }, [warnStale]);

  return { checkpoint, warning, ready, persist };
}
