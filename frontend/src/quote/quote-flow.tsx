"use client";

import { useEffect, useMemo, useReducer, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { ApiError, extractVehicle, getCatalog, openQuote, openSharedQuoteLink, previewQuote, saveQuote } from "./api";
import type { Catalog, Extraction, Part, PreviewRequest, QuoteReceipt, Vehicle, VehicleDetails } from "./api";
import { quoteId, retentionMs } from "./checkpoint";
import type { Checkpoint, SaveAttempt } from "./checkpoint";
import { CopyQuoteLink } from "./copy-quote-link";
import { clearQuoteLinkFromUrl, openBrowserQuoteLink } from "./quote-link";
import { ModificationsStep } from "./modifications-step";
import { emptyIntake, QuoteIntake } from "./quote-intake";
import { QuoteHeader } from "./quote-header";
import type { IntakeDraft, QuoteLayout } from "./quote-intake";
import { QuoteReview } from "./quote-review";
import { ReceiptView } from "./receipt-view";
import { useCheckpoint } from "./use-checkpoint";
import { useQuoteHistory } from "./use-quote-history";
import { initialState, reachableStep, transition } from "./workflow";
import type { ModificationDraft, Step } from "./workflow";
import { readVehicleDetails, VehicleDetailsFields } from "./vehicle-details-fields";

const steps = [
  { label: "Vehicle", screens: ["intake", "vehicle"] },
  { label: "Modifications", screens: ["mods"] },
  { label: "Details", screens: ["contact"] },
  { label: "Quote", screens: ["ready", "review"] },
];

const headings: Record<Exclude<Step, "intake" | "receipt">, string> = {
  vehicle: "Choose your vehicle",
  mods: "Confirm your modifications",
  contact: "Review your build",
  ready: "Your quote is ready",
  review: "Compare tune stages",
};

const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : "Something went wrong. Try again.";

const fallbackMessages: Record<NonNullable<Extraction["fallbackReason"]>, string> = {
  unavailable: "Live extraction is unavailable. We checked the demo catalog.",
  limited: "Live extraction is temporarily limited. We checked the demo catalog.",
  private_input: "We checked the demo catalog to keep possible contact details out of live extraction.",
};

const intakeSources = {
  manual: "Manual entry",
  live: "OpenAI extraction",
  fixture: "Demo example",
  catalog: "Catalog match",
};

const referenceNotes: Record<NonNullable<Vehicle["referenceMatch"]>, string> = {
  nameplate: "Model listed in VehiclesDB",
  vpic_exact: "U.S. year, make, and model matched by NHTSA vPIC",
  unlisted: "Model unverified",
  year_outside_range: "Year outside recorded range",
};

export function QuoteFlow({ layout }: { layout: QuoteLayout }) {
  const [state, dispatch] = useReducer(transition, initialState);
  const [intake, setIntake] = useState(emptyIntake);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [catalogAttempt, retryCatalog] = useReducer((value: number) => value + 1, 0);
  const [previewAttempt, retryPreview] = useReducer((value: number) => value + 1, 0);
  const { checkpoint, warning, ready, persist } = useCheckpoint();
  const [pending, setPending] = useState<SaveAttempt | null>(null);
  const [receipt, setReceipt] = useState<QuoteReceipt | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [modNotice, setModNotice] = useState<string | null>(null);
  const [working, setWorking] = useState(false);
  const requestActive = useRef(false);
  const nextRequestId = useRef(0);
  const extraction = useRef<AbortController | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const previousStep = useRef(state.step);
  const activeStep = steps.findIndex((step) => step.screens.includes(state.step));

  useQuoteHistory(state.step, (target) => {
    const destination = reachableStep(state, target, receipt !== null, pending !== null);
    if (destination !== state.step) {
      extraction.current?.abort();
      setConfirmed(false);
      dispatch({ type: "navigate", step: destination });
    }
    return destination;
  });

  useEffect(() => {
    const controller = new AbortController();
    getCatalog(controller.signal).then(setCatalog).catch((error: unknown) => {
      if (!controller.signal.aborted) setCatalogError(errorMessage(error));
    });
    return () => controller.abort();
  }, [catalogAttempt]);

  useEffect(() => () => extraction.current?.abort(), []);

  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("quote");
    if (id === null) return;
    const controller = new AbortController();
    extraction.current = controller;
    const link = window.location.href;
    async function restoreLink(id: string) {
      const unavailable = "This quote link has expired or is unavailable.";
      if (!quoteId.test(id)) {
        setSaveError(unavailable);
        return;
      }
      try {
        const snapshot = await openSharedQuoteLink(id, controller.signal);
        if (!controller.signal.aborted) dispatch({ type: "restore-link", snapshot, sharedUrl: link });
      } catch (error) {
        if (controller.signal.aborted) return;
        if (!(error instanceof ApiError) || error.code !== "QUOTE_LINK_NOT_FOUND") {
          setSaveError(errorMessage(error));
          return;
        }
        try {
          const snapshot = openBrowserQuoteLink(id);
          if (snapshot) {
            dispatch({ type: "restore-link", snapshot });
            return;
          }
        } catch {
          // Legacy links depend on browser storage.
        }
        setSaveError(unavailable);
      }
    }
    const timer = window.setTimeout(() => void restoreLink(id), 0);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, []);

  useEffect(() => {
    if (previousStep.current !== state.step) heading.current?.focus();
    previousStep.current = state.step;
  }, [state.step]);

  const selection = useMemo<PreviewRequest | null>(() => {
    if ((state.step !== "review" && state.step !== "ready") || !state.vehicle || !state.modStatus) return null;
    return {
      vehicleId: state.vehicle.id,
      ...(state.vehicle.entered && { vehicleDetails: {
        make: state.vehicle.make, model: state.vehicle.model, year: state.vehicle.year,
      } }),
      modStatus: state.modStatus,
      modIds: state.modIds,
      serviceId: state.serviceId,
      addOnIds: state.addOnIds,
    };
  }, [state.step, state.vehicle, state.modStatus, state.modIds, state.serviceId, state.addOnIds]);

  useEffect(() => {
    if (!selection || pending || receipt) return;
    const controller = new AbortController();
    const id = ++nextRequestId.current;
    const timer = window.setTimeout(() => {
      dispatch({ type: "start-preview", id });
      previewQuote(selection, controller.signal).then((preview) => {
        dispatch({ type: "priced", id, preview });
      }).catch((error: unknown) => {
        if (!controller.signal.aborted) dispatch({ type: "pricing-failed", id, message: errorMessage(error) });
      });
    }, 120);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [selection, previewAttempt, pending, receipt]);

  useEffect(() => {
    if (!ready || !selection || !state.contactConfirmed || state.linkedQuote || pending || receipt) return;
    const timer = window.setTimeout(() => {
      persist({
        version: 1, kind: "draft", expiresAt: Date.now() + retentionMs,
        source: state.source, selection, pending: null,
      });
    }, 0);
    return () => window.clearTimeout(timer);
  }, [ready, selection, state.contactConfirmed, state.linkedQuote, state.source, pending, receipt, persist]);

  function resumeDraft(draft: Extract<Checkpoint, { kind: "draft" }>) {
    const details = draft.selection.vehicleDetails;
    const vehicle = details ? {
      ...details, id: draft.selection.vehicleId, entered: true as const,
      label: `${details.year} ${details.make} ${details.model}`,
    } : catalog?.vehicles.find((option) => option.id === draft.selection.vehicleId);
    if (vehicle) {
      dispatch({ type: "restore", vehicle, selection: draft.selection, source: draft.source });
    } else if (!draft.pending) {
      persist(null);
      setSaveError("The vehicle in this draft is no longer in the catalog.");
      return;
    } else {
      dispatch({ type: "navigate", step: "review" });
    }
    setPending(draft.pending);
    setSaveError(null);
  }

  function showReceipt(saved: QuoteReceipt, explicit = false) {
    setReceipt(saved);
    setPending(null);
    dispatch({ type: "show-receipt", explicit });
    persist({ version: 1, kind: "receipt", quoteId: saved.quoteId, expiresAt: Date.parse(saved.expiresAt) });
  }

  async function reopen(quoteId: string) {
    if (requestActive.current) return;
    requestActive.current = true;
    setWorking(true);
    setSaveError(null);
    try {
      showReceipt(await openQuote(quoteId), true);
    } catch (error) {
      setSaveError(errorMessage(error));
    } finally {
      requestActive.current = false;
      setWorking(false);
    }
  }

  async function save(attempt: SaveAttempt) {
    if (requestActive.current) return;
    requestActive.current = true;
    setSaveError(null);
    setWorking(true);
    setPending(attempt);
    if (!pending) {
      persist({
        version: 1, kind: "draft", expiresAt: Date.now() + retentionMs,
        source: state.source, selection: attempt.body.selection, pending: attempt,
      });
    }
    try {
      showReceipt(await saveQuote(attempt.quoteId, attempt.body));
    } catch (error) {
      if (error instanceof ApiError && error.code === "CATALOG_CHANGED") {
        setPending(null);
        setConfirmed(false);
        persist({
          version: 1, kind: "draft", expiresAt: Date.now() + retentionMs,
          source: state.source, selection: attempt.body.selection, pending: null,
        });
        retryPreview();
      }
      setSaveError(errorMessage(error));
    } finally {
      requestActive.current = false;
      setWorking(false);
    }
  }

  function saveSelection() {
    if (!confirmed || !selection?.serviceId || !state.priceCurrent || !state.preview) return;
    void save({
      quoteId: crypto.randomUUID(),
      body: { confirmed: true, catalogVersion: state.preview.catalogVersion, selection },
    });
  }

  function restart(keepSavedReceipt = false) {
    extraction.current?.abort();
    clearQuoteLinkFromUrl();
    if (!keepSavedReceipt || checkpoint?.kind !== "receipt") persist(null);
    setReceipt(null);
    setPending(null);
    setConfirmed(false);
    setSaveError(null);
    setModNotice(null);
    setWorking(false);
    setIntake(emptyIntake);
    dispatch({ type: "reset" });
  }

  function changeIntake(next: IntakeDraft) {
    setIntake(next);
    const newAnswer = next.description !== intake.description || next.vehicleId !== intake.vehicleId;
    const changedModeDuringLookup = next.manual !== intake.manual && state.extractionRequest !== null;
    if (!newAnswer && !changedModeDuringLookup) return;
    clearQuoteLinkFromUrl();
    extraction.current?.abort();
    setConfirmed(false);
    setSaveError(null);
    dispatch({ type: "edit-intake" });
  }

  async function submitIntake(details?: VehicleDetails) {
    setSaveError(null);
    setReceipt(null);
    extraction.current?.abort();
    if (intake.manual && !details) {
      const vehicle = catalog?.vehicles.find(({ id }) => id === intake.vehicleId);
      if (vehicle) dispatch({ type: "manual", vehicle });
      return;
    }
    const text = intake.description.trim();
    if (!details && !text) {
      setSaveError("Enter a vehicle description to continue.");
      return;
    }
    const controller = new AbortController();
    extraction.current = controller;
    const requestId = ++nextRequestId.current;
    dispatch({ type: "start-intake", id: requestId });
    try {
      const result = await extractVehicle(details
        ? { mode: "manual", vehicle: details }
        : { mode: catalog?.liveEnabled ? "live" : "catalog", text }, controller.signal);
      if (result.match.status === "unsupported") {
        dispatch({
          type: "extraction-failed",
          id: requestId,
          message: "We couldn’t identify your vehicle from that description. Edit it or enter the vehicle details manually.",
          fallbackReason: result.fallbackReason,
        });
        return;
      }
      dispatch({ type: "extracted", id: requestId, extraction: result });
    } catch (error) {
      if (!controller.signal.aborted) {
        dispatch({ type: "extraction-failed", id: requestId, message: errorMessage(error) });
      }
    }
  }

  async function confirmVehicleDetails(details: VehicleDetails) {
    const controller = new AbortController();
    extraction.current?.abort();
    extraction.current = controller;
    setSaveError(null);
    setWorking(true);
    try {
      const result = await extractVehicle({ mode: "manual", vehicle: details }, controller.signal);
      const vehicle = result.match.candidates[0];
      if (vehicle && !controller.signal.aborted) dispatch({ type: "select-vehicle", vehicle });
    } catch (error) {
      if (!controller.signal.aborted) setSaveError(errorMessage(error));
    } finally {
      if (extraction.current === controller) setWorking(false);
    }
  }

  async function confirmModifications(draft: ModificationDraft) {
    setSaveError(null);
    setModNotice(null);
    const text = draft.description?.trim();
    const controller = new AbortController();
    extraction.current?.abort();
    extraction.current = controller;
    if (text) setWorking(true);
    try {
      const result = text ? await extractVehicle({
        mode: catalog?.liveEnabled ? "live" : "catalog", scope: "modifications", text,
      }, controller.signal) : null;
      if (controller.signal.aborted) return;

      const ids = [...new Set([...draft.ids, ...(result?.facts.modIds ?? [])])];
      const retained = state.parts.filter(({ id }) => draft.ids.includes(id)
        || (id === "unclassified" && !text && draft.status === "unsure"));
      const parts = [...retained, ...(result?.facts.parts ?? [])].filter((part, index, all) =>
        all.findIndex((other) => other.id === part.id && other.label.toLowerCase() === part.label.toLowerCase()) === index);
      const unclassified = parts.some(({ id }) => id === "unclassified")
        || !!(text && !result?.facts.stockMention && result?.facts.modIds.length === 0);
      const status = draft.status === "unsure" || unclassified
        ? "unsure" : ids.some((id) => id !== "misc") ? "modified" : "stock";
      if (result?.fallbackReason) setModNotice(fallbackMessages[result.fallbackReason]);
      dispatch({ type: "confirm-mods", status, modIds: ids, parts });
    } catch (error) {
      if (!controller.signal.aborted) setSaveError(errorMessage(error));
    } finally {
      if (extraction.current === controller) setWorking(false);
    }
  }

  const needsDetails = state.matchStatus === "incomplete" && state.candidates.length === 0;
  const vehicles = needsDetails ? [] : state.candidates.length > 0 ? state.candidates : catalog?.vehicles ?? [];
  const selectedMods = catalog?.modifications.filter((mod) => state.modIds.includes(mod.id)) ?? [];
  const parts = [...state.parts, ...selectedMods.filter((mod) => !state.parts.some((part) => part.id === mod.id))];
  const recommendation = state.preview?.eligibleServices.find(({ id }) => id === state.preview?.recommendedServiceId);
  const readyRecommendation = state.linkedQuote ? state.linkedQuote.recommendation : recommendation?.label;
  const intakeNotice = state.fallbackReason ? fallbackMessages[state.fallbackReason] : undefined;
  const unverified = state.matchStatus === "unverified";
  const suggestedDetails = state.vehicleHint ?? (unverified ? state.candidates[0] : state.vehicle ?? undefined);

  const hasProgress = state.step !== "receipt" && (state.step !== "intake" || state.vehicle !== null
    || Boolean(intake.description.trim()) || Boolean(intake.vehicleId) || state.extractionRequest !== null);

  function goToStepOne() {
    if (hasProgress || state.step === "receipt") restart(true);
    else {
      extraction.current?.abort();
      clearQuoteLinkFromUrl();
      setSaveError(null);
    }
    window.requestAnimationFrame(() => heading.current?.focus());
  }

  function frame(content: ReactNode) {
    if (layout !== "standalone") return content;
    return (
      <div className="quote-theme standalone-shell">
        <QuoteHeader hasProgress={hasProgress} busy={working} onStart={goToStepOne} />
        <main className="site-width studio-main">{content}</main>
      </div>
    );
  }

  if (state.step === "receipt") {
    return frame(receipt ? <ReceiptView receipt={receipt} onRestart={() => restart()} /> : null);
  }

  if (state.step === "review" && pending) {
    return frame(
      <section className="work-panel" aria-labelledby="pending-heading">
        <h2 id="pending-heading">{working ? "Saving quote…" : "Finish saving your quote"}</h2>
        <div className="step-content">
          {saveError && <p className="notice" role="alert">{saveError}</p>}
          {!working && <p>Retry this save to recover the original receipt if it already went through.</p>}
          {warning && <p role="status">{warning}</p>}
          {working ? <p role="status">Waiting for the quote service.</p> : (
            <div className="context-actions">
              <button className="primary" onClick={() => void save(pending)}>Retry save</button>
              <button className="secondary" onClick={() => restart()}>Start a new quote</button>
            </div>
          )}
        </div>
      </section>
    );
  }

  const progress = (
    <nav aria-label="Quote progress" className="progress">
        <ol>
          {steps.map((step, index) => (
            <li key={step.label} data-past={index < activeStep}
              aria-current={index === activeStep ? "step" : undefined}>
              {step.label}
            </li>
          ))}
        </ol>
    </nav>
  );

  if (state.step === "intake") {
    const recovery = checkpoint?.kind === "draft" ? (
      <Button variant="outline" onClick={() => resumeDraft(checkpoint)}>
        {checkpoint.pending ? "Resume pending save" : "Resume saved draft"}
      </Button>
    ) : checkpoint?.kind === "receipt" ? (
      <Button variant="outline" disabled={working} onClick={() => void reopen(checkpoint.quoteId)}>
        {working ? "Opening quote…" : "Reopen saved quote"}
      </Button>
    ) : null;

    return frame(
      <>
        {layout === "standalone" && progress}
        <QuoteIntake layout={layout} headingRef={heading} catalog={catalog} intake={intake}
          busy={state.extractionRequest !== null || (!catalog && !catalogError)}
          error={saveError ?? state.error ?? catalogError}
          notice={intakeNotice}
          onChange={changeIntake} onSubmit={(details) => void submitIntake(details)}
          recovery={warning || recovery || catalogError ? (
            <>
              {warning && <p role="status">{warning}</p>}
              {recovery}
              {catalogError && <Button variant="outline" onClick={() => { setCatalogError(null); retryCatalog(); }}>Retry catalog</Button>}
            </>
          ) : undefined} />
      </>
    );
  }

  return frame(
    <div className="quote-steps">
      {progress}
      <div className="workspace">
        <section className="work-panel" aria-labelledby="step-heading">
          <h2 id="step-heading" ref={heading} tabIndex={-1}>{headings[state.step]}</h2>
          {warning && <p className="field-help" role="status">{warning}</p>}
          {intakeNotice && <p className="field-help" role="status">{intakeNotice}</p>}
          {saveError && <p className="notice" role="alert">{saveError}</p>}
          {!catalog && !catalogError && <p role="status">Loading the demo catalog…</p>}
          {catalogError && (
            <div className="notice" role="alert">
              <p>{catalogError}</p>
              <button className="secondary" onClick={() => { setCatalogError(null); retryCatalog(); }}>
                Retry catalog
              </button>
            </div>
          )}
          {catalog && state.step === "vehicle" && (
            <div className="step-content">
              <p className="muted">{state.matchStatus === "conflict"
                ? "Your description has conflicting details. Confirm the right vehicle."
                : unverified
                  ? "Confirm or edit the vehicle details. A vehicle we can’t verify can still go to a tuner for custom pricing."
                : state.candidates.length > 0
                  ? "Confirm which vehicle matches your description."
                  : needsDetails ? "Confirm the make, model, and year so we can review your build."
                    : "Select a vehicle or enter its details."}</p>
              {state.candidates.length > 6 ? (
                <form className="step-content" onSubmit={(event) => {
                  event.preventDefault();
                  const id = new FormData(event.currentTarget).get("vehicle");
                  const vehicle = vehicles.find((candidate) => candidate.id === id);
                  if (vehicle) dispatch({ type: "select-vehicle", vehicle });
                }}>
                  <Label htmlFor="clarify-vehicle">Vehicle and year</Label>
                  <NativeSelect id="clarify-vehicle" name="vehicle" defaultValue="" required>
                    <NativeSelectOption value="">Choose a match</NativeSelectOption>
                    {vehicles.map((vehicle) => (
                      <NativeSelectOption key={vehicle.id} value={vehicle.id}>{vehicle.label}</NativeSelectOption>
                    ))}
                  </NativeSelect>
                  <div><Button type="submit" disabled={working}>Continue</Button></div>
                </form>
              ) : vehicles.length > 0 && <div className="offer-list">
                {vehicles.map((vehicle) => (
                  <button className="example vehicle-choice" key={vehicle.id}
                    disabled={working}
                    onClick={() => dispatch({ type: "select-vehicle", vehicle })}>
                    <span>
                      <strong>{vehicle.label}</strong>
                      {vehicle.referenceMatch && <small className="field-help block">{referenceNotes[vehicle.referenceMatch]}</small>}
                    </span>
                    <span className="example-arrow" aria-hidden="true">→</span>
                  </button>
                ))}
              </div>}
              <details open={needsDetails || undefined}>
                <summary>{unverified ? "Edit vehicle details" : "Enter vehicle details manually"}</summary>
                <form className="step-content" onSubmit={(event) => {
                  event.preventDefault();
                  void confirmVehicleDetails(readVehicleDetails(event.currentTarget));
                }}>
                  <VehicleDetailsFields catalog={catalog} suggested={suggestedDetails} />
                  <div><Button type="submit" disabled={working}>Confirm vehicle</Button></div>
                </form>
              </details>
              <div><button className="secondary" onClick={() => {
                extraction.current?.abort();
                dispatch({ type: "navigate", step: "intake" });
              }}>Edit description</button></div>
            </div>
          )}
          {catalog && state.step === "mods" && (
            <ModificationsStep key={state.vehicle?.id} modifications={catalog.modifications}
              draft={state.modificationDraft ?? {
                ids: state.modIds,
                status: state.modStatus ?? (state.parts.some(({ id }) => id === "unclassified") ? "unsure" : null),
              }}
              busy={working} liveEnabled={catalog.liveEnabled}
              onChange={(draft) => dispatch({ type: "change-mods", draft })}
              onConfirm={(draft) => void confirmModifications(draft)} />
          )}
          {state.step === "ready" && (
            <div className="step-content">
              {state.error && <div className="notice" role="alert">
                <p>{state.error}</p><Button variant="outline" onClick={retryPreview}>Retry pricing</Button>
              </div>}
              <div className="total-panel">
                <h3>{state.vehicle?.label}</h3>
                {(state.linkedQuote || state.priceCurrent) && (
                  <p>{readyRecommendation ? `Recommended · ${readyRecommendation}` : "This build needs custom pricing."}</p>
                )}
                {!state.priceCurrent && !state.error && <p role="status">Checking current quote options…</p>}
                {state.linkedQuote && state.priceCurrent && readyRecommendation !== (recommendation?.label ?? null) && (
                  <p>{recommendation ? `Checkout now recommends ${recommendation.label}.` : "Checkout now requires custom pricing."}</p>
                )}
              </div>
              <PartSummary parts={parts} />
              <div><Button onClick={() => dispatch({ type: "open-checkout" })} disabled={!state.priceCurrent}>
                See your quote
              </Button></div>
              {selection && (state.priceCurrent || state.linkedQuote?.sharedUrl) && (
                <CopyQuoteLink selection={selection} parts={parts} sharedUrl={state.linkedQuote?.sharedUrl} />
              )}
            </div>
          )}
          {state.step === "review" && (receipt ? (
            <div className="step-content">
              <p>This quote is already saved. Reopen the receipt to check the original total.</p>
              <div><Button onClick={() => dispatch({ type: "show-receipt", explicit: true })}>View saved quote</Button></div>
            </div>
          ) : (
            <>
              <QuoteReview preview={state.preview} serviceId={state.serviceId} addOnIds={state.addOnIds}
                priceCurrent={state.priceCurrent} error={state.error}
                onSelectService={(id) => { setConfirmed(false); dispatch({ type: "select-service", id }); }}
                onToggleAddOn={(id) => { setConfirmed(false); dispatch({ type: "toggle-addon", id }); }}
                onRetry={retryPreview} />
              {state.priceCurrent && state.preview?.totalMinor != null && (
                <div className="step-content">
                  <label className="choice">
                    <input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
                    Confirm these demo prices
                  </label>
                  <div><button className="primary" disabled={!confirmed || working} onClick={saveSelection}>
                    Save quote
                  </button></div>
                </div>
              )}
            </>
          ))}
          {state.step === "contact" && (
            <div className="step-content">
              <PartSummary parts={parts} />
              {modNotice && <p className="field-help" role="status">{modNotice}</p>}
              <h3 className="font-semibold">Demo contact</h3>
              <p>This is a fictional contact. Your draft stays in this browser.</p>
              <dl className="demo-contact">
                <div><dt>Name</dt><dd>Alex Demo</dd></div>
                <div><dt>Email</dt><dd>alex@adaptive-quote.example</dd></div>
              </dl>
              <div><Button onClick={() => dispatch({ type: "confirm-contact" })}>
                Continue to quote <span aria-hidden="true">→</span>
              </Button></div>
            </div>
          )}
        </section>

        <aside className="context-panel" aria-labelledby="context-heading">
          <h2 id="context-heading">Your configuration</h2>
          <dl>
            <div>
              <dt>Vehicle</dt>
              <dd>
                {state.vehicle?.label ?? "No vehicle selected"}
                {state.vehicle?.referenceMatch === "vpic_exact" && (
                  <small className="field-help block">{referenceNotes.vpic_exact}</small>
                )}
              </dd>
            </div>
            <div>
              <dt>Modifications</dt>
              <dd>{selectedMods.length > 0
                ? selectedMods.map((mod) => mod.label).join(", ")
                : state.modStatus === "stock" ? "Stock" : state.modStatus === "unsure" ? "Unsure" : "Awaiting confirmation"}</dd>
            </div>
            <div>
              <dt>Intake</dt>
              <dd>{state.linkedQuote ? "Saved quote link" : state.source ? intakeSources[state.source] : "Vehicle description"}</dd>
            </div>
          </dl>
          {(state.step === "mods" || state.step === "contact" || state.step === "ready" || state.step === "review") && (
            <div className="context-actions">
              <button className="secondary" onClick={() => {
                extraction.current?.abort();
                setConfirmed(false);
                dispatch({ type: "edit-vehicle" });
              }}>
                Edit vehicle
              </button>
              {state.step !== "mods" && <button className="secondary"
                onClick={() => { setConfirmed(false); dispatch({ type: "edit-mods" }); }}>Edit modifications</button>}
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function PartSummary({ parts }: { parts: Part[] }) {
  if (parts.length === 0) return null;
  const groups = [
    { title: "Performance", items: parts.filter(({ id }) => id !== "misc" && id !== "unclassified") },
    { title: "Miscellaneous", items: parts.filter(({ id }) => id === "misc") },
    { title: "Needs review", items: parts.filter(({ id }) => id === "unclassified") },
  ];
  return (
    <div className="total-panel grid gap-3">
      {groups.filter(({ items }) => items.length > 0).map(({ title, items }) => (
        <section key={title} aria-label={title}>
          <h3>{title}</h3>
          <ul className="flex flex-wrap gap-2">
            {items.map(({ id, label }) => <li key={id + label}
              className="max-w-full rounded-md border border-input bg-input/30 px-2 py-1 text-sm break-words">{label}</li>)}
          </ul>
        </section>
      ))}
    </div>
  );
}
