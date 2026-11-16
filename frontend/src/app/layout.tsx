import type { Metadata } from "next";
import type { ReactNode } from "react";
import "@fontsource-variable/instrument-sans";
import "@fontsource/barlow-semi-condensed/latin-600.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "Adaptive Quote",
  referrer: "no-referrer",
  description: "Explore a performance quote for real vehicles with demo services and prices.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        {children}
        <footer className="site-width data-credit">
          <a href="https://vehiclesdb.com">Vehicle data by VehiclesDB</a>
          <a href="/vehicle-data-attribution.md">Data attribution</a>
        </footer>
      </body>
    </html>
  );
}
