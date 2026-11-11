/*
 * mikarun — Mika 沙箱启动器。
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) 2026 Mika authors
 *
 * 用法:
 *   libmikarun.so [--timeout <秒,1-3600>] [--net on|off] [--nice <0-19,默认10>]
 *                 [--max-procs <默认256>] [--max-file-mb <默认2048>]
 *                 [--pty [--pty-cols <1-1000,默认80>] [--pty-rows <1-1000,默认24>]
 *                  [--pty-size <文件>]] -- <程序> [参数…]
 *   libmikarun.so --self-test    输出 "mikarun ok"、"seccomp ok" / "seccomp unavailable: <原因>"、
 *                                "pty ok" / "pty unavailable: <原因>",退出 0
 *
 * 不给 --net 时按 off 处理(断网是默认值)。不给 --timeout 时不限时。
 * --pty 把命令接到一块伪终端上(控制终端),用于用户自己开的终端;不给时仍是普通管道。
 * --pty-size 指向一个普通文件,内容是 "列 行";运行中每 200ms 读一次,变了就用 TIOCSWINSZ
 * 改窗口大小(内核会给前台进程组发 SIGWINCH)。
 * 退出码:命令自身的退出码;超时 124;命令被信号 N 杀死 128+N;
 *         启动器自身出错 125,原因写在标准错误最后一行,以 "mikarun:" 开头。
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#ifdef __ANDROID__
#include <android/api-level.h>
#endif

#define EXIT_TIMEOUT 124
#define EXIT_LAUNCHER_ERROR 125

/* aarch64 的系统调用号(过滤器只认这一种架构) */
#define NR_IOCTL_AARCH64 29
#define NR_SOCKET_AARCH64 198
#define NR_IO_URING_SETUP_AARCH64 425
#define NR_PIDFD_SEND_SIGNAL_AARCH64 424
#define NR_PIDFD_OPEN_AARCH64 434
#define NR_CLOSE_RANGE_AARCH64 436
#define BINDER_WRITE_READ_CMD 0xc0306201u /* _IOWR('b', 1, struct binder_write_read),64 位 */

#define GRACE_MS 2000          /* SIGTERM 之后等多久再 SIGKILL */
#define POLL_MS 50
#define KILL_ROUNDS_MAX 200    /* SIGKILL 循环的上限(约 10 秒),防止异常情况下永远不退出 */
#define SCAN_MAX 32768

struct options {
	int self_test;
	long timeout_sec;  /* 0 = 不限时 */
	int net_off;
	long nice;
	long max_procs;
	long max_file_mb;
	int pty;           /* 1 = 给命令一块伪终端 */
	long pty_cols;     /* 1-1000,默认 80 */
	long pty_rows;     /* 1-1000,默认 24 */
	const char *pty_size_path; /* --pty-size:每 200ms 读一次 "列 行",变了就改窗口大小 */
	char **command; /* 以 NULL 结尾,指向 argv 内部 */
};

static int fail(const char *what, const char *detail)
{
	if (detail != NULL)
		fprintf(stderr, "mikarun: %s: %s\n", what, detail);
	else
		fprintf(stderr, "mikarun: %s\n", what);
	return EXIT_LAUNCHER_ERROR;
}

static int parse_long(const char *name, const char *text, long min, long max, long *out)
{
	char *end;
	long v;

	if (text == NULL)
		return fail("missing value for", name), -1;
	errno = 0;
	v = strtol(text, &end, 10);
	if (errno != 0 || end == text || *end != '\0' || v < min || v > max) {
		fprintf(stderr, "mikarun: invalid value for %s: %s (allowed %ld-%ld)\n", name, text, min, max);
		return -1;
	}
	*out = v;
	return 0;
}

/* 返回 0 成功;非 0 时已写好错误信息。 */
static int parse_options(int argc, char **argv, struct options *opts)
{
	int i = 1;

	memset(opts, 0, sizeof(*opts));
	opts->net_off = 1;
	opts->nice = 10;
	opts->max_procs = 256;
	opts->max_file_mb = 2048;
	opts->pty_cols = 80;
	opts->pty_rows = 24;
	for (; i < argc; i++) {
		const char *arg = argv[i];
		const char *value = i + 1 < argc ? argv[i + 1] : NULL;
		long *target = NULL;
		long min = 0, max = 0;

		if (strcmp(arg, "--") == 0) {
			i++;
			break;
		}
		if (strcmp(arg, "--self-test") == 0) {
			opts->self_test = 1;
			continue;
		}
		if (strcmp(arg, "--pty") == 0) {
			opts->pty = 1;
			continue;
		}
		if (strcmp(arg, "--net") == 0) {
			if (value != NULL && strcmp(value, "on") == 0)
				opts->net_off = 0;
			else if (value != NULL && strcmp(value, "off") == 0)
				opts->net_off = 1;
			else
				return fail("invalid value for --net (on|off)", value), -1;
			i++;
			continue;
		}
		if (strcmp(arg, "--pty-size") == 0) {
			if (value == NULL)
				return fail("missing value for --pty-size", NULL), -1;
			opts->pty = 1;
			opts->pty_size_path = value;
			i++;
			continue;
		}
		if (strcmp(arg, "--timeout") == 0) {
			target = &opts->timeout_sec; min = 1; max = 3600;
		} else if (strcmp(arg, "--nice") == 0) {
			target = &opts->nice; min = 0; max = 19;
		} else if (strcmp(arg, "--max-procs") == 0) {
			target = &opts->max_procs; min = 1; max = 65536;
		} else if (strcmp(arg, "--max-file-mb") == 0) {
			target = &opts->max_file_mb; min = 1; max = 1048576;
		} else if (strcmp(arg, "--pty-cols") == 0) {
			opts->pty = 1;
			target = &opts->pty_cols; min = 1; max = 1000;
		} else if (strcmp(arg, "--pty-rows") == 0) {
			opts->pty = 1;
			target = &opts->pty_rows; min = 1; max = 1000;
		}
		if (target != NULL) {
			if (parse_long(arg, value, min, max, target) != 0)
				return -1;
			i++;
			continue;
		}
		if (strncmp(arg, "--", 2) == 0)
			return fail("unknown option", arg), -1;
		break; /* 第一个非选项参数即命令 */
	}

	if (opts->self_test)
		return 0;
	if (i >= argc)
		return fail("missing command", NULL), -1;
	opts->command = &argv[i];
	return 0;
}

/* ---------- 断网:seccomp 过滤器 ---------- */

/*
 * 白名单:socket() 只放行 AF_UNIX(桥要用)与 AF_NETLINK(查本机网卡、路由等,不出本机),其余地址族 → EACCES;
 * io_uring_setup → ENOSYS(它能绕开 socket 过滤);binder 的 BINDER_WRITE_READ ioctl → EACCES
 * (经系统服务也能把数据带出去,PRoot 里本来也不绑 binder 设备,这里再拦一道);其余放行。
 * 非 aarch64 的系统调用(32 位兼容层)一律杀进程,防止换个调用号绕过。过滤器随 fork/exec 继承、不能撤销。
 */
static int install_net_filter(char *reason, size_t reason_len)
{
	struct sock_filter filter[] = {
		/* 0 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
		/* 1 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
		/* 2 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
		/* 3 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
		/* 4 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_IO_URING_SETUP_AARCH64, 0, 1),
		/* 5 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (ENOSYS & SECCOMP_RET_DATA)),
		/* 6 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_SOCKET_AARCH64, 2, 0),          /* → 9 */
		/* 7 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_IOCTL_AARCH64, 6, 0),           /* → 14 */
		/* 8 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
		/* 小端:参数的低 32 位在前;socket 的 domain、ioctl 的 cmd 都是 32 位,内核只看低 32 位 */
		/* 9 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
		/* 10 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 2, 0),                  /* → 13 */
		/* 11 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_NETLINK, 1, 0),               /* → 13 */
		/* 12 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
		/* 13 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
		/* 14 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
		/* 15 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, BINDER_WRITE_READ_CMD, 0, 1),
		/* 16 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
		/* 17 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
	};
	struct sock_fprog prog = {
		.len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
		.filter = filter,
	};

	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
		snprintf(reason, reason_len, "PR_SET_NO_NEW_PRIVS: %s", strerror(errno));
		return -1;
	}
	if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog, 0, 0) != 0) {
		snprintf(reason, reason_len, "PR_SET_SECCOMP: %s", strerror(errno));
		return -1;
	}
	return 0;
}

/* 在子进程里装过滤器并实测:AF_INET 被拒、AF_UNIX 可用。返回 0 可用,否则 reason 写原因。 */
static int probe_net_filter(char *reason, size_t reason_len)
{
	int fds[2];
	pid_t pid;
	int status;
	ssize_t n;

	if (pipe(fds) != 0) {
		snprintf(reason, reason_len, "pipe: %s", strerror(errno));
		return -1;
	}
	pid = fork();
	if (pid < 0) {
		snprintf(reason, reason_len, "fork: %s", strerror(errno));
		close(fds[0]);
		close(fds[1]);
		return -1;
	}
	if (pid == 0) {
		char msg[128] = "";
		int s;

		close(fds[0]);
		if (install_net_filter(msg, sizeof(msg)) == 0) {
			s = socket(AF_INET, SOCK_STREAM, 0);
			if (s >= 0)
				snprintf(msg, sizeof(msg), "filter installed but AF_INET socket still allowed");
			else if (errno != EACCES)
				snprintf(msg, sizeof(msg), "AF_INET socket failed with %s, expected EACCES", strerror(errno));
			else if ((s = socket(AF_UNIX, SOCK_STREAM, 0)) < 0)
				snprintf(msg, sizeof(msg), "AF_UNIX socket blocked: %s", strerror(errno));
		}
		if (msg[0] != '\0' && write(fds[1], msg, strlen(msg)) < 0)
			_exit(2);
		_exit(msg[0] == '\0' ? 0 : 1);
	}
	close(fds[1]);
	n = read(fds[0], reason, reason_len - 1);
	close(fds[0]);
	reason[n > 0 ? n : 0] = '\0';
	while (waitpid(pid, &status, 0) < 0 && errno == EINTR)
		;
	if (WIFSIGNALED(status)) {
		snprintf(reason, reason_len, "probe killed by signal %d", WTERMSIG(status));
		return -1;
	}
	if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
		if (reason[0] == '\0')
			snprintf(reason, reason_len, "probe failed");
		return -1;
	}
	return 0;
}

static int pty_self_test(char *reason, size_t reason_len);

static int self_test(void)
{
	char reason[160];

	if (printf("mikarun ok\n") < 0 || fflush(stdout) != 0)
		return fail("self-test", "cannot write stdout");
	if (probe_net_filter(reason, sizeof(reason)) == 0)
		printf("seccomp ok\n");
	else
		printf("seccomp unavailable: %s\n", reason);
	if (pty_self_test(reason, sizeof(reason)) == 0)
		printf("pty ok\n");
	else
		printf("pty unavailable: %s\n", reason);
	if (fflush(stdout) != 0)
		return fail("self-test", "cannot write stdout");
	return 0;
}

/* ---------- /proc 扫描 ---------- */

/*
 * 安卓给 App 装的 seccomp 策略对它不认识的系统调用发 SIGSYS(直接杀进程),而不是返回 ENOSYS。
 * pidfd_open/pidfd_send_signal 从 API 31、close_range 从 API 34 起才在策略里,更早的系统上不能试。
 */
static int api_level(void)
{
#ifdef __ANDROID__
	return android_get_device_api_level();
#elif defined(MIKARUN_HOST_API_LEVEL)
	return MIKARUN_HOST_API_LEVEL; /* 只用于在普通 Linux 上测退路 */
#else
	return 10000;
#endif
}

/* 读 /proc/<pid>/stat 里的父进程号与线程数。返回 0 成功。 */
static int read_stat(int proc_fd, const char *pid_name, pid_t *ppid, long *threads)
{
	char path[64], buf[1024];
	const char *p;
	ssize_t n;
	int fd, pp;
	long nt;
	char state;

	snprintf(path, sizeof(path), "%s/stat", pid_name);
	fd = openat(proc_fd, path, O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return -1;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return -1;
	buf[n] = '\0';
	p = strrchr(buf, ')'); /* 进程名里可能有空格和括号,从最后一个 ')' 往后读 */
	if (p == NULL)
		return -1;
	/* 字段 3 state、4 ppid,……,20 num_threads */
	if (sscanf(p + 2, "%c %d %*d %*d %*d %*d %*u %*u %*u %*u %*u %*u %*u %*d %*d %*d %*d %ld",
		   &state, &pp, &nt) != 3)
		return -1;
	*ppid = pp;
	*threads = nt;
	return 0;
}

static int is_pid_name(const char *s)
{
	if (*s == '\0')
		return 0;
	for (; *s != '\0'; s++)
		if (*s < '0' || *s > '9')
			return 0;
	return 1;
}

/* 本身份(uid)当前的任务数(线程也算,RLIMIT_NPROC 按任务计数)。 */
static long count_user_tasks(void)
{
	DIR *dir = opendir("/proc");
	struct dirent *e;
	uid_t uid = getuid();
	long total = 0;

	if (dir == NULL)
		return -1;
	while ((e = readdir(dir)) != NULL) {
		struct stat st;
		pid_t ppid;
		long threads;

		if (!is_pid_name(e->d_name))
			continue;
		if (fstatat(dirfd(dir), e->d_name, &st, 0) != 0 || st.st_uid != uid)
			continue;
		if (read_stat(dirfd(dir), e->d_name, &ppid, &threads) == 0)
			total += threads;
	}
	closedir(dir);
	return total;
}

static pid_t scan_pid[SCAN_MAX];
static pid_t scan_ppid[SCAN_MAX];
static unsigned char scan_mark[SCAN_MAX];
static int scan_count;
static int pidfd_usable = -1; /* -1 未知,0 不可用,1 可用 */

/* 再读一次 /proc/<pid>/stat:父进程仍是本进程,或是本轮扫描认定的后代。 */
static int still_descendant(pid_t pid)
{
	char name[32];
	pid_t ppid;
	long threads;
	int i;

	snprintf(name, sizeof(name), "/proc/%d", (int)pid);
	if (read_stat(AT_FDCWD, name, &ppid, &threads) != 0)
		return 0;
	if (ppid == getpid())
		return 1;
	for (i = 0; i < scan_count; i++)
		if (scan_mark[i] && scan_pid[i] == ppid)
			return 1;
	return 0;
}

/*
 * 扫描与发信号之间,进程号可能已被回收并复用给同一身份(Mika 自己)的别的进程。
 * 有 pidfd 时:先 pidfd_open 钉住进程身份,再确认它仍是后代,然后经 pidfd 发信号——不会误杀。
 * 没有 pidfd 时只能在 kill 前再确认一次(窗口很小);kill_tree 另外先 SIGSTOP 整棵树再重扫,
 * 停住的进程不会再回收子进程,重扫后的进程号就稳定了。
 */
static void send_to_descendant(pid_t pid, int sig)
{
	if (pidfd_usable < 0)
		pidfd_usable = api_level() >= 31;
	if (pidfd_usable) {
		int fd = (int)syscall(NR_PIDFD_OPEN_AARCH64, pid, 0);
		if (fd >= 0) {
			if (still_descendant(pid))
				syscall(NR_PIDFD_SEND_SIGNAL_AARCH64, fd, sig, NULL, 0);
			close(fd);
			return;
		}
		if (errno == ESRCH)
			return;
		pidfd_usable = 0; /* 内核太旧(ENOSYS)等:改走下面的退路 */
	}
	if (still_descendant(pid))
		kill(pid, sig);
}

/*
 * 找出父进程链最终指向本进程的所有进程(本进程是子进程收割者,脱离父进程的后代也会挂回来),
 * sig 非 0 时给它们发信号。返回找到的个数(含尚未被回收的僵尸);扫描表装满时多算一个,当作「还有」。
 */
static int signal_descendants(int sig)
{
	DIR *dir = opendir("/proc");
	struct dirent *e;
	pid_t self = getpid();
	int n = 0, found = 0, full, changed, i;

	if (dir == NULL)
		return 1; /* 看不到就当还有,让调用方继续等 */
	while ((e = readdir(dir)) != NULL && n < SCAN_MAX) {
		pid_t ppid;
		long threads;

		if (!is_pid_name(e->d_name))
			continue;
		if (read_stat(dirfd(dir), e->d_name, &ppid, &threads) != 0)
			continue;
		scan_pid[n] = (pid_t)atoi(e->d_name);
		scan_ppid[n] = ppid;
		scan_mark[n] = ppid == self;
		n++;
	}
	full = e != NULL; /* 还没读完就满了 */
	closedir(dir);

	do {
		changed = 0;
		for (i = 0; i < n; i++) {
			int j;
			if (scan_mark[i])
				continue;
			for (j = 0; j < n; j++) {
				if (scan_mark[j] && scan_pid[j] == scan_ppid[i]) {
					scan_mark[i] = 1;
					changed = 1;
					break;
				}
			}
		}
	} while (changed);

	scan_count = n;
	for (i = 0; i < n; i++) {
		if (!scan_mark[i])
			continue;
		found++;
		if (sig != 0)
			send_to_descendant(scan_pid[i], sig);
	}
	return found + full;
}

/* ---------- 运行与清场 ---------- */

/* 回收所有已结束的直接子进程(包括挂回来的孤儿);主子进程的状态记下来。 */
static void reap(pid_t child, int *status, int *have_status)
{
	pid_t p;
	int st;

	while ((p = waitpid(-1, &st, WNOHANG)) > 0) {
		if (p == child) {
			*status = st;
			*have_status = 1;
		}
	}
}

static void sleep_ms(long ms)
{
	struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
	while (nanosleep(&ts, &ts) != 0 && errno == EINTR)
		;
}

/* 先给进程组和所有后代发 SIGTERM,等 GRACE_MS;还在的后代 SIGKILL,循环到一个不剩。 */
static void kill_tree(pid_t child, int *status, int *have_status)
{
	long waited;
	int round;

	reap(child, status, have_status);
	if (signal_descendants(0) == 0)
		return;
	kill(-getpid(), SIGTERM); /* 本进程也在组里;SIGTERM 已被屏蔽,不影响自己 */
	signal_descendants(SIGTERM);
	for (waited = 0; waited < GRACE_MS; waited += POLL_MS) {
		sleep_ms(POLL_MS);
		reap(child, status, have_status);
		if (signal_descendants(0) == 0)
			return;
	}
	for (round = 0; round < KILL_ROUNDS_MAX; round++) {
		if (!pidfd_usable)
			signal_descendants(SIGSTOP); /* 先冻住整棵树,下面重扫得到的进程号不会再变 */
		if (signal_descendants(SIGKILL) == 0)
			return;
		sleep_ms(POLL_MS);
		reap(child, status, have_status);
	}
	fprintf(stderr, "mikarun: warning: descendants still present after SIGKILL\n");
}

static long now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/*
 * PRoot 在 PROOT_LOADER 未设置时会把内嵌的载入器写进临时目录再执行(安卓禁止执行数据目录里的文件),
 * 所以启动 libproot.so 前要求 PROOT_LOADER 指向与启动器同目录(nativeLibraryDir)里的文件。
 * 返回 0 通过;非 0 时已写好错误信息。
 */
static int check_proot_loader(const char *program)
{
	char path[PATH_MAX], self[PATH_MAX], loader[PATH_MAX], self_dir[PATH_MAX];
	const char *env = getenv("PROOT_LOADER");
	ssize_t n;

	snprintf(path, sizeof(path), "%s", program);
	if (strcmp(basename(path), "libproot.so") != 0)
		return 0;
	if (env == NULL || env[0] == '\0' || realpath(env, loader) == NULL)
		return fail("PROOT_LOADER missing", env);
	n = readlink("/proc/self/exe", self, sizeof(self) - 1);
	if (n < 0)
		return fail("cannot read /proc/self/exe", strerror(errno));
	self[n] = '\0';
	/* bionic 的 dirname 返回共用的缓冲区,先拷出一份 */
	snprintf(self_dir, sizeof(self_dir), "%s", dirname(self));
	snprintf(path, sizeof(path), "%s", loader);
	if (strcmp(basename(path), "libproot_loader.so") != 0)
		return fail("PROOT_LOADER missing", "not libproot_loader.so");
	if (strcmp(self_dir, dirname(loader)) != 0)
		return fail("PROOT_LOADER missing", "not in the launcher directory");
	return 0;
}

/* 文件大小与进程数上限;软硬限一起设,且不超过原有硬限。 */
static int set_limit(int resource, rlim_t value, const char *name)
{
	struct rlimit rl;

	if (getrlimit(resource, &rl) != 0)
		return fail(name, strerror(errno));
	if (rl.rlim_max != RLIM_INFINITY && value > rl.rlim_max)
		value = rl.rlim_max;
	rl.rlim_cur = value;
	rl.rlim_max = value;
	if (setrlimit(resource, &rl) != 0)
		return fail(name, strerror(errno));
	return 0;
}

/* oom_score_adj 只能调高(普通身份);写不进去就算了。 */
static void set_oom_score_adj(const char *value)
{
	int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
	if (fd >= 0) {
		ssize_t ignored = write(fd, value, strlen(value));
		(void)ignored;
		close(fd);
	}
}

/* 关掉 0/1/2 以外继承来的描述符,命令拿不到 Mika 的任何文件或套接字。 */
static void close_inherited_fds(void)
{
	DIR *dir;
	struct dirent *e;

	if (api_level() >= 34 && syscall(NR_CLOSE_RANGE_AARCH64, 3, ~0U, 0) == 0)
		return;
	dir = opendir("/proc/self/fd");
	if (dir == NULL)
		return;
	while ((e = readdir(dir)) != NULL) {
		int fd;
		if (!is_pid_name(e->d_name))
			continue;
		fd = atoi(e->d_name);
		if (fd >= 3 && fd != dirfd(dir))
			close(fd);
	}
	closedir(dir);
}

/* ---------- 伪终端 ---------- */

/* 写满 [len] 字节;被信号打断就接着写。返回 0 成功。 */
static int write_all(int fd, const char *buf, size_t len)
{
	while (len > 0) {
		ssize_t n = write(fd, buf, len);

		if (n < 0) {
			if (errno == EINTR)
				continue;
			return -1;
		}
		buf += n;
		len -= (size_t)n;
	}
	return 0;
}

/* 建一对 pty;[cols]/[rows] 已限定在 1-1000。失败时 reason 写原因。 */
static int open_pty(int *master, int *slave, long cols, long rows, char *reason, size_t reason_len)
{
	struct winsize ws;
	char *name;
	int m, s;

	m = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
	if (m < 0) {
		snprintf(reason, reason_len, "posix_openpt: %s", strerror(errno));
		return -1;
	}
	if (grantpt(m) != 0) {
		snprintf(reason, reason_len, "grantpt: %s", strerror(errno));
		close(m);
		return -1;
	}
	if (unlockpt(m) != 0) {
		snprintf(reason, reason_len, "unlockpt: %s", strerror(errno));
		close(m);
		return -1;
	}
	name = ptsname(m);
	if (name == NULL) {
		snprintf(reason, reason_len, "ptsname: %s", strerror(errno));
		close(m);
		return -1;
	}
	s = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
	if (s < 0) {
		snprintf(reason, reason_len, "open %s: %s", name, strerror(errno));
		close(m);
		return -1;
	}
	memset(&ws, 0, sizeof(ws));
	ws.ws_col = (unsigned short)cols;
	ws.ws_row = (unsigned short)rows;
	if (ioctl(s, TIOCSWINSZ, &ws) != 0) {
		snprintf(reason, reason_len, "TIOCSWINSZ: %s", strerror(errno));
		close(s);
		close(m);
		return -1;
	}
	*master = m;
	*slave = s;
	return 0;
}

static int pty_self_test(char *reason, size_t reason_len)
{
	int master = -1, slave = -1;

	if (open_pty(&master, &slave, 80, 24, reason, reason_len) != 0)
		return -1;
	if (!isatty(slave)) {
		snprintf(reason, reason_len, "slave is not a tty");
		close(slave);
		close(master);
		return -1;
	}
	close(slave);
	close(master);
	return 0;
}

/*
 * 读 --pty-size 文件("列 行");变了就 ioctl(TIOCSWINSZ),内核会给前台进程组发 SIGWINCH。
 * 文件不存在、内容不完整或数值越界时什么也不做,下一轮再试。
 */
static void apply_pty_size(int master, const char *path, long *cols, long *rows)
{
	char buf[64];
	struct winsize ws;
	long c, r;
	ssize_t n;
	int fd;

	if (path == NULL || *path == '\0')
		return;
	fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return;
	buf[n] = '\0';
	if (sscanf(buf, "%ld %ld", &c, &r) != 2)
		return;
	if (c < 1 || c > 1000 || r < 1 || r > 1000 || (c == *cols && r == *rows))
		return;
	memset(&ws, 0, sizeof(ws));
	ws.ws_col = (unsigned short)c;
	ws.ws_row = (unsigned short)r;
	if (ioctl(master, TIOCSWINSZ, &ws) == 0) {
		*cols = c;
		*rows = r;
	}
}

/*
 * pty 模式下的等待循环:把 stdin 转给 master、把 master 的输出转给 stdout,同时照看子进程和信号。
 * 子进程退出、超时或被 SIGTERM 打断后,再把从端里剩下的字读完(最多 200 毫秒),不丢最后一行。
 */
static void relay_pty(int master, pid_t child, const struct options *opts,
	int *status, int *have_status, int *timed_out, int *terminated, sigset_t *wait_set)
{
	char buf[8192];
	int master_open = 1;
	int stdin_open = 1;
	long deadline = 0;
	long last_cols = opts->pty_cols;
	long last_rows = opts->pty_rows;
	long last_size_check = 0;

	if (opts->timeout_sec > 0)
		deadline = now_ms() + opts->timeout_sec * 1000L;

	for (;;) {
		struct pollfd fds[2];
		struct timespec zero = { 0, 0 };
		int timeout = POLL_MS;
		int pr, i, sig;
		long now = now_ms();

		if (opts->pty_size_path != NULL && now - last_size_check >= 200) {
			apply_pty_size(master, opts->pty_size_path, &last_cols, &last_rows);
			last_size_check = now;
		}
		reap(child, status, have_status);
		if (*have_status)
			break;
		if (deadline != 0) {
			long left = deadline - now_ms();

			if (left <= 0) {
				*timed_out = 1;
				break;
			}
			if (left < timeout)
				timeout = (int)left;
		}

		fds[0].fd = master_open ? master : -1;
		fds[0].events = POLLIN;
		fds[0].revents = 0;
		fds[1].fd = stdin_open ? STDIN_FILENO : -1;
		fds[1].events = POLLIN;
		fds[1].revents = 0;
		pr = poll(fds, 2, timeout);
		if (pr < 0 && errno != EINTR)
			break;
		if (pr > 0) {
			for (i = 0; i < 2; i++) {
				ssize_t n;

				if (fds[i].fd < 0 || fds[i].revents == 0)
					continue;
				if (fds[i].fd == master) {
					n = read(master, buf, sizeof(buf));
					if (n > 0) {
						if (write_all(STDOUT_FILENO, buf, (size_t)n) != 0)
							master_open = 0;
					} else if (n == 0 || (errno != EINTR && errno != EAGAIN)) {
						master_open = 0; /* EIO:子进程关掉了从端 */
					}
				} else {
					n = read(STDIN_FILENO, buf, sizeof(buf));
					if (n > 0) {
						if (write_all(master, buf, (size_t)n) != 0)
							stdin_open = 0;
					} else if (n == 0 || (errno != EINTR && errno != EAGAIN)) {
						stdin_open = 0;
						/* stdin 关了:给 shell 一个 EOF,让它自己退出;从端还开着就还能收输出 */
						if (master_open && write_all(master, "\004", 1) != 0)
							master_open = 0;
					}
				}
			}
		}
		/* 不阻塞地看一眼有没有要处理的信号;SIGCHLD 下一轮 reap */
		sig = sigtimedwait(wait_set, NULL, &zero);
		if (sig == SIGTERM || sig == SIGINT || sig == SIGHUP) {
			*terminated = sig;
			break;
		}
	}

	while (master_open) {
		struct pollfd pfd;
		ssize_t n;

		pfd.fd = master;
		pfd.events = POLLIN;
		pfd.revents = 0;
		if (poll(&pfd, 1, 200) <= 0)
			break;
		n = read(master, buf, sizeof(buf));
		if (n <= 0)
			break;
		if (write_all(STDOUT_FILENO, buf, (size_t)n) != 0)
			break;
	}
}

/* 自成会话、收割者、父死信号、优先级、OOM 分数、资源上限、断网。返回 0 成功。 */
static int prepare(const struct options *opts)
{
	pid_t parent = getppid();
	char reason[160];
	long used;

	/* 父进程在读到 PPID 之前就没了:已被过继给 init,父死信号再也不会来 */
	if (parent == 1)
		return fail("parent exited", NULL);
	if (prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0) != 0)
		return fail("PR_SET_PDEATHSIG", strerror(errno));
	if (getppid() != parent) /* 设置之前父进程就已经没了 */
		return fail("parent exited", NULL);
	if (setsid() < 0 && getsid(0) != getpid())
		return fail("setsid", strerror(errno));
	if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0)
		return fail("PR_SET_CHILD_SUBREAPER", strerror(errno));

	/*
	 * 优先级与 OOM 分数只是保护 Mika 自身,做不到不拦命令。启动器自己 900、命令 1000(子进程里再调):
	 * 内存不够时先杀命令,启动器活着才能清场。
	 */
	setpriority(PRIO_PROCESS, 0, (int)opts->nice);
	set_oom_score_adj("900");

	if (set_limit(RLIMIT_FSIZE, (rlim_t)opts->max_file_mb << 20, "RLIMIT_FSIZE") != 0)
		return -1;
	/*
	 * RLIMIT_NPROC 由内核按身份(uid)整体计数:Mika 的线程、同时在跑的其它沙箱命令都占同一个额度,
	 * 所以上限 = 设置时本身份已有的任务数 + max-procs,而不是单给这条命令 max-procs 个。
	 */
	used = count_user_tasks();
	if (used < 0)
		return fail("cannot count processes", strerror(errno));
	if (set_limit(RLIMIT_NPROC, (rlim_t)(used + opts->max_procs), "RLIMIT_NPROC") != 0)
		return -1;

	if (opts->net_off && install_net_filter(reason, sizeof(reason)) != 0)
		return fail("network isolation unavailable", reason);
	return 0;
}

static int run(const struct options *opts)
{
	sigset_t wait_set, orig_set;
	int status = 0, have_status = 0, timed_out = 0, terminated = 0;
	int pty_master = -1, pty_slave = -1;
	char pty_reason[160];
	long deadline = 0;
	pid_t child;

	if (check_proot_loader(opts->command[0]) != 0)
		return EXIT_LAUNCHER_ERROR;
	/* 先屏蔽要等的信号,再做别的,避免信号在 sigtimedwait 之前到来而丢失 */
	sigemptyset(&wait_set);
	sigaddset(&wait_set, SIGCHLD);
	sigaddset(&wait_set, SIGTERM);
	sigaddset(&wait_set, SIGINT);
	sigaddset(&wait_set, SIGHUP);
	if (sigprocmask(SIG_BLOCK, &wait_set, &orig_set) != 0)
		return fail("sigprocmask", strerror(errno));
	if (prepare(opts) != 0)
		return EXIT_LAUNCHER_ERROR;
	if (opts->pty && open_pty(&pty_master, &pty_slave, opts->pty_cols, opts->pty_rows, pty_reason, sizeof(pty_reason)) != 0)
		return fail("pty", pty_reason);

	child = fork();
	if (child < 0) {
		if (opts->pty) {
			close(pty_slave);
			close(pty_master);
		}
		return fail("fork", strerror(errno));
	}
	if (child == 0) {
		sigprocmask(SIG_SETMASK, &orig_set, NULL);
		/*
		 * 启动器被直接 SIGKILL 时没机会清场:PRoot 靠 PDEATHSIG 跟着死,PRoot 打了补丁带
		 * PTRACE_O_EXITKILL,它一死所有被跟踪的进程都被内核杀掉。Mika 整个进程被系统杀掉时,
		 * 安卓按 App 的 cgroup 把同一 cgroup 里的进程(包括这里所有沙箱进程)一起杀掉,这是最后一道。
		 */
		prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
		set_oom_score_adj("1000");
		if (opts->pty) {
			/*
			 * 父进程在 prepare() 里已经 setsid();子进程另建一个会话,才能把从端设成控制终端。
			 * 从端在 fork 前就打开了,这里不再经过文件系统。
			 */
			if (setsid() < 0 && getsid(0) != getpid()) {
				fprintf(stderr, "mikarun: pty: setsid: %s\n", strerror(errno));
				_exit(EXIT_LAUNCHER_ERROR);
			}
			if (ioctl(pty_slave, TIOCSCTTY, 0) != 0) {
				fprintf(stderr, "mikarun: pty: TIOCSCTTY: %s\n", strerror(errno));
				_exit(EXIT_LAUNCHER_ERROR);
			}
			if (dup2(pty_slave, STDIN_FILENO) < 0 || dup2(pty_slave, STDOUT_FILENO) < 0 ||
			    dup2(pty_slave, STDERR_FILENO) < 0) {
				fprintf(stderr, "mikarun: pty: dup2: %s\n", strerror(errno));
				_exit(EXIT_LAUNCHER_ERROR);
			}
			close(pty_slave);
			close(pty_master);
		}
		close_inherited_fds();
		execv(opts->command[0], opts->command);
		fprintf(stderr, "mikarun: exec failed: %s\n", strerror(errno));
		_exit(EXIT_LAUNCHER_ERROR);
	}

	if (opts->pty) {
		close(pty_slave);
		relay_pty(pty_master, child, opts, &status, &have_status, &timed_out, &terminated, &wait_set);
		close(pty_master);
	} else {
		if (opts->timeout_sec > 0)
			deadline = now_ms() + opts->timeout_sec * 1000L;
		for (;;) {
			struct timespec ts, *tsp = NULL;
			int sig;

			reap(child, &status, &have_status);
			if (have_status)
				break;
			if (deadline != 0) {
				long left = deadline - now_ms();
				if (left <= 0) {
					timed_out = 1;
					break;
				}
				ts.tv_sec = left / 1000;
				ts.tv_nsec = (left % 1000) * 1000000L;
				tsp = &ts;
			}
			sig = sigtimedwait(&wait_set, NULL, tsp);
			if (sig == SIGTERM || sig == SIGINT || sig == SIGHUP) {
				terminated = sig;
				break;
			}
			/* SIGCHLD、超时(EAGAIN)、EINTR:回到循环开头重新检查 */
		}
	}

	/* 命令正常结束时也清一遍:留在后台的进程不能活过这条命令 */
	kill_tree(child, &status, &have_status);
	if (timed_out)
		return EXIT_TIMEOUT;
	if (have_status) {
		if (WIFEXITED(status))
			return WEXITSTATUS(status);
		if (WIFSIGNALED(status))
			return 128 + WTERMSIG(status);
	}
	return 128 + terminated;
}

int main(int argc, char **argv)
{
	struct options opts;

	if (parse_options(argc, argv, &opts) != 0)
		return EXIT_LAUNCHER_ERROR;
	if (opts.self_test)
		return self_test();
	return run(&opts);
}
