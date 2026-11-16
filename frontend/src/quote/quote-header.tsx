import Link from "next/link";
import { BriefcaseBusiness, Gauge, GitFork } from "lucide-react";
import { AlertDialog } from "radix-ui";
import { Button } from "@/components/ui/button";

type Props = {
  hasProgress: boolean;
  busy: boolean;
  onStart: () => void;
};

export function QuoteHeader({ hasProgress, busy, onStart }: Props) {
  const brand = (
    <button className="brand rounded-md" type="button" disabled={busy} onClick={hasProgress ? undefined : onStart}>
      <span className="brand-mark"><Gauge aria-hidden="true" /></span>
      Adaptive Quote
    </button>
  );

  return (
    <header className="site-header">
      <div className="site-width site-header-content">
        {hasProgress ? (
          <AlertDialog.Root>
            <AlertDialog.Trigger asChild>{brand}</AlertDialog.Trigger>
            <AlertDialog.Portal>
              <AlertDialog.Overlay className="fixed inset-0 z-40 bg-black/65" />
              <AlertDialog.Content className="quote-theme fixed top-1/2 left-1/2 z-50 grid w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 gap-4 rounded-lg border border-border p-6 shadow-lg">
                <AlertDialog.Title className="text-lg font-semibold">Discard your progress?</AlertDialog.Title>
                <AlertDialog.Description className="text-sm text-muted-foreground">
                  Your current answers will be cleared and you’ll return to Step 1.
                </AlertDialog.Description>
                <div className="flex flex-wrap justify-end gap-2">
                  <AlertDialog.Cancel asChild><Button variant="outline">Keep editing</Button></AlertDialog.Cancel>
                  <AlertDialog.Action asChild><Button onClick={onStart}>Discard and restart</Button></AlertDialog.Action>
                </div>
              </AlertDialog.Content>
            </AlertDialog.Portal>
          </AlertDialog.Root>
        ) : brand}
        <nav className="site-links items-center" aria-label="Quote resources">
          <Link className="contract-link" href="/embedded">Embedded quote</Link>
          <a className="contract-link" href="/openapi.yaml">API contract</a>
          <Button variant="ghost" size="icon-sm" asChild>
            <a href="https://github.com/DinhJDev/adaptive-quote" aria-label="GitHub repository" title="GitHub repository">
              <GitFork aria-hidden="true" />
            </a>
          </Button>
          <Button variant="ghost" size="icon-sm" asChild>
            <a href="https://dinhjdev.com/" aria-label="Portfolio" title="Portfolio">
              <BriefcaseBusiness aria-hidden="true" />
            </a>
          </Button>
        </nav>
      </div>
    </header>
  );
}
