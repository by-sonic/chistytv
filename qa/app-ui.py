import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from hashlib import sha256

from home import ROOT, HOME, EXPECTED, adb, shell, resolve, assert_home, home_after_key

APP = "ru.bysonic.chistytv"
ART = Path("ui-evidence")
ART.mkdir(exist_ok=True)


def snapshot():
    out = shell("uiautomator dump /sdcard/chistytv-qa.xml")
    if "dumped" not in out.lower():
        raise RuntimeError(out)
    data = adb("shell", "cat /sdcard/chistytv-qa.xml")
    (ART / "ui.xml").write_text(data)
    return list(ET.fromstring(data).iter("node"))


def tap(n):
    coords = list(map(int, re.findall(r"\d+", n.attrib["bounds"])))
    x, y = (coords[0] + coords[2])//2, (coords[1] + coords[3])//2
    print("Tap", n.get("text"), n.get("class"), x, y, flush=True)
    shell(f"input tap {x} {y}")


def find(value, kind="text", swipe=False, seconds=28):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        nodes = snapshot()
        for n in nodes:
            if n.get(kind) == value:
                return n
        for n in nodes:
            if n.get("package") in ("com.android.systemui", "com.android.settings", "com.android.tv.settings") and n.get("text", "").lower() in ("allow", "always allow", "ok"):
                print("Confirm disposable TV ADB authorization")
                tap(n)
                time.sleep(.5)
                break
        else:
            if swipe:
                shell("input swipe 500 1470 500 630 420")
                time.sleep(.4)
    raise AssertionError((value, [(n.get("class"),n.get("text")) for n in nodes if n.get("text")]))


def action(value, **kwargs):
    tap(find(value, **kwargs))


def main():
    assert os.environ["RUNNER_ENVIRONMENT"] == "github-hosted"
    assert shell("getprop ro.kernel.qemu") == "1" and shell("getprop ro.build.version.sdk") == "30"
    initial = resolve()
    assert initial.startswith("com.google.android.tvlauncher/"), initial
    print("Default", initial, flush=True)
    old = os.environ.get("BASELINE") == "yes"
    if not old:
        with ZipFile("client.apk") as apk:
            assert sha256(apk.read("assets/CleanYourTV-Launcher-3.6.apk")).hexdigest() == EXPECTED
    assert "Success" in adb("install", "-r", "client.apk")
    assert "package:" + ROOT not in shell("pm list packages").splitlines()
    # Give the phone client a portrait surface on the emulator which also hosts the TV.
    shell("wm size 1080x1920")
    shell("wm density 280")
    shell("settings put system accelerometer_rotation 0")
    shell("settings put system user_rotation 0")
    print("adbd over emulator LAN loopback endpoint, existing shell:", shell("getprop ro.adb.secure"))
    # Open a real adbd TCP listener on the test TV. The emulator host port 5555 is its separate qemu transport.
    print(adb("tcpip", "15555"), flush=True)
    adb("wait-for-device")
    assert shell("getprop service.adb.tcp.port") == "15555"
    time.sleep(2)
    print("Listener diagnostic:", shell("ss -ltn 2>/dev/null || netstat -ln 2>/dev/null || true")[:300], flush=True)
    # Avoid leaving the onboarding screen off the bottom during manual IP entry.
    assert "Status: ok" in shell(f"am start -W -n {APP}/.MainActivity")
    action("Найти телевизор", swipe=True)
    action("Ввести IP", swipe=True)
    action("android.widget.EditText", kind="class")
    shell("input text 127.0.0.1:15555")
    shell("input keyevent KEYCODE_BACK")
    action("Подключить")
    action("Новый главный экран", swipe=True, seconds=90)
    button = find("Установить и сделать главным")
    if old:
        print("Real app connected to Android TV and rendered the launcher installation screen.")
        return
    # Block HTTP(S) requests by this app through Android's global HTTP proxy; local ADB is a raw socket.
    shell("settings put global http_proxy 127.0.0.1:9")
    try:
        tap(button)
        for _ in range(60):
            if "package:" + ROOT in shell("pm list packages -e").splitlines():
                break
            time.sleep(0.5)
        assert "package:" + ROOT in shell("pm list packages -e").splitlines()
        time.sleep(5)
        assert "package:" + initial.split("/")[0] in shell("pm list packages -e").splitlines()
        if not home_after_key(ROOT):
            print("Stock HOME retained. Return to the connected ChistyTV client and explicitly confirm its hard mode.", flush=True)
            shell(f"am start -n {APP}/.MainActivity --activity-reorder-to-front")
            action("Отключить штатный лаунчер", swipe=True)
            action("Отключить")
            for _ in range(15):
                if "package:" + initial.split("/")[0] in shell("pm list packages -d").splitlines():
                    break
                time.sleep(1)
        assert_home(ROOT)
        Path(ART / "home.png").write_bytes(subprocess.check_output(["adb", "-s", "emulator-5554", "exec-out", "screencap", "-p"]))
        print("Released client installed and activated the launcher through its visible UI.")
    finally:
        shell("settings put global http_proxy :0")
        shell("pm enable " + initial.split("/")[0])
        assert "Success" in shell("cmd package set-home-activity " + initial)
        assert_home(initial.split("/")[0])


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print(shell("logcat -d -s ChistyTV:I AndroidRuntime:E ActivityTaskManager:W | tail -n 45"))
        print(shell("dumpsys activity activities | grep 'mResumedActivity' | head -n 3"))
        (ART / "failed.png").write_bytes(subprocess.check_output(["adb", "-s", "emulator-5554", "exec-out", "screencap", "-p"]))
        raise
