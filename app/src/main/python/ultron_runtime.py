"""Bundled Python utilities for deterministic plan validation."""
import json
from typing import Any, Dict, List

ALLOWED_TOOLS = {
    "workspace_list", "workspace_read", "workspace_write", "workspace_delete",
    "ui_read_visible", "ui_tap_text", "ui_global_action", "app_launch",
    "apk_build_start", "apk_build_status",
}
HIGH_RISK = {"workspace_delete", "ui_tap_text", "ui_global_action", "app_launch", "apk_build_start"}

def validate_plan(raw_json: str) -> str:
    payload: Dict[str, Any] = json.loads(raw_json)
    steps: List[Dict[str, Any]] = payload.get("steps", [])
    if not isinstance(steps, list) or len(steps) > 12:
        raise ValueError("A plan must contain at most 12 steps")
    normalized = []
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            raise ValueError("Each step must be an object")
        tool = str(step.get("tool", ""))
        if tool not in ALLOWED_TOOLS:
            raise ValueError("Unsupported tool: " + tool)
        args = step.get("arguments", {})
        if not isinstance(args, dict):
            raise ValueError("Tool arguments must be an object")
        normalized.append({
            "index": index, "tool": tool, "arguments": args,
            "requires_approval": tool in HIGH_RISK or bool(step.get("requires_approval", False)),
        })
    return json.dumps({"steps": normalized}, separators=(",", ":"), ensure_ascii=False)

def runtime_info() -> str:
    return json.dumps({"runtime": "python", "version": "3.11+", "mode": "plan-validation-only"})
