// Minimal Worker runtime surface used by this project. Wrangler still generates
// worker-configuration.d.ts on demand; keeping that multi-megabyte generated
// file out of the local TypeScript program makes CI and constrained machines
// type-check application code reliably.
declare namespace Cloudflare {
  interface Env {}
}

interface ExecutionContext<Props = unknown> {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
  readonly props: Props;
}

interface ExportedHandler<Env = unknown, _Queue = unknown, _Cf = unknown, Props = unknown> {
  fetch?(request: Request, env: Env, ctx: ExecutionContext<Props>): Response | Promise<Response>;
}

interface KVNamespace {}

interface DurableObjectState {
  readonly storage: {
    sql: {
      exec<T extends Record<string, string | number | ArrayBuffer | null>>(
        query: string,
        ...bindings: Array<string | number | ArrayBuffer | null>
      ): { toArray(): T[] };
    };
    setAlarm(scheduledTime: number | Date): Promise<void>;
    deleteAlarm(): Promise<void>;
  };
  blockConcurrencyWhile<T>(callback: () => Promise<T>): Promise<T>;
}

declare abstract class WorkerEntrypoint<Env = Cloudflare.Env> {
  protected ctx: ExecutionContext;
  protected env: Env;
  constructor(ctx: ExecutionContext, env: Env);
}
