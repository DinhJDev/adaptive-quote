import type { Metadata } from "next";
import Link from "next/link";
import { Wrench } from "lucide-react";
import { QuoteFlow } from "@/quote/quote-flow";

export const metadata: Metadata = { title: "Embedded quote · Adaptive Quote" };

export default function EmbeddedQuotePage() {
  return (
    <div className="garage-shell">
      <header className="garage-header">
        <div className="site-width site-header-content">
          <span className="brand"><span className="brand-mark"><Wrench aria-hidden="true" /></span>Demo Garage</span>
          <Link className="contract-link" href="/">Standalone quote</Link>
        </div>
      </header>
      <main className="site-width garage-main">
        <h1>Performance tuning</h1>
        <p>Get a demo estimate based on your vehicle and its modifications.</p>
        <div className="quote-theme embedded-shell">
          <QuoteFlow layout="embedded" />
        </div>
      </main>
    </div>
  );
}
