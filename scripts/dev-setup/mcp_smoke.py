#!/usr/bin/env python3
"""Smoke test the MCP server: open SSE, run initialize + tools/list + a battery of tools/call.

Usage:
  python3 mcp_smoke.py                     # full battery against the 4 fixtures
  python3 mcp_smoke.py call <name> <json>  # ad-hoc single call

Examples:
  python3 mcp_smoke.py call getPickPackage '{"idOrCode":"PK/MAR-01/V-2026/223799"}'
  python3 mcp_smoke.py call getPickList '{"pickListId":106641}'
  python3 mcp_smoke.py call evaluatePickListReadiness '{"idOrCode":"PK/MAR-01/V-2026/223799"}'
"""
import json
import sys
import threading
import time
import urllib.request
from queue import Queue, Empty

BASE = "http://localhost:8081"

# Battery — fixtures confirmed to exist on the QA2 stockholm_marunda_restore snapshot.
BATTERY = [
    ("getPickList",                   {"pickListId": 106641}),
    ("getPickPackage",                {"idOrCode": "PK/MAR-01/V-2026/223799"}),
    ("evaluatePickListReadiness",     {"idOrCode": "PK/MAR-01/V-2026/223799"}),    # storage_stock_reserved → rule 5 fails
    ("evaluatePickListReadiness",     {"idOrCode": "PK/MAR-01/V-2026/224143"}),    # priority_cal_done → rule 7 fails (HOLD request)
    ("evaluatePickListReadiness",     {"idOrCode": "PK/MAR-01/V-2026/223506"}),    # partial_package
]

sse_events: Queue = Queue()


def sse_reader():
    req = urllib.request.Request(f"{BASE}/sse", headers={"Accept": "text/event-stream"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        event_type = None
        for raw in resp:
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                event_type = line[6:].strip()
            elif line.startswith("data:"):
                payload = line[5:].strip()
                sse_events.put((event_type, payload))
                event_type = None


def post(message_url: str, body: dict, timeout: float = 8.0):
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        message_url, data=data, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status


def wait_event(predicate, timeout: float = 15.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            evt = sse_events.get(timeout=max(0.05, deadline - time.time()))
        except Empty:
            return None
        if predicate(evt):
            return evt
    return None


def open_session():
    threading.Thread(target=sse_reader, daemon=True).start()
    endpoint_evt = wait_event(lambda e: e[0] == "endpoint")
    if not endpoint_evt:
        sys.exit("FAIL: no SSE endpoint event")
    message_url = BASE + endpoint_evt[1]

    post(message_url, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-test", "version": "0.1"},
        },
    })
    init_resp = wait_event(lambda e: '"id":1' in e[1])
    si = json.loads(init_resp[1]).get("result", {}).get("serverInfo") if init_resp else None
    print(f"connected to {si}")
    post(message_url, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    return message_url


def call_tool(message_url: str, request_id: int, name: str, args: dict, summarize: bool = True):
    post(message_url, {
        "jsonrpc": "2.0", "id": request_id, "method": "tools/call",
        "params": {"name": name, "arguments": args},
    })
    evt = wait_event(lambda e: f'"id":{request_id}' in e[1])
    if not evt:
        print(f"  FAIL: no response for {name}")
        return None
    parsed = json.loads(evt[1])
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
        msg_url = open_session()
        result = call_tool(msg_url, 100, name, args)
        if result is not None and isinstance(result, dict):
            print("\nfull JSON:")
            print(json.dumps(result, indent=2)[:3000])
        return

    msg_url = open_session()
    for i, (name, args) in enumerate(BATTERY, start=10):
        call_tool(msg_url, i, name, args)


if __name__ == "__main__":
    main()
