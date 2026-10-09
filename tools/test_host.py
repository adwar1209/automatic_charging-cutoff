#!/usr/bin/env python3
"""Exercise production C control logic and the Android wire codec without hardware."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, text=True, **kwargs)

with tempfile.TemporaryDirectory(prefix="charging-cutoff-tests-") as temp:
    build = Path(temp)
    executable = build / "cutoff_test"
    run([*shlex.split(os.environ.get("CC", "cc")), "-std=c11", "-Wall", "-Wextra", "-Werror",
         "-fsanitize=undefined", "-fno-sanitize-recover=all", "-g", "-I", ROOT / "firmware/main",
         ROOT / "firmware/main/cutoff_core.c", ROOT / "validation/test_cutoff.c", "-o", executable])
    run([executable])
    # The module invocation also works with JDK installations lacking a javac launcher.
    run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", build,
         ROOT / "android-app/app/src/main/java/com/adwar/chargingcutoff/Wire.java",
         ROOT / "validation/WireTest.java"])
    java = ["java", "-cp", build, "WireTest"]
    run(java)
    commands = run([*java, "encode"], capture_output=True).stdout
    statuses = run([executable, "--wire"], input=commands, capture_output=True).stdout
    run([*java, "decode"], input=statuses)
    print("Host validation passed. BLE radio, Android lifecycle, and physical relay remain hardware tests.")
