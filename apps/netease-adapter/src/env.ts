import dotenv from "dotenv";
import { existsSync } from "node:fs";
import path from "node:path";

export function loadEnv() {
  let current = process.cwd();
  for (let index = 0; index < 6; index += 1) {
    const candidate = path.join(current, ".env");
    if (existsSync(candidate)) {
      dotenv.config({ path: candidate });
      return;
    }
    const parent = path.dirname(current);
    if (parent === current) return;
    current = parent;
  }
}
