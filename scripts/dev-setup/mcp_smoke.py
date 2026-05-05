#!/usr/bin/env python3
"""Smoke test the MCP server: streamable HTTP transport with MCP protocol 2025-11-25.

Usage:
  python3 mcp_smoke.py                     # full battery against the 4 fixtures
  python3 mcp_smoke.py call <name> <json>  # ad-hoc single call

Examples:
  python3 mcp_smoke.py call getPickPackage '{"idOrCode":"PK/MAR-01/V-2026/223799"}'
  python3 mcp_smoke.py call getPickList '{"pickListId":106641}'
  python3 mcp_smoke.py call evaluatePickListReadiness '{"idOrCode":"PK/MAR-01/V-2026/223799"}'

Transport note: the server speaks Streamable HTTP at /mcp (not SSE at /sse). Each
JSON-RPC call is a POST whose response body is an SSE-encoded single event
("event:message" + "data:<jsonrpc-payload>"). The session id from the initialize
response must be threaded into every subsequent request via Mcp-Session-Id.
"""
import json
import sys
import urllib.request

ENDPOINT = "http://localhost:8081/mcp"
PROTOCOL_VERSION = "2025-11-25"

BATTERY = [
    ("getPickList",                   {"pickListId": 106641}),
    ("getPickPackage",                {"idOrCode": "PK/MAR-01/V-2026/223799"}),
    ("evaluatePickListReadiness",     {"idOrCode": "PK/MAR-01/V-2026/223799"}),    # all 13 pass → "candidate truly stuck"
    ("evaluatePickListReadiness",     {"idOrCode": "PK/MAR-01/V-2026/224143"}),    # PRIORITY_CAL_DONE; rule 7 fails (HOLD task request)
    ("evaluatePickListReadiness",     {"idOrCode": "PK/MAR-01/V-2026/223506"}),    # PARTIAL_PACKAGE; rule 6 fails (active PLDs)
]


def post(body: dict, session_id: str | None = None, timeout: float = 10.0):
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(
        ENDPOINT, data=json.dumps(body).encode(), method="POST", headers=headers
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode()
        sid = resp.headers.get("Mcp-Session-Id")
        return raw, sid


def parse_sse_payload(raw: str) -> dict | None:
    """Streamable HTTP wraps the JSON-RPC payload in an SSE event."""
    if not raw.strip():
        return None
    for line in raw.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip())
    if raw.strip().startswith("{"):
        return json.loads(raw)
    return None


def open_session() -> str:
    init_resp_raw, sid = post({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "smoke-test", "version": "0.1"},
        },
    })
    init_resp = parse_sse_payload(init_resp_raw)
    if init_resp is None or "result" not in init_resp:
        sys.exit(f"FAIL: initialize: {init_resp_raw[:300]}")
    if not sid:
        sys.exit("FAIL: server did not return Mcp-Session-Id")
    si = init_resp["result"].get("serverInfo")
    pv = init_resp["result"].get("protocolVersion")
    print(f"connected to {si} via protocol {pv}")
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, session_id=sid)
    return sid


def call_tool(sid: str, request_id: int, name: str, args: dict, summarize: bool = True):
    raw, _ = post({
        "jsonrpc": "2.0", "id": request_id, "method": "tools/call",
        "params": {"name": name, "arguments": args},
    }, session_id=sid)
    parsed = parse_sse_payload(raw)
    if parsed is None:
        print(f"  FAIL: empty response for {name}")
        return None
    if "error" in parsed:
        print(f"  ERROR {name}: {parsed['error']}")
        return None
    payload_text = parsed["result"]["content"][0].get("text", "")
    try:
        result = json.loads(payload_text)
    except json.JSONDecodeError:
        result = payload_text
    if summarize:
        summarize_result(name, args, result)
    return result


def summarize_result(name: str, args: dict, result):
    print(f"\n→ {name} {json.dumps(args)}")
    if not isinstance(result, dict):
        print(f"  raw: {str(result)[:200]}")
        return
    if name == "getPickPackage":
        h = result.get("pickPackage")
        if h is None:
            print("  pickPackage: null")
            return
        print(f"  pp.code={h['code']}, pickingStatus={h['pickingStatus']}, "
              f"hus={len(result.get('handlingUnits', []))}, sos={len(result.get('salesOrders', []))}")
    elif name == "getPickList":
        h = result.get("pickList")
        if h is None:
            print("  pickList: null")
            return
        print(f"  pl.id={h['id']}, status={h['status']}, picker={h.get('pickerId')}, "
              f"lineItems={len(result.get('lineItems', []))}")
    elif name == "evaluatePickListReadiness":
        h = result.get("pickPackage")
        s = result.get("summary", {})
        rules = result.get("rules", [])
        if h is None:
            print(f"  pp not found, summary={s}")
            return
        print(f"  pp.code={h['code']}, pickingStatus={h['pickingStatus']}, summary={s}")
        for r in rules:
            mark = "PASS" if r["pass"] else "FAIL"
            ev = json.dumps(r["evidence"])
            print(f"    {mark}  {r['ruleId']:<26} ({r['targetDb']:<9}) ev={ev}")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "call":
        name = sys.argv[2]
        args = json.loads(sys.argv[3])
        sid = open_session()
        result = call_tool(sid, 100, name, args)
        if result is not None and isinstance(result, dict):
            print("\nfull JSON:")
            print(json.dumps(result, indent=2)[:3000])
        return

    sid = open_session()
    for i, (name, args) in enumerate(BATTERY, start=10):
        call_tool(sid, i, name, args)


if __name__ == "__main__":
    main()
