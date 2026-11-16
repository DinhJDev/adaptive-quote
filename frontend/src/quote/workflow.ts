import type { Extraction, ModStatus, Part, Preview, PreviewRequest, QuoteLinkSnapshot, Vehicle } from "./api";

export const quoteSteps = ["intake", "vehicle", "mods", "contact", "ready", "review", "receipt"] as const;
export type Step = (typeof quoteSteps)[number];

export type ModificationDraft = { status: ModStatus | null; ids: string[]; description?: string };

type QuoteState = {
  step: Step;
  vehicle: Vehicle | null;
  candidates: Vehicle[];
  source: Extraction["source"] | "manual" | null;
  fallbackReason: Extraction["fallbackReason"];
  matchStatus: Extraction["match"]["status"] | null;
  vehicleHint: Extraction["match"]["hint"];
  contactConfirmed: boolean;
  modStatus: ModStatus | null;
  modIds: string[];
  parts: Part[];
  linkedQuote?: { recommendation: string | null; sharedUrl?: string };
  modificationDraft: ModificationDraft | null;
  serviceId: string | null;
  addOnIds: string[];
  preview: Preview | null;
  priceCurrent: boolean;
  extractionRequest: number | null;
  previewRequest: number | null;
  error: string | null;
};

type Event =
  | { type: "reset" }
  | { type: "edit-intake" }
  | { type: "manual"; vehicle: Vehicle }
  | { type: "start-intake"; id: number }
  | { type: "extracted"; id: number; extraction: Extraction }
  | { type: "extraction-failed"; id: number; message: string; fallbackReason?: Extraction["fallbackReason"] }
  | { type: "edit-vehicle" }
  | { type: "select-vehicle"; vehicle: Vehicle }
  | { type: "edit-mods" }
  | { type: "change-mods"; draft: ModificationDraft }
  | { type: "confirm-mods"; status: ModStatus; modIds: string[]; parts?: Part[] }
  | { type: "confirm-contact" }
  | { type: "open-checkout" }
  | { type: "restore"; vehicle: Vehicle; selection: PreviewRequest; source: QuoteState["source"] }
  | { type: "restore-link"; snapshot: QuoteLinkSnapshot; sharedUrl?: string }
  | { type: "navigate"; step: Step }
  | { type: "show-receipt"; explicit: boolean }
  | { type: "select-service"; id: string }
  | { type: "toggle-addon"; id: string }
  | { type: "start-preview"; id: number }
  | { type: "priced"; id: number; preview: Preview }
  | { type: "pricing-failed"; id: number; message: string };

export const initialState: QuoteState = {
  step: "intake",
  vehicle: null,
  candidates: [],
  source: null,
  fallbackReason: undefined,
  matchStatus: null,
  vehicleHint: undefined,
  contactConfirmed: false,
  modStatus: null,
  modIds: [],
  parts: [],
  modificationDraft: null,
  serviceId: null,
  addOnIds: [],
  preview: null,
  priceCurrent: false,
  extractionRequest: null,
  previewRequest: null,
  error: null,
};

const stalePrice = { priceCurrent: false, previewRequest: null, error: null } as const;

export function reachableStep(state: QuoteState, step: Step, hasReceipt: boolean, hasPending: boolean): Step {
  if (step === "intake") return step;
  if (step === "receipt" && hasReceipt) return step;
  if (step === "review" && hasPending) return step;
  if (step === "vehicle" && state.source) return step;
  if (!state.vehicle) return state.source ? "vehicle" : "intake";
  if (step === "mods" || !state.modStatus || state.modificationDraft) return "mods";
  if (step === "contact" || !state.contactConfirmed) return "contact";
  if (step === "ready") return "ready";
  return "review";
}

export function transition(state: QuoteState, event: Event): QuoteState {
  switch (event.type) {
    case "reset":
      return initialState;
    case "edit-intake":
      return { ...initialState, step: "intake" };
    case "manual":
      return { ...initialState, step: "mods", source: "manual", vehicle: event.vehicle };
    case "navigate":
      return state.step === event.step
        ? state
        : { ...state, ...stalePrice, extractionRequest: null, step: event.step };
    case "show-receipt":
      return event.explicit || state.step === "review" ? { ...state, step: "receipt" } : state;
    case "restore":
      return {
        ...initialState,
        step: "review",
        contactConfirmed: true,
        source: event.source,
        vehicle: event.vehicle,
        modStatus: event.selection.modStatus,
        modIds: event.selection.modIds,
        serviceId: event.selection.serviceId,
        addOnIds: event.selection.addOnIds,
      };
    case "restore-link": {
      const { vehicle, modStatus, modIds, parts, recommendation } = event.snapshot;
      return {
        ...initialState, step: "ready", contactConfirmed: true,
        vehicle, modStatus, modIds, parts, linkedQuote: { recommendation, sharedUrl: event.sharedUrl },
      };
    }
    case "start-intake":
      return { ...initialState, extractionRequest: event.id };
    case "extraction-failed":
      return state.extractionRequest === event.id
        ? { ...state, extractionRequest: null, error: event.message, fallbackReason: event.fallbackReason }
        : state;
    case "extracted": {
      if (state.extractionRequest !== event.id) return state;

      const { match, facts } = event.extraction;
      const vehicle = match.status === "matched" ? match.candidates[0] : null;
      return {
        ...initialState,
        step: vehicle ? "mods" : "vehicle",
        source: event.extraction.source,
        fallbackReason: event.extraction.fallbackReason,
        matchStatus: match.status,
        vehicleHint: match.hint,
        vehicle,
        candidates: match.candidates,
        modIds: facts.modIds,
        parts: facts.parts ?? [],
        modStatus: facts.stockMention ? "stock" : null,
      };
    }
    case "edit-vehicle":
      return { ...state, ...stalePrice, step: "vehicle", candidates: [], matchStatus: null, vehicleHint: undefined };
    case "select-vehicle": {
      const changed = state.vehicle !== null && state.vehicle.id !== event.vehicle.id;
      return {
        ...state,
        ...stalePrice,
        step: "mods",
        vehicle: event.vehicle,
        modIds: changed ? [] : state.modIds,
        parts: changed ? [] : state.parts,
        linkedQuote: changed ? undefined : state.linkedQuote,
        modStatus: changed ? null : state.modStatus,
        modificationDraft: changed ? null : state.modificationDraft,
        serviceId: changed ? null : state.serviceId,
        addOnIds: changed ? [] : state.addOnIds,
        preview: changed ? null : state.preview,
      };
    }
    case "edit-mods":
      return { ...state, ...stalePrice, step: "mods" };
    case "change-mods":
      return { ...state, ...stalePrice, modificationDraft: event.draft };
    case "confirm-mods": {
      const changed =
        state.modStatus !== event.status ||
        state.modIds.length !== event.modIds.length ||
        event.modIds.some((id) => !state.modIds.includes(id));
      return {
        ...state,
        ...stalePrice,
        step: state.contactConfirmed ? "review" : "contact",
        modStatus: event.status,
        modIds: changed ? event.modIds : state.modIds,
        parts: event.parts ?? state.parts,
        linkedQuote: undefined,
        modificationDraft: null,
        serviceId: changed ? null : state.serviceId,
        addOnIds: changed ? [] : state.addOnIds,
        preview: changed ? null : state.preview,
      };
    }
    case "confirm-contact":
      return { ...state, ...stalePrice, step: "ready", contactConfirmed: true };
    case "open-checkout":
      return {
        ...state, ...stalePrice, step: "review",
        serviceId: state.serviceId ?? state.preview?.recommendedServiceId ?? null,
      };
    case "select-service":
      return state.serviceId === event.id
        ? state
        : { ...state, ...stalePrice, serviceId: event.id, addOnIds: [] };
    case "toggle-addon":
      return {
        ...state,
        ...stalePrice,
        addOnIds: state.addOnIds.includes(event.id) ? [] : [event.id],
      };
    case "start-preview":
      return { ...state, ...stalePrice, previewRequest: event.id };
    case "priced":
      return state.previewRequest === event.id
        ? { ...state, previewRequest: null, preview: event.preview, priceCurrent: true }
        : state;
    case "pricing-failed":
      return state.previewRequest === event.id
        ? { ...state, previewRequest: null, error: event.message }
        : state;
  }
}
