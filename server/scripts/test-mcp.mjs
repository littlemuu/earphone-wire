const serverUrl = new URL(process.env.MCP_URL ?? "http://localhost:8787/mcp");
const response = await fetch(serverUrl, {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
});

if (response.status !== 401 || !response.headers.get("www-authenticate")?.includes("resource_metadata")) {
  throw new Error("Expected the OAuth protected-resource challenge from /mcp.");
}

console.log(JSON.stringify({ ok: true, mcp: "OAuth challenge verified", serverUrl: serverUrl.toString() }));
