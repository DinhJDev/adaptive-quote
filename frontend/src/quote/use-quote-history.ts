import { useEffect, useEffectEvent, useLayoutEffect, useRef } from "react";
import { quoteSteps } from "./workflow";
import type { Step } from "./workflow";

type Entry = { flow: string; step: Step };

function entryFrom(state: unknown): Entry | null {
  if (!state || typeof state !== "object" || !("adaptiveQuote" in state)) return null;
  const entry = state.adaptiveQuote;
  if (!entry || typeof entry !== "object" || !("flow" in entry) || !("step" in entry)
      || typeof entry.flow !== "string" || !quoteSteps.includes(entry.step as Step)) return null;
  return { flow: entry.flow, step: entry.step as Step };
}

function record(entry: Entry, replace: boolean) {
  const state = { ...window.history.state, adaptiveQuote: entry };
  if (replace) window.history.replaceState(state, "");
  else window.history.pushState(state, "");
}

export function useQuoteHistory(step: Step, visit: (target: Step) => Step) {
  const flow = useRef<string | null>(null);
  const visitCurrent = useEffectEvent(visit);
  const initialStep = useEffectEvent(() => step);

  useEffect(() => {
    const key = crypto.randomUUID();
    flow.current = key;
    record({ flow: key, step: initialStep() }, true);

    function pop(event: PopStateEvent) {
      const entry = entryFrom(event.state);
      if (entry?.flow !== key) return;
      const destination = visitCurrent(entry.step);
      if (destination !== entry.step) record({ flow: key, step: destination }, true);
    }

    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, []);

  useLayoutEffect(() => {
    if (!flow.current) return;
    const current = entryFrom(window.history.state);
    if (current?.flow === flow.current && current.step !== step) {
      record({ flow: flow.current, step }, false);
    }
  }, [step]);
}
