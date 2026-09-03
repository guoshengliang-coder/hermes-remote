import { serializeReleaseError } from "./lib/release-errors.mjs";

const [kind = "smoke", technicalCause = "unknown_failure"] = process.argv.slice(2);
console.error(serializeReleaseError(kind, technicalCause));
