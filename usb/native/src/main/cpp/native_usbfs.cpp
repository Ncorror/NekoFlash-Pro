#include <jni.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <unordered_set>
#include <utility>
#include <vector>

#ifndef USBDEVFS_URB_TYPE_BULK
#define USBDEVFS_URB_TYPE_BULK 3
#endif

#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 13, void*)
#endif

#ifndef USBDEVFS_DISCARDURB
#define USBDEVFS_DISCARDURB _IO('U', 11)
#endif

namespace {
constexpr int64_t STAGE_PREFLIGHT = 1;
constexpr int64_t STAGE_OPEN = 2;
constexpr int64_t STAGE_READ = 3;
constexpr int64_t STAGE_SUBMIT = 4;
constexpr int64_t STAGE_REAP = 5;
constexpr int64_t STAGE_TIMEOUT = 6;
constexpr int64_t STAGE_STATUS = 7;
constexpr int64_t STAGE_LENGTH = 8;
constexpr int64_t STAGE_JNI = 9;
constexpr int64_t STAGE_DONE = 10;
constexpr int64_t STAGE_CANCELLED = 11;
constexpr int64_t STAGE_HARD_TIMEOUT = 12;
constexpr int64_t STAGE_DRAIN = 13;
constexpr int64_t DRAIN_NOT_NEEDED = 0;
constexpr int64_t DRAIN_REAPED = 1;
constexpr int64_t DRAIN_FAILED = 2;

// Production USBFS backend for Fastboot DATA transfers. The transport mirrors
// AOSP fastboot with two in-flight URBs and an adaptive 256 KiB -> 16 KiB write
// size fallback when the kernel cannot allocate a large URB.
constexpr int MAX_USBFS_BULK_WRITE_SIZE = 256 * 1024;
constexpr int MIN_USBFS_BULK_WRITE_SIZE = 16 * 1024;
constexpr int MIN_PIPELINE_DEPTH = 1;
constexpr int MAX_PIPELINE_DEPTH = 2;
constexpr useconds_t REAP_POLL_SLEEP_US = 2 * 1000;  // 2 ms, avoids 0.25 ms busy-loop.
constexpr int64_t DRAIN_TIMEOUT_MS = 5 * 1000;

struct TransferControl {
    explicit TransferControl(int64_t transfer_token) : token(transfer_token) {}
    const int64_t token;
    std::atomic<bool> cancelled{false};
    std::atomic<int64_t> confirmed_bytes{0};
    std::atomic<int64_t> submitted_bytes{0};
    std::atomic<int64_t> total_bytes{0};
    std::atomic<int64_t> started_ms{0};
    std::atomic<int64_t> last_completion_ms{0};
    std::atomic<int64_t> stage{STAGE_PREFLIGHT};
};

std::mutex g_transfer_mutex;
std::shared_ptr<TransferControl> g_active_transfer;
std::unordered_set<int64_t> g_pre_cancelled_tokens;
bool g_backend_poisoned = false;

void poison_backend_noexcept() noexcept;

class UniqueFd {
public:
    explicit UniqueFd(int fd = -1) noexcept : fd_(fd) {}
    ~UniqueFd() { reset(); }

    UniqueFd(const UniqueFd&) = delete;
    UniqueFd& operator=(const UniqueFd&) = delete;

    UniqueFd(UniqueFd&& other) noexcept : fd_(other.release()) {}

    UniqueFd& operator=(UniqueFd&& other) noexcept {
        if (this != &other) reset(other.release());
        return *this;
    }

    [[nodiscard]] int get() const noexcept { return fd_; }
    [[nodiscard]] bool valid() const noexcept { return fd_ >= 0; }

    int release() noexcept {
        const int released = fd_;
        fd_ = -1;
        return released;
    }

    void reset(int replacement = -1) noexcept {
        const int old_fd = fd_;
        fd_ = replacement;
        if (old_fd >= 0) {
            // close(2) must not be retried after EINTR because the descriptor may
            // already have been released and reused by another thread.
            static_cast<void>(close(old_fd));
        }
    }

private:
    int fd_;
};



int64_t now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000LL +
           static_cast<int64_t>(ts.tv_nsec) / 1000000LL;
}

jlongArray make_result(JNIEnv* env, int64_t success, int64_t confirmed_bytes,
                       int64_t stop_errno, int64_t status, int64_t actual,
                       int64_t elapsed_ms, int64_t stage,
                       int64_t submitted_bytes = 0,
                       int64_t pending_at_stop = 0,
                       int64_t last_completion_age_ms = 0,
                       int64_t drain_state = DRAIN_NOT_NEEDED,
                       int64_t drain_errno = 0,
                       int64_t backend_poisoned = 0,
                       int64_t kernel_ioctl_errno = 0) {
    const jlong values[14] = {
        static_cast<jlong>(success),
        static_cast<jlong>(confirmed_bytes),
        static_cast<jlong>(stop_errno),
        static_cast<jlong>(status),
        static_cast<jlong>(actual),
        static_cast<jlong>(elapsed_ms),
        static_cast<jlong>(stage),
        static_cast<jlong>(submitted_bytes),
        static_cast<jlong>(pending_at_stop),
        static_cast<jlong>(last_completion_age_ms),
        static_cast<jlong>(drain_state),
        static_cast<jlong>(drain_errno),
        static_cast<jlong>(backend_poisoned),
        static_cast<jlong>(kernel_ioctl_errno)
    };
    jlongArray out = env->NewLongArray(14);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 14, values);
    return out;
}

jlongArray make_backend_state_result(JNIEnv* env, int64_t poisoned,
                                     int64_t active = 0, int64_t token = 0,
                                     int64_t confirmed = 0, int64_t submitted = 0,
                                     int64_t total = 0, int64_t started = 0,
                                     int64_t last_completion = 0,
                                     int64_t stage = STAGE_PREFLIGHT) {
    const jlong values[9] = {
        static_cast<jlong>(poisoned),
        static_cast<jlong>(active),
        static_cast<jlong>(token),
        static_cast<jlong>(confirmed),
        static_cast<jlong>(submitted),
        static_cast<jlong>(total),
        static_cast<jlong>(started),
        static_cast<jlong>(last_completion),
        static_cast<jlong>(stage)
    };
    jlongArray out = env->NewLongArray(9);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 9, values);
    return out;
}

bool pread_exact(int file_fd, unsigned char* buffer, size_t size, int64_t offset, int* err) {
    if (offset < 0 || offset > static_cast<int64_t>(std::numeric_limits<off_t>::max()) ||
        size > static_cast<size_t>(std::numeric_limits<off_t>::max() - static_cast<off_t>(offset))) {
        *err = EOVERFLOW;
        return false;
    }
    size_t total = 0;
    while (total < size) {
        const ssize_t read_result = pread(
            file_fd, buffer + total, size - total,
            static_cast<off_t>(offset + static_cast<int64_t>(total)));
        if (read_result < 0) {
            if (errno == EINTR) continue;
            *err = errno;
            return false;
        }
        if (read_result == 0) {
            // The payload was truncated or changed after fstat().
            *err = EIO;
            return false;
        }
        total += static_cast<size_t>(read_result);
    }
    return true;
}

struct UrbSlot {
    explicit UrbSlot(size_t buffer_size)
        : urb(std::make_unique<usbdevfs_urb>()),
          buffer(new unsigned char[buffer_size]) {}

    UrbSlot(const UrbSlot&) = delete;
    UrbSlot& operator=(const UrbSlot&) = delete;
    UrbSlot(UrbSlot&&) noexcept = default;
    UrbSlot& operator=(UrbSlot&&) noexcept = default;

    std::unique_ptr<usbdevfs_urb> urb;
    std::unique_ptr<unsigned char[]> buffer;
    bool pending = false;
    int requested = 0;
};

int find_slot_by_urb(const std::vector<UrbSlot>& slots, const void* completed) {
    for (size_t index = 0; index < slots.size(); ++index) {
        if (slots[index].urb.get() == completed) return static_cast<int>(index);
    }
    return -1;
}

void abandon_slot_ownership(std::vector<UrbSlot>& slots) noexcept {
    for (auto& slot : slots) {
        static_cast<void>(slot.buffer.release());
        static_cast<void>(slot.urb.release());
    }
}

class PendingUrbOwnershipGuard {
public:
    explicit PendingUrbOwnershipGuard(std::vector<UrbSlot>& slots) noexcept
        : slots_(slots) {}

    ~PendingUrbOwnershipGuard() {
        if (armed_ && has_pending_urbs()) {
            // An unexpected C++ exception must never free memory that usbfs may
            // still reference. Deliberately leak the bounded slot allocation and
            // fail closed until process restart, matching the drain-failure path.
            abandon_slot_ownership(slots_);
            poison_backend_noexcept();
        }
    }

    PendingUrbOwnershipGuard(const PendingUrbOwnershipGuard&) = delete;
    PendingUrbOwnershipGuard& operator=(const PendingUrbOwnershipGuard&) = delete;

    void abandon_and_poison() noexcept {
        if (!armed_) return;
        abandon_slot_ownership(slots_);
        armed_ = false;
        poison_backend_noexcept();
    }

    void disarm() noexcept { armed_ = false; }

private:
    [[nodiscard]] bool has_pending_urbs() const noexcept {
        for (const auto& slot : slots_) {
            if (slot.pending) return true;
        }
        return false;
    }

    std::vector<UrbSlot>& slots_;
    bool armed_ = true;
};

/**
 * Cancel every submitted URB and reap every completion before memory is freed.
 *
 * USBDEVFS_DISCARDURB only requests cancellation; the corresponding URB is
 * still returned through REAPURB. Therefore pending is intentionally not
 * cleared until its completion has been reaped.
 */
bool discard_and_reap_all(int usb_fd, std::vector<UrbSlot>& slots,
                          int* pending_count, int* drain_errno) {
    for (auto& slot : slots) {
        if (!slot.pending || slot.urb == nullptr) continue;
        if (ioctl(usb_fd, USBDEVFS_DISCARDURB, slot.urb.get()) < 0) {
            // ENOENT normally means it completed between the last poll and the
            // discard request and is already waiting in the completion queue.
            if (errno != ENOENT && errno != EINVAL && *drain_errno == 0) {
                *drain_errno = errno;
            }
        }
    }

    const int64_t drain_started_ms = now_ms();
    while (*pending_count > 0) {
        void* completed = nullptr;
        if (ioctl(usb_fd, USBDEVFS_REAPURBNDELAY, &completed) < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN) {
                if (now_ms() - drain_started_ms > DRAIN_TIMEOUT_MS) {
                    if (*drain_errno == 0) *drain_errno = ETIMEDOUT;
                    return false;
                }
                usleep(REAP_POLL_SLEEP_US);
                continue;
            }
            if (*drain_errno == 0) *drain_errno = errno;
            return false;
        }
        if (completed == nullptr) {
            if (*drain_errno == 0) *drain_errno = EPROTO;
            return false;
        }

        // Compare the pointer value before dereferencing it. This also safely
        // drains any foreign/stale completion that may already be queued.
        const int index = find_slot_by_urb(slots, completed);
        if (index < 0) continue;

        UrbSlot& slot = slots[static_cast<size_t>(index)];
        if (!slot.pending) continue;
        slot.pending = false;
        --(*pending_count);
    }
    return true;
}

int clamp_block_size(int requested) {
    if (requested <= 0) return MIN_USBFS_BULK_WRITE_SIZE;
    if (requested > MAX_USBFS_BULK_WRITE_SIZE) return MAX_USBFS_BULK_WRITE_SIZE;
    if (requested < MIN_USBFS_BULK_WRITE_SIZE) return MIN_USBFS_BULK_WRITE_SIZE;
    return (requested / MIN_USBFS_BULK_WRITE_SIZE) * MIN_USBFS_BULK_WRITE_SIZE;
}

int clamp_pipeline_depth(int requested) {
    if (requested < MIN_PIPELINE_DEPTH) return MIN_PIPELINE_DEPTH;
    if (requested > MAX_PIPELINE_DEPTH) return MAX_PIPELINE_DEPTH;
    return requested;
}

std::shared_ptr<TransferControl> register_transfer(int64_t token, int* error) {
    try {
        std::lock_guard<std::mutex> lock(g_transfer_mutex);
        if (g_backend_poisoned) {
            *error = EUCLEAN;
            return nullptr;
        }
        if (g_active_transfer != nullptr) {
            *error = EBUSY;
            return nullptr;
        }
        auto control = std::make_shared<TransferControl>(token);
        if (g_pre_cancelled_tokens.erase(token) > 0) {
            control->cancelled.store(true, std::memory_order_release);
        }
        g_active_transfer = control;
        return control;
    } catch (const std::bad_alloc&) {
        *error = ENOMEM;
        return nullptr;
    } catch (...) {
        *error = EIO;
        return nullptr;
    }
}

void unregister_transfer(const std::shared_ptr<TransferControl>& control) {
    std::lock_guard<std::mutex> lock(g_transfer_mutex);
    if (g_active_transfer == control) g_active_transfer.reset();
    g_pre_cancelled_tokens.erase(control->token);
}

void unregister_transfer_noexcept(const std::shared_ptr<TransferControl>& control) noexcept {
    try {
        unregister_transfer(control);
    } catch (...) {
        // Leaving an active registration behind is safer than throwing across a
        // destructor/JNI boundary. Poisoning keeps subsequent transfers blocked.
        poison_backend_noexcept();
    }
}

class TransferRegistrationGuard {
public:
    explicit TransferRegistrationGuard(std::shared_ptr<TransferControl> control) noexcept
        : control_(std::move(control)) {}

    ~TransferRegistrationGuard() { reset(); }

    TransferRegistrationGuard(const TransferRegistrationGuard&) = delete;
    TransferRegistrationGuard& operator=(const TransferRegistrationGuard&) = delete;

    void reset() noexcept {
        if (control_ == nullptr) return;
        unregister_transfer_noexcept(control_);
        control_.reset();
    }

private:
    std::shared_ptr<TransferControl> control_;
};

void poison_backend() {
    std::lock_guard<std::mutex> lock(g_transfer_mutex);
    g_backend_poisoned = true;
}

void poison_backend_noexcept() noexcept {
    try {
        poison_backend();
    } catch (...) {
        // An exceptional JNI escape is already process-restart-only. Never let
        // a secondary mutex failure cross back into ART.
    }
}

bool backend_poisoned_noexcept() noexcept {
    try {
        std::lock_guard<std::mutex> lock(g_transfer_mutex);
        return g_backend_poisoned;
    } catch (...) {
        poison_backend_noexcept();
        return true;
    }
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_ncorror_nekoflash_fastboot_transport_NativeUsbfsBackend_nativeCancelTransfer(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong transfer_token) {
    const int64_t token = static_cast<int64_t>(transfer_token);
    if (token <= 0) return JNI_FALSE;

    try {
        std::lock_guard<std::mutex> lock(g_transfer_mutex);
        if (g_active_transfer != nullptr && g_active_transfer->token == token) {
            g_active_transfer->cancelled.store(true, std::memory_order_release);
            return JNI_TRUE;
        }

        // Handles the narrow race where Kotlin has published the token but the
        // blocking JNI function has not registered it yet.
        if (g_pre_cancelled_tokens.size() >= 64U) {
            g_pre_cancelled_tokens.clear();
        }
        g_pre_cancelled_tokens.insert(token);
        return JNI_TRUE;
    } catch (...) {
        // Cancellation must never terminate ART because an unordered_set
        // allocation or mutex operation failed. The caller will keep waiting
        // for the hard deadline and report cancellation=false.
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_ncorror_nekoflash_fastboot_transport_NativeUsbfsBackend_nativeBackendState(
    JNIEnv* env, jobject /*thiz*/) {
    try {
        int64_t poisoned = 0;
        std::shared_ptr<TransferControl> control;
        {
            std::lock_guard<std::mutex> lock(g_transfer_mutex);
            poisoned = g_backend_poisoned ? 1 : 0;
            control = g_active_transfer;
        }
        return make_backend_state_result(
            env, poisoned, control != nullptr ? 1 : 0,
            control != nullptr ? control->token : 0,
            control != nullptr ? control->confirmed_bytes.load(std::memory_order_acquire) : 0,
            control != nullptr ? control->submitted_bytes.load(std::memory_order_acquire) : 0,
            control != nullptr ? control->total_bytes.load(std::memory_order_acquire) : 0,
            control != nullptr ? control->started_ms.load(std::memory_order_acquire) : 0,
            control != nullptr ? control->last_completion_ms.load(std::memory_order_acquire) : 0,
            control != nullptr ? control->stage.load(std::memory_order_acquire) : STAGE_PREFLIGHT);
    } catch (...) {
        // Fail closed. A state-query failure must not cross JNI or make the
        // Kotlin layer believe another native transfer is safe to start.
        poison_backend_noexcept();
        return make_backend_state_result(env, 1);
    }
}

static jlongArray native_bulk_out_urb_impl(
    JNIEnv* env,
    jobject /*thiz*/,
    jint fd,
    jint endpoint_address,
    jint payload_fd_input,
    jlong total_bytes,
    jint block_bytes,
    jint pipeline_depth,
    jint stall_timeout_ms,
    jint hard_timeout_ms,
    jlong transfer_token) {

    const int64_t started = now_ms();
    if (fd < 0 || payload_fd_input < 0 || total_bytes <= 0 ||
        total_bytes > static_cast<jlong>(std::numeric_limits<off_t>::max()) ||
        block_bytes <= 0 ||
        stall_timeout_ms <= 0 || hard_timeout_ms <= 0 || hard_timeout_ms < stall_timeout_ms ||
        transfer_token <= 0 || endpoint_address < 0 || endpoint_address > 0xFF ||
        (endpoint_address & 0x80) != 0 || (endpoint_address & 0x0F) == 0) {
        return make_result(env, 0, 0, EINVAL, 0, 0, 0, STAGE_PREFLIGHT);
    }

    int register_errno = 0;
    const auto control = register_transfer(static_cast<int64_t>(transfer_token), &register_errno);
    if (control == nullptr) {
        return make_result(env, 0, 0, register_errno, 0, 0,
                           now_ms() - started, STAGE_PREFLIGHT);
    }
    TransferRegistrationGuard transfer_registration(control);
    control->total_bytes.store(static_cast<int64_t>(total_bytes), std::memory_order_release);
    control->started_ms.store(started, std::memory_order_release);
    control->last_completion_ms.store(started, std::memory_order_release);
    control->stage.store(STAGE_OPEN, std::memory_order_release);

    // Duplicate the caller-owned payload descriptor before any long-lived native
    // ownership begins. The Kotlin/SAF owner may close its descriptor after the JNI
    // call returns, while this duplicate remains valid for the complete URB drain.
    UniqueFd payload_fd(dup(static_cast<int>(payload_fd_input)));
    if (!payload_fd.valid()) {
        const int dup_errno = errno;
        transfer_registration.reset();
        return make_result(env, 0, 0, dup_errno, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }

    struct stat payload_stat{};
    if (fstat(payload_fd.get(), &payload_stat) < 0) {
        const int stat_errno = errno;
        transfer_registration.reset();
        return make_result(env, 0, 0, stat_errno, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }
    if (!S_ISREG(payload_stat.st_mode) ||
        static_cast<int64_t>(payload_stat.st_size) != static_cast<int64_t>(total_bytes)) {
        transfer_registration.reset();
        return make_result(env, 0, 0, EINVAL, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }

    UniqueFd usb_fd(dup(fd));
    if (!usb_fd.valid()) {
        const int dup_errno = errno;
        transfer_registration.reset();
        return make_result(env, 0, 0, dup_errno, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }

    const int safe_block = clamp_block_size(block_bytes);
    int current_block = safe_block;
    const int safe_depth = clamp_pipeline_depth(pipeline_depth);
    std::vector<UrbSlot> slots;
    PendingUrbOwnershipGuard pending_urb_guard(slots);
    try {
        slots.reserve(static_cast<size_t>(safe_depth));
        for (int index = 0; index < safe_depth; ++index) {
            slots.emplace_back(static_cast<size_t>(safe_block));
        }
    } catch (const std::bad_alloc&) {
        transfer_registration.reset();
        return make_result(env, 0, 0, ENOMEM, 0, 0,
                           now_ms() - started, STAGE_PREFLIGHT);
    } catch (...) {
        transfer_registration.reset();
        return make_result(env, 0, 0, EIO, 0, 0,
                           now_ms() - started, STAGE_JNI);
    }

    int64_t file_offset = 0;       // bytes read and submitted
    int64_t completed_total = 0;  // bytes confirmed by reaped URBs
    int pending_count = 0;
    int last_errno = 0;
    int last_kernel_ioctl_errno = 0;
    int last_status = 0;
    int last_actual = 0;
    int64_t last_submit_failure_stage = STAGE_SUBMIT;
    int64_t last_progress_ms = now_ms();

    auto finish = [&](bool success, int error_code, int status, int actual,
                      int64_t stage, int kernel_ioctl_errno = 0) -> jlongArray {
        control->confirmed_bytes.store(completed_total, std::memory_order_release);
        control->submitted_bytes.store(file_offset, std::memory_order_release);
        control->stage.store(stage, std::memory_order_release);
        const int pending_at_stop = pending_count;
        const int64_t stop_ms = now_ms();
        const int64_t last_completion_age_ms =
            (stop_ms - last_progress_ms) > 0 ? (stop_ms - last_progress_ms) : 0;
        int drain_state = pending_count > 0 ? DRAIN_REAPED : DRAIN_NOT_NEEDED;
        int drain_errno = 0;
        if (pending_count > 0) {
            if (!discard_and_reap_all(usb_fd.get(), slots, &pending_count, &drain_errno)) {
                // Never free memory that may still be referenced by usbfs. The
                // ownership guard releases the bounded allocation intentionally
                // and poisons the backend before any later operation can throw.
                pending_urb_guard.abandon_and_poison();
                drain_state = DRAIN_FAILED;
                success = false;
                error_code = drain_errno != 0 ? drain_errno : EIO;
                stage = STAGE_DRAIN;
            }
        }
        control->stage.store(stage, std::memory_order_release);
        pending_urb_guard.disarm();
        slots.clear();
        usb_fd.reset();
        payload_fd.reset();
        transfer_registration.reset();
        const bool backend_poisoned = backend_poisoned_noexcept();
        return make_result(env, success ? 1 : 0, completed_total, error_code,
                           status, actual, now_ms() - started, stage,
                           file_offset, pending_at_stop, last_completion_age_ms,
                           drain_state, drain_errno, backend_poisoned ? 1 : 0,
                           kernel_ioctl_errno);
    };

    auto cancellation_stage = [&]() -> int64_t {
        if (control->cancelled.load(std::memory_order_acquire)) return STAGE_CANCELLED;
        if (now_ms() - started > static_cast<int64_t>(hard_timeout_ms)) {
            return STAGE_HARD_TIMEOUT;
        }
        return 0;
    };

    auto submit_slot = [&](int index) -> bool {
        last_submit_failure_stage = STAGE_SUBMIT;
        if (file_offset >= total_bytes) return false;
        UrbSlot& slot = slots[static_cast<size_t>(index)];
        if (slot.pending) return false;
        if (control->cancelled.load(std::memory_order_acquire)) {
            last_errno = ECANCELED;
            last_submit_failure_stage = STAGE_CANCELLED;
            return false;
        }
        if (now_ms() - started > static_cast<int64_t>(hard_timeout_ms)) {
            last_errno = ETIMEDOUT;
            last_submit_failure_stage = STAGE_HARD_TIMEOUT;
            return false;
        }

        int requested = 0;
        while (true) {
            const int64_t remaining = static_cast<int64_t>(total_bytes) - file_offset;
            requested = remaining < current_block ? static_cast<int>(remaining) : current_block;
            last_submit_failure_stage = STAGE_READ;
            if (!pread_exact(
                    payload_fd.get(), slot.buffer.get(), static_cast<size_t>(requested),
                    file_offset, &last_errno)) {
                return false;
            }
            if (control->cancelled.load(std::memory_order_acquire)) {
                last_errno = ECANCELED;
                last_submit_failure_stage = STAGE_CANCELLED;
                return false;
            }
            if (now_ms() - started > static_cast<int64_t>(hard_timeout_ms)) {
                last_errno = ETIMEDOUT;
                last_submit_failure_stage = STAGE_HARD_TIMEOUT;
                return false;
            }

            last_submit_failure_stage = STAGE_SUBMIT;
            std::memset(slot.urb.get(), 0, sizeof(*slot.urb));
            slot.urb->type = USBDEVFS_URB_TYPE_BULK;
            slot.urb->endpoint = static_cast<unsigned char>(endpoint_address & 0xFF);
            slot.urb->buffer = slot.buffer.get();
            slot.urb->buffer_length = requested;
            slot.urb->usercontext = reinterpret_cast<void*>(static_cast<intptr_t>(index + 1));
            slot.requested = requested;

            if (ioctl(usb_fd.get(), USBDEVFS_SUBMITURB, slot.urb.get()) == 0) break;

            last_errno = errno;
            last_kernel_ioctl_errno = last_errno;
            if (last_errno == ENOMEM && current_block > MIN_USBFS_BULK_WRITE_SIZE) {
                current_block /= 2;
                if (current_block < MIN_USBFS_BULK_WRITE_SIZE) {
                    current_block = MIN_USBFS_BULK_WRITE_SIZE;
                }
                current_block = (current_block / MIN_USBFS_BULK_WRITE_SIZE) * MIN_USBFS_BULK_WRITE_SIZE;
                if (current_block < MIN_USBFS_BULK_WRITE_SIZE) {
                    current_block = MIN_USBFS_BULK_WRITE_SIZE;
                }
                continue;
            }
            return false;
        }

        last_kernel_ioctl_errno = 0;
        slot.pending = true;
        ++pending_count;
        file_offset += requested;
        control->submitted_bytes.store(file_offset, std::memory_order_release);
        control->stage.store(STAGE_SUBMIT, std::memory_order_release);
        return true;
    };

    auto submit_failure_stage = [&]() -> int64_t {
        return last_submit_failure_stage;
    };

    for (int index = 0; index < safe_depth && file_offset < total_bytes; ++index) {
        const int64_t early_stage = cancellation_stage();
        if (early_stage != 0) {
            return finish(false, early_stage == STAGE_CANCELLED ? ECANCELED : ETIMEDOUT,
                          last_status, last_actual, early_stage);
        }
        if (!submit_slot(index)) {
            const int64_t stage = submit_failure_stage();
            return finish(false, last_errno, 0, 0, stage, last_kernel_ioctl_errno);
        }
    }

    while (completed_total < total_bytes || pending_count > 0) {
        const int64_t stop_stage = cancellation_stage();
        if (stop_stage != 0) {
            return finish(false, stop_stage == STAGE_CANCELLED ? ECANCELED : ETIMEDOUT,
                          last_status, last_actual, stop_stage);
        }

        void* completed = nullptr;
        if (ioctl(usb_fd.get(), USBDEVFS_REAPURBNDELAY, &completed) == 0) {
            if (completed == nullptr) {
                return finish(false, EPROTO, 0, 0, STAGE_REAP);
            }

            // Do not dereference the returned pointer until it is proven to be
            // one of the currently allocated URBs.
            const int index = find_slot_by_urb(slots, completed);
            if (index < 0) {
                return finish(false, EPROTO, 0, 0, STAGE_REAP);
            }

            UrbSlot& slot = slots[static_cast<size_t>(index)];
            if (!slot.pending) {
                return finish(false, EPROTO, 0, 0, STAGE_REAP);
            }

            const auto* completed_urb = slot.urb.get();
            last_status = completed_urb->status;
            last_actual = completed_urb->actual_length;
            slot.pending = false;
            --pending_count;

            if (last_status != 0) {
                return finish(false, 0, last_status, last_actual, STAGE_STATUS);
            }
            if (last_actual < 0 || last_actual > slot.requested) {
                return finish(false, EPROTO, last_status, last_actual, STAGE_LENGTH);
            }
            if (last_actual != slot.requested) {
                completed_total += static_cast<int64_t>(last_actual);
                return finish(false, 0, last_status, last_actual, STAGE_LENGTH);
            }

            completed_total += static_cast<int64_t>(slot.requested);
            last_progress_ms = now_ms();
            control->confirmed_bytes.store(completed_total, std::memory_order_release);
            control->last_completion_ms.store(last_progress_ms, std::memory_order_release);
            control->stage.store(STAGE_REAP, std::memory_order_release);

            if (file_offset < total_bytes) {
                if (!submit_slot(index)) {
                    const int64_t stage = submit_failure_stage();
                    return finish(false, last_errno, 0, 0, stage, last_kernel_ioctl_errno);
                }
            }
            continue;
        }

        last_errno = errno;
        if (last_errno != EAGAIN && last_errno != EINTR) {
            last_kernel_ioctl_errno = last_errno;
            return finish(false, last_errno, 0, 0, STAGE_REAP, last_kernel_ioctl_errno);
        }

        const int64_t current_ms = now_ms();
        if (current_ms - last_progress_ms > static_cast<int64_t>(stall_timeout_ms)) {
            return finish(false, ETIMEDOUT, last_status, last_actual, STAGE_TIMEOUT);
        }
        if (current_ms - started > static_cast<int64_t>(hard_timeout_ms)) {
            return finish(false, ETIMEDOUT, last_status, last_actual, STAGE_HARD_TIMEOUT);
        }
        usleep(REAP_POLL_SLEEP_US);
    }

    return finish(true, 0, last_status, last_actual, STAGE_DONE);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_ncorror_nekoflash_fastboot_transport_NativeUsbfsBackend_nativeBulkOutUrb(
    JNIEnv* env,
    jobject thiz,
    jint fd,
    jint endpoint_address,
    jint payload_fd_input,
    jlong total_bytes,
    jint block_bytes,
    jint pipeline_depth,
    jint stall_timeout_ms,
    jint hard_timeout_ms,
    jlong transfer_token) {
    try {
        return native_bulk_out_urb_impl(
            env, thiz, fd, endpoint_address, payload_fd_input, total_bytes, block_bytes,
            pipeline_depth, stall_timeout_ms, hard_timeout_ms, transfer_token);
    } catch (const std::bad_alloc&) {
        // Unknown exceptional escape after native ownership may have begun:
        // preserve process safety by blocking all further native transfers.
        poison_backend_noexcept();
        return make_result(env, 0, 0, ENOMEM, 0, 0, 0, STAGE_JNI,
                           0, 0, 0, DRAIN_FAILED, ENOMEM, 1);
    } catch (...) {
        poison_backend_noexcept();
        return make_result(env, 0, 0, EIO, 0, 0, 0, STAGE_JNI,
                           0, 0, 0, DRAIN_FAILED, EIO, 1);
    }
}
