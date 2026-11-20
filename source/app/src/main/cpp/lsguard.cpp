// ============================================================================
//  灵手 · NDK 双进程守护 native 层
//  com.byd.ls  /  DiLink 4.0 (Android 10)
//
//  作用：从 App 进程 fork 出一个脱离 ART 的原生守护进程，脱离后它不受 GC / ANR /
//  Java 层崩溃影响，专门盯着"灵手是否还活着"，一旦被杀就用 am 命令拉回来。
//
//  与 Java 层 GuardService(:guard 进程) 构成互补：
//    · Java 互绑      —— 反应最快，防单进程被杀
//    · 本 native 守护 —— 防两个 Java 进程同时被杀
//    · AlarmManager   —— 防整个应用被按 UID 整组清理
//
//  ⚠️ 诚实说明能力边界：如果比亚迪的"应用加速管理"是按 UID 对整个进程组下 kill -9，
//  那么本守护进程（同样跑在 App UID 下）会一起被杀。此时兜底靠 AlarmManager 心跳，
//  以及 AdbManager.installWatchdogScript() 装到 /data/local/tmp 的 shell 侧看门狗
//  （那个跑在 UID 2000，不属于本 App，能把它捞回来）。
//
//  fork 安全说明：父进程是 ART 多线程进程，fork 之后到 exec 之前只能调用
//  async-signal-safe 函数。因此孙子进程里只用了 setsid/open/read/write/close/
//  execl/_exit 这一类调用，没有 malloc、没有 JNI、没有 C++ 异常。
// ============================================================================

#include <jni.h>
#include <android/log.h>

#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <stdint.h>
#include <stddef.h>
#include <pthread.h>

#define LOG_TAG "LsGuardNative"
#define GUARD_SOCKET "com.byd.ls.guard.lock.v3"
#define GUARD_NAME   "ls.guard"

static int g_lock_fd = -1;
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ----------------------------------------------------------------------------
//  基础工具
// ----------------------------------------------------------------------------
static bool file_exists(const char *path) {
    if (path == nullptr || path[0] == '\0') return false;
    struct stat st;
    return stat(path, &st) == 0;
}

static void write_text_file(const char *path, const char *text) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    size_t len = strlen(text);
    ssize_t ignored = write(fd, text, len);
    (void) ignored;
    close(fd);
}

static void append_log(const char *logFile, const char *line) {
    if (logFile == nullptr || logFile[0] == '\0') return;
    int fd = open(logFile, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) return;

    // 简易体积控制：超过 128KB 就清空重写，避免无限增长
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size > 128 * 1024) {
        close(fd);
        fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd < 0) return;
    }

    time_t now = time(nullptr);
    struct tm tmv;
    localtime_r(&now, &tmv);
    char head[48];
    int n = snprintf(head, sizeof(head), "%02d-%02d %02d:%02d:%02d daemon[%d]: ",
                     tmv.tm_mon + 1, tmv.tm_mday, tmv.tm_hour, tmv.tm_min, tmv.tm_sec,
                     (int) getpid());
    if (n > 0) { ssize_t ig = write(fd, head, (size_t) n); (void) ig; }
    ssize_t ig2 = write(fd, line, strlen(line));
    (void) ig2;
    ssize_t ig3 = write(fd, "\n", 1);
    (void) ig3;
    close(fd);
}

/**
 * 灵手是否还有任何一个 App 进程活着。
 *
 * 做法：扫 /proc/<pid>/cmdline，匹配 "com.byd.ls" 或 "com.byd.ls:xxx"。
 * 以 App UID 运行时，别的进程的 cmdline 读不到（EACCES）→ 直接跳过，
 * 这类失败开销极小，不影响 2 秒一轮的扫描节奏。
 *
 * ⚠️ 关键点：守护进程本身是 App 进程 fork 出来的，cmdline 仍是 "com.byd.ls"。
 * 若不排除自己，扫描永远能命中自己，app_alive 恒为 true，保活拉起逻辑形同虚设。
 * 因此必须传入守护自身的 pid 并跳过。
 */
static bool app_alive(const char *pkg, pid_t selfPid) {
    if (pkg == nullptr || pkg[0] == '\0') return true;
    const size_t plen = strlen(pkg);
    char selfBuf[16];
    snprintf(selfBuf, sizeof(selfBuf), "%d", (int) selfPid);

    DIR *d = opendir("/proc");
    if (d == nullptr) return true;   // 读不到 /proc 就当作活着，宁可漏拉不可狂拉

    bool found = false;
    char path[96];
    char buf[256];
    struct dirent *e;

    while ((e = readdir(d)) != nullptr) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        if (strcmp(e->d_name, selfBuf) == 0) continue;   // 跳过自己（守护进程）

        snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;

        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';

        if ((size_t) n >= plen && memcmp(buf, pkg, plen) == 0) {
            const char c = buf[plen];
            if (c == '\0' || c == ':') { found = true; break; }
        }
    }
    closedir(d);
    return found;
}

/**
 * 执行一条 shell 命令。
 *
 * 不用 system()：它会在守护进程里安装 SIGCHLD/SIGINT 处理器，和我们自己的信号管理打架。
 * 改成再 fork 一次 + execl("/system/bin/sh")，并限时回收，避免留下僵尸进程。
 */
static void run_shell(const char *cmd, int timeoutDeciSec) {
    pid_t p = fork();
    if (p < 0) {
        LOGW("fork 执行命令失败: %s", strerror(errno));
        return;
    }
    if (p == 0) {
        // 子进程：立刻 exec，不做任何多余调用
        execl("/system/bin/sh", "sh", "-c", cmd, (char *) nullptr);
        _exit(127);
    }

    int status = 0;
    for (int i = 0; i < timeoutDeciSec; ++i) {
        pid_t r = waitpid(p, &status, WNOHANG);
        if (r == p) return;
        if (r < 0) return;
        usleep(100 * 1000);
    }
    // 超时强杀，防止 am 卡住拖死守护循环
    kill(p, SIGKILL);
    waitpid(p, &status, WNOHANG);
}

/**
 * 把组件列表逐个拉起。
 * compsCsv 形如：
 *   "com.byd.ls/com.byd.ls.service.GlobalGestureService,com.byd.ls/com.byd.ls.service.GuardService"
 * 前台服务用 start-foreground-service（Android 10 后台启动普通 service 受限），其余用 start-service。
 */
static void restart_components(const char *compsCsv, const char *logFile) {
    (void) compsCsv;
    // 服务的 android:exported=false，shell 直接 am start-foreground-service 会被
    // "Requires permission not exported" 拦住。改为显式广播，由应用进程本地拉起。
    const char *bcmd =
        "am broadcast -a com.byd.ls.action.RESTART "
        "-n com.byd.ls/com.byd.ls.receiver.RestartReceiver";
    append_log(logFile, bcmd);
    run_shell(bcmd, 30);

    // 广播被系统限制时的兜底（可能失败，仅作最后尝试）
    const char *fgs =
        "am start-foreground-service -n com.byd.ls/com.byd.ls.service.GlobalGestureService";
    append_log(logFile, fgs);
    run_shell(fgs, 30);
}

// ----------------------------------------------------------------------------
//  单例锁 / 子进程回收
// ----------------------------------------------------------------------------
/**
 * 用 abstract socket 做单例锁：
 *  · bind 成功 = 当前进程是唯一守护，把 fd 一直持有到退出；
 *  · bind 失败（EADDRINUSE）= 已有守护在跑，调用方直接退出；
 *  · abstract 命名空间随进程退出自动释放，不会留下需要清理的 socket 文件。
 */
static int try_acquire_lock(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    // abstract socket：sun_path[0] = '\0'
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, GUARD_SOCKET, sizeof(addr.sun_path) - 2);

    socklen_t len = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + strlen(GUARD_SOCKET));
    if (bind(fd, (struct sockaddr *) &addr, len) != 0) {
        close(fd);
        return -1;
    }
    // 必须 listen：否则其他进程 connect 会 ECONNREFUSED，无法用 socket 探活
    if (listen(fd, 4) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/** 探测是否已有守护进程在监听（不依赖 pid 文件，避免旧 pid 文件误判） */
static bool guard_socket_alive(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, GUARD_SOCKET, sizeof(addr.sun_path) - 2);
    socklen_t len = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + strlen(GUARD_SOCKET));

    int r = connect(fd, (struct sockaddr *) &addr, len);
    close(fd);
    return r == 0;
}

/**
 * 接受并立即关闭探活连接。
 *
 * 锁 socket 只用于“单例 + 存活探测”，不做数据传输；如果不 accept，backlog 会被
 * 每次状态查询占满，后续 connect 就会失败/误判 daemon 不在。
 */
static void *lock_acceptor(void *) {
    for (;;) {
        int c = accept(g_lock_fd, nullptr, nullptr);
        if (c >= 0) {
            close(c);
            continue;
        }
        if (errno == EINTR) continue;
        break;
    }
    return nullptr;
}

/** 回收任意已退出子进程，杜绝 Z 状态堆积 */
static void sigchld_handler(int) {
    int saved = errno;
    while (waitpid(-1, nullptr, WNOHANG) > 0) { }
    errno = saved;
}

// ----------------------------------------------------------------------------
//  守护主循环（运行在脱离会话的孙子进程里）
// ----------------------------------------------------------------------------
static void daemon_loop(const char *pkg,
                        const char *compsCsv,
                        const char *stopFile,
                        const char *pidFile,
                        const char *logFile,
                        int intervalSec) {
    // 不再伪装成包名：prctl 改的是 /proc/pid/comm，ps 能一眼看出是守护进程
    prctl(PR_SET_NAME, GUARD_NAME, 0, 0, 0);

    // 子进程回收：没有这个 handler，run_shell 超时分支和中断分支都会留下僵尸
    signal(SIGCHLD, sigchld_handler);

    // 单例：拿不到锁说明已有守护在跑，本实例静默退出
    g_lock_fd = try_acquire_lock();
    if (g_lock_fd < 0) {
        LOGW("已有守护进程持有锁，本实例退出");
        _exit(0);
    }
    pthread_t acceptor;
    if (pthread_create(&acceptor, nullptr, lock_acceptor, nullptr) == 0) {
        pthread_detach(acceptor);
    }

    char pidbuf[16];
    snprintf(pidbuf, sizeof(pidbuf), "%d", (int) getpid());
    write_text_file(pidFile, pidbuf);

    append_log(logFile, "守护进程已启动（单例锁已获取）");

    int failStreak = 0;
    int pidTick = 0;
    const int maxInterval = intervalSec > 0 ? intervalSec : 2;
    int backoffSec = maxInterval;

    for (;;) {
        // 收到停止信号：清掉标记与 pid 文件后退出
        if (file_exists(stopFile)) {
            unlink(stopFile);
            unlink(pidFile);
            append_log(logFile, "收到停止指令，守护进程退出");
            break;
        }

        // 定期重写 pid 文件：防止旧版本/异常退出留下过期 pid 文件导致状态误报
        if ((++pidTick % 15) == 0) {
            write_text_file(pidFile, pidbuf);
        }

        if (!app_alive(pkg, getpid())) {
            failStreak++;
            append_log(logFile, "检测到灵手进程不在，执行拉起");
            restart_components(compsCsv, logFile);

            // 连续两轮还是拉不起来，说明后台启动被限制了，
            // 改用广播兜底（BOOT_COMPLETED 接收器里会走完整的启动链路）
            if (failStreak >= 2) {
                char bcmd[512];
                snprintf(bcmd, sizeof(bcmd),
                         "am broadcast -a android.intent.action.BOOT_COMPLETED -n %s/com.byd.ls.receiver.BootReceiver",
                         pkg);
                append_log(logFile, bcmd);
                run_shell(bcmd, 20);
            }
            if (app_alive(pkg, getpid())) {
                failStreak = 0;
                backoffSec = maxInterval;
                append_log(logFile, "拉起成功");
            } else {
                // 指数退避：2 → 4 → 8 … 上限 60s，避免拉不起来时高频空转
                backoffSec = backoffSec * 2;
                if (backoffSec > 60) backoffSec = 60;
            }
            sleep(backoffSec);
        } else {
            failStreak = 0;
            backoffSec = maxInterval;
            sleep(maxInterval);
        }
    }

    if (g_lock_fd >= 0) close(g_lock_fd);
    _exit(0);
}

// ----------------------------------------------------------------------------
//  JNI 接口
// ----------------------------------------------------------------------------
extern "C" {

/**
 * 启动守护进程（幂等）。
 *
 * 双 fork 脱离：
 *   父进程(App) → fork → 子进程 setsid() 成为新会话首进程 → 再 fork → 孙子进程
 *   子进程立刻退出，孙子进程被 init(1) 收养，从此与 App 的会话/进程组解耦。
 *
 * @return 守护进程 pid；已在运行则返回现有 pid；失败返回 -1
 */
JNIEXPORT jint JNICALL
Java_com_byd_ls_guard_DaemonGuard_nativeStartDaemon(JNIEnv *env,
                                                    jobject /* thiz */,
                                                    jstring jPkg,
                                                    jstring jCompsCsv,
                                                    jstring jStopFile,
                                                    jstring jPidFile,
                                                    jstring jLogFile,
                                                    jint jIntervalSec) {
    const char *pkg = env->GetStringUTFChars(jPkg, nullptr);
    const char *comps = env->GetStringUTFChars(jCompsCsv, nullptr);
    const char *stopFile = env->GetStringUTFChars(jStopFile, nullptr);
    const char *pidFile = env->GetStringUTFChars(jPidFile, nullptr);
    const char *logFile = env->GetStringUTFChars(jLogFile, nullptr);
    const int interval = (int) jIntervalSec;

    // 幂等：socket 探活优先于 pid 文件
    if (guard_socket_alive()) {
        LOGI("守护进程已在监听，跳过重复启动");
        env->ReleaseStringUTFChars(jPkg, pkg);
        env->ReleaseStringUTFChars(jCompsCsv, comps);
        env->ReleaseStringUTFChars(jStopFile, stopFile);
        env->ReleaseStringUTFChars(jPidFile, pidFile);
        env->ReleaseStringUTFChars(jLogFile, logFile);
        return -2;
    }

    // 幂等：已有存活的守护进程就不再 fork
    if (file_exists(pidFile)) {
        FILE *f = fopen(pidFile, "r");
        if (f != nullptr) {
            int oldPid = 0;
            if (fscanf(f, "%d", &oldPid) == 1 && oldPid > 0) {
                char procPath[64];
                snprintf(procPath, sizeof(procPath), "/proc/%d", oldPid);
                if (file_exists(procPath)) {
                    fclose(f);
                    LOGI("守护进程已在运行 pid=%d，跳过重复启动", oldPid);
                    env->ReleaseStringUTFChars(jPkg, pkg);
                    env->ReleaseStringUTFChars(jCompsCsv, comps);
                    env->ReleaseStringUTFChars(jStopFile, stopFile);
                    env->ReleaseStringUTFChars(jPidFile, pidFile);
                    env->ReleaseStringUTFChars(jLogFile, logFile);
                    return (jint) oldPid;
                }
            }
            fclose(f);
        }
        unlink(pidFile);   // 残留的过期 pid 文件
    }

    // 清掉上次可能遗留的停止标记，否则新守护进程一起来就自杀
    if (file_exists(stopFile)) unlink(stopFile);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("第一次 fork 失败: %s", strerror(errno));
        env->ReleaseStringUTFChars(jPkg, pkg);
        env->ReleaseStringUTFChars(jCompsCsv, comps);
        env->ReleaseStringUTFChars(jStopFile, stopFile);
        env->ReleaseStringUTFChars(jPidFile, pidFile);
        env->ReleaseStringUTFChars(jLogFile, logFile);
        return -1;
    }

    if (pid > 0) {
        // 父进程（App）：中间子进程会立刻 _exit，这里必须阻塞回收，
        // 原来用 WNOHANG 会在 App 进程里留下 Z 状态中间进程（实测 ps 可见）。
        int status = 0;
        waitpid(pid, &status, 0);
        LOGI("已 fork 中间进程 pid=%d", (int) pid);
        env->ReleaseStringUTFChars(jPkg, pkg);
        env->ReleaseStringUTFChars(jCompsCsv, comps);
        env->ReleaseStringUTFChars(jStopFile, stopFile);
        env->ReleaseStringUTFChars(jPidFile, pidFile);
        env->ReleaseStringUTFChars(jLogFile, logFile);
        return (jint) pid;
    }

    // ---- 子进程：脱离会话 ----
    setsid();
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    pid_t pid2 = fork();
    if (pid2 < 0) _exit(1);
    if (pid2 > 0) _exit(0);   // 子进程退出，孙子进程被 init 收养

    // ---- 孙子进程：真正的守护 ----
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, SIG_IGN);   // 不响应普通 kill，只能靠 stopFile 优雅退出
    umask(0);

    // 切到根目录，避免占住 App 私有目录导致无法卸载/清理
    if (chdir("/") != 0) { /* 忽略 */ }

    // 关掉继承来的标准流，重定向到 /dev/null，防止写满管道阻塞
    int devnull = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (devnull >= 0) {
        dup2(devnull, STDIN_FILENO);
        dup2(devnull, STDOUT_FILENO);
        dup2(devnull, STDERR_FILENO);
        if (devnull > STDERR_FILENO) close(devnull);
    }

    daemon_loop(pkg, comps, stopFile, pidFile, logFile, interval);
    _exit(0);
    return -1;  // 不可达，仅为满足编译器
}

/** 写停止标记文件，让守护进程在下一轮自检时优雅退出 */
JNIEXPORT void JNICALL
Java_com_byd_ls_guard_DaemonGuard_nativeStopDaemon(JNIEnv *env, jobject /* thiz */, jstring jStopFile) {
    const char *stopFile = env->GetStringUTFChars(jStopFile, nullptr);
    write_text_file(stopFile, "1");
    LOGI("已写入停止标记: %s", stopFile);
    env->ReleaseStringUTFChars(jStopFile, stopFile);
}

/** 读当前守护进程 pid；不存在返回 -1 */
JNIEXPORT jint JNICALL
Java_com_byd_ls_guard_DaemonGuard_nativeDaemonPid(JNIEnv *env, jobject /* thiz */, jstring jPidFile) {
    const char *pidFile = env->GetStringUTFChars(jPidFile, nullptr);
    jint result = -1;
    FILE *f = fopen(pidFile, "r");
    if (f != nullptr) {
        int p = 0;
        if (fscanf(f, "%d", &p) == 1 && p > 0) {
            char procPath[64];
            snprintf(procPath, sizeof(procPath), "/proc/%d", p);
            if (file_exists(procPath)) result = (jint) p;
        }
        fclose(f);
    }
    env->ReleaseStringUTFChars(jPidFile, pidFile);
    return result;
}

/** 守护进程是否在监听（独立于 pid 文件） */
JNIEXPORT jboolean JNICALL
Java_com_byd_ls_guard_DaemonGuard_nativeDaemonAlive(JNIEnv *env, jobject /* thiz */) {
    (void) env;
    return guard_socket_alive() ? JNI_TRUE : JNI_FALSE;
}

/** 供 Java 层自检 so 是否正确加载 */
JNIEXPORT jstring JNICALL
Java_com_byd_ls_guard_DaemonGuard_nativeVersion(JNIEnv *env, jobject /* thiz */) {
    return env->NewStringUTF("lsguard 1.0.0 / DiLink4.0");
}

}  // extern "C"
