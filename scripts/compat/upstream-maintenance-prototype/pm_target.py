"""Compare a prepared fixture with a separately supplied plan; never grant authority.

There is deliberately no plan-from-observation or finish API. A matching diagnostic
does not authenticate the producer of expectations or authorize a recovered updater.
"""
import os
from pathlib import Path
import re

from importlib.util import module_from_spec, spec_from_file_location


def load(name, path=None):
    spec = spec_from_file_location("pm_target_" + name, path or Path(__file__).with_name(name + ".py"))
    value = module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


pm, intent = load("pm_generation"), load("durable_intent")
binding = pm.binding
BOOTSTRAP = load("bootstrap", Path(__file__).parents[1] / "hermes-maintenance-bootstrap.py")
POLICIES = ("pm-prepared-target-diagnostic-v1", "pm-activated-target-diagnostic-v1")
GENERATION = {"environment", "environmentIdentity", "runtimeBase", "runtimeIdentity",
              "environmentDigest", "runtimeDigest", "runtimeVersion", "packagingVersion"}


def sha(value):
    return type(value) is str and re.fullmatch("[0-9a-f]{64}", value) is not None


def validate(held, plan, expected_run, expected_source, expected_sha):
    root = pm.scope(held)
    if (type(plan) is not dict or set(plan) != {"schema", "policy", "context", "inputs", "generation"}
            or type(plan["schema"]) is not int or plan["schema"] != 1
            or plan["policy"] not in POLICIES or not sha(expected_sha)
            or len(binding.encode(plan)) > binding.LIMIT or binding.digest(plan) != expected_sha):
        raise ValueError("explicit bounded target plan required")
    context, inputs, generation = (plan[k] for k in ("context", "inputs", "generation"))
    if (type(context) is not dict or type(context.get("files")) is not dict
            or set(context["files"]) != set(BOOTSTRAP.BOOTSTRAP_SHA256)
            or type(inputs) is not dict or set(inputs) != set(pm.PINNED) | set(pm.INPUTS)
            or any(not sha(value) for value in inputs.values())
            or type(generation) is not dict or set(generation) != GENERATION):
        raise ValueError("target plan shape changed")
    version = generation["runtimeVersion"]
    if (type(version) is not list or len(version) != 3
            or any(type(v) is not int or v < 0 for v in version) or version[:2] < [3, 11]
            or any(not sha(generation[k]) for k in ("environmentDigest", "runtimeDigest"))
            or type(generation["packagingVersion"]) is not str
            or re.fullmatch(r"[A-Za-z0-9.+_-]{1,128}", generation["packagingVersion"]) is None):
        raise ValueError("target runtime shape changed")
    for name in ("environment", "runtimeBase"):
        identity = generation[name.replace("Base", "") + "Identity"]
        if (type(generation[name]) is not str or type(identity) is not list or len(identity) != 2
                or any(type(v) is not int or v < 0 for v in identity)):
            raise ValueError("target directory shape changed")
    records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in held.domains]
    binding.validate(context, expected_run, expected_source, root / "checkout", root / "data",
                     "hermes_cli.main", records)
    if binding.snapshot(root / "checkout", inputs) != inputs:
        raise ValueError("planned source inputs changed")
    expected_intent = intent.record(context)
    for domain in held.domains:
        fd = domain.intent()
        try:
            if os.pread(fd, intent.LIMIT + 1, 0) != expected_intent:
                raise ValueError("plan does not match active transaction")
        finally:
            os.close(fd)
    return root


def verify(held, plan, expected_run, expected_source, expected_sha, command_prefix=(), *, checkpoint=None):
    validate(held, plan, expected_run, expected_source, expected_sha)
    if checkpoint:
        checkpoint("validated", 0)
    observed = pm.observe(held, command_prefix)
    if checkpoint:
        checkpoint("observed", 1)
    generation = plan["generation"]
    actual = {"environment": observed["selected"]["environment"],
              "environmentIdentity": binding.directory(observed["selected"]["environment"]),
              "runtimeBase": observed["runtime"]["base"],
              "runtimeIdentity": binding.directory(observed["runtime"]["base"]),
              "environmentDigest": observed["environmentDigest"], "runtimeDigest": observed["runtimeDigest"],
              "runtimeVersion": observed["runtime"]["version"], "packagingVersion": observed["runtime"]["packaging"]}
    if actual != generation:
        raise ValueError("selected generation does not match target plan")
    if plan["policy"] == POLICIES[1] and not observed["activationInputsCurrent"]:
        raise ValueError("target activation inputs have not been published")
    pm.revalidate(held, observed, command_prefix)
    validate(held, plan, expected_run, expected_source, expected_sha)
    if checkpoint:
        checkpoint("verified", 2)
    return {"preparedTargetMatched": True, "policy": plan["policy"],
            "activationInputsCurrent": observed["activationInputsCurrent"],
            "targetAuthorityVerified": False, "durableHandoffVerified": False,
            "productionCompletionVerified": False, "certifiesUpgrade": False}
