import { expect, test } from "vitest";
import { hiddenModel, parseInstanceModels, preferenceMutation, type InstanceModelSnapshot } from "./instanceModelPreferences";
const snapshot: InstanceModelSnapshot = { version: 1, instanceId: "00000000-0000-4000-8000-000000000001", revision: "9007199254740993", rows: [
  { provider: "p", model: "", favorite: false, hidden: true, favoriteRevision: "0", hiddenRevision: "1" },
  { provider: "p", model: "m/a::b", favorite: false, hidden: false, favoriteRevision: "2", hiddenRevision: "3" },
] };
test("provider hide-all yields to explicit model restoration and import cannot overwrite cancellations", () => {
  const parsed = parseInstanceModels(snapshot);
  expect(hiddenModel(parsed, "p", "new-model")).toBe(true);
  expect(hiddenModel(parsed, "p", "m/a::b")).toBe(false);
  expect(preferenceMutation(parsed, "p", "m/a::b", "favorite", true).baseRevision).toBe("2");
  expect(preferenceMutation(parsed, "p", "m/a::b", "favorite", true, true).baseRevision).toBe("0");
});
test("rejects duplicate tuple, unknown schema and lossy revisions", () => {
  expect(() => parseInstanceModels({ ...snapshot, rows: [...snapshot.rows, snapshot.rows[0]] })).toThrow();
  expect(() => parseInstanceModels({ ...snapshot, version: 2 })).toThrow();
  expect(() => parseInstanceModels({ ...snapshot, revision: 4 })).toThrow();
  expect(() => parseInstanceModels({ ...snapshot, revision: "1" })).toThrow();
});
