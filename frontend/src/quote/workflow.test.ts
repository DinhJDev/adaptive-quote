import { describe, expect, it } from "vitest";
import { initialState, reachableStep, transition } from "./workflow";
import type { Extraction, Preview } from "./api";

const model = { id: "toyota-gr86-2022", label: "2022 Toyota GR86", make: "Toyota", model: "GR86", year: 2022 };
const otherYear = { ...model, id: "toyota-gr86-2023", label: "2023 Toyota GR86", year: 2023 };
const mustang = { id: "ford-mustang-gt-2022", label: "2022 Ford Mustang GT", make: "Ford", model: "Mustang GT", year: 2022 };
const tune = { id: "tune", label: "Calibration tune", amountMinor: 39900 };
const samplePreview: Preview = {
  catalogVersion: "demo-2",
  eligibleServices: [tune],
  eligibleAddOns: [],
  lineItems: [],
  totalMinor: null,
  currency: "USD",
};

describe("quote workflow", () => {
  it("asks for clarification only if the example leaves multiple choices", () => {
    const pending = transition(initialState, { type: "start-intake", id: 1 });
    const facts = { modIds: ["intake"], stockMention: false };
    const extraction: Extraction = {
      source: "fixture",
      schemaVersion: "vehicle_extraction_v1",
      facts,
      match: { status: "ambiguous", candidates: [model, otherYear] },
    };

    const clarifying = transition(pending, { type: "extracted", id: 1, extraction });
    expect(clarifying.step).toBe("vehicle");
    const confirmed = transition(clarifying, { type: "select-vehicle", vehicle: model });
    expect(confirmed.step).toBe("mods");
    expect(confirmed.modIds).toEqual(["intake"]);
  });

  it("drops an extraction after the visitor has switched to manual entry", () => {
    const pending = transition(initialState, { type: "start-intake", id: 1 });
    const manual = transition(pending, { type: "manual", vehicle: model });

    expect(
      transition(manual, {
        type: "extracted",
        id: 1,
        extraction: {
          source: "fixture",
          schemaVersion: "vehicle_extraction_v1",
          facts: { modIds: [], stockMention: false },
          match: { status: "matched", candidates: [model] },
        },
      }),
    ).toBe(manual);
  });

  it("rejects an old price response and clears selections after a vehicle change", () => {
    let state = transition(initialState, { type: "manual", vehicle: model });
    state = transition(state, { type: "confirm-mods", status: "stock", modIds: [] });
    expect(state.step).toBe("contact");
    state = transition(state, { type: "confirm-contact" });
    state = transition(state, { type: "start-preview", id: 1 });
    state = transition(state, { type: "select-service", id: "tune" });
    expect(transition(state, { type: "priced", id: 1, preview: samplePreview })).toBe(state);

    state = transition(state, { type: "edit-vehicle" });
    state = transition(state, {
      type: "select-vehicle",
      vehicle: mustang,
    });
    expect(state).toMatchObject({ step: "mods", serviceId: null, addOnIds: [], modIds: [] });
  });

  it("blocks browser Forward when modifications have unconfirmed edits", () => {
    let state = transition(initialState, { type: "manual", vehicle: model });
    state = transition(state, { type: "confirm-mods", status: "stock", modIds: [] });
    state = transition(state, { type: "confirm-contact" });
    state = transition(state, { type: "navigate", step: "mods" });
    expect(reachableStep(state, "review", false, false)).toBe("review");
    state = transition(state, { type: "change-mods", draft: { status: "modified", ids: ["exhaust"] } });
    expect(reachableStep(state, "review", false, false)).toBe("mods");
    expect(state.modificationDraft?.ids).toEqual(["exhaust"]);
  });

  it("preserves the draft checkpoint's save sequence when revisiting a pending review", () => {
    expect(reachableStep(initialState, "review", false, false)).toBe("intake");
    expect(reachableStep(initialState, "review", false, true)).toBe("review");
  });
});
