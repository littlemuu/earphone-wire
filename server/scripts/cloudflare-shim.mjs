// Node-only test shim. Production Workers resolve this module from the runtime.
import { registerHooks } from "node:module";
import path from "node:path";

const workersShim = `data:text/javascript,${encodeURIComponent(`
export class WorkerEntrypoint {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }
}
export class DurableObject {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }
}
`)}`;

globalThis.Cloudflare = {
  ...(globalThis.Cloudflare ?? {}),
  compatibilityFlags: { global_fetch_strictly_public: true },
};

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === "cloudflare:workers") {
      return { url: workersShim, shortCircuit: true };
    }
    if (
      (specifier.startsWith("./") || specifier.startsWith("../")) &&
      path.extname(specifier) === "" &&
      context.parentURL?.startsWith("file:")
    ) {
      return {
        url: new URL(`${specifier}.ts`, context.parentURL).href,
        shortCircuit: true,
      };
    }
    return nextResolve(specifier, context);
  },
});
