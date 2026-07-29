import { build } from "esbuild";
await build({ bundle: true, platform: "node", format: "esm", target: "node20",
  entryPoints: ["src/index.ts", "src/driver.ts"], outdir: "dist", logLevel: "info" });
