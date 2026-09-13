#!/usr/bin/env python3
"""Offline-only Slice 3 release gate; public execution cannot mutate anything."""
from __future__ import annotations
import argparse, ipaddress, json, math, re, sys, time, uuid
from datetime import datetime, timedelta, timezone
from types import MappingProxyType
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

IMAGE=re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}@sha256:[0-9a-fA-F]{64}$")
NAME=re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
PROJECT=re.compile(r"^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
IDENT=re.compile(r"^[A-Za-z0-9_.@:+/-]{1,128}$")
PLACEHOLDER=re.compile(r"(?:<[^>]*>|\$\{[^}]*\}|__[^_]+__|ACTUAL_SERVICE_URL|IMAGE_DIGEST)")

class GateError(Exception): pass
class DeadlineError(GateError): pass
class SignalError(GateError): pass
class DeploymentInputError(GateError): pass

# Single source of truth for the internal, non-serializable capability protocol.
_PROTOCOL = MappingProxyType({"marker": "SLICE3-OFFLINE-OBJECT", "version": 1})

def _get_protocol_spec() -> Any:
    """Return the immutable structured protocol handle for the trusted harness."""
    return _PROTOCOL

class _Capability:
    __slots__ = ("runner", "used")
    def __init__(self, runner: "_FakeRunner") -> None:
        self.runner = runner
        self.used: set[str] = set()

class _FakeRunner:
    __slots__ = ("_responses", "calls", "events", "rollback_evidence", "cleanup_outcomes", "forced_cleanup_outcomes", "forced_cleanup_supported")
    def __init__(self, responses: dict[str, Any]) -> None:
        self._responses = {k: (dict(v) if isinstance(v, dict) else v) for k, v in responses.items()}
        self.calls: list[str] = []
        self.events: list[dict[str, Any]] = []
        self.rollback_evidence: list[dict[str, Any]] = []
        self.cleanup_outcomes: dict[str, str] = {}
        self.forced_cleanup_outcomes: dict[str, str] = {}
        self.forced_cleanup_supported = True

def _create_trusted_fake_runner(responses: dict[str, Any]) -> tuple[Any, Any]:
    runner = _FakeRunner(responses)
    return runner, _Capability(runner)

class _RollbackContext:
    __slots__ = ("request", "migration_status", "smoke_status", "invocation_id", "rollback_attempt_id", "consumed")
    def __init__(self, request: dict[str, Any], migration_status: str, smoke_status: str,
                 invocation_id: str | None = None, rollback_attempt_id: str | None = None) -> None:
        self.request = dict(request)
        self.migration_status = migration_status
        self.smoke_status = smoke_status
        self.invocation_id = invocation_id or uuid.uuid4().hex
        self.rollback_attempt_id = rollback_attempt_id or uuid.uuid4().hex
        self.consumed = False

def _create_rollback_context(project: str, region: str, service: str,
                             candidate_revision: str, target_revision: str,
                             migration_status: str = "pass",
                             smoke_status: str = "fail", invocation_id: str | None = None,
                             rollback_attempt_id: str | None = None) -> _RollbackContext:
    context = _RollbackContext({
        "project": project, "region": region, "service": service,
        "candidate_revision": candidate_revision, "target_revision": target_revision,
    }, migration_status, smoke_status, invocation_id, rollback_attempt_id)
    context.request["invocation_id"] = context.invocation_id
    context.request["rollback_attempt_id"] = context.rollback_attempt_id
    return context

def _run_simulated_operation(runner: Any, capability: Any, operation: str,
                             request: dict[str, Any], protocol: Any = _PROTOCOL) -> dict[str, Any]:
    """Compatibility name intentionally rejects direct operation dispatch."""
    raise GateError("operation must use deadline-aware dispatcher")

_ROLLBACK_RESULT_FIELDS = frozenset(("status", "project", "region", "service",
    "candidate_revision", "target_revision", "traffic_revision",
    "invocation_id", "rollback_attempt_id"))
_ROLLBACK_EVIDENCE_FIELDS = frozenset(("event_type", "event_name", "operation",
    "status", "reason", "invocation_id", "rollback_attempt_id"))

def _rollback_record(context: _RollbackContext, name: str, status: str,
                    reason: str | None = None) -> dict[str, Any]:
    record = {"event_type": "rollback-evidence", "event_name": name,
              "operation": "rollback", "status": status,
              "invocation_id": context.invocation_id,
              "rollback_attempt_id": context.rollback_attempt_id}
    if reason is not None:
        record["reason"] = reason
    return record

def _validate_rollback_evidence(evidence: Any, context: Any, terminal: str) -> None:
    if type(context) is not _RollbackContext or not isinstance(evidence, list):
        raise GateError("rollback evidence is malformed")
    expected_names = {"success": ("rollback-requested", "rollback-succeeded"),
                      "failure": ("rollback-requested", "rollback-failed"),
                      "not-attempted": ("rollback-not-attempted",)}.get(terminal)
    if expected_names is None or len(evidence) != len(expected_names):
        raise GateError("rollback evidence sequence is invalid")
    for record, expected_name in zip(evidence, expected_names):
        if not isinstance(record, dict) or set(record) - _ROLLBACK_EVIDENCE_FIELDS:
            raise GateError("rollback evidence contains unexpected fields")
        required = {"event_type", "event_name", "operation", "status",
                    "invocation_id", "rollback_attempt_id"}
        if set(record) & {"reason"}:
            required.add("reason")
        if set(record) != required or record.get("event_type") != "rollback-evidence":
            raise GateError("rollback evidence schema is invalid")
        if any(not isinstance(record.get(k), str) or not record[k].strip()
               for k in required):
            raise GateError("rollback evidence field type is invalid")
        if record["event_name"] != expected_name or record["operation"] != "rollback":
            raise GateError("rollback evidence order is invalid")
        if record["invocation_id"] != context.invocation_id or record["rollback_attempt_id"] != context.rollback_attempt_id:
            raise GateError("rollback evidence association is invalid")
    if terminal == "success" and evidence[-1]["status"] != "pass":
        raise GateError("rollback success evidence is invalid")
    if terminal == "failure" and evidence[-1]["status"] != "failed":
        raise GateError("rollback failure evidence is invalid")
    if terminal == "not-attempted" and evidence[-1]["status"] != "not_attempted":
        raise GateError("rollback not-attempted evidence is invalid")

def _rollback_evidence(runner: Any, capability: Any, context: Any) -> tuple[int, dict[str, Any]]:
    """Run one offline rollback attempt with invocation-scoped result freshness."""
    if type(runner) is not _FakeRunner or type(capability) is not _Capability or capability.runner is not runner:
        raise GateError("rollback runner or capability is not trusted")
    if type(context) is not _RollbackContext or context.consumed:
        raise GateError("rollback context is stale or already consumed")
    request = context.request
    if set(request) != {"project", "region", "service", "candidate_revision", "target_revision", "invocation_id", "rollback_attempt_id"}:
        raise GateError("rollback request schema is invalid")
    if context.migration_status != "pass":
        record = _rollback_record(context, "rollback-not-attempted", "not_attempted", "migration-not-passed")
        runner.rollback_evidence.append(record)
        _validate_rollback_evidence(runner.rollback_evidence, context, "not-attempted")
        return 21, record
    if context.smoke_status != "fail":
        record = _rollback_record(context, "rollback-not-attempted", "not_attempted", "smoke-not-failed")
        runner.rollback_evidence.append(record)
        _validate_rollback_evidence(runner.rollback_evidence, context, "not-attempted")
        return 0, record
    required=("project", "region", "service", "candidate_revision", "target_revision", "invocation_id", "rollback_attempt_id")
    if any(not isinstance(request.get(k), str) or not request[k].strip() for k in required):
        raise GateError("rollback identity is missing")
    if request["candidate_revision"] == request["target_revision"]:
        raise GateError("rollback target is not distinct")
    if any(not NAME.fullmatch(request[k]) for k in ("candidate_revision", "target_revision", "service")):
        raise GateError("rollback identity is invalid")
    rollback_request = dict(request)
    expected = {"project": rollback_request["project"], "region": rollback_request["region"],
                "service": rollback_request["service"], "candidate_revision": rollback_request["candidate_revision"],
                "target_revision": rollback_request["target_revision"],
                "invocation_id": rollback_request["invocation_id"],
                "rollback_attempt_id": rollback_request["rollback_attempt_id"],
                "traffic_revision": rollback_request["target_revision"]}
    runner.rollback_evidence.append(_rollback_record(context, "rollback-requested", "requested"))
    operation_state = _create_operation("rollback", 1.0, time.monotonic,
                                        rollback_request["invocation_id"],
                                        rollback_request["rollback_attempt_id"])
    dispatch_status, result = _dispatch_operation(runner, capability, operation_state,
                                                   rollback_request, time.monotonic)
    if dispatch_status == 21 and result is None:
        context.consumed = True
        record = _rollback_record(context, "rollback-failed", "failed", "operation-timeout")
        runner.rollback_evidence.append(record)
        return 21, record
    context.consumed = True
    terminal = "failure"
    reason = "malformed-result"
    if isinstance(result, dict) and set(result) == _ROLLBACK_RESULT_FIELDS:
        association = ("project", "region", "service", "candidate_revision",
                        "target_revision", "invocation_id", "rollback_attempt_id")
        typed = all(isinstance(result.get(k), str) and result[k].strip()
                    for k in _ROLLBACK_RESULT_FIELDS)
        if typed and result.get("status") in ("pass", "fail") and all(result.get(k) == v for k, v in expected.items()) and result.get("status") == "pass":
            terminal = "success"
            reason = None
        else:
            reason = "state-not-verified"
    record = _rollback_record(context, "rollback-succeeded" if terminal == "success" else "rollback-failed",
                              "pass" if terminal == "success" else "failed", reason)
    runner.rollback_evidence.append(record)
    _validate_rollback_evidence(runner.rollback_evidence, context, terminal)
    return 21, record

_OPERATION_FIELDS = frozenset(("invocation_id", "operation", "attempt_id", "deadline"))
_OPERATION_EVENTS = frozenset(("operation-started", "operation-succeeded", "operation-failed",
    "operation-timed-out", "cleanup-requested", "cleanup-completed", "cleanup-failed",
    "forced-cleanup", "late-result-rejected"))

class _OperationState:
    __slots__ = ("invocation_id", "operation", "attempt_id", "deadline", "owner",
                 "terminal", "cancel_requested", "cleanup_completed", "forced_cleanup",
                 "evidence")
    def __init__(self, invocation_id: str, operation: str, attempt_id: str,
                 deadline: float) -> None:
        self.invocation_id = invocation_id
        self.operation = operation
        self.attempt_id = attempt_id
        self.deadline = deadline
        self.owner = "owned"
        self.terminal: str | None = None
        self.cancel_requested = False
        self.cleanup_completed = False
        self.forced_cleanup = False
        self.evidence: list[dict[str, Any]] = []

class _DispatchPermit:
    __slots__ = ("state",)
    def __init__(self, state: _OperationState) -> None:
        self.state = state

def _create_operation(operation: str, duration: float, clock: Any = time.monotonic,
                      invocation_id: str | None = None,
                      attempt_id: str | None = None) -> _OperationState:
    if not isinstance(operation, str) or not operation or not isinstance(duration, (int, float)) or duration <= 0:
        raise GateError("operation deadline is invalid")
    invocation_id = invocation_id or uuid.uuid4().hex
    attempt_id = attempt_id or uuid.uuid4().hex
    state = _OperationState(invocation_id, operation, attempt_id, float(clock()) + float(duration))
    state.evidence.append(_operation_record(state, "operation-started", "started"))
    return state

def _operation_request(state: Any, payload: Any = None) -> dict[str, Any]:
    if type(state) is not _OperationState or not isinstance(state.operation, str):
        raise GateError("operation state is invalid")
    request = {"invocation_id": state.invocation_id, "operation": state.operation,
               "attempt_id": state.attempt_id, "deadline": state.deadline}
    if payload is not None:
        if not isinstance(payload, dict):
            raise GateError("operation payload is invalid")
        for key, value in dict(payload).items():
            if key in request:
                if value != request[key]:
                    raise GateError("operation payload conflicts with internal association")
                continue
            request[key] = value
    return request

def _run_simulated_operation_unchecked(runner: Any, capability: Any, operation: str,
                                       request: dict[str, Any], permit: Any,
                                       protocol: Any = _PROTOCOL) -> dict[str, Any]:
    if type(permit) is not _DispatchPermit or permit.state.operation != operation:
        raise GateError("operation dispatch permit is invalid")
    if protocol is not _PROTOCOL or type(runner) is not _FakeRunner:
        raise GateError("protocol or runner is not trusted")
    if type(capability) is not _Capability or capability.runner is not runner:
        raise GateError("capability does not belong to runner")
    capability_key = operation + ":" + str(request.get("sample_index", request.get("bucket_number", "")))
    if not isinstance(operation, str) or capability_key in capability.used:
        raise GateError("capability is missing, stale, or already used")
    if operation not in runner._responses or not isinstance(request, dict):
        raise GateError("unsupported simulated operation")
    response = runner._responses[operation]
    if callable(response):
        response = response(dict(request))
    if not isinstance(response, dict):
        raise GateError("simulated response is malformed")
    capability.used.add(capability_key)
    runner.calls.append(operation)
    runner.events.append({"event_type": "simulated-operation", "event_name": "offline-operation-recorded",
                          "operation": operation, "runner_id": id(runner),
                          "status": response.get("status", "unknown")})
    return dict(response)

def _dispatch_operation(runner: Any, capability: Any, state: Any, payload: Any = None,
                        clock: Any = time.monotonic, protocol: Any = _PROTOCOL) -> tuple[int, Any]:
    if type(state) is not _OperationState:
        raise GateError("operation state is invalid")
    _check_operation_owner(state, runner, capability)
    if state.terminal is not None:
        raise GateError("operation is already terminal")
    if float(clock()) >= state.deadline:
        _timeout_operation(state, runner, capability, clock)
        return 21, None
    request = _operation_request(state, payload)
    result = _run_simulated_operation_unchecked(runner, capability, state.operation, request,
                                                _DispatchPermit(state), protocol)
    return _complete_operation(state, runner, capability, result, clock), result

def _operation_record(state: _OperationState, name: str, status: str) -> dict[str, Any]:
    if name not in _OPERATION_EVENTS or not isinstance(status, str):
        raise GateError("operation evidence is invalid")
    return {"event_type": "operation-evidence", "event_name": name,
            "operation": state.operation, "invocation_id": state.invocation_id,
            "attempt_id": state.attempt_id, "status": status}

def _check_operation_owner(state: Any, runner: Any, capability: Any) -> None:
    if type(state) is not _OperationState or type(runner) is not _FakeRunner or type(capability) is not _Capability or capability.runner is not runner:
        raise GateError("operation owner is not trusted")
    if state.owner != "owned":
        raise GateError("operation ownership is revoked")

def _cancel_operation(state: _OperationState, runner: Any, capability: Any) -> None:
    if type(state) is not _OperationState or type(runner) is not _FakeRunner or type(capability) is not _Capability or capability.runner is not runner:
        raise GateError("operation owner is not trusted")
    if state.owner not in ("owned", "revoked"):
        raise GateError("operation ownership is revoked")
    if state.cancel_requested:
        return
    state.cancel_requested = True
    state.evidence.append(_operation_record(state, "cleanup-requested", "cancel_requested"))

def _cleanup_operation(state: _OperationState, runner: Any, capability: Any, forced: bool = False) -> None:
    if type(state) is not _OperationState or type(runner) is not _FakeRunner or type(capability) is not _Capability or capability.runner is not runner:
        raise GateError("cleanup owner is not trusted")
    if state.cleanup_completed:
        return
    if state.owner != "revoked" or not state.cancel_requested:
        raise GateError("cleanup is not owned")
    if forced and state.forced_cleanup:
        return
    if forced:
        state.forced_cleanup = True
        state.evidence.append(_operation_record(state, "forced-cleanup", "requested"))
    outcome = (runner.forced_cleanup_outcomes if forced else runner.cleanup_outcomes).get(state.operation, "completed")
    if outcome == "completed" or (forced and outcome == "force-completed"):
        state.cleanup_completed = True
        state.evidence.append(_operation_record(state, "cleanup-completed", "completed"))
    elif outcome == "failed":
        state.evidence.append(_operation_record(state, "cleanup-failed", "failed"))
        if not forced and runner.forced_cleanup_supported:
            _cleanup_operation(state, runner, capability, True)
    else:
        state.evidence.append(_operation_record(state, "cleanup-failed", "unresolved"))
        if not forced and runner.forced_cleanup_supported:
            _cleanup_operation(state, runner, capability, True)
        else:
            return

def _timeout_operation(state: Any, runner: Any, capability: Any, clock: Any = time.monotonic) -> int:
    if type(state) is not _OperationState:
        raise GateError("operation state is invalid")
    if state.terminal == "timed_out":
        return 21
    if state.terminal is not None:
        return 0
    if float(clock()) < state.deadline:
        raise DeadlineError("operation deadline has not elapsed")
    _check_operation_owner(state, runner, capability)
    state.terminal = "timed_out"
    state.owner = "revoked"
    state.evidence.append(_operation_record(state, "operation-timed-out", "timed_out"))
    _cancel_operation(state, runner, capability)
    _cleanup_operation(state, runner, capability)
    return 21

def _complete_operation(state: Any, runner: Any, capability: Any, result: Any,
                        clock: Any = time.monotonic) -> int:
    if type(state) is not _OperationState:
        raise GateError("operation state is invalid")
    if state.terminal == "timed_out":
        if not any(e["event_name"] == "late-result-rejected" for e in state.evidence):
            state.evidence.append(_operation_record(state, "late-result-rejected", "rejected"))
        return 21
    if state.terminal is not None:
        return 0
    if float(clock()) >= state.deadline:
        _timeout_operation(state, runner, capability, clock)
        state.evidence.append(_operation_record(state, "late-result-rejected", "rejected"))
        return 21
    _check_operation_owner(state, runner, capability)
    # Observation samples and aggregate responses have their own exact schemas;
    # they deliberately do not carry the generic operation ``status`` field.
    # The dispatcher still owns the transition, and only these two internal
    # operation names may use a schema-valid mapping as a successful result.
    observation_result = state.operation in ("observation", "observation-aggregate")
    state.terminal = "succeeded" if isinstance(result, dict) and (result.get("status") == "pass" or observation_result) else "failed"
    state.owner = "released"
    state.evidence.append(_operation_record(state, "operation-succeeded" if state.terminal == "succeeded" else "operation-failed", state.terminal))
    return 0 if state.terminal == "succeeded" else 21

def _validate_operation_evidence(state: Any, expected_attempt: str | None = None) -> None:
    if type(state) is not _OperationState or not isinstance(state.evidence, list):
        raise GateError("operation evidence is malformed")
    if expected_attempt is not None and state.attempt_id != expected_attempt:
        raise GateError("operation attempt is stale")
    statuses = {"operation-started": {"started"}, "operation-succeeded": {"succeeded"},
                "operation-failed": {"failed"}, "operation-timed-out": {"timed_out"},
                "cleanup-requested": {"cancel_requested"}, "cleanup-completed": {"completed"},
                "cleanup-failed": {"failed", "unresolved"}, "forced-cleanup": {"requested"},
                "late-result-rejected": {"rejected"}}
    names=[]
    for event in state.evidence:
        if set(event) != {"event_type", "event_name", "operation", "invocation_id", "attempt_id", "status"}:
            raise GateError("operation evidence schema is invalid")
        if any(not isinstance(event[k], str) or not event[k].strip() for k in event):
            raise GateError("operation evidence types are invalid")
        if event["event_type"] != "operation-evidence" or event["operation"] != state.operation or event["invocation_id"] != state.invocation_id or event["attempt_id"] != state.attempt_id:
            raise GateError("operation evidence correlation is invalid")
        if event["event_name"] not in statuses or event["status"] not in statuses[event["event_name"]]:
            raise GateError("operation evidence value is invalid")
        names.append(event["event_name"])
    if not names or names[0] != "operation-started":
        raise GateError("operation evidence must start with operation-started")
    terminals=[n for n in names if n in ("operation-succeeded","operation-failed","operation-timed-out")]
    if len(terminals) != 1:
        raise GateError("operation evidence terminal cardinality is invalid")
    terminal_index=names.index(terminals[0])
    if any(n in ("operation-started","operation-succeeded","operation-failed","operation-timed-out") for n in names[terminal_index+1:]):
        raise GateError("multiple operation terminal outcomes")
    if terminals[0] != "operation-timed-out":
        if any(n.startswith("cleanup-") or n in ("forced-cleanup", "late-result-rejected") for n in names[terminal_index+1:]):
            raise GateError("cleanup is only valid after timeout")
        return
    tail=names[terminal_index+1:]
    if tail.count("cleanup-requested") != 1 or tail.index("cleanup-requested") != 0:
        raise GateError("cleanup request sequence is invalid")
    if "late-result-rejected" in tail and tail[-1] != "late-result-rejected":
        raise GateError("late result must be final evidence")
    if tail.count("late-result-rejected") > 1:
        raise GateError("duplicate late result evidence")
    core=tail[:-1] if tail and tail[-1] == "late-result-rejected" else tail
    allowed_cleanup_sequences = {
        ("cleanup-requested", "cleanup-completed"),
        ("cleanup-requested", "cleanup-failed"),
        ("cleanup-requested", "cleanup-failed", "forced-cleanup", "cleanup-completed"),
        ("cleanup-requested", "cleanup-failed", "forced-cleanup", "cleanup-failed"),
    }
    if tuple(core) not in allowed_cleanup_sequences:
        raise GateError("cleanup evidence transition is invalid")

def _validate_cleanup_ack(state: Any, acknowledgement: Any) -> None:
    fields = {"status", "invocation_id", "operation", "attempt_id"}
    if type(state) is not _OperationState or not isinstance(acknowledgement, dict) or set(acknowledgement) != fields:
        raise GateError("cleanup acknowledgement is malformed")
    if any(not isinstance(acknowledgement[k], str) or not acknowledgement[k].strip() for k in fields):
        raise GateError("cleanup acknowledgement types are invalid")
    if acknowledgement["invocation_id"] != state.invocation_id or acknowledgement["operation"] != state.operation or acknowledgement["attempt_id"] != state.attempt_id:
        raise GateError("cleanup acknowledgement is stale")
    if acknowledgement["status"] not in ("completed", "failed"):
        raise GateError("cleanup acknowledgement status is invalid")

_OBSERVATION_SAMPLE_FIELDS = frozenset(("sample_index", "window_started_at", "window_ended_at",
    "request_count", "server_error_count", "latency_p95_ms", "probe_attempted",
    "probe_succeeded", "observed_revision", "observed_at"))
_OBSERVATION_AGGREGATE_FIELDS = frozenset(("project", "region", "service", "candidate_revision",
    "invocation_id", "attempt_id", "aggregate_p95_ms", "bucket_p95_ms"))
_OBSERVATION_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")

def _parse_observation_timestamp(value: str) -> datetime:
    if not isinstance(value, str) or not _OBSERVATION_TIMESTAMP.fullmatch(value):
        raise GateError("observation telemetry timestamp is invalid")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise GateError("observation telemetry timestamp is invalid") from exc

class _ObservationState:
    __slots__ = ("invocation_id", "operation", "attempt_id", "project", "region", "service",
                 "candidate_revision", "service_url", "owner", "t0", "observation_deadline", "hard_deadline",
                 "wall_start", "terminal", "consumed", "samples", "sample_indices", "evidence",
                 "aggregate_p95_ms", "bucket_p95_ms", "bucket_breaches")
    def __init__(self, project: str, region: str, service: str, candidate_revision: str,
                 clock: Any, wall_start: datetime, service_url: str | None = None) -> None:
        self.invocation_id = uuid.uuid4().hex
        self.operation = "observation"
        self.attempt_id = uuid.uuid4().hex
        self.project, self.region, self.service = project, region, service
        self.candidate_revision = candidate_revision
        self.service_url = service_url
        self.owner = "owned"
        self.t0 = float(clock())
        self.observation_deadline = self.t0 + 1800.0
        self.hard_deadline = self.t0 + 1980.0
        self.wall_start = wall_start.astimezone(timezone.utc).replace(microsecond=0)
        self.terminal = None
        self.consumed = False
        self.samples = []
        self.sample_indices = set()
        self.evidence = [{"event_type":"observation-evidence", "event_name":"observation-started",
                          "operation":"observation", "invocation_id":self.invocation_id,
                          "attempt_id":self.attempt_id, "status":"started"}]
        self.aggregate_p95_ms = None
        self.bucket_p95_ms = None
        self.bucket_breaches = {"error": [], "latency": []}

def _observation_timestamp(state: _ObservationState, seconds: int) -> str:
    return (state.wall_start + timedelta(seconds=seconds)).strftime("%Y-%m-%dT%H:%M:%S.%f")[:23] + "Z"

def _observation_event(state: _ObservationState, name: str, status: str, sample_index: int | None = None) -> dict[str, Any]:
    event = {"event_type":"observation-evidence", "event_name":name, "operation":"observation",
             "invocation_id":state.invocation_id, "attempt_id":state.attempt_id, "status":status}
    if sample_index is not None: event["sample_index"] = sample_index
    return event

def _create_observation(project: str, region: str, service: str, candidate_revision: str,
                        migration_status: str, deploy_status: str, smoke_status: str,
                        clock: Any = time.monotonic, wall_start: datetime | None = None,
                        service_url: str | None = None) -> _ObservationState:
    if (migration_status, deploy_status, smoke_status) != ("pass", "pass", "pass"):
        raise GateError("observation prerequisites are not satisfied")
    for value, pattern in ((project, PROJECT), (service, NAME), (candidate_revision, NAME)):
        if not isinstance(value, str) or not pattern.fullmatch(value):
            raise GateError("observation identity is invalid")
    if service_url is not None:
        service_url = check_url(service_url)
    return _ObservationState(project, region, service, candidate_revision, clock,
                             wall_start or datetime.now(timezone.utc), service_url)

def _observation_request(state: _ObservationState, index: int) -> dict[str, Any]:
    if type(state) is not _ObservationState or state.owner != "owned" or state.terminal is not None:
        raise GateError("observation state is not dispatchable")
    if not isinstance(index, int) or isinstance(index, bool) or not 1 <= index <= 30 or index in state.sample_indices:
        raise GateError("observation sample index is invalid")
    request = {"project":state.project, "region":state.region, "service":state.service,
            "candidate_revision":state.candidate_revision, "invocation_id":state.invocation_id,
            "operation":"observation", "attempt_id":state.attempt_id,
            "sample_index":index, "window_started_at":_observation_timestamp(state,(index-1)*60),
            "window_ended_at":_observation_timestamp(state,index*60),
            "observed_at":_observation_timestamp(state,index*60), "deadline":state.hard_deadline}
    if state.service_url is not None:
        request["service_url"] = state.service_url
    return request

def _validate_observation_sample(sample: Any, request: dict[str, Any], state: _ObservationState) -> None:
    if not isinstance(sample, dict) or set(sample) != _OBSERVATION_SAMPLE_FIELDS:
        raise GateError("observation sample schema is invalid")
    integer_fields = ("sample_index", "request_count", "server_error_count")
    if any(not isinstance(sample[k], int) or isinstance(sample[k], bool) or sample[k] < 0 for k in integer_fields):
        raise GateError("observation sample integer type is invalid")
    if not isinstance(sample["latency_p95_ms"], int) and sample["latency_p95_ms"] is not None:
        raise GateError("observation latency type is invalid")
    if isinstance(sample["latency_p95_ms"], int) and sample["latency_p95_ms"] < 0:
        raise GateError("observation latency is invalid")
    if any(not isinstance(sample[k], bool) for k in ("probe_attempted", "probe_succeeded")):
        raise GateError("observation probe type is invalid")
    if not isinstance(sample["observed_revision"], str) or sample["observed_revision"] != state.candidate_revision:
        raise GateError("observation revision is invalid")
    if sample["sample_index"] != request["sample_index"] or sample["request_count"] < sample["server_error_count"]:
        raise GateError("observation sample association is invalid")
    for key in ("window_started_at", "window_ended_at", "observed_at"):
        if not isinstance(sample[key], str) or not _OBSERVATION_TIMESTAMP.fullmatch(sample[key]):
            raise GateError("observation timestamp is invalid")
    if sample["window_started_at"] != request["window_started_at"] or sample["window_ended_at"] != request["window_ended_at"] or sample["observed_at"] != request["observed_at"]:
        raise GateError("observation timestamp association is invalid")
    if sample["request_count"] == 0 and (sample["server_error_count"] != 0 or sample["latency_p95_ms"] is not None):
        raise GateError("zero-request observation sample is invalid")
    if sample["request_count"] > 0 and sample["latency_p95_ms"] is None:
        raise GateError("observation latency is missing")
    if sample["probe_attempted"] is not True or sample["probe_succeeded"] is not True:
        raise GateError("observation probe did not succeed")

def _validate_observation_telemetry_timestamp(value: str, request: dict[str, Any], state: _ObservationState) -> None:
    event_time = _parse_observation_timestamp(value)
    start = _parse_observation_timestamp(request["window_started_at"])
    end = _parse_observation_timestamp(request["window_ended_at"])
    if not start <= event_time < end:
        raise GateError("observation telemetry timestamp is outside its half-open interval")

def _dispatch_observation_sample(runner: Any, capability: Any, state: _ObservationState,
                                 index: int, clock: Any = time.monotonic) -> tuple[int, Any]:
    if type(runner) is not _FakeRunner or type(capability) is not _Capability or capability.runner is not runner:
        raise GateError("observation runner is not trusted")
    request = _observation_request(state, index)
    now = float(clock())
    expected = state.t0 + index * 60.0
    if now < expected or now > state.observation_deadline or now >= state.hard_deadline:
        raise GateError("observation sample is outside collection deadline")
    op_state = _create_operation("observation", state.hard_deadline - now, clock,
                                  state.invocation_id, state.attempt_id)
    status, result = _dispatch_operation(runner, capability, op_state, request, clock)
    if status != 0:
        state.terminal = "timed_out"; state.owner = "revoked"
        state.evidence.append(_observation_event(state, "operation-timed-out", "timed_out"))
        return 21, None
    telemetry_timestamp = request["window_started_at"]
    if isinstance(result, dict) and set(result) == {"sample", "telemetry_event_timestamp"}:
        telemetry_timestamp = result["telemetry_event_timestamp"]
        result = result["sample"]
    _validate_observation_telemetry_timestamp(telemetry_timestamp, request, state)
    _validate_observation_sample(result, request, state)
    state.samples.append(dict(result)); state.sample_indices.add(index)
    state.evidence.append(_observation_event(state, "observation-sample-recorded", "recorded", index))
    if index % 5 == 0:
        bucket = state.samples[index-5:index]
        bucket_number = index // 5
        _dispatch_observation_aggregate(runner, capability, state, clock, bucket_number)
        if all(s["request_count"] == 0 for s in bucket) and state.bucket_p95_ms[bucket_number - 1] is not None:
            raise GateError("zero-request bucket latency must be not-applicable")
        if sum(s["server_error_count"] for s in bucket) * 100 > sum(s["request_count"] for s in bucket) * 2:
            state.bucket_breaches["error"].append(bucket_number)
        for metric, breaches in state.bucket_breaches.items():
            if len(breaches) >= 3 or (len(breaches) >= 2 and breaches[-1] == breaches[-2] + 1):
                state.terminal = "failed"; state.owner = "released"; state.consumed = True
                state.evidence.append(_observation_event(state, "observation-failed", "failed"))
                return 21, None
    return 0, dict(result)

def _dispatch_observation_aggregate(runner: Any, capability: Any, state: _ObservationState,
                                    clock: Any = time.monotonic,
                                    bucket_number: int | None = None) -> tuple[int, Any]:
    if state.terminal is not None or (bucket_number is None and len(state.samples) != 30) or (bucket_number is not None and (not isinstance(bucket_number, int) or not 1 <= bucket_number <= 6 or len(state.samples) < bucket_number * 5)):
        raise GateError("observation aggregate prerequisite is invalid")
    request = {"project":state.project, "region":state.region, "service":state.service,
               "candidate_revision":state.candidate_revision, "invocation_id":state.invocation_id,
               "operation":"observation-aggregate", "attempt_id":state.attempt_id,
               "deadline":state.hard_deadline}
    if bucket_number is not None:
        request["bucket_number"] = bucket_number
    op_state = _create_operation("observation-aggregate", max(0.001, state.hard_deadline-float(clock())), clock,
                                 state.invocation_id, state.attempt_id)
    status, result = _dispatch_operation(runner, capability, op_state, request, clock)
    if status != 0 or not isinstance(result, dict) or set(result) != _OBSERVATION_AGGREGATE_FIELDS:
        raise GateError("observation aggregate is invalid")
    if any(not isinstance(result[k], str) or result[k] != request[k] for k in ("project","region","service","candidate_revision","invocation_id","attempt_id")):
        raise GateError("observation aggregate association is invalid")
    if result["aggregate_p95_ms"] is not None and (not isinstance(result["aggregate_p95_ms"], int) or isinstance(result["aggregate_p95_ms"], bool) or result["aggregate_p95_ms"] < 0):
        raise GateError("observation aggregate latency is invalid")
    if not isinstance(result["bucket_p95_ms"], list) or len(result["bucket_p95_ms"]) != 6 or any(v is not None and (not isinstance(v, int) or isinstance(v, bool) or v < 0) for v in result["bucket_p95_ms"]):
        raise GateError("observation bucket latency is invalid")
    state.aggregate_p95_ms = result["aggregate_p95_ms"] if bucket_number is None else state.aggregate_p95_ms
    if bucket_number is None:
        state.bucket_p95_ms = list(result["bucket_p95_ms"])
    else:
        if state.bucket_p95_ms is None:
            state.bucket_p95_ms = [None] * 6
        state.bucket_p95_ms[bucket_number - 1] = result["bucket_p95_ms"][bucket_number - 1]
        value = state.bucket_p95_ms[bucket_number - 1]
        if value is not None and value > 1500:
            state.bucket_breaches["latency"].append(bucket_number)
            breaches = state.bucket_breaches["latency"]
            if len(breaches) >= 3 or (len(breaches) >= 2 and breaches[-1] == breaches[-2] + 1):
                state.terminal = "failed"; state.owner = "released"; state.consumed = True
                state.evidence.append(_observation_event(state, "observation-failed", "failed"))
                return 21, None
    return 0, dict(result)

def _finish_observation(state: _ObservationState, clock: Any = time.monotonic) -> int:
    if type(state) is not _ObservationState or state.terminal is not None or len(state.samples) != 30:
        raise GateError("observation cannot be finalized")
    requests = sum(s["request_count"] for s in state.samples)
    errors = sum(s["server_error_count"] for s in state.samples)
    if errors * 100 > requests or state.aggregate_p95_ms is None and requests > 0 or state.aggregate_p95_ms is not None and state.aggregate_p95_ms > 1000 or state.bucket_p95_ms is None or any(state.bucket_breaches.values()):
        state.terminal = "failed"
    else:
        state.terminal = "succeeded"
    state.owner = "released"; state.consumed = True
    state.evidence.append(_observation_event(state, "observation-succeeded" if state.terminal == "succeeded" else "observation-failed", state.terminal))
    return 0 if state.terminal == "succeeded" else 21

def _validate_observation_evidence(state: Any) -> None:
    if type(state) is not _ObservationState or not isinstance(state.evidence, list):
        raise GateError("observation evidence is malformed")
    base_fields = {"event_type", "event_name", "operation", "invocation_id", "attempt_id", "status"}
    if not state.evidence or not isinstance(state.evidence[0], dict) or state.evidence[0].get("event_name") != "observation-started":
        raise GateError("observation evidence must start correctly")
    allowed = {
        "observation-started": ("started", base_fields),
        "observation-sample-recorded": ("recorded", base_fields | {"sample_index"}),
        "observation-succeeded": ("succeeded", base_fields),
        "observation-failed": ("failed", base_fields),
        "operation-timed-out": ("timed_out", base_fields),
    }
    sample_events = []
    terminals = []
    closed = False
    for event in state.evidence:
        if not isinstance(event, dict):
            raise GateError("observation evidence event is malformed")
        name = event.get("event_name")
        if name not in allowed or set(event) != allowed[name][1]:
            raise GateError("observation evidence schema is invalid")
        expected_status, _ = allowed[name]
        if (event.get("event_type"), event.get("operation"), event.get("invocation_id"), event.get("attempt_id"), event.get("status")) != ("observation-evidence", "observation", state.invocation_id, state.attempt_id, expected_status):
            raise GateError("observation evidence correlation or status is invalid")
        if any(not isinstance(event[key], str) or not event[key].strip() for key in base_fields):
            raise GateError("observation evidence field types are invalid")
        if name == "observation-sample-recorded":
            if not isinstance(event["sample_index"], int) or isinstance(event["sample_index"], bool):
                raise GateError("observation sample index type is invalid")
            if closed:
                raise GateError("observation event follows terminal")
            sample_events.append(event)
        elif name == "observation-started":
            if len(sample_events) or sum(e.get("event_name") == name for e in state.evidence) != 1:
                raise GateError("observation started cardinality is invalid")
        else:
            if terminals or closed:
                raise GateError("observation terminal cardinality is invalid")
            terminals.append(event)
            closed = True
    if len(sample_events) != len(state.sample_indices) or [e["sample_index"] for e in sample_events] != list(range(1, len(sample_events) + 1)):
        raise GateError("observation sample evidence is invalid")
    if len(terminals) != 1:
        raise GateError("observation terminal cardinality is invalid")
    expected_terminal = {"succeeded": "observation-succeeded",
                         "failed": "observation-failed",
                         "timed_out": "operation-timed-out"}
    if state.terminal not in expected_terminal or terminals[0]["event_name"] != expected_terminal[state.terminal]:
        raise GateError("observation terminal state does not match evidence")
    if state.terminal == "succeeded" and len(sample_events) != 30:
        raise GateError("observation success requires complete sample coverage")

def _run_deployment_workflow(runner: Any, capability: Any, project: str, region: str,
                             service: str, candidate_revision: str, target_revision: str,
                             clock: Any = time.monotonic,
                             expected_oauth_callback: str | None = None) -> dict[str, Any]:
    """Authoritative offline lifecycle: migration, deploy, smoke, observation, verdict."""
    if type(runner) is not _FakeRunner or type(capability) is not _Capability or capability.runner is not runner:
        raise GateError("workflow runner is not trusted")
    states = {}
    deployment_url: str | None = None
    for operation, seconds in (("migration", 900.0), ("deployment", 120.0), ("smoke", 600.0)):
        state = _create_operation(operation, seconds, clock)
        states[operation] = state
        payload = {
            "project": project, "region": region, "service": service,
            "candidate_revision": candidate_revision}
        if operation == "smoke":
            if deployment_url is None:
                raise DeploymentInputError("trusted deployment URL is missing")
            payload["service_url"] = deployment_url
        status, result = _dispatch_operation(runner, capability, state, payload, clock)
        if status != 0:
            return {"terminal": "failure", "failed_operation": operation,
                    "observation_called": False, "rollback_attempts": 0}
        if operation == "deployment":
            try:
                if not isinstance(result, dict) or set(result) != {"status", "service_url"} or result.get("status") != "pass":
                    raise DeploymentInputError("trusted deployment URL is missing")
                deployment_url = check_url(result["service_url"])
                if expected_oauth_callback is not None and expected_oauth_callback != deployment_url + "/login/oauth2/code/google":
                    raise DeploymentInputError("trusted deployment callback is inconsistent")
            except GateError as exc:
                rollback = _create_rollback_context(project, region, service, candidate_revision,
                                                     target_revision, "pass", "fail")
                _rollback_evidence(runner, capability, rollback)
                if isinstance(exc, DeploymentInputError):
                    raise
                raise DeploymentInputError("trusted deployment URL is invalid") from exc
    observation = _create_observation(project, region, service, candidate_revision,
                                      "pass", "pass", "pass", clock,
                                      service_url=deployment_url)
    advance = getattr(clock, "advance", None)
    try:
        for index in range(1, 31):
            if callable(advance):
                advance(60.0)
            status, _ = _dispatch_observation_sample(runner, capability, observation, index, clock)
            if status != 0:
                break
        if observation.terminal is None:
            _dispatch_observation_aggregate(runner, capability, observation, clock)
            _finish_observation(observation, clock)
        _validate_observation_evidence(observation)
    except GateError:
        if observation.terminal is None:
            observation.terminal = "failed"; observation.owner = "released"; observation.consumed = True
            observation.evidence.append(_observation_event(observation, "observation-failed", "failed"))
    if observation.terminal == "succeeded":
        return {"terminal": "success", "observation_called": True,
                "observation_status": "succeeded", "rollback_attempts": 0}
    rollback = _create_rollback_context(project, region, service, candidate_revision,
                                        target_revision, "pass", "fail",
                                        observation.invocation_id)
    rollback_status, _ = _rollback_evidence(runner, capability, rollback)
    return {"terminal": "failure", "observation_called": True,
            "observation_status": observation.terminal,
            "rollback_attempts": 1, "rollback_status": rollback_status}

class _OfflineValidationClock:
    def __init__(self) -> None:
        self.value = 0.0
    def __call__(self) -> float:
        return self.value
    def advance(self, seconds: float) -> None:
        self.value += seconds

def _run_validated_offline_workflow(i: dict[str, Any]) -> None:
    """Use the same lifecycle path for validation, with no external runner."""
    clock = _OfflineValidationClock()
    def sample(request: dict[str, Any]) -> dict[str, Any]:
        return {"sample_index": request["sample_index"],
                "window_started_at": request["window_started_at"],
                "window_ended_at": request["window_ended_at"], "request_count": 0,
                "server_error_count": 0, "latency_p95_ms": None,
                "probe_attempted": True, "probe_succeeded": True,
                "observed_revision": request["candidate_revision"],
                "observed_at": request["observed_at"]}
    def aggregate(request: dict[str, Any]) -> dict[str, Any]:
        return {"project": request["project"], "region": request["region"],
                "service": request["service"], "candidate_revision": request["candidate_revision"],
                "invocation_id": request["invocation_id"], "attempt_id": request["attempt_id"],
                "aggregate_p95_ms": None, "bucket_p95_ms": [None] * 6}
    runner, capability = _create_trusted_fake_runner({
        "migration": {"status": "pass"}, "deployment": {"status": "pass", "service_url": "https://video-call-staging-abc123.run.app"},
        "smoke": {"status": "pass"}, "observation": sample,
        "observation-aggregate": aggregate})
    result = _run_deployment_workflow(runner, capability, i["project"], i["region"],
                                      i["service_name"], i["candidate_revision"],
                                      i["previous_revision"], clock, i["oauth_callback"])
    if result.get("terminal") != "success":
        raise GateError("offline lifecycle validation did not succeed")

def safe(v:Any)->Any:
    if isinstance(v,dict): return {str(k):safe(x) for k,x in v.items() if not re.search(r"(?i)(secret|password|token|cookie|credential|session|authorization|oauth.?code)",str(k))}
    if isinstance(v,list): return [safe(x) for x in v]
    return v if v is None or isinstance(v,(str,int,float,bool)) else "[omitted]"
def emit(event:str,**fields:Any)->None: print(json.dumps({"event":event,**safe(fields)},sort_keys=True,separators=(",", ":")))
def req(o:dict[str,Any],k:str)->Any:
    v=o.get(k)
    if v is None or (isinstance(v,str) and not v.strip()): raise GateError("missing required input: "+k)
    return v
def digest(v:Any,label:str)->str:
    if not isinstance(v,str) or PLACEHOLDER.search(v) or not IMAGE.fullmatch(v): raise GateError(label+" must be immutable")
    return v
def manifest_image(m:Any,label:str)->str:
    if not isinstance(m,dict) or set(m)!={"spec"} or not isinstance(m["spec"],dict) or set(m["spec"])!={"template"} or not isinstance(m["spec"]["template"],dict) or set(m["spec"]["template"])!={"containers"}:
        raise GateError(label+" manifest shape is ambiguous")
    try: cs=m["spec"]["template"]["containers"]
    except (KeyError,TypeError): raise GateError(label+" manifest image is missing")
    if not isinstance(cs,list) or len(cs)!=1 or not isinstance(cs[0],dict) or set(cs[0])!={"image"}: raise GateError(label+" manifest shape is ambiguous")
    return digest(cs[0].get("image"),label+" manifest image")
def check_url(v:Any,fixture:bool=False)->str:
    if not isinstance(v,str) or len(v)>253 or PLACEHOLDER.search(v) or any(ord(ch)<0x21 or ord(ch)==0x7f or ch in "\\%" for ch in v):
        raise GateError("invalid stable URL")
    try: parsed=urlsplit(v); host=parsed.hostname or ""; port=parsed.port
    except ValueError: raise GateError("invalid stable URL")
    if not v.startswith("https://") or parsed.scheme!="https" or parsed.username or parsed.password or not host or parsed.netloc!=host or port is not None or parsed.query or parsed.fragment:
        raise GateError("invalid stable URL")
    if parsed.path not in ("", "/") or host!=host.lower() or host.endswith("."):
        raise GateError("invalid stable URL")
    try: host.encode("ascii")
    except UnicodeEncodeError: raise GateError("invalid stable URL")
    try: address=ipaddress.ip_address(host)
    except ValueError: address=None
    if address is not None or host=="localhost" or not host.endswith(".run.app"):
        raise GateError("invalid stable URL")
    labels=host.split(".")
    if len(labels)<3 or labels[-2:]!=["run","app"] or any(not NAME.fullmatch(label) for label in labels[:-2]):
        raise GateError("invalid stable URL")
    return "https://"+host
def owned(e:Any,rev:str,i:dict[str,Any],label:str)->None:
    exact={"project":i["project"],"region":i["region"],"service":i["service_name"],"revision":rev}
    if not isinstance(e,dict) or e!=exact or not NAME.fullmatch(rev): raise GateError(label+" ownership is not exact")
_INPUT_FIELDS=frozenset(("target_platform","environment","region","project","service_name","candidate_revision","previous_revision","candidate_image","previous_image","runtime_image","service_manifest","migration_manifest","candidate_revision_evidence","previous_revision_evidence","oauth_callback","identities","secret_versions","migration_before_serving","no_migration_downgrade","migration_result","smoke_result","observation_result","observation_policy","budgets","cost_preflight","overall_deadline_seconds"))
_REQUIRED_INPUT_FIELDS=_INPUT_FIELDS-{"overall_deadline_seconds"}
_BUDGETS={"migration_seconds":900,"startup_seconds":120,"probe_seconds":5,"smoke_seconds":600,"observation_seconds":1800,"rollback_seconds":600}
_CLOUD_RUN={"cpu":1,"memory_gib":1,"concurrency":20,"timeout_seconds":300,"min_instances":0,"max_instances":3}
_CLOUD_SQL={"mysql_major":8,"storage_gib":10,"autogrow":False,"ha":False,"backups":True,"pitr":False}

def _validate_input_shape(i:Any)->None:
    if not isinstance(i,dict) or not _REQUIRED_INPUT_FIELDS.issubset(i) or set(i)-_INPUT_FIELDS:
        raise GateError("input fields are not exact")
    if any(i.get(k) is None for k in _REQUIRED_INPUT_FIELDS): raise GateError("required input is null")
    string_fields=("target_platform","environment","region","project","service_name","candidate_revision","previous_revision","candidate_image","previous_image","runtime_image","oauth_callback","migration_result","smoke_result","observation_result")
    if any(type(i[k]) is not str for k in string_fields): raise GateError("input type is invalid")
    if any(not i[k].strip() for k in ("project","service_name","candidate_revision","previous_revision","candidate_image","previous_image","runtime_image","oauth_callback")): raise GateError("input string is empty")
    if type(i["migration_before_serving"]) is not bool or type(i["no_migration_downgrade"]) is not bool: raise GateError("input boolean type is invalid")
    if type(i["identities"]) is not dict or set(i["identities"])!={"runtime","migrator","deployer"} or any(type(v) is not str or not v.strip() for v in i["identities"].values()): raise GateError("identity shape is invalid")
    if type(i["secret_versions"]) is not dict or set(i["secret_versions"])!={"runtime","migrator"} or any(type(v) is not list or len(v)!=1 or type(v[0]) is not str or not v[0].strip() for v in i["secret_versions"].values()): raise GateError("secret version shape is invalid")
    for evidence in (i["candidate_revision_evidence"],i["previous_revision_evidence"]):
        if type(evidence) is not dict or set(evidence)!={"project","region","service","revision"} or any(type(v) is not str or not v.strip() for v in evidence.values()): raise GateError("revision evidence shape is invalid")
    manifest_image(i["service_manifest"], "service")
    manifest_image(i["migration_manifest"], "migration")
    policy=i["observation_policy"]
    if type(policy) is not dict or set(policy)!={"minimum_samples","maximum_gap_seconds","freshness_seconds","thresholds"}: raise GateError("observation policy shape is invalid")
    if type(policy["minimum_samples"]) is not int or isinstance(policy["minimum_samples"],bool) or type(policy["maximum_gap_seconds"]) not in (int,float) or isinstance(policy["maximum_gap_seconds"],bool) or type(policy["freshness_seconds"]) not in (int,float) or isinstance(policy["freshness_seconds"],bool) or not isinstance(policy["thresholds"],dict) or set(policy["thresholds"]) != {"max_error_rate", "max_latency_ms"}: raise GateError("observation policy type is invalid")
    if not math.isfinite(float(policy["maximum_gap_seconds"])) or not math.isfinite(float(policy["freshness_seconds"])) or policy["maximum_gap_seconds"]<=0 or policy["freshness_seconds"]<=0: raise GateError("observation policy range is invalid")
    if type(i["budgets"]) is not dict or set(i["budgets"])!=set(_BUDGETS) or any(type(i["budgets"][k]) is not int or isinstance(i["budgets"][k],bool) or i["budgets"][k]!=v for k,v in _BUDGETS.items()): raise GateError("deadline budgets invalid")
    if "overall_deadline_seconds" in i and (type(i["overall_deadline_seconds"]) not in (int,float) or isinstance(i["overall_deadline_seconds"],bool) or not math.isfinite(float(i["overall_deadline_seconds"])) or not 0.001<=i["overall_deadline_seconds"]<=sum(_BUDGETS.values())): raise GateError("overall deadline range is invalid")
    c=i["cost_preflight"]
    if type(c) is not dict or set(c) != {"billing_approval_evidence","currency","monthly_budget","alerts_percent","notification_destination","expiration","region","cloud_run","cloud_sql","networking","retention_policy","log_policy","recurring_inventory","teardown_owner","teardown_deadline"}: raise GateError("cost preflight shape is invalid")
    if set(c["cloud_run"]) != set(_CLOUD_RUN) or set(c["cloud_sql"]) != set(_CLOUD_SQL): raise GateError("resource guardrail fields are invalid")
    if type(c.get("monthly_budget")) is not int or isinstance(c.get("monthly_budget"),bool) or type(c.get("alerts_percent")) is not list or any(type(v) is not int or isinstance(v,bool) for v in c.get("alerts_percent",[])): raise GateError("cost preflight type is invalid")
    if type(c.get("cloud_run")) is not dict or type(c.get("cloud_sql")) is not dict: raise GateError("resource guardrail shape is invalid")
    if c["cloud_run"]!=_CLOUD_RUN or c["cloud_sql"]!=_CLOUD_SQL: raise GateError("resource guardrail mismatch")

def _reject_duplicate_keys(pairs):
    result={}
    for key,value in pairs:
        if key in result: raise GateError("duplicate input field")
        result[key]=value
    return result

def _reject_nonfinite(value): raise GateError("non-finite number")

def _load_input(path:Any)->dict[str,Any]:
    text=Path(path).read_text(encoding="utf-8")
    if not text.strip(): raise GateError("empty input")
    result=json.loads(text,object_pairs_hook=_reject_duplicate_keys,parse_constant=_reject_nonfinite)
    if not isinstance(result,dict): raise GateError("root must be object")
    return result

def validate(i:dict[str,Any],fixture:bool)->None:
    _validate_input_shape(i)
    if (i.get("target_platform"),i.get("environment"),i.get("region"))!=("GOOGLE_CLOUD_RUN","staging","asia-southeast1"): raise GateError("unsupported target")
    for k in ("project","service_name"):
        v=req(i,k)
        if not isinstance(v,str) or not (PROJECT.fullmatch(v) if k=="project" else NAME.fullmatch(v)): raise GateError("invalid "+k)
    candidate=digest(req(i,"candidate_image"),"candidate_image"); digest(req(i,"previous_image"),"previous_image")
    if not (candidate==manifest_image(req(i,"service_manifest"),"service")==manifest_image(req(i,"migration_manifest"),"migration")==digest(req(i,"runtime_image"),"runtime")): raise GateError("image fields do not match exactly")
    pr,cr=req(i,"previous_revision"),req(i,"candidate_revision")
    if not isinstance(pr,str) or not isinstance(cr,str) or not NAME.fullmatch(pr) or not NAME.fullmatch(cr): raise GateError("invalid revision name")
    owned(i.get("previous_revision_evidence"),pr,i,"previous"); owned(i.get("candidate_revision_evidence"),cr,i,"candidate")
    if not isinstance(i.get("oauth_callback"), str) or not i["oauth_callback"].endswith("/login/oauth2/code/google"):
        raise GateError("callback shape is invalid")
    ids=i.get("identities")
    if not isinstance(ids,dict) or set(ids)!={"runtime","migrator","deployer"} or len(set(ids.values()))!=3 or any(not isinstance(x,str) or not IDENT.fullmatch(x) for x in ids.values()): raise GateError("identities invalid")
    if i.get("secret_versions")!={"runtime":["1"],"migrator":["1"]} or i.get("migration_before_serving") is not True or i.get("no_migration_downgrade") is not True: raise GateError("migration controls missing")
    budgets={"migration_seconds":900,"startup_seconds":120,"probe_seconds":5,"smoke_seconds":600,"observation_seconds":1800,"rollback_seconds":600}
    if i.get("budgets")!=budgets or not isinstance(i.get("overall_deadline_seconds",sum(budgets.values())),(int,float)) or i.get("overall_deadline_seconds",sum(budgets.values()))<=0: raise GateError("deadline budgets invalid")
    c=i.get("cost_preflight")
    if not isinstance(c,dict) or c.get("billing_approval_evidence") is not True or c.get("currency")!="USD" or c.get("monthly_budget")!=25 or c.get("alerts_percent")!=[50,80] or c.get("region")!="asia-southeast1": raise GateError("cost guardrail mismatch")
    for k in ("notification_destination","expiration","retention_policy","log_policy","recurring_inventory","teardown_owner","teardown_deadline"): req(c,k)
    if c.get("cloud_run")!={"cpu":1,"memory_gib":1,"concurrency":20,"timeout_seconds":300,"min_instances":0,"max_instances":3} or c.get("cloud_sql")!={"mysql_major":8,"storage_gib":10,"autogrow":False,"ha":False,"backups":True,"pitr":False} or c.get("networking")!="public-ip-cloud-sql-java-connector": raise GateError("resource guardrail mismatch")
    for k in ("migration_result","smoke_result","observation_result"):
        if i.get(k) not in ("pass","fail","ambiguous"): raise GateError("invalid gate result")
    p=i.get("observation_policy")
    if i.get("observation_result")=="pass" and (not isinstance(p,dict) or not isinstance(p.get("minimum_samples"),int) or p["minimum_samples"]<2 or not isinstance(p.get("maximum_gap_seconds"),(int,float)) or p["maximum_gap_seconds"]<=0 or not isinstance(p.get("freshness_seconds"),(int,float)) or p["freshness_seconds"]<=0 or not isinstance(p.get("thresholds"),dict)): raise GateError("observation policy invalid")

class _ValidationArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise GateError("CLI_USAGE")

def _validation_reason(error: Exception)->str:
    text=str(error).lower()
    if "duplicate" in text: return "DUPLICATE_FIELD"
    if "stable url" in text or "run.app" in text or "url" in text: return "INVALID_SERVICE_URL"
    if "timestamp" in text: return "INVALID_TIMESTAMP"
    if "range" in text or "deadline" in text or "budget" in text: return "INVALID_RANGE"
    if "type" in text or "shape" in text or "root" in text: return "INVALID_TYPE"
    if "missing" in text or "empty" in text or "required" in text: return "MISSING_FIELD"
    return "INVALID_TYPE"

def _emit_validation_failure(reason: str)->None:
    emit("validation-failed",code="INVALID_DEPLOYMENT_INPUT",message="Deployment input validation failed.",reason=reason)

def main(argv: list[str] | None = None)->int:
    ap=_ValidationArgumentParser(description="offline validation, planning, and dry-run only")
    ap.add_argument("--input",required=True)
    mode=ap.add_mutually_exclusive_group()
    mode.add_argument("--validate",action="store_true")
    mode.add_argument("--plan",action="store_true")
    mode.add_argument("--dry-run",action="store_true")
    try: args=ap.parse_args(argv)
    except GateError as error:
        _emit_validation_failure("INVALID_TYPE"); return 2
    try:
        i=_load_input(args.input); validate(i,False)
    except Exception as error:
        _emit_validation_failure(_validation_reason(error)); return 2
    try:
        selected="validate" if args.validate else "plan" if args.plan else "dry_run"
        if args.validate: _run_validated_offline_workflow(i)
        emit(selected,mutation="not_performed")
        return 0
    except DeploymentInputError:
        _emit_validation_failure("INVALID_SERVICE_URL"); return 2
    except Exception:
        emit("blocked",reason="runtime-validation-failure")
        return 21

if __name__=="__main__": sys.exit(main())
