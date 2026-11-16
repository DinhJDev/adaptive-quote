import type { components } from "./api.generated";

type Schemas = components["schemas"];

export type Catalog = Schemas["Catalog"];
export type Example = Schemas["Example"];
export type Extraction = Schemas["Extraction"];
export type ExtractionRequest = Schemas["ExtractionRequest"];
export type ModStatus = Schemas["ModStatus"];
export type Part = Schemas["Part"];
export type Preview = Schemas["Preview"];
export type PreviewRequest = Schemas["PreviewRequest"];
export type Vehicle = Schemas["Vehicle"];
export type VehicleDetails = Schemas["VehicleDetails"];
export type SaveQuoteRequest = Schemas["SaveQuoteRequest"];
export type QuoteReceipt = Schemas["QuoteReceipt"];
export type QuoteLinkSnapshot = Schemas["QuoteLinkSnapshot"];

export class ApiError extends Error {
  constructor(message: string, readonly code: string | undefined) {
    super(message);
  }
}

async function request<T>(path: string, signal: AbortSignal, body?: unknown, method = "POST"): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      signal,
      ...(body === undefined
        ? {}
        : {
            method,
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          }),
    });
  } catch {
    throw new Error("Couldn’t reach the quote service. Try again.");
  }

  if (!response.ok) {
    const problem: Partial<Schemas["Problem"]> | null = await response.json().catch(() => null);
    throw new ApiError(problem?.detail ?? "The quote service couldn’t complete the request.", problem?.code);
  }

  return response.json();
}

export function getCatalog(signal: AbortSignal) {
  return request<Catalog>("/api/v1/catalog", signal);
}

export function extractVehicle(body: ExtractionRequest, signal: AbortSignal) {
  return request<Extraction>("/api/v1/extractions", signal, body);
}

export function previewQuote(body: PreviewRequest, signal: AbortSignal) {
  return request<Preview>("/api/v1/quotes/preview", signal, body);
}

export function saveQuote(quoteId: string, body: SaveQuoteRequest) {
  return request<QuoteReceipt>("/api/v1/quotes/" + quoteId, AbortSignal.timeout(10_000), body, "PUT");
}

export function openQuote(quoteId: string) {
  return request<QuoteReceipt>("/api/v1/quotes/" + quoteId, AbortSignal.timeout(10_000));
}

export function createQuoteLink(selection: PreviewRequest, parts: Part[]) {
  return request<Schemas["QuoteLink"]>("/api/v1/quote-links", AbortSignal.timeout(10_000), {
    selection,
    parts: parts.map(({ id, label }) => ({ id, label })),
  });
}

export function openSharedQuoteLink(id: string, signal: AbortSignal) {
  return request<QuoteLinkSnapshot>("/api/v1/quote-links/" + encodeURIComponent(id), signal);
}
