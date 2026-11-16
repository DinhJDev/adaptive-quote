import type { QuoteReceipt } from "./api";
import { cost } from "./quote-review";

export function ReceiptView({ receipt, onRestart }: { receipt: QuoteReceipt; onRestart: () => void }) {
  return (
    <section className="work-panel" aria-labelledby="receipt-heading">
      <h2 id="receipt-heading" tabIndex={-1} ref={(heading) => heading?.focus()}>Quote saved</h2>
      <div className="step-content">
        <p>You can reopen this quote from the same browser for seven days.</p>
        {receipt.selection.vehicleDetails && (
          <p>{receipt.selection.vehicleDetails.year} {receipt.selection.vehicleDetails.make} {receipt.selection.vehicleDetails.model}</p>
        )}
        <div className="total-panel" aria-label="Saved quote summary">
          <h3>Saved quote summary</h3>
          <dl>
            {receipt.lineItems.map((line) => (
              <div key={line.id}><dt>{line.label}</dt><dd>{cost(line.amountMinor)}</dd></div>
            ))}
          </dl>
          <div className="total-value"><span>Total</span><strong>{cost(receipt.totalMinor)}</strong></div>
        </div>
        <details className="receipt-example">
          <summary>Example checkout request</summary>
          <p>This illustrates the saved quote as checkout data. It never starts a payment.</p>
          <pre>{JSON.stringify(receipt.checkoutExample, null, 2)}</pre>
        </details>
        <div><button className="secondary" onClick={onRestart}>Start a new quote</button></div>
      </div>
    </section>
  );
}
