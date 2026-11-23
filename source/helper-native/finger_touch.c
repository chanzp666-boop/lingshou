/*
 * finger-touch: 抓取车机触摸屏 event0，单指/双指原样转发到 uinput 虚拟触摸屏，
 * 三指时截断触摸并只上报 C/S 事件，避免第三方 App 跟着滚动。
 *
 * 运行：finger-touch <appUid> <tcpPort>
 * 输出协议：C,<手指数> / S,<cx>,<cy>（与旧 Java 助手一致）
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define TOUCH_PATH "/dev/input/event0"
#define MAX_SLOTS 10
#define MAX_FRAME 128
#define VIRTUAL_NAME "finger-virtual"

static int phys_fd = -1;
static int ui_fd = -1;
static int sock_fd = -1;
static int virtual_ready = 0;
static int grabbed = 0;
static int sock_alive = 1;

static int cur_slot = 0;
static int slot_active[MAX_SLOTS];
static int slot_x[MAX_SLOTS];
static int slot_y[MAX_SLOTS];
static int active_count = 0;
static int last_count = -1;
static int suppress = 0;
static int virtual_touch = 0;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void send_line(const char *fmt, ...) {
    if (!sock_alive) return;
    char buf[128];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    ssize_t w = send(sock_fd, buf, (size_t)n, MSG_DONTWAIT);
    if (w < 0 && (errno == EPIPE || errno == ECONNRESET)) sock_alive = 0;
}

static int connect_app(int port) {
    for (int i = 0; i < 20; i++) {
        int s = socket(AF_INET, SOCK_STREAM, 0);
        if (s < 0) return -1;
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons((uint16_t)port);
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (connect(s, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            int one = 1;
            setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
            return s;
        }
        close(s);
        usleep(100 * 1000);
    }
    return -1;
}

static int virtual_registered(void) {
    FILE *p = popen("/system/bin/getevent -pl 2>/dev/null", "r");
    if (!p) return 0;
    char line[512];
    int found = 0;
    while (fgets(line, sizeof(line), p)) {
        if (strstr(line, VIRTUAL_NAME)) { found = 1; break; }
    }
    pclose(p);
    return found;
}

static void emit(int type, int code, int value) {
    if (ui_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (uint16_t)type;
    ev.code = (uint16_t)code;
    ev.value = value;
    write(ui_fd, &ev, sizeof(ev));
}

static int create_virtual(void) {
    ui_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ui_fd < 0) {
        printf("open uinput failed: %s\n", strerror(errno));
        return -1;
    }
    ioctl(ui_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(ui_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(ui_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_X);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MINOR);
    ioctl(ui_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = ABS_X; abs.absinfo.minimum = 0; abs.absinfo.maximum = 1919;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_Y; abs.absinfo.minimum = 0; abs.absinfo.maximum = 1079;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_SLOT; abs.absinfo.minimum = 0; abs.absinfo.maximum = 9;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TRACKING_ID; abs.absinfo.minimum = 0; abs.absinfo.maximum = 65535;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_X; abs.absinfo.minimum = 0; abs.absinfo.maximum = 1919;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_Y; abs.absinfo.minimum = 0; abs.absinfo.maximum = 1079;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TOUCH_MAJOR; abs.absinfo.minimum = 0; abs.absinfo.maximum = 255;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TOUCH_MINOR; abs.absinfo.minimum = 0; abs.absinfo.maximum = 255;
    ioctl(ui_fd, UI_ABS_SETUP, &abs);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, VIRTUAL_NAME);
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x1234;
    setup.id.product = 0x5678;
    setup.id.version = 1;
    if (ioctl(ui_fd, UI_DEV_SETUP, &setup) < 0) {
        printf("UI_DEV_SETUP failed: %s\n", strerror(errno));
        close(ui_fd); ui_fd = -1;
        return -1;
    }
    if (ioctl(ui_fd, UI_DEV_CREATE) < 0) {
        printf("UI_DEV_CREATE failed: %s\n", strerror(errno));
        close(ui_fd); ui_fd = -1;
        return -1;
    }
    virtual_ready = 1;
    return 0;
}

static void destroy_virtual(void) {
    if (ui_fd >= 0) {
        if (virtual_ready) ioctl(ui_fd, UI_DEV_DESTROY);
        close(ui_fd);
        ui_fd = -1;
        virtual_ready = 0;
    }
}

static void cancel_virtual_touch(void) {
    if (!virtual_ready || !virtual_touch) return;
    for (int slot = 0; slot < MAX_SLOTS; slot++) {
        emit(EV_ABS, ABS_MT_SLOT, slot);
        emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    }
    emit(EV_KEY, BTN_TOUCH, 0);
    emit(EV_SYN, SYN_REPORT, 0);
    virtual_touch = 0;
}

static void forward_frame(struct input_event *frame, int n) {
    if (!virtual_ready || suppress) return;
    for (int i = 0; i < n; i++) {
        struct input_event *ev = &frame[i];
        if (ev->type == EV_ABS && ev->code == ABS_MT_TRACKING_ID) {
            if (ev->value >= 0) virtual_touch = 1;
        }
        if (ev->type == EV_KEY && ev->code == BTN_TOUCH && ev->value == 1) virtual_touch = 1;
        write(ui_fd, ev, sizeof(*ev));
    }
    emit(EV_SYN, SYN_REPORT, 0);
}

static void process_frame(struct input_event *frame, int n) {
    int old_count = active_count;
    for (int i = 0; i < n; i++) {
        struct input_event *ev = &frame[i];
        if (ev->type == EV_ABS) {
            if (ev->code == ABS_MT_SLOT) {
                if (ev->value >= 0 && ev->value < MAX_SLOTS) cur_slot = ev->value;
            } else if (ev->code == ABS_MT_TRACKING_ID) {
                int active = ev->value >= 0;
                if (active != slot_active[cur_slot]) {
                    slot_active[cur_slot] = active;
                    active_count += active ? 1 : -1;
                    if (active_count < 0) active_count = 0;
                }
            } else if (ev->code == ABS_MT_POSITION_X) {
                slot_x[cur_slot] = ev->value;
            } else if (ev->code == ABS_MT_POSITION_Y) {
                slot_y[cur_slot] = ev->value;
            }
        }
    }
    int count = active_count;
    if (count != last_count) {
        send_line("C,%d\n", count);
        if (count >= 3) suppress = 1;
        if (count == 0) suppress = 0;
        printf("touch count=%d suppress=%d\n", count, suppress);
        if (count >= 3 && old_count < 3) cancel_virtual_touch();
        last_count = count;
    }

    if (count == 3) {
        int sx = 0, sy = 0, n2 = 0;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slot_active[i]) { sx += slot_x[i]; sy += slot_y[i]; n2++; }
        }
        if (n2 > 0) {
            static long long last_sample = 0;
            long long now = now_ms();
            if (now - last_sample >= 16) {
                send_line("S,%d,%d\n", sx / n2, sy / n2);
                last_sample = now;
            }
        }
    }

    if (!suppress) forward_frame(frame, n);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGPIPE, SIG_IGN);
    int port = argc > 2 ? atoi(argv[2]) : -1;
    printf("finger-touch start uid=%d port=%d\n", getuid(), port);
    if (port <= 0) { printf("no app port\n"); return 1; }

    sock_fd = connect_app(port);
    if (sock_fd < 0) { printf("connect app failed\n"); return 1; }
    printf("connected to app\n");

    if (create_virtual() != 0) {
        printf("virtual touch unavailable, read-only mode\n");
    } else {
        int ready = 0;
        for (int i = 0; i < 5; i++) {
            usleep(300 * 1000);
            if (virtual_registered()) { ready = 1; break; }
        }
        if (!ready) {
            printf("virtual touch not registered, read-only mode\n");
            destroy_virtual();
        } else {
            phys_fd = open(TOUCH_PATH, O_RDONLY);
            if (phys_fd < 0) {
                printf("open %s failed: %s\n", TOUCH_PATH, strerror(errno));
                destroy_virtual();
                return 1;
            }
            if (ioctl(phys_fd, EVIOCGRAB, 1) == 0) {
                grabbed = 1;
                printf("grab ok\n");
            } else {
                printf("grab failed: %s\n", strerror(errno));
                destroy_virtual();
            }
        }
    }

    if (phys_fd < 0) {
        phys_fd = open(TOUCH_PATH, O_RDONLY);
        if (phys_fd < 0) { printf("open touch failed\n"); return 1; }
    }

    struct input_event frame[MAX_FRAME];
    int fn = 0;
    while (1) {
        struct input_event ev;
        ssize_t r = read(phys_fd, &ev, sizeof(ev));
        if (r != (ssize_t)sizeof(ev)) {
            if (errno == EINTR) continue;
            printf("read touch end: %s\n", strerror(errno));
            break;
        }
        if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            process_frame(frame, fn);
            fn = 0;
        } else if (fn < MAX_FRAME) {
            frame[fn++] = ev;
        }
    }

    if (grabbed) ioctl(phys_fd, EVIOCGRAB, 0);
    close(phys_fd);
    destroy_virtual();
    close(sock_fd);
    printf("finger-touch exit\n");
    return 0;
}
