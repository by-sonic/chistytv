from hashlib import sha256
import os
from pathlib import Path
import shutil
import subprocess

from home import ROOT, EXPECTED, adb, shell, resolve, home_after_key, assert_home

assert os.environ["RUNNER_ENVIRONMENT"] == "github-hosted"
assert shell("getprop ro.kernel.qemu") == "1"
assert sha256(Path("candidate/data/CleanYourTV-Launcher-3.6.apk").read_bytes()).hexdigest() == EXPECTED
assert "package:" + ROOT not in shell("pm list packages").splitlines()
stock = resolve()
assert stock.startswith("com.google.android.tvlauncher/")
env = os.environ.copy()
env.update({"HTTP_PROXY": "http://127.0.0.1:9", "HTTPS_PROXY": "http://127.0.0.1:9", "NO_PROXY": "127.0.0.1,localhost"})
base = ["pwsh", "-NoLogo", "-NoProfile", "-File", "candidate/chistytv.ps1", "-TvIp", "127.0.0.1:5555",
        "-AdbPath", shutil.which("adb"), "-WorkDir", str(Path(os.environ["RUNNER_TEMP"]) / "client-work")]
def run(action, *, yes, answers=""):
    out = subprocess.run(base + ["-Action", action] + (["-Yes"] if yes else []),
                         text=True, input=answers, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         check=True, timeout=95, env=env).stdout
    print(out, flush=True)
    return out

soft = run("launcher", yes=True)
assert "package:" + ROOT in shell("pm list packages -e").splitlines(), soft
assert "package:" + stock.split("/")[0] in shell("pm list packages -e").splitlines()
if home_after_key(ROOT):
    print("Soft integrated PS path activated the launcher. Exercising manual return to original HOME.")
else:
    print("Soft integrated PS path respected the original HOME. Testing explicit hard-mode consent using the same production action.")
    out = run("launcher", yes=False, answers="n\ny\n")
    assert home_after_key(ROOT), out
    assert "package:" + stock.split("/")[0] in shell("pm list packages -d").splitlines()
    restore = run("restore", yes=True)
    assert "package:" + stock.split("/")[0] in shell("pm list packages -e").splitlines(), restore
assert "Success" in shell("cmd package set-home-activity --user 0 " + stock)
assert_home(stock.split("/")[0])
