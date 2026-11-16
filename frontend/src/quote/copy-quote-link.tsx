import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { createQuoteLink } from "./api";
import type { Part, PreviewRequest } from "./api";

export function CopyQuoteLink({ selection, parts, sharedUrl }: {
  selection: PreviewRequest;
  parts: Part[];
  sharedUrl?: string;
}) {
  const [link, setLink] = useState("");
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");

  async function copy() {
    setError("");
    setFeedback("");
    let url = link || sharedUrl;
    if (!url) {
      setSaving(true);
      try {
        url = (await createQuoteLink(selection, parts)).url;
      } catch (error) {
        setError(error instanceof Error ? error.message : "Couldn’t create the quote link. Try again.");
        return;
      } finally {
        setSaving(false);
      }
    }
    setLink(url);
    try {
      await navigator.clipboard.writeText(url);
      setFeedback("Quote link copied.");
    } catch {
      setFeedback("Copy again or select the link to copy it manually.");
    }
  }

  return (
    <div className="grid gap-2">
      <div><Button type="button" variant="outline" disabled={saving} onClick={() => void copy()}>
        {saving ? "Creating link…" : "Copy quote link"}
      </Button></div>
      <p className="muted text-sm">Anyone with the link can open this summary for 7 days. Leave out personal details.</p>
      {link && <Input aria-label="Quote link" value={link} readOnly onFocus={(event) => event.currentTarget.select()} />}
      {feedback && <p role="status" className="text-sm">{feedback}</p>}
      {error && <p role="alert" className="notice">{error}</p>}
    </div>
  );
}
