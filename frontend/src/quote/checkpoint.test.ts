import { describe, expect, it } from "vitest";
import { decodeCheckpoint } from "./checkpoint";
import type { Checkpoint } from "./checkpoint";

const quoteId = "92dc821a-c3ca-401a-a7d0-e20ebc633225";
const selected = {
  vehicleId: "aster-2018",
  modStatus: "modified",
  modIds: ["intake"],
  serviceId: "tune",
  addOnIds: ["log-review"],
} as const;

const draft: Checkpoint = {
  version: 1,
  kind: "draft",
  expiresAt: 1000,
  source: "fixture",
  selection: { ...selected, modIds: [...selected.modIds], addOnIds: [...selected.addOnIds] },
  pending: null,
};

describe("quote checkpoint", () => {
  it("restores the exact pending request after reload", () => {
    const state = {
      ...draft,
      pending: { quoteId, body: { confirmed: true, catalogVersion: "demo-1", selection: selected } },
    };
    expect(decodeCheckpoint(JSON.stringify(state), 500)).toEqual(state);
  });

  it("discards expired, malformed, and unsupported data", () => {
    expect(decodeCheckpoint(JSON.stringify(draft), 1000)).toBeNull();
    expect(decodeCheckpoint("{", 500)).toBeNull();
    expect(decodeCheckpoint(JSON.stringify({ ...draft, version: 2 }), 500)).toBeNull();
    expect(decodeCheckpoint(JSON.stringify({ ...draft, pending: { quoteId } }), 500)).toBeNull();
  });

  it("accepts a receipt pointer without persisting its contents", () => {
    const receipt = { version: 1, kind: "receipt", expiresAt: 1000, quoteId };
    expect(decodeCheckpoint(JSON.stringify({ ...receipt, email: "demo@example.com" }), 500)).toEqual(receipt);
  });
});
