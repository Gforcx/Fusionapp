#!/usr/bin/env python3
"""External ADB accessibility binding check; no instrumentation, UiAutomation or Waze.

The already-installed APK is exercised unchanged. All evidence is kept, even if
binding fails. This checks Android platform behavior, not Xiaomi/HyperOS behavior.
"""
from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import time
import traceback
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "fr.roaddeck.launcher"
COMPONENT = PACKAGE + "/" + PACKAGE + ".SplitScreenService"
SHORT_COMPONENT = PACKAGE + "/.SplitScreenService"
MAIN = PACKAGE + "/.MainActivity"
TARGET = re.compile(re.escape(PACKAGE) + r"/(?:" + re.escape(PACKAGE) + r")?\.SplitScreenService(?=$|[\s},])")


def balanced(text: str, marker: str, opening="{", closing="}") -> str | None:
    pos = text.find(marker)
    if pos < 0:
        return None
    start = text.find(opening, pos + len(marker) - 1)
    if start < 0:
        return None
    depth = 0
    for end in range(start, len(text)):
        if text[end] == opening:
            depth += 1
        elif text[end] == closing:
            depth -= 1
            if depth == 0:
                return text[start + 1:end]
    return None


def normalized_label(label: str) -> str:
    plain = unicodedata.normalize("NFKD", label).encode("ascii", "ignore").decode()
    return " ".join(re.findall(r"[a-z0-9]+", plain.lower()))


def parse_accessibility(text: str) -> dict:
    users = re.split(r"(?=^\s*User state\[)", text, flags=re.M)
    current = [block for block in users if re.search(r"currentUser\s*=\s*true\b", block)]
    block = current[0] if len(current) == 1 else text
    sections = {name: balanced(block, name + ":") for name in
                ("Bound services", "Enabled services", "Binding services", "Crashed services")}
    bound = sections["Bound services"] or ""
    entries = []
    for match in re.finditer(r"Service\[", bound):
        entry = balanced(bound[match.start():], "Service[", "[", "]")
        if entry is not None:
            entries.append(entry)
    roaddeck = []
    for entry in entries:
        label = re.search(r"(?:^|,)\s*label\s*=\s*([^,]+)", entry)
        if label and normalized_label(label.group(1)) == "roaddeck ecran partage":
            roaddeck.append(entry)
    configured = any(re.search(r"notificationTimeout\s*=\s*100\b", item) for item in roaddeck)
    enabled = bool(TARGET.search(sections["Enabled services"] or ""))
    binding = bool(TARGET.search(sections["Binding services"] or ""))
    crashed = bool(TARGET.search(sections["Crashed services"] or ""))
    known = all(sections[key] is not None for key in
                ("Bound services", "Enabled services", "Binding services"))
    return {"healthy": bool(known and roaddeck and configured and enabled and not binding and not crashed),
            "formatRecognized": known, "boundLabel": bool(roaddeck),
            "configuredNotificationTimeout100": bool(configured), "enabled": enabled,
            "binding": binding, "frameworkCrashed": crashed,
            "crashedSectionPresent": sections["Crashed services"] is not None,
            "boundEntries": roaddeck, "currentUserBlocks": len(current)}


def package_stopped(text: str, user_id: str) -> bool | None:
    match = re.search(r"User\s+" + re.escape(user_id) + r":([^\n]*)", text)
    value = re.search(r"\bstopped=(true|false)\b", match.group(1) if match else "")
    return value.group(1) == "true" if value else None


class Check:
    def __init__(self, args):
        self.output = Path(args.output).resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.adb = [args.adb] + (["-s", args.serial] if args.serial else [])
        self.records = []
        self.started = time.monotonic()
        self.user_id = "0"
        self.result = {"schema": 1, "package": PACKAGE, "component": COMPONENT,
                       "startedAtUtc": datetime.now(timezone.utc).isoformat(),
                       "instrumentationUsed": False, "uiAutomationUsed": False,
                       "wazeLaunched": False, "productionModified": False,
                       "platformOnly": True, "status": "running"}

    def write(self, name, text):
        (self.output / name).write_text(text, encoding="utf-8")

    def command(self, args, name=None, timeout=8, binary=False):
        command = self.adb + list(args)
        started = time.monotonic()
        try:
            run = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 timeout=timeout, check=False)
            payload = run.stdout
            out = "" if binary else payload.decode("utf-8", "replace")
            err = run.stderr.decode("utf-8", "replace")
            code = run.returncode
        except subprocess.TimeoutExpired as error:
            payload = error.stdout or b""
            out = "" if binary else payload.decode("utf-8", "replace")
            err = (error.stderr or b"").decode("utf-8", "replace") + "\nADB command timeout"
            code = 124
        except OSError as error:
            payload, out, err, code = b"", "", str(error), 127
        self.records.append({"seconds": round(started - self.started, 3), "argv": command,
                             "durationSeconds": round(time.monotonic() - started, 3),
                             "returncode": code, "stdoutFile": name, "stderr": err})
        if name:
            if binary:
                (self.output / name).write_bytes(payload)
            else:
                self.write(name, out)
            if err:
                self.write(name + ".stderr.txt", err)
        return out, code

    def shell(self, args, name=None, timeout=8):
        # ADB's remote shell receives a properly quoted command, never host-shell interpolation.
        return self.command(["shell", shlex.join([str(arg) for arg in args])], name, timeout)

    def checkpoint(self, name):
        self.shell(["dumpsys", "package", PACKAGE], name + "-package.txt")
        self.shell(["dumpsys", "accessibility"], name + "-accessibility.txt")
        self.shell(["dumpsys", "activity", "services", PACKAGE], name + "-services.txt")
        self.shell(["dumpsys", "activity", "activities"], name + "-activities.txt")
        self.shell(["pidof", PACKAGE], name + "-pid.txt")

    def sample(self, name, timeout=4):
        # Both commands share the caller's remaining polling budget.
        started = time.monotonic()
        dump, code = self.shell(["dumpsys", "accessibility"], name + "-accessibility.txt", timeout)
        remaining = timeout - (time.monotonic() - started)
        if remaining > 0.1:
            pids, pidcode = self.shell(["pidof", PACKAGE], name + "-pid.txt", remaining)
        else:
            pids, pidcode = "", 124
        state = parse_accessibility(dump)
        state.update({"seconds": round(time.monotonic() - self.started, 3),
                      "pids": pids.split() if pidcode == 0 else [],
                      "adbReturncode": code, "pidReturncode": pidcode})
        state["healthy"] = state["healthy"] and code == 0 and len(state["pids"]) == 1
        return state

    def await_binding(self, name, duration=15):
        deadline = time.monotonic() + duration
        samples = []
        while time.monotonic() + 0.1 < deadline:
            sample = self.sample(f"{name}-{len(samples):02d}", min(4, deadline - time.monotonic()))
            samples.append(sample)
            if sample["healthy"] and time.monotonic() <= deadline:
                return {"bound": True, "samples": samples, "last": sample}
            if sample["frameworkCrashed"]:
                break
            time.sleep(min(0.5, max(0, deadline - time.monotonic())))
        return {"bound": False, "samples": samples, "last": samples[-1] if samples else {}}

    def observe(self, name, expected_pid, duration=5):
        deadline = time.monotonic() + duration
        samples = []
        while time.monotonic() + 0.1 < deadline:
            # The observation period controls when a new measurement may begin.
            # A measurement already begun always has its complete ADB budget:
            # truncating it to the last fractions of a second manufactured a
            # transport timeout even with a healthy service and an unchanged PID.
            sample = self.sample(f"{name}-{len(samples):02d}", 4)
            sample["expectedPidMatches"] = bool(expected_pid and sample["pids"] == [expected_pid])
            samples.append(sample)
            time.sleep(min(0.5, max(0, deadline - time.monotonic())))
        # Check once at the end as well, so the final half-second cannot be unobserved.
        sample = self.sample(f"{name}-end")
        sample["expectedPidMatches"] = bool(expected_pid and sample["pids"] == [expected_pid])
        samples.append(sample)
        return {"durationRequestedSeconds": duration,
                "passed": all(s["healthy"] and s["expectedPidMatches"] for s in samples),
                "samples": samples}

    def start_main(self, name):
        return self.shell(["am", "start", "-W", "-n", MAIN,
                           "--es", "roaddeckPage", "about", "--ez", "skipWazeAuto", "true"],
                          name + "-launch.txt", 15)

    def capture_debug_evidence(self):
        """Best-effort read-only evidence on every result, with no UI automation."""
        evidence = {}
        commands = [
            ("activity-lastanr", ["dumpsys", "activity", "lastanr"], 10),
            ("activity-exit-info", ["dumpsys", "activity", "exit-info", PACKAGE], 10),
            ("gfxinfo-framestats", ["dumpsys", "gfxinfo", PACKAGE, "framestats"], 10),
            ("cpuinfo", ["dumpsys", "cpuinfo"], 8),
            ("memory-info", ["cat", "/proc/meminfo"], 5),
            ("pressure-cpu", ["cat", "/proc/pressure/cpu"], 5),
            ("pressure-memory", ["cat", "/proc/pressure/memory"], 5),
            ("pressure-io", ["cat", "/proc/pressure/io"], 5),
            ("dropbox-data-app-anr", ["dumpsys", "dropbox", "--print", "data_app_anr"], 15),
            ("dropbox-system-app-anr", ["dumpsys", "dropbox", "--print", "system_app_anr"], 15),
            ("anr-directory", ["ls", "-l", "/data/anr"], 5),
        ]
        for name, command, timeout in commands:
            _, code = self.shell(command, name + ".txt", timeout)
            evidence[name] = {"returncode": code}
        # /data/anr access depends on the emulator image/ADB identity. A denial is
        # preserved as evidence; this check never switches ADB to root or changes
        # file permissions. DropBox/lastanr above remain the fallback.
        _, pull_code = self.command(["pull", "/data/anr", str(self.output / "anr-traces")],
                                    "anr-pull.txt", 20)
        evidence["anr-traces"] = {"returncode": pull_code}
        _, screen_code = self.command(["exec-out", "screencap", "-p"], "final-screen.png", 12, binary=True)
        screenshot = self.output / "final-screen.png"
        evidence["screenshot"] = {"returncode": screen_code,
                                  "pngHeader": screenshot.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"}
        self.result["debugEvidence"] = evidence

    def run(self):
        log_stream = None
        with (self.output / "logcat-live.txt").open("wb") as live, \
                (self.output / "logcat-live-stderr.txt").open("wb") as logerr:
            try:
                log_stream = subprocess.Popen(self.adb + ["logcat", "-v", "threadtime", "-b", "main",
                                                         "-b", "system", "-b", "crash", "-T", "1"],
                                              stdout=live, stderr=logerr)
                user, _ = self.shell(["am", "get-current-user"], "user.txt")
                if user.strip().isdigit():
                    self.user_id = user.strip()
                api, _ = self.shell(["getprop", "ro.build.version.sdk"], "api.txt")
                build, _ = self.shell(["getprop", "ro.build.fingerprint"], "build-fingerprint.txt")
                self.result.update({"api": api.strip(), "buildFingerprint": build.strip(), "userId": self.user_id})
                self.checkpoint("baseline")
                baseline_package = (self.output / "baseline-package.txt").read_text(encoding="utf-8")
                self.result["initialPackageStopped"] = package_stopped(baseline_package, self.user_id)
                installed, install_code = self.shell(["pm", "path", PACKAGE], "installed-apk.txt")
                if install_code or not installed.startswith("package:"):
                    raise RuntimeError("RoadDeck APK is not installed or package query failed")

                prior, prior_code = self.shell(["settings", "--user", "current", "get", "secure",
                                               "enabled_accessibility_services"], "enabled-before.txt")
                if prior_code:
                    raise RuntimeError("Cannot read enabled accessibility services")
                services = [part for part in prior.strip().split(":") if part and part != "null"]
                if any(not re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", part) for part in services):
                    raise RuntimeError("Unexpected enabled-service format; preserved without changes")
                services = [part for part in services if part not in (COMPONENT, SHORT_COMPONENT)] + [COMPONENT]
                _, enable_code = self.shell(["settings", "--user", "current", "put", "secure",
                                             "enabled_accessibility_services", ":".join(services)], "enable-service.txt")
                _, global_code = self.shell(["settings", "--user", "current", "put", "secure",
                                             "accessibility_enabled", "1"], "enable-accessibility.txt")
                self.result["activationCommandsSucceeded"] = enable_code == 0 and global_code == 0

                print("Phase 1: binding without an Activity or WebView", flush=True)
                phase1 = self.await_binding("phase1")
                self.checkpoint("phase1-end")
                stopped = package_stopped((self.output / "phase1-end-package.txt").read_text(encoding="utf-8"), self.user_id)
                phase1["packageStoppedAtEnd"] = stopped
                if not phase1["bound"]:
                    if phase1["last"].get("frameworkCrashed"):
                        phase1["classification"] = "framework_reports_service_disconnect_or_crash"
                    elif stopped:
                        phase1["classification"] = "unbound_while_package_stopped"
                        phase1["limitation"] = "No Activity was launched; stopped=true may prevent binding. This is not proof of an application crash."
                    else:
                        phase1["classification"] = "binding_not_confirmed_no_crash_proven"
                self.result["phase1"] = phase1

                print("Phase 2: MainActivity, Android accessibility settings, return to MainActivity", flush=True)
                _, start_code = self.start_main("phase2-main")
                phase2 = self.await_binding("phase2-binding")
                pids = phase2["last"].get("pids", [])
                expected_pid = pids[0] if phase2["bound"] and len(pids) == 1 else None
                phase2.update({"expectedPid": expected_pid, "mainLaunchReturncode": start_code})
                phase2["mainObservation"] = self.observe("phase2-main", expected_pid)
                self.checkpoint("phase2-main-end")
                _, settings_code = self.shell(["am", "start", "-W", "-a", "android.settings.ACCESSIBILITY_SETTINGS"],
                                              "phase2-settings-launch.txt", 15)
                phase2["settingsLaunchReturncode"] = settings_code
                phase2["settingsObservation"] = self.observe("phase2-settings", expected_pid)
                self.checkpoint("phase2-settings-end")
                _, return_code = self.start_main("phase2-return")
                phase2["returnMainLaunchReturncode"] = return_code
                phase2["returnObservation"] = self.observe("phase2-return", expected_pid)
                self.checkpoint("phase2-return-end")
                phase2["passed"] = (phase2["bound"] and start_code == settings_code == return_code == 0
                                    and all(phase2[key]["passed"] for key in
                                            ("mainObservation", "settingsObservation", "returnObservation")))
                self.result["phase2"] = phase2
                self.result["status"] = ("passed" if phase1["bound"] and phase2["passed"] else
                                         "passed_after_activity_with_phase1_limitation" if phase2["passed"]
                                         and phase1.get("classification") == "unbound_while_package_stopped"
                                         else "failed")
            except Exception as error:
                self.result.update({"status": "failed", "harnessError": str(error)})
                self.write("harness-exception.txt", traceback.format_exc())
            finally:
                self.checkpoint("final")
                self.capture_debug_evidence()
                self.command(["logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"],
                             "logcat-final.txt", 15)
                if log_stream is not None:
                    log_stream.terminate()
                    try:
                        log_stream.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        log_stream.kill()
                        log_stream.wait(timeout=3)
        self.finish()
        return 0 if self.result["status"].startswith("passed") else 1

    def finish(self):
        logs = (self.output / "logcat-live.txt").read_text(encoding="utf-8", errors="replace")
        lines = logs.splitlines()
        fatal = []
        for index, line in enumerate(lines):
            if "Process: " + PACKAGE + ", PID:" in line and any("FATAL EXCEPTION" in prior for prior in lines[max(0, index - 5):index + 1]):
                fatal.append("\n".join(lines[max(0, index - 5):index + 35]))
        self.write("application-fatal-exceptions.txt", "\n\n".join(fatal))
        self.result["javaFatalExceptionObserved"] = bool(fatal)
        self.result["nativeFatalMarkerObserved"] = ">>> " + PACKAGE + " <<<" in logs
        anr_pattern = re.compile(r"\bANR in " + re.escape(PACKAGE) + r"(?=[\s(]|$)")
        anr = ["\n".join(lines[max(0, index - 2):index + 65])
               for index, line in enumerate(lines) if anr_pattern.search(line)]
        self.write("application-anr-excerpts.txt", "\n\n".join(anr))
        self.result["applicationAnrObserved"] = bool(anr)
        final_dump = (self.output / "final-accessibility.txt").read_text(encoding="utf-8", errors="replace")
        self.result["finalBinding"] = parse_accessibility(final_dump)
        final_pids = (self.output / "final-pid.txt").read_text(encoding="utf-8", errors="replace").split()
        expected_pid = self.result.get("phase2", {}).get("expectedPid")
        self.result["finalPidStable"] = bool(expected_pid and final_pids == [expected_pid])
        self.result["elapsedSeconds"] = round(time.monotonic() - self.started, 3)
        self.result["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        self.result["evidenceDirectory"] = str(self.output)
        if (self.result["javaFatalExceptionObserved"] or self.result["nativeFatalMarkerObserved"]
                or self.result["applicationAnrObserved"]
                or not self.result["finalBinding"]["healthy"] or not self.result["finalPidStable"]
                or not self.result.get("activationCommandsSucceeded", False)):
            self.result["status"] = "failed"
        self.write("commands.json", json.dumps(self.records, ensure_ascii=False, indent=2))
        self.write("result.json", json.dumps(self.result, ensure_ascii=False, indent=2))
        print(json.dumps({key: self.result.get(key) for key in
                          ("status", "api", "javaFatalExceptionObserved", "nativeFatalMarkerObserved", "applicationAnrObserved", "elapsedSeconds")},
                         ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--output", default="service-evidence")
    parser.add_argument("--parse", type=Path, help="Only parse an existing dumpsys accessibility file")
    args = parser.parse_args()
    if args.parse:
        print(json.dumps(parse_accessibility(args.parse.read_text(encoding="utf-8", errors="replace")), ensure_ascii=False, indent=2))
        return 0
    return Check(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
