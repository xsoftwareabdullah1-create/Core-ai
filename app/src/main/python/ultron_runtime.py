"""ULTRON local plan validation and policy boundary.

This module validates proposed tool calls only; it never executes them.
Device operations still require explicit approval from the Android service.
It uses only the Python standard library for reliable Chaquopy packaging.
"""
import json
import re
from pathlib import PurePosixPath
from typing import Any, Dict, Mapping

MAX_PLAN_BYTES = 48 * 1024
MAX_STEPS = 12
MAX_TEXT_LENGTH = 4000
MAX_PATH_LENGTH = 240
MAX_COORDINATE = 100000
MAX_SWIPE_DURATION_MS = 10000

READ_ONLY_TOOLS = frozenset({"workspace_list", "workspace_read", "ui_read_visible", "apk_build_status"})
WRITE_TOOLS = frozenset({"workspace_write", "workspace_delete", "apk_build_start"})
DEVICE_TOOLS = frozenset({"ui_tap_text", "ui_tap_coordinates", "ui_swipe", "ui_scroll", "ui_type_text", "ui_global_action", "app_launch"})
ALLOWED_TOOLS = READ_ONLY_TOOLS | WRITE_TOOLS | DEVICE_TOOLS
HIGH_RISK = WRITE_TOOLS | DEVICE_TOOLS
GLOBAL_ACTIONS = frozenset({"back", "home", "recents", "notifications", "quick_settings"})
PACKAGE_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


class PlanValidationError(ValueError):
    """A proposed plan does not satisfy the local tool contract."""


def _object(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise PlanValidationError(label + " must be an object")
    return value


def _text(value: Any, label: str, limit: int = MAX_TEXT_LENGTH) -> str:
    if not isinstance(value, str):
        raise PlanValidationError(label + " must be text")
    if len(value) > limit or "\x00" in value:
        raise PlanValidationError(label + " is too long or contains a NUL")
    return value


def _workspace_path(value: Any) -> str:
    raw = _text(value, "path", MAX_PATH_LENGTH).replace("\\", "/")
    path = PurePosixPath(raw)
    if path.is_absolute() or not raw or any(p in ("", ".", "..") for p in raw.split("/")):
        raise PlanValidationError("path must remain inside the workspace")
    if path.parts[0] not in ("workspace", "projects"):
        raise PlanValidationError("path must start with workspace/ or projects/")
    return path.as_posix()


def _arguments(tool: str, args: Mapping[str, Any]) -> Dict[str, Any]:
    clean = dict(args)
    if tool in {"workspace_read", "workspace_delete", "workspace_write"}:
        clean["path"] = _workspace_path(args.get("path"))
    if tool == "workspace_write":
        clean["content"] = _text(args.get("content"), "content", 1_000_000)
    if tool == "ui_tap_text":
        clean["text"] = _text(args.get("text"), "text", 180).strip()
        if not clean["text"]:
            raise PlanValidationError("tap text cannot be empty")
    if tool == "ui_type_text":
        clean["text"] = _text(args.get("text"), "text", 2000)
    if tool == "ui_tap_coordinates":
        for axis in ("x", "y"):
            value = args.get(axis)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= MAX_COORDINATE:
                raise PlanValidationError(axis + " is outside the valid coordinate range")
    if tool == "ui_swipe":
        for axis in ("start_x", "start_y", "end_x", "end_y"):
            value = args.get(axis)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= MAX_COORDINATE:
                raise PlanValidationError(axis + " is outside the valid coordinate range")
        duration = args.get("duration_ms", 350)
        if isinstance(duration, bool) or not isinstance(duration, int) or not 50 <= duration <= MAX_SWIPE_DURATION_MS:
            raise PlanValidationError("duration_ms must be between 50 and 10000")
        clean["duration_ms"] = duration
    if tool == "ui_global_action":
        action = _text(args.get("action"), "action", 40).lower()
        if action not in GLOBAL_ACTIONS:
            raise PlanValidationError("unsupported global action")
        clean["action"] = action
    if tool == "app_launch":
        package = _text(args.get("package"), "package", 180)
        if not PACKAGE_PATTERN.fullmatch(package):
            raise PlanValidationError("invalid Android application ID")
        clean["package"] = package
    if tool == "apk_build_start":
        variant = str(args.get("variant", "debug")).lower()
        if variant not in ("debug", "release"):
            raise PlanValidationError("variant must be debug or release")
        clean["variant"] = variant
    return clean


def validate_plan(raw_json: str) -> str:
    """Validate and normalize a bounded sequence of proposed tools."""
    if not isinstance(raw_json, str) or len(raw_json.encode("utf-8")) > MAX_PLAN_BYTES:
        raise PlanValidationError("plan is missing or exceeds the size limit")
    try:
        root = _object(json.loads(raw_json), "plan")
    except json.JSONDecodeError as exc:
        raise PlanValidationError("plan is not valid JSON") from exc
    steps = root.get("steps", [])
    if not isinstance(steps, list) or len(steps) > MAX_STEPS:
        raise PlanValidationError("a plan must contain at most 12 steps")
    normalized = []
    for index, item in enumerate(steps):
        step = _object(item, "step")
        tool = _text(step.get("tool", ""), "tool", 80)
        if tool not in ALLOWED_TOOLS:
            raise PlanValidationError("unsupported tool: " + tool)
        args = _object(step.get("arguments", {}), "arguments")
        normalized.append({
            "index": index,
            "tool": tool,
            "arguments": _arguments(tool, args),
            "requires_approval": tool in HIGH_RISK or bool(step.get("requires_approval", False)),
        })
    return json.dumps({"steps": normalized, "policy_version": 2}, separators=(",", ":"), sort_keys=True, ensure_ascii=False)


def summarize_plan(raw_json: str) -> str:
    """Return safe aggregate counts without exposing private arguments."""
    plan = json.loads(validate_plan(raw_json))
    steps = plan["steps"]
    summary = {
        "step_count": len(steps),
        "approval_count": sum(bool(s["requires_approval"]) for s in steps),
        "read_only_count": sum(s["tool"] in READ_ONLY_TOOLS for s in steps),
        "write_count": sum(s["tool"] in WRITE_TOOLS for s in steps),
        "device_count": sum(s["tool"] in DEVICE_TOOLS for s in steps),
    }
    return json.dumps(summary, separators=(",", ":"), sort_keys=True)


def runtime_info() -> str:
    return json.dumps({
        "runtime": "python", "version": "3.11+",
        "mode": "local-plan-validation", "policy_version": 2,
        "execution_enabled": False, "allowed_tool_count": len(ALLOWED_TOOLS),
    }, separators=(",", ":"), sort_keys=True)


def self_test() -> str:
    """Run deterministic valid/invalid policy cases for diagnostics."""
    cases = [
        ('{"steps":[]}', True),
        ('{"steps":[{"tool":"workspace_read","arguments":{"path":"workspace/readme.md"}}]}', True),
        ('{"steps":[{"tool":"ui_global_action","arguments":{"action":"home"}}]}', True),
        ('{"steps":[{"tool":"workspace_read","arguments":{"path":"../../secrets"}}]}', False),
        ('{"steps":[{"tool":"shell_exec","arguments":{}}]}', False),
        ('{"steps":[{"tool":"app_launch","arguments":{"package":"not a package"}}]}', False),
    ]
    failed = []
    for index, (sample, expected) in enumerate(cases):
        try:
            validate_plan(sample)
            actual = True
        except PlanValidationError:
            actual = False
        if actual != expected:
            failed.append(index)
    return json.dumps({"passed": len(cases) - len(failed), "total": len(cases), "failed_cases": failed, "ok": not failed}, separators=(",", ":"))
