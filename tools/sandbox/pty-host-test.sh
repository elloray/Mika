#!/bin/sh
# 主机侧 pty 验证（Linux，不是安卓）：编译 mikarun 的 host 版本，在 PRoot 里跑一个 shell，
# 检查 tty、窗口大小（含运行中改大小）、输出转发和 Ctrl+C。只用于开发，不进 APK。
# 用法（在 Alpine 容器里）：
#   docker run --rm --cap-add=SYS_PTRACE --security-opt seccomp=unconfined \
#     -v "$PWD:/src:ro" alpine:3.22 sh /src/tools/sandbox/pty-host-test.sh
set -eu

SRC="${SRC:-/src}"
CC="${CC:-gcc}"
BIN=/tmp/mikarun

apk add --no-cache build-base linux-headers python3 >/dev/null 2>&1
# proot 不在 3.22 的 main/community 里，用 edge/community 的 5.4.0（和本仓库同一个上游版本）。
apk add --no-cache --repository=https://dl-cdn.alpinelinux.org/alpine/edge/community proot >/dev/null 2>&1
"$CC" -O2 -Wall -Wextra -Werror -DMIKARUN_HOST_API_LEVEL=30 -o "$BIN" "$SRC/app/src/main/cpp/mikarun/mikarun.c"
echo "COMPILE_OK"

"$BIN" --self-test

python3 - "$BIN" <<'PY'
import queue, re, subprocess, sys, threading, time

bin = sys.argv[1]
size_path = "/tmp/mika-pty-size"
with open(size_path, "w") as f:
    f.write("80 24")
cmd = [bin, "--pty", "--pty-cols", "80", "--pty-rows", "24", "--pty-size", size_path, "--net", "on",
       "--", "/usr/bin/proot", "-0", "-r", "/", "-b", "/proc", "-b", "/sys", "-b", "/dev", "-w", "/", "/bin/sh"]

def start():
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    q = queue.Queue()

    def read():
        while True:
            b = p.stdout.read(1)
            if not b:
                break
            q.put(b)

    threading.Thread(target=read, daemon=True).start()
    return p, q

def expect(q, needle, timeout=10):
    data = b""
    end = time.time() + timeout
    while time.time() < end:
        try:
            data += q.get(timeout=0.2)
        except queue.Empty:
            pass
        if needle.encode() in data:
            return data.decode("utf-8", "replace")
    raise AssertionError("missing %r; got %r" % (needle, data.decode("utf-8", "replace")))

def run(script, timeout=15):
    p, q = start()
    p.stdin.write(script)
    p.stdin.flush()
    out = b""
    end = time.time() + timeout
    while time.time() < end:
        try:
            out += q.get(timeout=0.2)
        except queue.Empty:
            pass
        if p.poll() is not None:
            while True:
                try:
                    out += q.get_nowait()
                except queue.Empty:
                    break
            break
    p.wait(timeout=timeout)
    return out.decode("utf-8", "replace"), p.returncode

out, rc = run(b"tty\nstty size\necho MARK_$((1+1))\nexit\n")
print(out)
assert "/dev/pts/" in out, "no tty: " + out
assert re.search(r"^24 80\r?$", out, re.M), "wrong size: " + out
assert "MARK_2" in out, "missing echo: " + out
print("PTY_BASIC_OK")

with open(size_path, "w") as f:
    f.write("80 24")
p, q = start()
try:
    p.stdin.write(b"stty size\n")
    p.stdin.flush()
    expect(q, "24 80")
    with open(size_path, "w") as f:
        f.write("100 40")
    time.sleep(0.6)
    p.stdin.write(b"stty size\n")
    p.stdin.flush()
    expect(q, "40 100")
    print("PTY_RESIZE_OK")
finally:
    p.stdin.write(b"exit\n")
    p.stdin.flush()
    p.wait(timeout=5)

p, q = start()
p.stdin.write(b"sleep 30\n")
p.stdin.flush()
time.sleep(1)
t0 = time.time()
p.stdin.write(b"\x03")
p.stdin.flush()
time.sleep(0.5)
p.stdin.write(b"echo AFTER_$((2+2))\nexit\n")
p.stdin.flush()
out = expect(q, "AFTER_4", 10)
elapsed = time.time() - t0
print(out)
assert elapsed < 9, "sleep was not interrupted (took %.1fs)" % elapsed
p.wait(timeout=5)
print("PTY_SIGNAL_OK")
PY
