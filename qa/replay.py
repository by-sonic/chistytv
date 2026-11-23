"""Replay relevant, publicly reported input on the released script and package DB.

Uses only the project's filesystem-backed ADB double. No TCP connections to a TV.
"""
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPORTS = json.loads((ROOT / "qa/public-reports.json").read_text(encoding="utf-8"))
FAKE = str(ROOT / "test/fake-adb.ps1")
HOME = "com.leanbitlab.ltvL"
LAUNCHER = "com.google.android.tvlauncher"
STOCK = "com.google.android.apps.tv.launcherx"
WRAITH = "com.google.android.tungsten.setupwraith"


class Tv:
    def __init__(self, directory, report, installed=(), **state):
        self.path = Path(directory) / "state.json"
        self.props = {
            "ro.product.manufacturer": report["make"], "ro.product.brand": report["brand"],
            "ro.product.model": report["model"], "ro.build.version.sdk": report["sdk"],
            "ro.build.version.release": report["release"], "ro.product.cpu.abi": "armeabi-v7a",
            "ro.product.cpu.abilist": "armeabi-v7a,armeabi",
        }
        self.path.write_text(json.dumps(dict(
            props=self.props, installed=sorted(set(installed)), disabled=[], started=[],
            dns_mode="opportunistic", dns_spec="null", refuse=False, home="", home_works=False,
            wifi_owner="", tv_journal=""
        ) | state), encoding="utf-8")

    @property
    def state(self):
        return json.loads(self.path.read_text(encoding="utf-8-sig"))

    @property
    def log(self):
        return Path(str(self.path) + ".log").read_text(encoding="utf-8-sig")

    def run(self, script, *args, answer=""):
        p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            str(ROOT / script), *args],
                           env=dict(os.environ, FAKE_ADB_STATE=str(self.path)), input=answer.encode(),
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=80)
        result = p.stdout.decode("utf-8", errors="replace")
        assert p.returncode == 0, result
        return result

    def action(self, name, *args, **kwargs):
        return self.run("chistytv.ps1", "-Action", name, "-AdbPath", FAKE, "-WorkDir",
                        str(self.path.parent / "work"), "-TvIp", "127.0.0.1", *args, **kwargs)


def replay(n):
    with tempfile.TemporaryDirectory(prefix="chistytv-replay-") as tmp:
        r = REPORTS.get(str(n), dict(make="Xiaomi", brand="Xiaomi", model="MiTV-MOOQ1",
                                     sdk="30", release="11"))
        if n in (20, 39, 34, 26):
            expected = {
                20: [], 39: ["com.google.android.tvrecommendations", "ru.haier.evo"],
                34: ["ru.ivi.client", "ru.rt.video.app.tv"],
                26: ["ru.sberdevices.music", "ru.sberdevices.sberplay",
                     "ru.sberdevices.huaweicast", "ru.sberdevices.startube"],
            }[n]
            # Reports from old clients lack the installed/removed distinction. For #34
            # and #26 this models the explicit condition that the packages are installed.
            installed = r["disabled"] + expected + [p.split("/")[0] for p in r["candidates"]]
            components = [] if r["home"] == "не определён" else [r["home"]]
            tv = Tv(tmp, r, installed, disabled=r["disabled"], tv_journal="\n".join(r["journal"]),
                    home_apps=r["candidates"], resolved_components=components)
            before = tv.state
            tv.action("report", answer="n\n")
            report = next(Path(tmp).rglob("report-*.md")).read_text(encoding="utf-8-sig")
            def check(field, values):
                assert ("- **" + field + ":** " + (", ".join(values) or "ничего")) in report.splitlines(), report
            check("Отключил ЧистыйТВ", r["journal"])
            check("Отключено на ТВ", sorted(r["disabled"], key=str.casefold))
            check("Главный экран", [r["home"]])
            check("Кандидаты", r["candidates"])
            if expected:
                check("Включились снова (не через ЧистыйТВ)", expected)
            else:
                assert "Включились снова" not in report
            assert before == tv.state
            if n in (26, 34):
                # Exercise the other interpretation of older reports: all unlisted packages may be absent.
                removed = dict(before, installed=[p for p in before["installed"] if p not in expected])
                tv.path.write_text(json.dumps(removed), encoding="utf-8")
                alt = tv.action("report", answer="n\n")
                assert "Включились снова" not in alt and tv.state == removed
            return f"journal={len(r['journal'])}, disabled={len(r['disabled'])}, selected HOME and candidates matched, active-again={expected}; report read-only"
        if n in (30, 35):
            tv = Tv(tmp, r, [HOME, STOCK, WRAITH, "org.smarttube.stable", "com.happproxy"],
                    wifi_owner="cname=" + WRAITH, free_mb=0, need_mb=124, cache_mb=0)
            out = tv.action("launcher", answer="n\ny\n")
            assert WRAITH in out and "потеряет Wi-Fi" in out, out
            assert not tv.state["disabled"] and "shell pm disable" not in tv.log
            assert "shell pm uninstall" not in tv.log
            if n == 30:
                auto = tv.run("qa/discovery.ps1")
                assert "discovery and manual connection passed" in auto, auto
                apk = Path(tmp) / "sample.apk"
                apk.write_bytes(b"dummy APK; never executed")
                for label in ("SmartTube", "Happ"):
                    tv.run("test/run-install.ps1", "-Adb", FAKE, "-Apk", str(apk), "-Label", label)
                assert tv.state["trimmed"] and tv.state["free_mb"] == 0
                assert "com.installed.by.test" not in tv.state["installed"]
                assert tv.log.count("shell pm install -r ") == 2
                assert tv.log.count("shell pm trim-caches 999G") == 2
                out = tv.action("youtube", "-Yes", "-Subscription", "invalid")
                assert "Это не похоже на ссылку подписки" in out, out
                assert not tv.state["started"]
            return "Wi-Fi owner retained; zero disable/uninstall calls" + ("; no discoveries, manual ADB fallback; zero space after cache trim, both installs failed, invalid URL rejected" if n == 30 else "")
        if n == 38:
            pkgs = ["com.yandex.android.advid", "com.yandex.tv.bugreportsender"]
            tv = Tv(tmp, r, pkgs + [HOME, "com.yandex.tv.home"])
            tv.action("clean", "-Yes")
            assert set(tv.state["disabled"]) == set(pkgs), tv.state
            assert set(tv.state["tv_journal"].split()) == set(pkgs)
            tv.action("restore", "-Yes")
            assert not tv.state["disabled"] and not tv.state["tv_journal"]
            return "both reported optional packages disabled and restored; both HOME packages preserved"
        if n == 32:
            pkgs = ["com.google.android.tvrecommendations", "com.google.android.feedback",
                    "com.google.android.partnersetup"]
            tv = Tv(tmp, r, pkgs + [LAUNCHER], user_flag_illegal=True)
            tv.action("clean", "-Yes")
            assert set(tv.state["disabled"]) == set(pkgs), tv.state
            for pkg in pkgs:
                assert tv.log.index("pm disable-user --user 0 " + pkg) < tv.log.index("pm disable-user " + pkg)
            assert set(tv.state["tv_journal"].split()) == set(pkgs)
            return "three vendor Warning replies injected; all three fallback forms called and success journalled on fake ADB"
        if n == 23:
            tv = Tv(tmp, r, r["disabled"] + ["com.android.tv.settings"], disabled=r["disabled"],
                    tv_journal="\n".join(r["journal"]))
            tv.action("restore", "-Yes")
            restored = set(r["disabled"]) - set(tv.state["disabled"])
            assert restored == {LAUNCHER, "com.google.android.apps.mediashell",
                                "com.mediatek.android.leanbacklauncher.partnercustomizer"}, restored
            return "REPRODUCED v0.2.9: also restored unjournalled MediaTek ad-row service"
        if n == 31:
            pkgs = ["com.yandex.tv.bugreportsender", "com.yandex.launcher.updaterapp", "com.yandex.io.sdk"]
            tv = Tv(tmp, r, pkgs + [HOME, "com.yandex.tv.home"], protected=pkgs[1:])
            out = tv.action("clean", answer="y\n1,2\n")
            for pkg in pkgs[1:]:
                assert (pkg + "): прошивка защищает этот пакет") in out, out
            disabled = tv.state["disabled"]
            assert disabled == pkgs[0] or disabled == [pkgs[0]], tv.state
            assert tv.state["tv_journal"].strip() == pkgs[0]
            return "bugreportsender disabled, two protected-package refusals handled, both HOME packages remain; no factory-menu or reboot proof"
        if n == 6:
            apps = ["ru.kinopoisk.yandex.tv", "ru.ivi.client"]
            tv = Tv(tmp, r, apps + ["com.miui.tv.analytics", "com.xiaomi.statistic", LAUNCHER])
            tv.action("clean", "-Yes")
            assert set(tv.state["disabled"]) == {"com.miui.tv.analytics", "com.xiaomi.statistic"}, tv.state
            return "default cleanup left Kinopoisk and Ivi packages enabled; no playback test"


if __name__ == "__main__":
    import sys
    issues = list(map(int, sys.argv[1:])) or [6, 20, 23, 26, 30, 31, 32, 34, 35, 38, 39]
    failed = False
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        futures = {pool.submit(replay, n): n for n in issues}
        for f in concurrent.futures.as_completed(futures):
            try:
                print(f"PASS #{futures[f]} {f.result()}", flush=True)
            except Exception as exc:
                print(f"FAIL #{futures[f]} {exc!r}", flush=True)
                failed = True
    sys.exit(failed)
