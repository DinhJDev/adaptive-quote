import { useId, useRef, useState } from "react";
import type { FormEvent, ReactNode, RefObject } from "react";
import { ArrowRight, ChevronDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import type { Catalog, VehicleDetails } from "./api";
import { differentVehicle, readVehicleDetails, VehicleDetailsFields } from "./vehicle-details-fields";

export type QuoteLayout = "standalone" | "embedded";

export type IntakeDraft = {
  description: string;
  manual: boolean;
  vehicleId: string;
};

export const emptyIntake: IntakeDraft = { description: "", manual: false, vehicleId: "" };

type Props = {
  layout: QuoteLayout;
  headingRef: RefObject<HTMLHeadingElement | null>;
  catalog: Catalog | null;
  intake: IntakeDraft;
  busy: boolean;
  error: string | null;
  notice?: string;
  onChange: (next: IntakeDraft) => void;
  onSubmit: (vehicle?: VehicleDetails) => void;
  recovery?: ReactNode;
};

export function QuoteIntake({
  layout, headingRef, catalog, intake, busy, error, notice, onChange, onSubmit, recovery,
}: Props) {
  const id = useId();
  const descriptionInput = useRef<HTMLInputElement>(null);
  const manualSelect = useRef<HTMLSelectElement>(null);
  const [showExamples, setShowExamples] = useState(false);
  const Heading = layout === "embedded" ? "h2" : "h1";
  const fieldId = id + "-vehicle";
  const errorId = id + "-error";
  const examplesId = id + "-examples";

  function toggleManual() {
    const manual = !intake.manual;
    setShowExamples(false);
    onChange({ ...intake, manual });
    window.requestAnimationFrame(() => (manual ? manualSelect : descriptionInput).current?.focus());
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!catalog || busy) return;
    const vehicle = intake.manual && intake.vehicleId === differentVehicle
      ? readVehicleDetails(event.currentTarget) : undefined;
    onSubmit(vehicle);
  }

  return (
    <section className="intake-layout" data-layout={layout} aria-labelledby={id}>
      <div className="intake-content">
        {layout === "embedded" && <p className="intake-caption">Vehicle quote</p>}
        <Heading id={id} ref={headingRef} tabIndex={-1} className="intake-heading">What are you driving?</Heading>
        <p className="intake-description">Include the year, make, model, and any mods you know.</p>
        <form className="intake-form" onSubmit={submit} aria-busy={busy}>
          <div className="intake-submit-row">
            <div className="intake-field">
              <Label htmlFor={fieldId}>{intake.manual ? "Vehicle" : "Vehicle description"}</Label>
              {intake.manual ? (
                <NativeSelect id={fieldId} ref={manualSelect} value={intake.vehicleId} required
                  aria-describedby={error ? errorId : undefined}
                  onChange={(event) => onChange({ ...intake, vehicleId: event.target.value })}>
                  <NativeSelectOption value="">Choose a vehicle</NativeSelectOption>
                  <NativeSelectOption value={differentVehicle}>Enter a different vehicle</NativeSelectOption>
                  {catalog?.vehicles.map((vehicle) => (
                    <NativeSelectOption key={vehicle.id} value={vehicle.id}>{vehicle.label}</NativeSelectOption>
                  ))}
                </NativeSelect>
              ) : (
                <Input id={fieldId} ref={descriptionInput} value={intake.description} required
                  autoComplete="off" maxLength={500} placeholder="e.g. 2022 Toyota GR86 with GR intake"
                  aria-invalid={Boolean(error)} aria-describedby={error ? errorId : undefined}
                  onChange={(event) => onChange({ ...intake, description: event.target.value })} />
              )}
              {catalog && intake.manual && intake.vehicleId === differentVehicle && (
                <VehicleDetailsFields catalog={catalog} />
              )}
            </div>
            <Button type="submit" className="intake-continue" disabled={busy || !catalog}>
              Continue <ArrowRight aria-hidden="true" />
            </Button>
          </div>
          {busy && <p className="intake-feedback" role="status">
            {!catalog ? "Loading vehicles…" : catalog.liveEnabled ? "Reading your description…" : "Checking the demo catalog…"}
          </p>}
          {error && <p className="intake-feedback intake-error" id={errorId} role="alert">{error}</p>}
          {notice && <p className="intake-feedback" role="status">{notice}</p>}
          <div className="intake-secondary">
            {!intake.manual && (
              <Button type="button" variant="ghost" aria-controls={examplesId} aria-expanded={showExamples}
                onClick={() => setShowExamples(!showExamples)} disabled={!catalog}>
                Use an example <ChevronDown aria-hidden="true" />
              </Button>
            )}
            <Button type="button" variant="ghost" aria-expanded={intake.manual} onClick={toggleManual}
              disabled={!catalog}>
              {intake.manual ? "Use vehicle description" : "Enter vehicle manually"}
            </Button>
          </div>
          {!intake.manual && (
            <ul id={examplesId} className="intake-examples" hidden={!showExamples}>
              {catalog?.examples.map((example) => (
                <li key={example.id}>
                  <Button type="button" variant="outline" onClick={() => {
                    onChange({ ...intake, description: example.description });
                    setShowExamples(false);
                    descriptionInput.current?.focus();
                  }}>
                    {example.label}
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </form>
        {recovery && <div className="intake-recovery">{recovery}</div>}
      </div>
      <div className="intake-photo" aria-hidden="true" />
    </section>
  );
}
