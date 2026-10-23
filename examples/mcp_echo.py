#!/usr/bin/env python3
"""Small local MCP stdio example. No dependencies; stdout contains JSON-RPC only."""
import json
import sys


def reply(request):
    if "id" not in request:
        return None
    method = request.get("method")
    if method == "initialize":
        result = {"protocolVersion": "2025-11-25", "capabilities": {"tools": {}},
                  "serverInfo": {"name": "turnforge-echo", "version": "1.0.0"}}
    elif method == "tools/list":
        result = {"tools": [{"name": "echo", "description": "Echo the supplied text.",
                             "inputSchema": {"type": "object", "properties": {
                                 "text": {"type": "string"}}, "required": ["text"],
                                 "additionalProperties": False}}]}
    elif method == "tools/call":
        params = request.get("params", {})
        arguments = params.get("arguments", {})
        valid = params.get("name") == "echo" and isinstance(arguments.get("text"), str)
        result = {"content": [{"type": "text", "text": arguments["text"] if valid
                               else "echo requires a string text argument"}], "isError": not valid}
    elif method == "ping":
        result = {}
    else:
        return {"jsonrpc": "2.0", "id": request["id"],
                "error": {"code": -32601, "message": "Method not found"}}
    return {"jsonrpc": "2.0", "id": request["id"], "result": result}


def main():
    for line in sys.stdin:
        try:
            response = reply(json.loads(line))
            if response is not None:
                print(json.dumps(response), flush=True)
        except (ValueError, TypeError, KeyError) as error:
            print(f"Invalid MCP request: {error}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()
