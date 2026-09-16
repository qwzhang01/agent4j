#!/usr/bin/env python3
"""
Minimal real MCP server over stdio for agent4j integration tests.

Speaks enough of the MCP JSON-RPC protocol for ManagedMcpClient's full
lifecycle: initialize handshake, tools/list, tools/call, ping.

Crash-on-demand: if the CALL_ARG of tools/call equals "die", the process
exits(1) immediately (simulating a crashed server subprocess for the
self-healing restart path). The host restarts us and the retry succeeds
because the server is stateless.

Line-delimited JSON on stdin/stdout, one message per line (stdio framing).
"""
import json
import sys

TOOLS = [
    {
        "name": "echo",
        "description": "Echo the input text back.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "add",
        "description": "Add two integers.",
        "inputSchema": {
            "type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"],
        },
    },
]

SERVER_INFO = {"name": "py-mcp-it-server", "version": "1.0.0"}


def send(msg):
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


def send_result(req_id, result):
    send({"jsonrpc": "2.0", "id": req_id, "result": result})


def send_error(req_id, code, message):
    send({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})


def handle(line):
    msg = json.loads(line)
    if "id" not in msg:
        return  # notification: fire and forget
    method = msg.get("method", "")
    req_id = msg["id"]
    params = msg.get("params", {})

    if method == "initialize":
        send_result(req_id, {
            "protocolVersion": params.get("protocolVersion", "2024-11-05"),
            "capabilities": {"tools": {}},
            "serverInfo": SERVER_INFO,
        })
    elif method == "tools/list":
        send_result(req_id, {"tools": TOOLS})
    elif method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments", {})
        if args.get("text") == "die":
            sys.exit(1)  # crash-on-demand, no response written
        if name == "echo":
            send_result(req_id, {
                "content": [{"type": "text", "text": "echo:" + str(args.get("text", ""))}],
                "isError": False,
            })
        elif name == "add":
            send_result(req_id, {
                "content": [{"type": "text", "text": str(int(args.get("a", 0)) + int(args.get("b", 0)))}],
                "isError": False,
            })
        else:
            send_error(req_id, -32602, "Unknown tool: " + name)
    elif method == "ping":
        send_result(req_id, {})
    else:
        send_error(req_id, -32601, "Method not found: " + method)


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            handle(line)
        except SystemExit:
            raise
        except Exception as exc:  # protocol survival: never die on bad input
            sys.stderr.write("server-error: %s\n" % exc)


if __name__ == "__main__":
    main()
