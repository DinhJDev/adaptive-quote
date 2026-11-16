import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import type { Catalog } from "./api";
import type { ModificationDraft } from "./workflow";

type Props = {
  modifications: Catalog["modifications"];
  draft: ModificationDraft;
  busy: boolean;
  liveEnabled: boolean;
  onChange: (draft: ModificationDraft) => void;
  onConfirm: (draft: ModificationDraft) => void;
};

export function ModificationsStep({ modifications, draft, busy, liveEnabled, onChange, onConfirm }: Props) {
  const text = draft.description ?? "";
  const canConfirm = !!(text.trim() || draft.ids.length || draft.status);

  return (
    <form className="step-content" onSubmit={(event) => {
      event.preventDefault();
      if (canConfirm && !busy) onConfirm(draft);
    }}>
      <div className="grid gap-2">
        <Label htmlFor="part-description">Installed parts</Label>
        <textarea id="part-description" rows={4} maxLength={500} value={text} disabled={busy}
          placeholder="e.g. BC coilovers, Apex’i single-exit exhaust"
          className="w-full resize-y rounded-md border border-input bg-input/30 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
          aria-describedby={liveEnabled ? "parts-privacy" : undefined}
          onChange={(event) => onChange({
            ...draft, status: draft.status === "stock" ? null : draft.status, description: event.target.value,
          })} />
        {liveEnabled && <p className="field-help" id="parts-privacy">Part descriptions may be sent to OpenAI. Leave out personal details.</p>}
      </div>
      <fieldset>
        <legend>Common modifications</legend>
        <div className="flex flex-wrap gap-2">
          {modifications.map((mod) => {
            const selected = draft.ids.includes(mod.id);
            return (
              <Button key={mod.id} variant={selected ? "secondary" : "outline"} type="button"
                className="min-h-10 rounded-full" aria-pressed={selected} disabled={busy}
                onClick={() => onChange({
                  ...draft,
                  status: draft.status === "stock" && mod.kind === "miscellaneous" ? "stock" : null,
                  ids: selected ? draft.ids.filter((id) => id !== mod.id) : [...draft.ids, mod.id],
                })}>
                {selected && <span aria-hidden="true">✓</span>}
                {mod.label}
              </Button>
            );
          })}
        </div>
      </fieldset>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={!canConfirm || busy}>{busy ? "Identifying parts…" : "Continue"}</Button>
        <Button type="button" variant="outline" aria-pressed={draft.status === "stock"} disabled={busy}
          onClick={() => onChange({ status: "stock", ids: draft.ids.filter((id) => id === "misc") })}>
          Stock
        </Button>
        <Button type="button" variant="ghost" aria-pressed={draft.status === "unsure"} disabled={busy}
          onClick={() => onChange({ ...draft, status: draft.status === "unsure" ? null : "unsure" })}>
          Unsure
        </Button>
      </div>
    </form>
  );
}
