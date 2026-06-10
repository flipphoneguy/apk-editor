"""Filesystem-based job dirs with a TTL reaper.

Each upload becomes a directory <storage>/jobs/<token>/. State lives entirely on
the filesystem so it works across gunicorn workers without shared memory. A
daemon reaper deletes job dirs older than TTL; downloads/cancels delete
immediately. A startup sweep clears orphans left by a crash or restart.
"""
import hashlib
import os
import re
import secrets
import shutil
import threading
import time

TTL = 30 * 60          # 30 minutes
SWEEP_INTERVAL = 60    # reaper runs every minute
_TOKEN_RE = re.compile(r"^[0-9a-f]{32}$")
_reaper_started = False
_lock = threading.Lock()


def _jobs_root(storage):
    return os.path.join(storage, "jobs")


def new_job(storage):
    token = secrets.token_hex(16)
    d = os.path.join(_jobs_root(storage), token)
    os.makedirs(d, exist_ok=True)
    return token, d


def job_dir(storage, token):
    """Return the job dir for a valid existing token, else None."""
    if not token or not _TOKEN_RE.match(token):
        return None
    d = os.path.join(_jobs_root(storage), token)
    return d if os.path.isdir(d) else None


def delete_job(storage, token):
    if not token or not _TOKEN_RE.match(token):
        return
    shutil.rmtree(os.path.join(_jobs_root(storage), token), ignore_errors=True)


# ── Per-IP rate limiting (filesystem-backed so it's shared across workers) ──

def _rl_dir(storage):
    return os.path.join(storage, "rl")


def rate_ok(storage, ip, max_n, window):
    """Return True if `ip` is under `max_n` actions within `window` seconds.

    Backed by a small per-IP file of timestamps so the limit is shared across
    gunicorn workers (an in-memory dict would be per-process and ~Nx too loose).
    Minor read/write races between workers can undercount by one — acceptable.
    """
    d = _rl_dir(storage)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, hashlib.sha1(ip.encode()).hexdigest() + ".txt")
    now = time.time()
    try:
        with open(path) as f:
            ts = [float(x) for x in f.read().split() if x]
    except (OSError, ValueError):
        ts = []
    ts = [t for t in ts if now - t < window]
    if len(ts) >= max_n:
        return False
    ts.append(now)
    tmp = path + ".tmp"
    try:
        with open(tmp, "w") as f:
            f.write("\n".join("%.3f" % t for t in ts))
        os.replace(tmp, path)
    except OSError:
        pass
    return True


def _sweep(storage):
    now = time.time()
    root = _jobs_root(storage)
    try:
        for name in os.listdir(root):
            d = os.path.join(root, name)
            try:
                if os.path.isdir(d) and (now - os.path.getmtime(d)) > TTL:
                    shutil.rmtree(d, ignore_errors=True)
            except OSError:
                pass
    except OSError:
        pass
    # Drop stale rate-limit files (untouched for over an hour).
    rd = _rl_dir(storage)
    try:
        for name in os.listdir(rd):
            p = os.path.join(rd, name)
            try:
                if os.path.isfile(p) and (now - os.path.getmtime(p)) > 3600:
                    os.remove(p)
            except OSError:
                pass
    except OSError:
        pass


def start_reaper(storage):
    """Start the background reaper once per process (idempotent)."""
    global _reaper_started
    with _lock:
        if _reaper_started:
            return
        _reaper_started = True

    os.makedirs(_jobs_root(storage), exist_ok=True)
    _sweep(storage)  # startup sweep of orphans

    def loop():
        while True:
            time.sleep(SWEEP_INTERVAL)
            try:
                _sweep(storage)
            except Exception:
                pass

    threading.Thread(target=loop, daemon=True).start()
