import {
  closeSync,
  constants,
  fstatSync,
  openSync,
  readFileSync,
} from "node:fs";
import { isAbsolute } from "node:path";

const MAX_TOKEN_BYTES = 256;
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

export function loadHermesSessionToken(config: {
  inline?: string;
  file?: string;
}): string | undefined {
  if (config.inline && config.file) {
    throw new Error("Hermes session token has multiple sources");
  }
  if (config.inline) return config.inline;
  if (!config.file) return undefined;
  if (!isAbsolute(config.file) || config.file.includes("\0")) {
    throw new Error("Hermes session token file is invalid");
  }

  let descriptor: number | undefined;
  try {
    descriptor = openSync(config.file, constants.O_RDONLY | constants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    const currentUid = typeof process.getuid === "function" ? process.getuid() : undefined;
    if (!metadata.isFile()
        || (currentUid !== undefined && metadata.uid !== currentUid)
        || (metadata.mode & 0o077) !== 0
        || metadata.size < 1
        || metadata.size > MAX_TOKEN_BYTES) {
      throw new Error("Hermes session token file is unsafe");
    }
    const token = readFileSync(descriptor, { encoding: "utf8" });
    if (!TOKEN_PATTERN.test(token)) {
      throw new Error("Hermes session token file is malformed");
    }
    return token;
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
  }
}
