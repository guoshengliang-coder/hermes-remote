"""Test-only child: execute a disposable CLI import, stop after its scratch hook.

PM launch/activation and interrupted-update recovery are explicitly injected no-ops.
Admission placement is injected, not an upstream patch or supported launch method.
"""
import importlib.util
import json
import os
from pathlib import Path
import runpy
import sys
import types


class Boundary(BaseException):
    pass


class PrefixReached(BaseException):
    pass


def run(checkout, domain_records, mode, argv, entry_module="hermes_cli.main", role="writer", descriptors=(), binding_args=None):
    checkout = Path(checkout)
    home = Path(os.environ["HERMES_HOME"])
    owned = checkout.parent
    result = {"blocked": False, "bootstrapEntered": False, "prefixReached": False,
              "admissionCalls": 0, "homeMutationAttempts": 0, "pmRecoveryInjected": True}
    held = []
    spec = importlib.util.spec_from_file_location("maintenance_lease", Path(__file__).with_name("maintenance_lease.py"))
    lease = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(lease)
    bound = role == "bound-recovery"
    durable = role == "durable-recovery"
    if bound:
        result.update(boundIdentityVerified=False, identityRejected=False)
    if durable:
        result.update(durableIntentVerified=False, identityRejected=False)
    if bound or durable:
        spec = importlib.util.spec_from_file_location("bound_handoff", Path(__file__).with_name("bound_handoff.py"))
        binding = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(binding)
    if durable:
        spec = importlib.util.spec_from_file_location("durable_intent", Path(__file__).with_name("durable_intent.py"))
        intent = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(intent)

    def enter():
        result["admissionCalls"] += 1
        domains = [lease.Domain(*record) for record in domain_records]
        if bound or durable:
            if not result["boundIdentityVerified" if bound else "durableIntentVerified"] or len(held) != 1:
                raise Boundary()
            return held[0]
        elif role == "recovery":
            value = lease.Lease.inherit_maintenance(domains, descriptors)
            for fd in descriptors:
                os.close(fd)  # The adopted duplicates now own this child's custody.
        elif role == "writer":
            value = lease.Lease(domains)
        else:
            raise Boundary()
        held.append(value)
        return value

    def module(name, **values):
        value = types.ModuleType(name)
        value.__dict__.update(values)
        sys.modules[name] = value
        return value

    # The only allowed real writer in this prefix experiment is scratch setup.
    # No dependency activation, recovery, process restart or service is exercised.
    module("pm", __path__=[])
    module("pm.environments", activate_dependencies=lambda *_: None, install_state_permission_message=lambda *_: None)
    module("hermes_cli._early_recovery", restore_interrupted_pull=lambda *_: False, recover_if_needed=lambda *_: False)
    module("hermes_cli._parser", command_argv=lambda value: value)
    module("hermes_cli.venv_sync", prepare_launch=lambda *_: None, relaunch_command=lambda *_: [])
    module("hermes_cli._hg199_entry_binding", enter=enter)

    def path(value):
        if isinstance(value, int):
            raise Boundary()
        return Path(os.path.abspath(os.fsdecode(value)))

    def mutation(value):
        target = path(value)
        if target != owned and owned not in target.parents:
            raise Boundary()
        if target == home or home in target.parents:
            result["homeMutationAttempts"] += 1

    def audit(event, args):
        if event == "open":
            flags = args[2]
            if flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND):
                mutation(args[0])
        elif event in ("os.mkdir", "os.remove", "os.rmdir", "os.chmod", "os.utime", "os.truncate"):
            mutation(args[0])
        elif event in ("os.rename", "os.link", "os.symlink"):
            mutation(args[0])
            mutation(args[1])
        elif event in ("subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.kill", "os.killpg",
                       "socket.connect", "socket.bind", "socket.getaddrinfo"):
            raise Boundary()

    bootstrap = str(checkout / "hermes_bootstrap.py")

    def profile(frame, event, _):
        if frame.f_code.co_filename == bootstrap:
            if event == "call" and frame.f_code.co_name == "<module>":
                result["bootstrapEntered"] = True
            if event == "return" and frame.f_code.co_name == "export_scratch_tmp_env":
                raise PrefixReached()

    sys.path.insert(0, str(checkout))
    entry_path = checkout / (entry_module.replace(".", "/") + ".py")
    sys.argv = [str(entry_path), *argv]
    sys.addaudithook(audit)
    # Authorize before importing ANY copied package, including package __init__.
    # PM hooks remain no-ops; this is only a live trusted-launcher experiment.
    if bound:
        transition_fd = binding_args.get("transition")
        try:
            def acquire():
                value = lease.Lease.inherit_maintenance([lease.Domain(*record) for record in domain_records], descriptors)
                if transition_fd is not None:
                    try:
                        spec = importlib.util.spec_from_file_location("prefix_transition", Path(__file__).with_name("generation_transition.py"))
                        transition = importlib.util.module_from_spec(spec)
                        spec.loader.exec_module(transition)
                        plan = binding.read_context(transition_fd)
                        if plan["after"]["run"] != binding_args["run"] or plan["after"]["source"] != binding_args["source"]:
                            raise ValueError("fixture target identity")
                        transition.target(value, plan)
                    except BaseException:
                        value.close()
                        raise
                return value
            adopted = binding.accept(binding_args["channel"], binding_args["context"],
                                     binding_args["run"], binding_args["source"], checkout, home, entry_module,
                                     domain_records, acquire)
            held.append(adopted)
            result["boundIdentityVerified"] = True
        except (ValueError, OSError):
            result.update(identityRejected=True, blocked=True)
        finally:
            for fd in (*descriptors, binding_args["context"]):
                os.close(fd)
            if transition_fd is not None:
                os.close(transition_fd)
    if durable:
        adopted = None
        try:
            context = binding.read_context(binding_args["context"])
            binding.validate(context, binding_args["run"], binding_args["source"], checkout, home, entry_module, domain_records)
            adopted = lease.Lease([lease.Domain(*record) for record in domain_records], maintenance=True)
            if not adopted.drain():
                raise BlockingIOError("maintenance still busy")
            intent.resume(adopted, context)
            held.append(adopted)
            result["durableIntentVerified"] = True
        except (ValueError, OSError):
            if adopted is not None:
                adopted.close()
            result.update(identityRejected=True, blocked=True)
        finally:
            os.close(binding_args["context"])
    sys.setprofile(profile)
    try:
        if (bound or durable) and result["identityRejected"]:
            pass
        elif mode == "module":
            runpy.run_module(entry_module, run_name="__main__")
        elif mode == "console-import":
            __import__(entry_module)
        elif mode == "script":
            runpy.run_path(str(entry_path), run_name="__main__")
        else:
            raise Boundary()
    except BlockingIOError:
        result["blocked"] = True
    except PrefixReached:
        result["prefixReached"] = True
    finally:
        sys.setprofile(None)
    result["scratchCreated"] = (home / "cache/scratch").is_dir()
    # An admitted CLI import keeps its writer lease until this controlled prefix exits.
    print(json.dumps(result), flush=True)
    if sys.stdin.readline().strip() != "release":
        raise Boundary()
    for value in held:
        value.close()


if __name__ == "__main__":
    try:
        run(sys.argv[1], json.loads(sys.argv[2]), sys.argv[3], json.loads(sys.argv[4]),
            sys.argv[5], sys.argv[6], json.loads(sys.argv[7]), json.loads(sys.argv[8]))
    except (Exception, Boundary):
        print(json.dumps({"failed": True}), flush=True)
        raise SystemExit(1) from None
