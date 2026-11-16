import { useState } from "react";
import { Button } from "@/components/ui/button";
import type { Preview } from "./api";

const money = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
export const cost = (minor: number) => money.format(minor / 100);
const serviceDetails: Record<string, string> = {
  tune: "Baseline calibration for stock or lightly modified builds.",
  "stage-2": "Calibration for builds with supporting bolt-ons.",
  "stage-3": "Extended calibration and review for more involved builds.",
  assessment: "Review your setup with a tuner before choosing a calibration.",
};

type Props = {
  preview: Preview | null;
  serviceId: string | null;
  addOnIds: string[];
  priceCurrent: boolean;
  error: string | null;
  onSelectService: (id: string) => void;
  onToggleAddOn: (id: string) => void;
  onRetry: () => void;
};

export function QuoteReview({
  preview, serviceId, addOnIds, priceCurrent, error, onSelectService, onToggleAddOn, onRetry,
}: Props) {
  if (priceCurrent && preview?.customReviewReason) {
    return <CustomPricing key={preview.customReviewReason} reason={preview.customReviewReason} />;
  }
  return (
    <div className="step-content">
      {error && (
        <div className="notice" role="alert">
          <p>{error}</p>
          <button className="secondary" onClick={onRetry}>Retry pricing</button>
        </div>
      )}
      {preview && (
        <fieldset>
          <legend>Tune options</legend>
          <div className="grid gap-2 lg:grid-cols-2">
            {preview.eligibleServices.map((offer) => (
              <label className="choice items-start" key={offer.id}>
                <input type="radio" name="service" checked={serviceId === offer.id}
                  onChange={() => onSelectService(offer.id)} />
                <span className="grid gap-1">
                  <span className="font-semibold">{offer.label}</span>
                  <strong className="tabular-nums">{cost(offer.amountMinor)}</strong>
                  {offer.id === preview.recommendedServiceId && (
                    <span className="text-xs font-semibold text-primary">Recommended</span>
                  )}
                  <small className="muted">{serviceDetails[offer.id]}</small>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {preview && preview.lineItems[0]?.id === serviceId && preview.eligibleAddOns.length > 0 && (
        <fieldset>
          <legend>Optional add-ons</legend>
          <div className="offer-list">
            {preview.eligibleAddOns.map((offer) => (
              <label className="choice offer" key={offer.id}>
                <input type="checkbox" checked={addOnIds.includes(offer.id)}
                  onChange={() => onToggleAddOn(offer.id)} />
                <span>{offer.label}</span>
                <strong>{cost(offer.amountMinor)}</strong>
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <section className="total-panel" aria-label="Quote summary" aria-live="polite">
        <h3>Quote summary</h3>
        {!priceCurrent && <p>{error ? "Price unavailable" : "Getting prices…"}</p>}
        {priceCurrent && preview && preview.totalMinor === null && <p>Select a service to see your total.</p>}
        {priceCurrent && preview && preview.totalMinor !== null && (
          <>
            <dl>
              {preview.lineItems.map((line) => (
                <div key={line.id}><dt>{line.label}</dt><dd>{cost(line.amountMinor)}</dd></div>
              ))}
            </dl>
            <div className="total-value"><span>Total</span><strong>{cost(preview.totalMinor)}</strong></div>
          </>
        )}
      </section>
    </div>
  );
}

function CustomPricing({ reason }: { reason: NonNullable<Preview["customReviewReason"]> }) {
  const [channel, setChannel] = useState("text");
  const [prepared, setPrepared] = useState(false);
  const descriptions = {
    older_vehicle: "A tuner would review this vehicle’s engine and setup before giving you a price.",
    complex_build: "A tuner would review your performance modifications before giving you a price.",
    unverified_vehicle: "A tuner would confirm this vehicle’s model, year, and setup before giving you a price.",
  };

  return (
    <div className="step-content">
      <section className="total-panel" aria-labelledby="custom-pricing-heading">
        <h3 id="custom-pricing-heading">Custom pricing</h3>
        <p>{descriptions[reason]}</p>
      </section>
      <fieldset>
        <legend>Preferred follow-up</legend>
        <div className="choice-grid">
          {["text", "call"].map((option) => (
            <label className="choice" key={option}>
              <input type="radio" name="follow-up" checked={channel === option}
                onChange={() => { setChannel(option); setPrepared(false); }} />
              {option === "text" ? "Text" : "Phone call"}
            </label>
          ))}
        </div>
      </fieldset>
      <div><Button type="button" onClick={() => setPrepared(true)}>Preview tuner follow-up</Button></div>
      {prepared && <p role="status">In a live shop, a tuner would follow up by {channel === "text" ? "text" : "phone"}.
        This demo sends no messages.</p>}
    </div>
  );
}
