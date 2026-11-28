import { createHmac } from "node:crypto";
import { createApi } from "../src/api";

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function base32Decode(text: string): Buffer {
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const c of text.replace(/[=\s-]/g, "").toUpperCase()) {
    buffer = (buffer << 5) | ALPHABET.indexOf(c);
    bits += 5;
    if (bits >= 8) {
      bytes.push((buffer >> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

/** RFC 6238 code, written independently of the Java one: if both agree the two implementations are right. */
export function totp(secret: string, atMs = Date.now()): string {
  const step = BigInt(Math.floor(atMs / 1000 / 30));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(step);
  const hash = createHmac("sha1", base32Decode(secret)).update(counter).digest();
  const offset = hash[hash.length - 1]! & 0x0f;
  const binary =
    ((hash[offset]! & 0x7f) << 24) | (hash[offset + 1]! << 16) | (hash[offset + 2]! << 8) | hash[offset + 3]!;
  return String(binary % 1_000_000).padStart(6, "0");
}

export function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === "") {
    throw new Error(`${name} is not set: run these tests through scripts/e2e.sh`);
  }
  return value;
}

export function client(actor = "e2e") {
  return createApi(env("E2E_API_URL"), env("E2E_API_TOKEN"), actor);
}
