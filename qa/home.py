from hashlib import sha256
from pathlib import Path
from subprocess import check_output
import os
import time

ROOT = "com.gathin.tvhome"
HOME = "-a android.intent.action.MAIN -c android.intent.category.HOME"
EXPECTED = "2711f35b112a9f645c97e5a857f2c5e366c775a43354f1b8667d2aa2ad186318"


def adb(*args):
    return check_output(["adb", "-s", "emulator-5554", *args], text=True).strip()


def shell(command):
    return adb("shell", command)


def resolve():
    return shell("cmd package resolve-activity --brief " + HOME).splitlines()[-1]


def assert_home(package):
    shell("input keyevent KEYCODE_HOME")
    for _ in range(14):
        resumed = [line for line in shell("dumpsys activity activities").splitlines()
                   if "mResumedActivity:" in line or "topResumedActivity=" in line]
        if any(package + "/" in line for line in resumed):
            print("Foreground after physical HOME key:", resumed, flush=True)
            return
        time.sleep(0.5)
    raise AssertionError((package, resumed))


def main():
    assert os.environ["RUNNER_ENVIRONMENT"] == "github-hosted"
    assert shell("getprop ro.kernel.qemu") == "1" and shell("getprop ro.build.version.sdk") == "30"
    assert "android.software.leanback" in shell("pm list features")
    stock = resolve()
    assert stock.startswith("com.google.android.tvlauncher/"), stock
    print("Initial HOME:", stock)
    apk = Path("data/CleanYourTV-Launcher-3.6.apk")
    assert sha256(apk.read_bytes()).hexdigest() == EXPECTED
    assert "Success" in adb("install", "-r", str(apk))
    try:
        new = shell("cmd package resolve-activity --brief " + HOME + " -p " + ROOT).splitlines()[-1]
        assert new.startswith(ROOT + "/"), new
        assert "Status: ok" in shell("am start -W -n " + new)
        assert "Success" in shell("cmd package set-home-activity " + new)
        assert resolve() == new, (new, resolve())
        assert "Status: ok" in shell("am start -W -a android.settings.SETTINGS")
        assert_home(ROOT)
        Path("clean-home.png").write_bytes(check_output(["adb", "-s", "emulator-5554", "exec-out", "screencap", "-p"]))
        assert "package:" + stock.split("/")[0] in shell("pm list packages -e").splitlines()
    finally:
        assert "Success" in shell("cmd package set-home-activity " + stock)
        assert resolve() == stock
        assert_home(stock.split("/")[0])
    print("Both launchers remain installed and enabled.")
    

if __name__ == "__main__":
    main()
