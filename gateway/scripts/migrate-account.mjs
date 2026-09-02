import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { Pool } from "pg";

const databaseUrl = await secret("ACCOUNT_DATABASE_URL");
const databaseSsl = booleanFlag("ACCOUNT_DATABASE_SSL", false);
const migrationsDirectory = resolve("migrations");
const migrationFiles = (await readdir(migrationsDirectory))
  .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
  .sort();
if (migrationFiles.length === 0) throw new Error("No account migrations were found");
const pool = new Pool({
  connectionString: databaseUrl,
  max: 1,
  ssl: databaseSsl ? { rejectUnauthorized: true } : undefined,
});

try {
  for (const migrationFile of migrationFiles) {
    const migration = await readFile(resolve(migrationsDirectory, migrationFile), "utf8");
    await pool.query(migration);
  }
  console.log(`Account authentication schema is up to date (${migrationFiles.length} migrations)`);
} finally {
  await pool.end();
}

async function secret(name) {
  const file = process.env[`${name}_FILE`];
  const value = process.env[name] ?? (file ? (await readFile(file, "utf8")).trim() : undefined);
  if (!value) throw new Error(`${name} or ${name}_FILE is required`);
  return value;
}

function booleanFlag(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined) return fallback;
  if (raw === "1") return true;
  if (raw === "0") return false;
  throw new Error(`${name} must be 0 or 1`);
}
