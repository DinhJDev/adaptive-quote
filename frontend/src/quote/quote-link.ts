import type { Part, QuoteLinkSnapshot } from "./api";
import { decodeSelection, isRecord, quoteId } from "./checkpoint";

const keyPrefix = "adaptive-quote-link:";

function decodeLink(raw: string | null): QuoteLinkSnapshot | null {
  if (raw === null) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }

  if (!isRecord(value) || value.version !== 1 || typeof value.expiresAt !== "number"
      || !Number.isFinite(value.expiresAt) || value.expiresAt <= Date.now()
      || (value.recommendation !== null && (typeof value.recommendation !== "string" || value.recommendation.length > 120))
      || !Array.isArray(value.parts) || !isRecord(value.vehicle)) return null;

  const { id, make, model, year, entered } = value.vehicle;
  if (typeof make !== "string" || !make || make.length > 32 || typeof model !== "string" || !model || model.length > 48
      || typeof year !== "number" || !Number.isInteger(year) || year < 1886 || year > 2099
      || (entered !== undefined && typeof entered !== "boolean")) return null;

  const selection = decodeSelection({
    vehicleId: id, modStatus: value.modStatus, modIds: value.modIds, serviceId: null, addOnIds: [],
    ...(entered && { vehicleDetails: { make, model, year } }),
  });
  if (!selection) return null;
  const parts: Part[] = [];
  for (const part of value.parts) {
    if (!isRecord(part) || typeof part.id !== "string"
        || (part.id !== "unclassified" && !selection.modIds.includes(part.id))
        || typeof part.label !== "string" || !part.label || part.label.length > 500) return null;
    parts.push({ id: part.id, label: part.label });
  }
  return {
    vehicle: { id: selection.vehicleId, make, model, year, label: `${year} ${make} ${model}`, ...(entered && { entered }) },
    modStatus: selection.modStatus,
    modIds: selection.modIds,
    parts,
    recommendation: value.recommendation,
  };
}

export function openBrowserQuoteLink(id: string): QuoteLinkSnapshot | null {
  if (!quoteId.test(id)) return null;
  const storage = window.localStorage;
  const key = keyPrefix + id;
  const raw = storage.getItem(key);
  const snapshot = decodeLink(raw);
  if (raw !== null && !snapshot) storage.removeItem(key);
  return snapshot;
}

export function clearQuoteLinkFromUrl() {
  const url = new URL(window.location.href);
  if (!url.searchParams.has("quote")) return;
  url.searchParams.delete("quote");
  window.history.replaceState(window.history.state, "", url);
}
