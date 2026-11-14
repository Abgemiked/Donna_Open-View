"""CPython worker guard, not an OS security sandbox for hostile Python code."""
from pathlib import Path
import os
import sys


class IsolationViolation(RuntimeError):
    pass


class WorkerGuard:
    def __init__(self, run_root, code_root, libraries):
        self.root = Path(run_root).resolve()
        self.code = Path(code_root).resolve()
        self.libraries = [Path(p).resolve() for p in libraries]
        self.violations = []

    def deny(self, code):
        self.violations.append(code)
        raise IsolationViolation(code)

    def inside(self, path, root):
        return path == root or root in path.parents

    def check_path(self, raw, write=False, sqlite=False):
        if isinstance(raw, int):
            return  # Existing stdio descriptors, never a way to open a new path.
        path = Path(os.fsdecode(raw)).resolve()
        code_suffixes = (".py", ".pyc", ".pyd")
        # A temporary run directory is not permission to read or create secrets.
        # Match directory components too, so credential-store/data.json is denied.
        if any(part.lower().startswith(".env") or part.lower() in {".ssh", ".aws", ".git", "credentials", "secrets"} for part in path.parts) or (path.suffix.lower() not in code_suffixes and any(s in path.name.lower() for s in ("credential", "secret", "token"))):
            self.deny("credential_access")
        if self.inside(path, self.root):
            return
        if sqlite or write:
            self.deny("outside_run_write")
        if any(self.inside(path, p) for p in self.libraries):
            return
        if any(self.inside(path, p) for p in getattr(self, "code_roots", [self.code])) and path.suffix.lower() in (".py", ".pyc", ".pyd"):
            return
        self.deny("outside_allowlist_read")

    def audit(self, event, args):
        if event == "open":
            path, mode, flags = args
            write = bool((flags or 0) & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND))
            self.check_path(path, write=write)
        elif event == "sqlite3.connect":
            self.check_path(args[0], sqlite=True)
        elif event.startswith("socket."):
            self.deny("network_denied")
        elif event in ("subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn", "os.startfile"):
            self.deny("process_denied")
        elif event in ("os.remove", "os.rmdir", "os.mkdir", "os.rename", "os.symlink", "os.link"):
            self.check_path(args[0], write=True)
            if event in ("os.rename", "os.symlink", "os.link"):
                self.check_path(args[1], write=True)

    def install(self):
        sys.dont_write_bytecode = True
        sys.addaudithook(self.audit)

    def assert_clean(self):
        if self.violations:
            raise IsolationViolation("worker_contaminated")
