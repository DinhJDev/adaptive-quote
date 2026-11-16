import type { Extraction, PreviewRequest, SaveQuoteRequest, VehicleDetails } from "./api";

export const checkpointKey = "adaptive-quote-checkpoint";
export const retentionMs = 7 * 24 * 60 * 60 * 1000;

export type SaveAttempt = { quoteId: string; body: SaveQuoteRequest };
export type Checkpoint =
  | {
      version: 1;
      kind: "draft";
      expiresAt: number;
      source: Extraction["source"] | "manual" | null;
      selection: PreviewRequest;
      pending: SaveAttempt | null;
    }
  | { version: 1; kind: "receipt"; expiresAt: number; quoteId: string };

const catalogId = /^[a-z0-9][a-z0-9-]{0,39}$/;
export const quoteId = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function id(value: unknown): value is string {
  return typeof value === "string" && catalogId.test(value);
}

function ids(value: unknown, limit: number): value is string[] {
  return Array.isArray(value) && value.length <= limit && value.every(id) && new Set(value).size === value.length;
}

export function decodeSelection(value: unknown): PreviewRequest | null {
  if (!isRecord(value)
      || !id(value.vehicleId)
      || (value.modStatus !== "stock" && value.modStatus !== "modified" && value.modStatus !== "unsure")
      || !ids(value.modIds, 6)
      || (value.serviceId !== null && !id(value.serviceId))
      || !ids(value.addOnIds, 1)) {
    return null;
  }
  let vehicleDetails: VehicleDetails | undefined;
  if (value.vehicleDetails !== undefined) {
    const details = value.vehicleDetails;
    if (!isRecord(details) || typeof details.make !== "string" || details.make.length > 32
      || typeof details.model !== "string" || details.model.length > 48
      || typeof details.year !== "number" || !Number.isInteger(details.year)
      || details.year < 1886 || details.year > 2099 || !/^entered-[0-9a-f]{24}$/.test(value.vehicleId)) return null;
    vehicleDetails = { make: details.make, model: details.model, year: details.year };
  } else if (value.vehicleId.startsWith("entered-")) {
    return null;
  }
  return {
    vehicleId: value.vehicleId,
    modStatus: value.modStatus,
    modIds: value.modIds,
    serviceId: value.serviceId,
    addOnIds: value.addOnIds,
    ...(vehicleDetails && { vehicleDetails }),
  };
}

function attempt(value: unknown): SaveAttempt | null {
  if (!isRecord(value) || typeof value.quoteId !== "string" || !quoteId.test(value.quoteId)
      || !isRecord(value.body) || !id(value.body.catalogVersion) || value.body.confirmed !== true) {
    return null;
  }
  const savedSelection = decodeSelection(value.body.selection);
  if (!savedSelection || savedSelection.serviceId === null) return null;
  return {
    quoteId: value.quoteId,
    body: { catalogVersion: value.body.catalogVersion, confirmed: true, selection: savedSelection },
  };
}

export function decodeCheckpoint(raw: string, now = Date.now()): Checkpoint | null {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(value) || value.version !== 1 || typeof value.expiresAt !== "number"
      || !Number.isFinite(value.expiresAt) || value.expiresAt <= now) return null;

  if (value.kind === "receipt" && typeof value.quoteId === "string" && quoteId.test(value.quoteId)) {
    return { version: 1, kind: "receipt", expiresAt: value.expiresAt, quoteId: value.quoteId };
  }
  if (value.kind !== "draft" || (value.source !== "fixture" && value.source !== "catalog"
      && value.source !== "live" && value.source !== "manual" && value.source !== null)) {
    return null;
  }
  const savedSelection = decodeSelection(value.selection);
  const pending = attempt(value.pending);
  if (!savedSelection || (value.pending !== null && !pending)) return null;

  return {
    version: 1,
    kind: "draft",
    expiresAt: value.expiresAt,
    source: value.source,
    selection: pending?.body.selection ?? savedSelection,
    pending,
  };
}
