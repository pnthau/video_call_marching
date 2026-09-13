#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf -- "$TMP"' EXIT HUP INT TERM
if [[ -n "${PYTHON:-}" ]]; then PY=( ${PYTHON} )
elif command -v py >/dev/null 2>&1 && py -3 -c 'import sys; raise SystemExit(sys.version_info < (3,10))' >/dev/null 2>&1; then PY=(py -3)
elif command -v python3 >/dev/null 2>&1; then PY=(python3)
elif command -v python >/dev/null 2>&1; then PY=(python)
else echo 'no supported Python interpreter' >&2; exit 2; fi
export PYTHONDONTWRITEBYTECODE=1
"${PY[@]}" -c 'import sys; assert sys.version_info >= (3,10)'
bash -n "$0"
"${PY[@]}" - "$ROOT" "$TMP" <<'PY'
import ast, copy, importlib.util, json, os, pathlib, shutil, socket, subprocess, sys, tempfile, urllib.request
from datetime import datetime, timezone
root, temp = map(pathlib.Path, sys.argv[1:])
source = root / "slice3_orchestrator.py"
spec = importlib.util.spec_from_file_location("slice3_internal", source)
orch = importlib.util.module_from_spec(spec); spec.loader.exec_module(orch)
compile(source.read_text(encoding="utf-8"), str(source), "exec")
sentinels=temp/"sentinels"; sentinels.mkdir()
control_audit=temp/"control-audit.jsonl"; actual_audit=temp/"actual-audit.jsonl"
for name in ("gcloud","gcloud.cmd","gcloud.exe","curl","curl.exe","wget","wget.exe","docker","docker.exe","sh","bash","cmd","powershell","pwsh"):
    (sentinels/name).write_text("sentinel fixture; never invoked by actual suite\n",encoding="utf-8")
control=sentinels/"gcloud"
control.write_text('''import json,pathlib,sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({"sentinel":"control","invoked":True})+"\\n",encoding="utf-8")
sys.exit(97)
''',encoding="utf-8")
original_run=subprocess.run
original_popen=subprocess.Popen
def blocked(*args,**kwargs):
    command=args[0] if args else kwargs.get("args")
    first=command[0] if isinstance(command,(list,tuple)) and command else command
    if pathlib.Path(str(first)).resolve()!=pathlib.Path(sys.executable).resolve():
        raise AssertionError("non-Python external execution attempted")
    return original_run(*args,**kwargs)
subprocess.run=blocked
def guarded_popen(*args,**kwargs):
    command=args[0] if args else kwargs.get("args")
    first=command[0] if isinstance(command,(list,tuple)) and command else command
    if pathlib.Path(str(first)).resolve()!=pathlib.Path(sys.executable).resolve():
        raise AssertionError("non-Python external process attempted")
    return original_popen(*args,**kwargs)
subprocess.Popen=guarded_popen
def blocked_process(*args,**kwargs): raise AssertionError("external process API attempted")
for api in ("call","check_call","check_output"):
    setattr(subprocess,api,blocked_process)
os.system=blocked_process; os.popen=blocked_process
socket.socket=blocked_process
urllib.request.urlopen=blocked_process
def evaluate_audit(records):
    if not isinstance(records,list): return {"accepted":False,"classification":"malformed_audit"}
    for record in records:
        if not isinstance(record,dict) or set(record)!={"sentinel","invoked"} or record.get("sentinel")!="control" or record.get("invoked") is not True:
            return {"accepted":False,"classification":"malformed_audit"}
    if records: return {"accepted":False,"classification":"external_invocation_violation"}
    return {"accepted":True,"classification":"clean"}
control_run=original_run([sys.executable,str(control),str(control_audit)],capture_output=True,text=True,timeout=5,
                         env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1","PATH":str(sentinels)})
control_records=[json.loads(x) for x in control_audit.read_text(encoding="utf-8").splitlines()]
assert control_run.returncode==97 and len(control_records)==1 and evaluate_audit(control_records)=={"accepted":False,"classification":"external_invocation_violation"}
actual_audit.write_text("",encoding="utf-8")
def ast_scan_text(text):
    tree=ast.parse(text,"fixture")
    banned_imports={"subprocess","ctypes","asyncio","importlib"}; banned_calls={"eval","exec","__import__","system","popen","urlopen","create_subprocess_exec","create_subprocess_shell","import_module"}
    for node in ast.walk(tree):
        if isinstance(node,ast.Import) and any(a.name.split(".")[0] in banned_imports for a in node.names): return False
        if isinstance(node,ast.ImportFrom) and (node.module or "").split(".")[0] in banned_imports: return False
        if isinstance(node,ast.Call) and isinstance(node.func,ast.Name) and node.func.id in banned_calls: return False
        if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and node.func.attr in banned_calls: return False
        if isinstance(node,ast.Constant) and isinstance(node.value,str) and any(x in node.value.lower() for x in ("gcloud","docker","curl","wget")) and ("/" in node.value or "\\" in node.value): return False
    return True
assert ast_scan_text(source.read_text(encoding="utf-8"))
ast_fixtures=["import asyncio as aio; aio.create_subprocess_exec('x')","from asyncio import create_subprocess_shell as spawn; spawn('x')","import importlib as il; il.import_module('x')","from importlib import import_module as load; load('x')","__import__('x')","import subprocess as sp; sp.run(['x'])","malformed ("]
ast_results=[]
for fixture in ast_fixtures:
    try: accepted=ast_scan_text(fixture)
    except (SyntaxError,ValueError): accepted=False
    ast_results.append(not accepted)
assert len(ast_results)==len(ast_fixtures)==7 and all(ast_results)
print(f"ast_adversarial: total={len(ast_fixtures)} passed={sum(ast_results)} failed={len(ast_results)-sum(ast_results)}")
digest = "a" * 64
base = {
 "target_platform":"GOOGLE_CLOUD_RUN","environment":"staging","region":"asia-southeast1","project":"video-call-staging","service_name":"video-call-staging",
 "candidate_revision":"video-call-staging-00012-abc","previous_revision":"video-call-staging-00011-def",
 "candidate_image":"registry.invalid/video@sha256:"+digest,"previous_image":"registry.invalid/video@sha256:"+"b"*64,"runtime_image":"registry.invalid/video@sha256:"+digest,
 "service_manifest":{"spec":{"template":{"containers":[{"image":"registry.invalid/video@sha256:"+digest}]}}},
 "migration_manifest":{"spec":{"template":{"containers":[{"image":"registry.invalid/video@sha256:"+digest}]}}},
 "candidate_revision_evidence":{"project":"video-call-staging","region":"asia-southeast1","service":"video-call-staging","revision":"video-call-staging-00012-abc"},
 "previous_revision_evidence":{"project":"video-call-staging","region":"asia-southeast1","service":"video-call-staging","revision":"video-call-staging-00011-def"},
 "oauth_callback":"https://video-call-staging-abc123.run.app/login/oauth2/code/google",
 "identities":{"runtime":"runtime@project.invalid","migrator":"migrator@project.invalid","deployer":"deployer@project.invalid"},
 "secret_versions":{"runtime":["1"],"migrator":["1"]},"migration_before_serving":True,"no_migration_downgrade":True,
 "migration_result":"pass","smoke_result":"pass","observation_result":"pass",
 "observation_policy":{"minimum_samples":2,"maximum_gap_seconds":100,"freshness_seconds":60,"thresholds":{"max_error_rate":.05,"max_latency_ms":500}},
 "budgets":{"migration_seconds":900,"startup_seconds":120,"probe_seconds":5,"smoke_seconds":600,"observation_seconds":1800,"rollback_seconds":600},
 "cost_preflight":{"billing_approval_evidence":True,"currency":"USD","monthly_budget":25,"alerts_percent":[50,80],"notification_destination":"owner@example.invalid","expiration":"2099-12-31","region":"asia-southeast1","cloud_run":{"cpu":1,"memory_gib":1,"concurrency":20,"timeout_seconds":300,"min_instances":0,"max_instances":3},"cloud_sql":{"mysql_major":8,"storage_gib":10,"autogrow":False,"ha":False,"backups":True,"pitr":False},"networking":"public-ip-cloud-sql-java-connector","retention_policy":"approved","log_policy":"approved","recurring_inventory":"owner-reviewed","teardown_owner":"release-owner","teardown_deadline":"2099-12-31"}}
def write_json(name, obj):
    p=temp/name; p.write_text(json.dumps(obj),encoding="utf-8"); return p
def assert_events(events, runner, capability, expected_operations):
    required={"event_type","event_name","operation","runner_id","status"}
    allowed_types={"simulated-operation"}; allowed_names={"offline-operation-recorded"}; allowed_status={"pass"}
    if not isinstance(events,list) or len(events)!=len(expected_operations): raise orch.GateError("event collection is invalid")
    seen=[]
    for event in events:
        if not isinstance(event,dict) or set(event)!=required: raise orch.GateError("event schema is invalid")
        if (not isinstance(event["event_type"],str) or not isinstance(event["event_name"],str)
                or not isinstance(event["operation"],str) or not isinstance(event["runner_id"],int)
                or isinstance(event["runner_id"],bool) or not isinstance(event["status"],str)):
            raise orch.GateError("event types are invalid")
        if event["event_type"] not in allowed_types or event["event_name"] not in allowed_names or event["status"] not in allowed_status:
            raise orch.GateError("event value is invalid")
        if event["runner_id"] != id(runner) or event["operation"] in seen: raise orch.GateError("event correlation is invalid")
        if any(word in event for word in ("capability","token","secret","command")): raise orch.GateError("event contains sensitive data")
        if any(value is capability for value in event.values()): raise orch.GateError("event contains capability")
        seen.append(event["operation"])
    if seen != expected_operations: raise orch.GateError("event order is invalid")
def public(args, expected, obj=None):
    inp=write_json("case.json", base if obj is None else obj) if obj is not None else None
    cmd=[sys.executable,str(source),*args]
    if inp: cmd[2:2]=["--input",str(inp)]
    r=subprocess.run(cmd,capture_output=True,text=True,env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1","PATH":str(sentinels)})
    ok=(r.returncode==expected and (expected==2 or not r.stderr) and "Traceback" not in r.stdout and "capability" not in r.stdout.lower())
    return r.returncode, ok
def public_plan_schema():
    inp=write_json("plan-case.json",base)
    r=subprocess.run([sys.executable,str(source),"--input",str(inp),"--plan"],capture_output=True,text=True,
                     env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1","PATH":str(sentinels)})
    if r.returncode!=0 or r.stderr: return r.returncode,False
    try: output=json.loads(r.stdout)
    except Exception: return r.returncode,False
    forbidden=("SLICE3-OFFLINE-OBJECT","protocol","capability","runner_id","_FakeRunner","token","secret","command")
    return r.returncode, set(output)=={"event","mutation"} and output.get("event")=="plan" and output.get("mutation")=="not_performed" and not any(x in r.stdout for x in forbidden)
def dispatch(runner,cap,operation,payload=None,clock=lambda:0.0,duration=100.0,protocol=None):
    state=orch._create_operation(operation,duration,clock)
    if protocol is None: protocol=orch._get_protocol_spec()
    return orch._dispatch_operation(runner,cap,state,{} if payload is None else payload,clock,protocol)
def internal(kind):
    responses={"migration":{"status":"pass"},"smoke":{"status":"pass"}}
    runner, cap=orch._create_trusted_fake_runner(responses)
    if kind=="missing": cap=None
    if kind=="string": cap="capability"
    if kind=="serialized":
        try: json.dumps(cap)
        except TypeError: return 21
    if kind=="wrong-runner":
        runner,_=orch._create_trusted_fake_runner(responses)
    if kind=="arbitrary-runner":
        class Duck:
            def __init__(self): self._responses=responses; self.calls=[]; self.events=[]
        runner=Duck()
    if kind=="subclass-runner":
        class Proxy(orch._FakeRunner): pass
        runner=Proxy(responses)
    if kind=="stale":
        _,cap=orch._create_trusted_fake_runner(responses); runner,_=orch._create_trusted_fake_runner(responses)
    try:
        if kind=="marker":
            dispatch(runner,cap,"migration",clock=lambda:0.0,protocol=dict(orch._get_protocol_spec()))
        else:
            dispatch(runner,cap,"migration")
            dispatch(runner,cap,"smoke")
            if kind=="ok":
                assert_events(runner.events,runner,cap,["migration","smoke"])
            if kind=="replay": dispatch(runner,cap,"migration")
        return 0
    except orch.GateError:
        return 21
cases=[]
def add(name, expected, fn): cases.append((name,expected,fn))
add("help",0,lambda: public(["--help"],0,None))
add("dry-run",0,lambda: public(["--dry-run"],0,base))
add("validate",0,lambda: public(["--validate"],0,base))
add("plan-public-schema-no-internal-protocol",0,public_plan_schema)
add("unknown-authorization-token",2,lambda: public(["--authorization-token","x"],2,base))
add("live-mode-rejected",2,lambda: public(["--offline-test"],2,base))
add("runner-path-rejected",2,lambda: public(["--runner-path","runner"],2,base))
add("matching-runner-two-operations",0,lambda: (internal("ok"),True))
add("missing-capability",21,lambda: (internal("missing"),True))
add("arbitrary-string-capability",21,lambda: (internal("string"),True))
add("serialized-capability",21,lambda: (internal("serialized"),True))
add("wrong-runner",21,lambda: (internal("wrong-runner"),True))
add("arbitrary-runner-object",21,lambda: (internal("arbitrary-runner"),True))
add("subclass-runner-object",21,lambda: (internal("subclass-runner"),True))
add("stale-capability",21,lambda: (internal("stale"),True))
add("replayed-capability",21,lambda: (internal("replay"),True))
add("wrong-marker",21,lambda: (internal("marker"),True))
bad=copy.deepcopy(base); bad["candidate_image"]="repo:latest"
add("bad-digest",2,lambda: public(["--validate"],2,bad))
mal=temp/"malformed.json"; mal.write_text("{",encoding="utf-8")
add("malformed-input",2,lambda: (subprocess.run([sys.executable,str(source),"--input",str(mal)],capture_output=True,text=True,env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1"}).returncode,True))
add("no-external-execution-ast-and-guards",0,lambda: (0,ast_scan_text(source.read_text(encoding="utf-8")) and evaluate_audit([])["accepted"]))
def event_fixture(kind, assertion=assert_events):
    runner,cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"smoke":{"status":"pass"}})
    dispatch(runner,cap,"migration")
    dispatch(runner,cap,"smoke")
    events=copy.deepcopy(runner.events)
    if kind=="missing": events.pop()
    elif kind=="wrong-type": events[0]["event_type"]="unexpected"
    elif kind=="wrong-order": events.reverse()
    elif kind=="duplicate": events.append(copy.deepcopy(events[0]))
    elif kind=="capability": events[0]["capability"]=cap
    elif kind=="extra": events.append({"event_type":"simulated-operation","event_name":"offline-operation-recorded","operation":"extra","runner_id":id(runner),"status":"pass"})
    elif kind=="missing-field": del events[0]["status"]
    elif kind=="wrong-runner": events[0]["runner_id"]=id(runner)+1
    elif kind=="cross-invocation":
        other,other_cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"smoke":{"status":"pass"}})
        dispatch(other,other_cap,"migration")
        events=copy.deepcopy(other.events); events.append(copy.deepcopy(runner.events[1]))
        try: assertion(events,other,other_cap,["migration","smoke"])
        except orch.GateError: pass
        else: raise AssertionError("cross invocation setup unexpectedly accepted")
        return 21, True
    try:
        assertion(events,runner,cap,["migration","smoke"])
    except (orch.GateError,AssertionError): return 21, True
    return 0, False
for fixture in ("missing","wrong-type","wrong-order","duplicate","capability","cross-invocation","extra","missing-field","wrong-runner"):
    add("event-"+fixture,21,lambda fixture=fixture: event_fixture(fixture))
def new_invocation_isolation():
    first,first_cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"}})
    dispatch(first,first_cap,"migration")
    second,second_cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"smoke":{"status":"pass"}})
    if second.events: return 21, False
    dispatch(second,second_cap,"migration")
    dispatch(second,second_cap,"smoke")
    assert_events(second.events,second,second_cap,["migration","smoke"])
    assert all(e["runner_id"] != id(first) for e in second.events)
    return 0, True
add("event-new-invocation-isolation",0,new_invocation_isolation)
def rollback_case(kind):
    project="video-call-staging"; region="asia-southeast1"; service="video-call-staging"
    new="video-call-staging-00012-abc"; stable="video-call-staging-00011-def"
    context=orch._create_rollback_context(project,region,service,new,stable)
    def echo(request):
        fields=("project","region","service","candidate_revision","target_revision","invocation_id","rollback_attempt_id")
        return {"status":"pass", **{k:request[k] for k in fields}, "traffic_revision":request["target_revision"]}
    def altered(field, value):
        def response(request):
            result=echo(request); result[field]=value; return result
        return response
    runner,cap=orch._create_trusted_fake_runner({"rollback":echo})
    if kind=="runner":
        class Duck: pass
        runner=Duck()
    if kind=="migration": context=orch._create_rollback_context(project,region,service,new,stable,"fail","fail"); request=context.request
    if kind=="smoke-success": context=orch._create_rollback_context(project,region,service,new,stable,"pass","pass"); request=context.request
    if kind=="malformed": runner._responses["rollback"]=lambda request: {}
    if kind=="rollback-fail": runner._responses["rollback"]=altered("status","fail")
    if kind=="wrong-service": runner._responses["rollback"]=altered("service","other-service")
    if kind=="wrong-target": runner._responses["rollback"]=altered("target_revision",new)
    if kind=="same-target": context=orch._create_rollback_context(project,region,service,new,new); request=context.request
    if kind=="missing-stable": context.request.pop("target_revision")
    if kind=="missing-new": context.request.pop("candidate_revision")
    if kind=="cross-invocation": runner._responses["rollback"]=altered("invocation_id","a"*32)
    if kind=="stale": runner._responses["rollback"]=altered("rollback_attempt_id","b"*32)
    if kind=="missing-invocation": runner._responses["rollback"]=lambda request: {"status":"pass", **{k:v for k,v in echo(request).items() if k!="invocation_id"}}
    if kind=="missing-attempt": runner._responses["rollback"]=lambda request: {"status":"pass", **{k:v for k,v in echo(request).items() if k!="rollback_attempt_id"}}
    if kind=="missing-candidate": runner._responses["rollback"]=lambda request: {"status":"pass", **{k:v for k,v in echo(request).items() if k!="candidate_revision"}}
    if kind=="wrong-attempt": runner._responses["rollback"]=altered("rollback_attempt_id","c"*32)
    if kind=="wrong-candidate": runner._responses["rollback"]=altered("candidate_revision",stable)
    if kind=="extra-field": runner._responses["rollback"]=lambda request: {**echo(request), "unexpected":"x"}
    if kind=="wrong-type": runner._responses["rollback"]=altered("invocation_id",123)
    if kind=="arbitrary": cap=object()
    if kind=="replayed":
        first_result,_=orch._rollback_evidence(runner,cap,context)
        assert first_result==21 and context.consumed and runner.calls==["rollback"]
        runner,cap=orch._create_trusted_fake_runner({"rollback":echo})
    if kind=="stale-result": runner._responses["rollback"]=lambda request: {**echo(request), "invocation_id":"d"*32, "rollback_attempt_id":"e"*32}
    try:
        if kind in ("duplicate-request","duplicate-terminal","extra-evidence"):
            result,_=orch._rollback_evidence(runner,cap,context)
            assert result==21 and len(runner.rollback_evidence)==2
            if kind=="duplicate-request": runner.rollback_evidence.insert(1,dict(runner.rollback_evidence[0]))
            if kind=="duplicate-terminal": runner.rollback_evidence.append(dict(runner.rollback_evidence[-1]))
            if kind=="extra-evidence": runner.rollback_evidence.append(orch._rollback_record(context,"rollback-extra","failed","unexpected"))
            orch._validate_rollback_evidence(runner.rollback_evidence,context,"success")
            return 0,False
        result,evidence=orch._rollback_evidence(runner,cap,context)
        if kind=="success":
            assert result==21 and runner.calls==["rollback"]
            assert [e["event_name"] for e in runner.rollback_evidence]==["rollback-requested","rollback-succeeded"]
            assert evidence["status"]=="pass"
            return 21,True
        if kind in ("migration","smoke-success"):
            assert runner.calls==[] and evidence["event_name"]=="rollback-not-attempted"
            return (21 if kind=="migration" else 0),True
        if kind=="rollback-fail":
            assert result==21 and runner.calls==["rollback"] and evidence["event_name"]=="rollback-failed"
            return 21,True
        if kind in ("malformed","wrong-service","wrong-target","rollback-fail","cross-invocation","stale","missing-invocation","missing-attempt","missing-candidate","wrong-attempt","wrong-candidate","extra-field","wrong-type","stale-result"):
            assert result==21 and runner.calls==["rollback"] and evidence["event_name"]=="rollback-failed"
            return 21,True
        return 0,False
    except orch.GateError:
        invalid={"malformed","wrong-service","wrong-target","same-target","missing-stable","missing-new","cross-invocation","stale","arbitrary","runner","missing-invocation","missing-attempt","missing-candidate","wrong-attempt","wrong-candidate","extra-field","wrong-type","replayed","stale-result","duplicate-request","duplicate-terminal","extra-evidence"}
        if kind in invalid:
            if kind not in ("runner","arbitrary") and hasattr(runner,"calls"): assert runner.calls==[] if kind in ("same-target","missing-stable","missing-new","replayed") else runner.calls==["rollback"]
            return 21,True
        return 0,False
for kind in ("success","migration","smoke-success","malformed","rollback-fail","wrong-service","wrong-target","same-target","missing-stable","missing-new","cross-invocation","stale","missing-invocation","missing-attempt","missing-candidate","wrong-attempt","wrong-candidate","extra-field","wrong-type","stale-result","replayed","duplicate-request","duplicate-terminal","extra-evidence","arbitrary","runner"):
    add("rollback-"+kind,21 if kind not in ("smoke-success",) else 0,lambda kind=kind: rollback_case(kind))
class Clock:
    def __init__(self): self.value=0.0
    def __call__(self): return self.value
    def advance(self, seconds): self.value += seconds
def operation_case(kind):
    clock=Clock(); runner,cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"smoke":{"status":"pass"},"rollback":{"status":"pass"}})
    operation="migration" if kind.startswith("migration") else "smoke" if kind.startswith("smoke") else "rollback"
    state=orch._create_operation(operation,1.0,clock)
    if kind in ("migration-complete","smoke-complete","rollback-complete"):
        clock.advance(.5); result,_=orch._dispatch_operation(runner,cap,state,{},clock)
        orch._validate_operation_evidence(state,state.attempt_id)
        assert result==0 and state.terminal=="succeeded"
        return 0,True
    if kind in ("migration-timeout","smoke-timeout","rollback-timeout","cleanup-failure","cleanup-unsupported","cleanup-unknown","forced-failure","duplicate-timeout"):
        if kind in ("cleanup-failure","forced-failure","cleanup-unsupported"): runner.cleanup_outcomes[operation]="failed"
        if kind=="cleanup-unknown": runner.cleanup_outcomes[operation]="stuck"
        if kind=="forced-failure": runner.forced_cleanup_outcomes[operation]="failed"
        if kind=="cleanup-unsupported": runner.forced_cleanup_supported=False
        clock.advance(1.0); result,_=orch._dispatch_operation(runner,cap,state,{},clock)
        assert result==21 and state.terminal=="timed_out" and state.cancel_requested and not any(e["event_name"]=="operation-succeeded" for e in state.evidence)
        if kind in ("cleanup-failure","cleanup-unknown"): assert state.forced_cleanup and [e["event_name"] for e in state.evidence].count("forced-cleanup")==1 and state.cleanup_completed
        if kind=="forced-failure": assert state.forced_cleanup and not state.cleanup_completed and [e["event_name"] for e in state.evidence].count("cleanup-failed")==2
        if kind=="cleanup-unsupported": assert not state.forced_cleanup and not state.cleanup_completed
        if kind=="duplicate-timeout": orch._timeout_operation(state,runner,cap,clock); assert [e["event_name"] for e in state.evidence].count("forced-cleanup")<=1
        orch._validate_operation_evidence(state,state.attempt_id)
        return 21,True
    if kind in ("boundary","late-success","late-failure","duplicate-cleanup","race"):
        if kind=="boundary": clock.advance(1.0); result,_=orch._dispatch_operation(runner,cap,state,{},clock); assert result==21
        else:
            clock.advance(1.0); assert orch._dispatch_operation(runner,cap,state,{},clock)[0]==21
            if kind=="late-success": assert orch._complete_operation(state,runner,cap,{"status":"pass"},clock)==21
            if kind=="late-failure": assert orch._complete_operation(state,runner,cap,{"status":"fail"},clock)==21
            if kind=="duplicate-cleanup":
                before=len(state.evidence); orch._cleanup_operation(state,runner,cap); orch._cancel_operation(state,runner,cap); assert len(state.evidence)==before
            if kind=="race": assert len([e for e in state.evidence if e["event_name"]=="operation-timed-out"])==1
        assert state.terminal=="timed_out" and len([e for e in state.evidence if e["event_name"] in ("operation-succeeded","operation-failed","late-result-rejected")])<=1
        orch._validate_operation_evidence(state,state.attempt_id)
        return 21,True
    if kind=="runner-advances-clock":
        def late_response(request): clock.advance(1.0); return {"status":"pass"}
        runner._responses[operation]=late_response
        result,_=orch._dispatch_operation(runner,cap,state,{},clock)
        assert result==21 and state.terminal=="timed_out" and runner.calls==[operation]
        return 21,True
    if kind=="direct-bypass":
        try: orch._run_simulated_operation(runner,cap,operation,{},orch._get_protocol_spec())
        except orch.GateError: return 21,True
        return 0,False
    if kind=="terminal-redispatch":
        clock.advance(.5); assert orch._dispatch_operation(runner,cap,state,{},clock)[0]==0
        try: orch._dispatch_operation(runner,cap,state,{},clock)
        except orch.GateError: return 21,True
        return 0,False
    if kind=="old-cleanup-new-attempt":
        clock.advance(1.0); orch._timeout_operation(state,runner,cap,clock)
        newer=orch._create_operation("smoke",1.0,clock)
        try: orch._validate_cleanup_ack(newer,{"status":"completed","invocation_id":state.invocation_id,"operation":"smoke","attempt_id":newer.attempt_id})
        except orch.GateError: pass
        else: return 0,False
        return 21,True
    if kind in ("wrong-ack-invocation","wrong-ack-operation","wrong-ack-attempt","stale-ack","replayed-ack"):
        ack={"status":"completed","invocation_id":state.invocation_id,"operation":operation,"attempt_id":state.attempt_id}
        if kind=="wrong-ack-invocation": ack["invocation_id"]="other"
        if kind=="wrong-ack-operation": ack["operation"]="other"
        if kind in ("wrong-ack-attempt","stale-ack","replayed-ack"): ack["attempt_id"]="old"
        try: orch._validate_cleanup_ack(state,ack)
        except orch.GateError: return 21,True
        return 0,False
    return 0,False
for kind in ("migration-complete","migration-timeout","smoke-complete","smoke-timeout","rollback-complete","rollback-timeout","boundary","late-success","late-failure","runner-advances-clock","terminal-redispatch","direct-bypass","old-cleanup-new-attempt","duplicate-cleanup","race","cleanup-failure","cleanup-unsupported","cleanup-unknown","forced-failure","duplicate-timeout","wrong-ack-invocation","wrong-ack-operation","wrong-ack-attempt","stale-ack","replayed-ack"):
    add("deadline-"+kind,0 if kind.endswith("complete") else 21,lambda kind=kind: operation_case(kind))

def evidence_case(kind):
    state=orch._create_operation("migration",1.0,lambda:0.0)
    def record(name,status): return orch._operation_record(state,name,status)
    sequences={
        "initial-completed":[("cleanup-requested","cancel_requested"),("cleanup-completed","completed")],
        "initial-failed":[("cleanup-requested","cancel_requested"),("cleanup-failed","failed")],
        "forced-completed":[("cleanup-requested","cancel_requested"),("cleanup-failed","failed"),("forced-cleanup","requested"),("cleanup-completed","completed")],
        "forced-failed":[("cleanup-requested","cancel_requested"),("cleanup-failed","failed"),("forced-cleanup","requested"),("cleanup-failed","failed")],
        "duplicate-failed":[("cleanup-requested","cancel_requested"),("cleanup-failed","failed"),("cleanup-failed","failed")],
        "duplicate-completed":[("cleanup-requested","cancel_requested"),("cleanup-completed","completed"),("cleanup-completed","completed")],
        "mixed-outcome":[("cleanup-requested","cancel_requested"),("cleanup-completed","completed"),("cleanup-failed","failed")],
        "forced-without-failure":[("cleanup-requested","cancel_requested"),("forced-cleanup","requested"),("cleanup-completed","completed")],
        "duplicate-forced":[("cleanup-requested","cancel_requested"),("cleanup-failed","failed"),("forced-cleanup","requested"),("forced-cleanup","requested"),("cleanup-completed","completed")],
        "forced-two-outcomes":[("cleanup-requested","cancel_requested"),("cleanup-failed","failed"),("forced-cleanup","requested"),("cleanup-completed","completed"),("cleanup-failed","failed")],
        "outcome-before-forced":[("cleanup-requested","cancel_requested"),("cleanup-failed","failed"),("cleanup-completed","completed"),("forced-cleanup","requested")],
    }
    state.evidence=[record("operation-started","started"),record("operation-timed-out","timed_out")]+[record(n,s) for n,s in sequences[kind]]
    try:
        orch._validate_operation_evidence(state,state.attempt_id)
        return 0, kind in {"initial-completed","initial-failed","forced-completed","forced-failed"}
    except orch.GateError:
        return 0, kind not in {"initial-completed","initial-failed","forced-completed","forced-failed"}

for kind in ("initial-completed","initial-failed","forced-completed","forced-failed","duplicate-failed","duplicate-completed","mixed-outcome","forced-without-failure","duplicate-forced","forced-two-outcomes","outcome-before-forced"):
    add("evidence-fsm-"+kind,0,lambda kind=kind: evidence_case(kind))

def observation_case(kind):
    clock=Clock()
    def sample_response(request):
        index=request["sample_index"]; requests=10; errors=0; latency=100
        if kind in ("zero-sample","zero-window"): requests=0; latency=None
        if kind=="threshold-equality": requests=100 if index==1 else 0; errors=1 if index==1 else 0; latency=1000 if requests else None
        if kind=="aggregate-error": requests=100 if index==1 else 0; errors=2 if index==1 else 0; latency=100 if requests else None
        if kind in ("bucket-error","single-bucket-breach") and index<=5: requests=100 if index==1 else 0; errors=3 if index==1 else 0; latency=100 if requests else None
        if kind=="two-consecutive-error" and index<=10: requests=100 if index in (1,6) else 0; errors=3 if index in (1,6) else 0; latency=100 if requests else None
        if kind=="three-error" and index<=15: requests=100 if index in (1,6,11) else 0; errors=3 if index in (1,6,11) else 0; latency=100 if requests else None
        if kind=="probe-failure" and index==1: return {"sample_index":index,"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":requests,"server_error_count":errors,"latency_p95_ms":latency,"probe_attempted":True,"probe_succeeded":False,"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
        result={"sample_index":index,"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":requests,"server_error_count":errors,"latency_p95_ms":latency,"probe_attempted":True,"probe_succeeded":True,"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
        if kind=="missing-field" and index==1: result.pop("observed_at")
        if kind=="extra-field" and index==1: result["extra"]="x"
        if kind=="wrong-type" and index==1: result["request_count"]=True
        if kind=="bad-timestamp" and index==1: result["observed_at"]=request["observed_at"].replace("Z","+00:00")
        if kind=="wrong-revision" and index==1: result["observed_revision"]="other-revision"
        return result
    def aggregate_response(request):
        p95=1000
        if kind=="aggregate-latency": p95=1001
        if kind in ("zero-sample","zero-window"): p95=None
        active={"zero-sample":set(),"zero-window":set(),"threshold-equality":{1},"aggregate-error":{1},"bucket-error":{1},"single-bucket-breach":{1},"two-consecutive-error":{1,2},"three-error":{1,2,3}}.get(kind,set(range(1,7)))
        buckets=[1500 if n in active else None for n in range(1,7)]
        if kind=="bucket-latency": buckets=[1501]+[1500]*5
        return {"project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"invocation_id":request["invocation_id"],"attempt_id":request["attempt_id"],"aggregate_p95_ms":p95,"bucket_p95_ms":buckets}
    runner,cap=orch._create_trusted_fake_runner({"observation":sample_response,"observation-aggregate":aggregate_response})
    if kind=="untrusted": runner=object()
    prerequisite = "fail" if kind=="prerequisite-failure" else "pass"
    try: state=orch._create_observation("video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc",prerequisite,prerequisite,prerequisite,clock,datetime.now(timezone.utc))
    except Exception: return 21, kind=="prerequisite-failure"
    if kind=="hard-timeout": clock.value=1980; return (21, _expect_gate(lambda: orch._dispatch_observation_sample(runner,cap,state,1,clock)))
    if kind=="no-sample-t0": return (21, _expect_gate(lambda: orch._dispatch_observation_sample(runner,cap,state,1,clock)))
    if kind=="payload-override":
        op=orch._create_operation("observation",100,clock)
        return (21, _expect_gate(lambda: orch._dispatch_operation(runner,cap,op,{"deadline":-1},clock)))
    if kind=="untrusted": return (21, _expect_gate(lambda: orch._dispatch_observation_sample(runner,cap,state,1,clock)))
    limit=29 if kind=="missing-coverage" else 30
    for index in range(1,limit+1):
        clock.value=index*60
        if kind=="duplicate-sample" and index==2: index=1
        if kind=="out-of-order" and index==1: index=2
        try: status,_=orch._dispatch_observation_sample(runner,cap,state,index,clock)
        except Exception: return 21, kind in {"missing-field","extra-field","wrong-type","bad-timestamp","wrong-revision","probe-failure","duplicate-sample","out-of-order"}
        if status!=0: return 21, kind in {"two-consecutive-error","three-error"}
    if kind=="missing-coverage": return (21,_expect_gate(lambda: orch._finish_observation(state,clock)))
    if kind=="after-terminal":
        orch._dispatch_observation_aggregate(runner,cap,state,clock); orch._finish_observation(state,clock)
        return (21,_expect_gate(lambda: orch._dispatch_observation_sample(runner,cap,state,1,clock)))
    try: orch._dispatch_observation_aggregate(runner,cap,state,clock); result=orch._finish_observation(state,clock); orch._validate_observation_evidence(state)
    except Exception: return 21, kind in {"aggregate-error","aggregate-latency","bucket-error","bucket-latency","single-bucket-breach","two-consecutive-error","three-error"}
    return result, True

def _expect_gate(fn):
    try: fn()
    except orch.GateError: return True
    return False
for kind in ("valid","threshold-equality","zero-sample","zero-window","single-bucket-breach","two-consecutive-error","three-error","aggregate-error","aggregate-latency","bucket-error","bucket-latency","prerequisite-failure","hard-timeout","no-sample-t0","missing-coverage","after-terminal","payload-override","untrusted","missing-field","extra-field","wrong-type","bad-timestamp","wrong-revision","probe-failure","duplicate-sample","out-of-order"):
    expected=0 if kind in ("valid","threshold-equality","zero-window","zero-sample") else 21
    add("observation-"+kind,expected,lambda kind=kind: observation_case(kind))
def workflow_case(kind):
    clock=Clock()
    def sample(request):
        index=request["sample_index"]
        return {"sample_index":index,"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":100,"server_error_count":0,"latency_p95_ms":100,"probe_attempted":True,"probe_succeeded":kind not in ("observation-failure","observation-timeout"),"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
    def aggregate(request):
        return {"project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"invocation_id":request["invocation_id"],"attempt_id":request["attempt_id"],"aggregate_p95_ms":100,"bucket_p95_ms":[100]*6}
    def rollback(request):
        return {"status":"pass","project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"target_revision":request["target_revision"],"traffic_revision":request["target_revision"],"invocation_id":request["invocation_id"],"rollback_attempt_id":request["rollback_attempt_id"]}
    responses={"migration":{"status":"pass"},"deployment":{"status":"pass","service_url":"https://video-call-staging-abc123.run.app"},"smoke":{"status":"pass"},"observation":sample,"observation-aggregate":aggregate,"rollback":rollback}
    if kind=="migration-failure": responses["migration"]={"status":"fail"}
    if kind=="deployment-failure": responses["deployment"]={"status":"fail"}
    if kind=="smoke-failure": responses["smoke"]={"status":"fail"}
    if kind=="observation-timeout":
        def timeout_response(request): clock.value=1980; return sample(request)
        responses["observation"]=timeout_response
    runner,cap=orch._create_trusted_fake_runner(responses)
    try: result=orch._run_deployment_workflow(runner,cap,"video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","video-call-staging-00011-def",clock)
    except Exception: return 21,False
    expected_obs=kind not in ("migration-failure","deployment-failure","smoke-failure")
    expected_rb=kind in ("observation-failure","observation-timeout")
    return (0 if kind=="observation-success" else 21), result.get("observation_called")==expected_obs and result.get("rollback_attempts")==int(expected_rb) and result.get("terminal")==("success" if kind=="observation-success" else "failure")
for kind in ("observation-success","observation-failure","observation-timeout","migration-failure","deployment-failure","smoke-failure"):
    add("actual-workflow-"+kind,0 if kind=="observation-success" else 21,lambda kind=kind: workflow_case(kind))
def observation_boundary_case(kind):
    clock=Clock(); calls=[]
    def sample(request):
        calls.append(request["sample_index"])
        if kind in ("grace","grace-timestamp") and request["sample_index"]==30: clock.value=1850
        if kind=="hard-deadline" and request["sample_index"]==30: clock.value=1980
        result={"sample_index":request["sample_index"],"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":10,"server_error_count":0,"latency_p95_ms":100,"probe_attempted":True,"probe_succeeded":True,"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
        if kind.startswith("telemetry") or kind=="grace-timestamp":
            target_index=1 if kind=="telemetry-before" else 30
            if request["sample_index"] != target_index: return result
            telemetry_offset={"telemetry-before":-1,"telemetry-start":1740,"telemetry-pre-end":1799,"telemetry-end":1800,"grace-timestamp":1799}[kind]
            return {"sample":result,"telemetry_event_timestamp":orch._observation_timestamp(state,telemetry_offset)}
        return result
    def aggregate(request):
        return {"project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"invocation_id":request["invocation_id"],"attempt_id":request["attempt_id"],"aggregate_p95_ms":100,"bucket_p95_ms":[100]*6}
    runner,cap=orch._create_trusted_fake_runner({"observation":sample,"observation-aggregate":aggregate})
    state=orch._create_observation("video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","pass","pass","pass",clock,datetime(2026,1,1,tzinfo=timezone.utc))
    if kind=="t0": return 21,_expect_gate(lambda: orch._dispatch_observation_sample(runner,cap,state,1,clock))
    for index in range(1,31):
        clock.value=index*60
        try: status,_=orch._dispatch_observation_sample(runner,cap,state,index,clock)
        except orch.GateError: return 21, kind in ("telemetry-before","telemetry-end")
        if status!=0: break
    if kind in ("grace","grace-timestamp"):
        orch._dispatch_observation_aggregate(runner,cap,state,clock); result=orch._finish_observation(state,clock)
        return result, result==0 and calls==list(range(1,31))
    if kind=="hard-deadline": return 21, state.terminal=="timed_out" and calls==list(range(1,31))
    if kind=="sample31": return 21,_expect_gate(lambda: orch._dispatch_observation_sample(runner,cap,state,31,clock))
    if kind in ("telemetry-start","telemetry-pre-end"):
        orch._dispatch_observation_aggregate(runner,cap,state,clock); return orch._finish_observation(state,clock),True
    return 21,False
for kind in ("t0","grace","grace-timestamp","hard-deadline","sample31","telemetry-before","telemetry-start","telemetry-pre-end","telemetry-end"):
    add("observation-boundary-"+kind,0 if kind in ("grace","grace-timestamp","telemetry-start","telemetry-pre-end") else 21,lambda kind=kind: observation_boundary_case(kind))
def observation_terminal_case(kind):
    state=orch._create_observation("video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","pass","pass","pass",lambda:0,datetime(2026,1,1,tzinfo=timezone.utc))
    terminal={"succeeded":"observation-succeeded","failed":"observation-failed","timed_out":"operation-timed-out"}.get(kind)
    if kind in ("succeeded","failed","timed_out"):
        state.terminal=kind; state.consumed=True
        if kind=="succeeded":
            state.sample_indices=set(range(1,31))
            state.evidence.extend(orch._observation_event(state,"observation-sample-recorded","recorded",i) for i in range(1,31))
        state.evidence.append(orch._observation_event(state,terminal,{"succeeded":"succeeded","failed":"failed","timed_out":"timed_out"}[kind]))
        return 0,_expect_gate(lambda: orch._validate_observation_evidence(state)) is False
    state.terminal="failed"; state.consumed=True
    if kind=="missing": return 21,_expect_gate(lambda: orch._validate_observation_evidence(state))
    if kind in ("timedout-forged-success","succeeded-forged-failure","succeeded-forged-timeout","failed-forged-success","duplicate-terminal"):
        actual_state={"timedout-forged-success":"timed_out","succeeded-forged-failure":"succeeded","succeeded-forged-timeout":"succeeded","failed-forged-success":"failed","duplicate-terminal":"failed"}[kind]
        state.terminal=actual_state
        if actual_state=="succeeded":
            state.sample_indices=set(range(1,31))
            state.evidence=[state.evidence[0]]+[orch._observation_event(state,"observation-sample-recorded","recorded",i) for i in range(1,31)]
        forged={"timedout-forged-success":"observation-succeeded","succeeded-forged-failure":"observation-failed","succeeded-forged-timeout":"operation-timed-out","failed-forged-success":"observation-succeeded","duplicate-terminal":"observation-failed"}[kind]
        status={"observation-succeeded":"succeeded","observation-failed":"failed","operation-timed-out":"timed_out"}[forged]
        state.evidence.append(orch._observation_event(state,forged,status))
        if kind=="duplicate-terminal": state.evidence.append(orch._observation_event(state,forged,status))
        accepted=_expect_gate(lambda: orch._validate_observation_evidence(state))
        return 21,accepted and state.terminal==actual_state
    state.evidence.append(orch._observation_event(state,"observation-succeeded","succeeded"))
    return 21,_expect_gate(lambda: orch._validate_observation_evidence(state))
for kind,expected in (("succeeded",0),("failed",0),("timed_out",0),("missing",21),("forged",21),("timedout-forged-success",21),("succeeded-forged-failure",21),("succeeded-forged-timeout",21),("failed-forged-success",21),("duplicate-terminal",21)):
    add("observation-terminal-"+kind,expected,lambda kind=kind: observation_terminal_case(kind))
def workflow_latency_case(kind):
    clock=Clock(); sample_calls=[]; aggregate_calls=[]; breach={"single":{1},"consecutive":{1,2},"three-total":{1,3,5},"equality":set()}.get(kind,set())
    def sample(request):
        sample_calls.append(request["sample_index"])
        return {"sample_index":request["sample_index"],"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":100,"server_error_count":0,"latency_p95_ms":100,"probe_attempted":True,"probe_succeeded":True,"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
    def aggregate(request):
        if "bucket_number" in request: aggregate_calls.append(request["bucket_number"])
        values=[1501 if x in breach else 1500 for x in range(1,7)]
        return {"project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"invocation_id":request["invocation_id"],"attempt_id":request["attempt_id"],"aggregate_p95_ms":100,"bucket_p95_ms":values}
    def rollback(request): return {"status":"pass","project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"target_revision":request["target_revision"],"traffic_revision":request["target_revision"],"invocation_id":request["invocation_id"],"rollback_attempt_id":request["rollback_attempt_id"]}
    runner,cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"deployment":{"status":"pass","service_url":"https://video-call-staging-abc123.run.app"},"smoke":{"status":"pass"},"observation":sample,"observation-aggregate":aggregate,"rollback":rollback})
    result=orch._run_deployment_workflow(runner,cap,"video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","video-call-staging-00011-def",clock)
    expected_count=30 if kind in ("single","equality") else 10 if kind=="consecutive" else 25
    expected_buckets=list(range(1,7)) if expected_count==30 else list(range(1,(expected_count//5)+1))
    return 0 if kind=="equality" else 21, result["terminal"]==("success" if kind=="equality" else "failure") and result["rollback_attempts"]==(0 if kind=="equality" else 1) and sample_calls==list(range(1,expected_count+1)) and aggregate_calls==expected_buckets
add("actual-workflow-latency-equality",0,lambda: workflow_latency_case("equality"))
for kind in ("single","consecutive","three-total"):
    add("actual-workflow-latency-"+kind,21,lambda kind=kind: workflow_latency_case(kind))
def latency_breach_accounting_case():
    def run(breaches, stop_after):
        clock=Clock(); calls=[]
        def sample(request):
            calls.append(request["sample_index"])
            return {"sample_index":request["sample_index"],"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":100,"server_error_count":0,"latency_p95_ms":100,"probe_attempted":True,"probe_succeeded":True,"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
        def aggregate(request):
            return {"project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"invocation_id":request["invocation_id"],"attempt_id":request["attempt_id"],"aggregate_p95_ms":100,"bucket_p95_ms":[1501 if n in breaches else 1500 for n in range(1,7)]}
        runner,cap=orch._create_trusted_fake_runner({"observation":sample,"observation-aggregate":aggregate})
        state=orch._create_observation("video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","pass","pass","pass",clock,datetime(2026,1,1,tzinfo=timezone.utc))
        statuses=[]
        for index in range(1,stop_after+1):
            clock.value=index*60; statuses.append(orch._dispatch_observation_sample(runner,cap,state,index,clock)[0])
            if statuses[-1] != 0: break
        return state,calls,statuses,clock
    single,calls,statuses,clock=run({1},30)
    assert calls==list(range(1,31)) and single.bucket_breaches["latency"]==[1]
    # Final evaluation must not re-account an already-finalized breach.
    before=list(single.bucket_breaches["latency"])
    orch._finish_observation(single,clock)
    assert before==[1] and single.bucket_breaches["latency"]==[1] and single.terminal=="failed"
    consecutive,calls,statuses,_=run({1,2},30)
    assert calls==list(range(1,11)) and statuses[-1]==21 and consecutive.bucket_breaches["latency"]==[1,2]
    return 0,True
add("targeted-latency-breach-accounting",0,latency_breach_accounting_case)
def workflow_mapping_case():
    clock=Clock(); samples=[]; buckets={}
    def sample(request):
        samples.append(request["sample_index"])
        return {"sample_index":request["sample_index"],"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":10,"server_error_count":0,"latency_p95_ms":100,"probe_attempted":True,"probe_succeeded":True,"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
    def aggregate(request):
        if "bucket_number" in request: buckets[request["bucket_number"]]=list(samples[(request["bucket_number"]-1)*5:request["bucket_number"]*5])
        return {"project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"invocation_id":request["invocation_id"],"attempt_id":request["attempt_id"],"aggregate_p95_ms":100,"bucket_p95_ms":[100]*6}
    runner,cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"deployment":{"status":"pass","service_url":"https://video-call-staging-abc123.run.app"},"smoke":{"status":"pass"},"observation":sample,"observation-aggregate":aggregate})
    result=orch._run_deployment_workflow(runner,cap,"video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","video-call-staging-00011-def",clock)
    expected={n:list(range((n-1)*5+1,n*5+1)) for n in range(1,7)}
    assert result["terminal"]=="success" and buckets==expected and sorted(sum(buckets.values(),[]))==list(range(1,31))
    assert all(len(set(v))==5 for v in buckets.values()) and set().union(*map(set,buckets.values()))==set(range(1,31))
    assert orch._observation_timestamp(orch._create_observation("video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","pass","pass","pass",lambda:0,datetime(2026,1,1,tzinfo=timezone.utc)),300).endswith("00.000Z")
    return 0,True
add("actual-workflow-six-bucket-mapping",0,workflow_mapping_case)
def workflow_error_case(kind):
    clock=Clock()
    def sample(request):
        index=request["sample_index"]; bucket=(index-1)//5+1
        if kind.startswith("bucket"):
            requests=(100 if index==1 else 0) if bucket==1 else 1000; errors=(2 if kind=="bucket-equality" else 3) if index==1 else 0
            if requests==0: latency=None
        else:
            requests=20; errors=(1 if index in (1,6,11,16,21,26) else 0) + (1 if kind=="aggregate-breach" and index==2 else 0)
        latency = None if requests == 0 else 100
        return {"sample_index":index,"window_started_at":request["window_started_at"],"window_ended_at":request["window_ended_at"],"request_count":requests,"server_error_count":errors,"latency_p95_ms":latency,"probe_attempted":True,"probe_succeeded":True,"observed_revision":request["candidate_revision"],"observed_at":request["observed_at"]}
    def aggregate(request): return {"project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"invocation_id":request["invocation_id"],"attempt_id":request["attempt_id"],"aggregate_p95_ms":100,"bucket_p95_ms":[100]*6}
    def rollback(request): return {"status":"pass","project":request["project"],"region":request["region"],"service":request["service"],"candidate_revision":request["candidate_revision"],"target_revision":request["target_revision"],"traffic_revision":request["target_revision"],"invocation_id":request["invocation_id"],"rollback_attempt_id":request["rollback_attempt_id"]}
    runner,cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"deployment":{"status":"pass","service_url":"https://video-call-staging-abc123.run.app"},"smoke":{"status":"pass"},"observation":sample,"observation-aggregate":aggregate,"rollback":rollback})
    result=orch._run_deployment_workflow(runner,cap,"video-call-staging","asia-southeast1","video-call-staging","video-call-staging-00012-abc","video-call-staging-00011-def",clock)
    expected_success=kind in ("bucket-equality","aggregate-equality")
    return 0 if expected_success else 21, result["terminal"]==("success" if expected_success else "failure")
for kind in ("bucket-equality","bucket-breach","aggregate-equality","aggregate-breach"):
    add("actual-workflow-error-"+kind,0 if kind in ("bucket-equality","aggregate-equality") else 21,lambda kind=kind: workflow_error_case(kind))
def no_orphan_child():
    for assertion_failure in (False,True):
        child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
                               env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1"})
        try:
            try: child.wait(timeout=.05)
            except subprocess.TimeoutExpired: child.terminate(); child.wait(timeout=2)
            assert child.poll() is not None
            if assertion_failure: assert child.returncode is not None
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=2)
    return 0,True
add("local-child-no-orphan",0,no_orphan_child)
passed=failed=skipped=0
for name, expected, fn in cases:
    try:
        actual, ok=fn(); ok=ok and actual==expected
    except Exception:
        actual="exception"; ok=False
    print(f"{name}: expected={expected} actual={actual} exit_status={actual} {'PASS' if ok else 'FAIL'}")
    if ok: passed+=1
    else: failed+=1
assert len(cases)>0 and passed+failed+skipped==len(cases) and failed==0
before=source.read_bytes()
def evaluate_meta_result(returncode, stdout, stderr):
    if returncode != 31 or not isinstance(stdout,str) or stderr:
        return False
    try: summary=json.loads(stdout)
    except Exception: return False
    if set(summary)!={"marker","executed","unexpected_acceptances"}: return False
    return (summary.get("marker")=="unexpected_event_fixture_acceptance"
            and summary.get("executed")==3 and summary.get("unexpected_acceptances")==3)
driver_code='''import copy, importlib.util, json, pathlib, sys
source=pathlib.Path(sys.argv[1])
spec=importlib.util.spec_from_file_location("slice3_meta_orchestrator",source)
orch=importlib.util.module_from_spec(spec); spec.loader.exec_module(orch)
def weakened_assertion(*args): return None
runner,cap=orch._create_trusted_fake_runner({"migration":{"status":"pass"},"smoke":{"status":"pass"}})
orch._dispatch_operation(runner,cap,orch._create_operation("migration",100,lambda:0),{},lambda:0)
orch._dispatch_operation(runner,cap,orch._create_operation("smoke",100,lambda:0),{},lambda:0)
valid=copy.deepcopy(runner.events)
fixtures=[]
missing=copy.deepcopy(valid); missing.pop(); fixtures.append(missing)
wrong_order=copy.deepcopy(valid); wrong_order.reverse(); fixtures.append(wrong_order)
leak=copy.deepcopy(valid); leak[0]["capability"]=cap; fixtures.append(leak)
accepted=0
for events in fixtures:
    weakened_assertion(events,runner,cap,["migration","smoke"])
    accepted+=1
print(json.dumps({"marker":"unexpected_event_fixture_acceptance","executed":len(fixtures),"unexpected_acceptances":accepted},sort_keys=True,separators=(",",":")))
sys.exit(31)
'''
with tempfile.TemporaryDirectory() as meta:
    meta=pathlib.Path(meta); driver=meta/"meta-inner.py"
    driver.write_text(driver_code,encoding="utf-8")
    try:
        inner=subprocess.run([sys.executable,str(driver),str(source)],capture_output=True,text=True,timeout=5,
                            env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1","PATH":str(sentinels)})
        inner_result=(inner.returncode,inner.stdout.strip(),inner.stderr.strip())
    except subprocess.TimeoutExpired:
        inner_result=(None,"", "timeout")
    assert evaluate_meta_result(*inner_result)
    print(f"meta_inner_suite: process_exit={inner_result[0]} summary={inner_result[1]}")
    adversarial=[
        (31,inner_result[1],"",True),
        (0,inner_result[1],"",False),
        (32,inner_result[1],"",False),
        (31,"{}","",False),
        (31,json.dumps({"marker":"unexpected_event_fixture_acceptance","executed":2,"unexpected_acceptances":3}),"",False),
        (None,"","timeout",False),
    ]
    for result,expected in [(x[:3],x[3]) for x in adversarial]:
        assert evaluate_meta_result(*result)==expected
def evaluate_timeout_meta(returncode, stdout, stderr):
    if returncode != 37 or stderr: return False
    try: summary=json.loads(stdout)
    except Exception: return False
    return (set(summary)=={"marker","executed","unexpected_acceptances"}
            and summary.get("marker")=="unexpected_timeout_acceptance"
            and summary.get("executed")==2 and summary.get("unexpected_acceptances")==2)
timeout_driver='''import json, sys, pathlib, importlib.util
source=pathlib.Path(sys.argv[1]); spec=importlib.util.spec_from_file_location("slice3_timeout_meta",source)
orch=importlib.util.module_from_spec(spec); spec.loader.exec_module(orch)
class Clock:
    value=0.0
    def __call__(self): return self.value
clock=Clock(); weakened=lambda *args: None; accepted=0
for name in ("migration","smoke"):
    state=orch._create_operation(name,1.0,clock); clock.value=2.0
    weakened(state, {"status":"pass"}); accepted+=1
print(json.dumps({"marker":"unexpected_timeout_acceptance","executed":2,"unexpected_acceptances":accepted},sort_keys=True,separators=(",",":")))
sys.exit(37)
'''
with tempfile.TemporaryDirectory() as timeout_meta:
    driver=pathlib.Path(timeout_meta)/"timeout-meta-inner.py"; driver.write_text(timeout_driver,encoding="utf-8")
    inner=subprocess.run([sys.executable,str(driver),str(source)],capture_output=True,text=True,timeout=5,
                         env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1","PATH":str(sentinels)})
    timeout_result=(inner.returncode,inner.stdout.strip(),inner.stderr.strip())
    assert evaluate_timeout_meta(*timeout_result)
    assert evaluate_timeout_meta(0,timeout_result[1],timeout_result[2]) is False
    assert evaluate_timeout_meta(38,timeout_result[1],timeout_result[2]) is False
    assert evaluate_timeout_meta(37,"{}","") is False
    assert evaluate_timeout_meta(37,timeout_result[1],"crash") is False
    print(f"timeout_meta_suite: process_exit={timeout_result[0]} summary={timeout_result[1]}")
assert source.read_bytes()==before
assert not list(root.rglob("*.pyc"))
actual_records=[json.loads(x) for x in actual_audit.read_text(encoding="utf-8").splitlines()]
assert evaluate_audit(actual_records)=={"accepted":True,"classification":"clean"}
print(f"slice3_offline_validation=passed total={len(cases)} passed={passed} failed={failed} skipped={skipped} meta_test_nonzero=proven source_preservation=proven no_bytecode=proven")
PY
