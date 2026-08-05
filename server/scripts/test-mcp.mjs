const serverUrl = new URL(process.env.MCP_URL ?? "http://localhost:8787/mcp");

async function call(method, params) {
  return fetch(serverUrl, {
    method: "POST",
    headers: { "content-type": "application/json", Accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
}

async function mcpPayload(response) {
  if (response.headers.get("content-type")?.includes("application/json")) return response.json();
  const data = (await response.text()).match(/^data:\s*(.+)$/mu)?.[1];
  if (!data) throw new Error("MCP response did not contain a data event.");
  return JSON.parse(data);
}

const initialize = await call("initialize", {
  protocolVersion: "2025-06-18",
  capabilities: {},
  clientInfo: { name: "earphone-wire-local-check", version: "1.0.0" },
});
if (initialize.status !== 200) {
  throw new Error(`Expected anonymous MCP initialize to return 200, got ${initialize.status}.`);
}

const list = await call("tools/list", {});
if (list.status !== 200) {
  throw new Error(`Expected anonymous MCP tools/list to return 200, got ${list.status}.`);
}
const payload = await mcpPayload(list);
const tools = payload?.result?.tools;
const tool = Array.isArray(tools) && tools.length === 1 ? tools[0] : null;
if (
  tool?.name !== "get_now_playing"
  || JSON.stringify(tool.securitySchemes) !== JSON.stringify([{ type: "oauth2", scopes: ["playback:read"] }])
  || tool.annotations?.readOnlyHint !== true
  || tool.annotations?.destructiveHint !== false
  || tool.annotations?.openWorldHint !== false
) {
  throw new Error("Anonymous MCP tools/list did not expose exactly one fully annotated OAuth tool.");
}

console.log(JSON.stringify({ ok: true, mcp: "anonymous discovery verified", serverUrl: serverUrl.toString() }));
