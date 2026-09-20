/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include "Aw20072.h"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>

namespace meizu::aw_light {

class Controller {
  public:
    struct State {
        uint64_t owner = 0;
        int requestId = -1;
        int effect = 0;
        int priority = 0;
        int requestCount = 0;
        bool active = false;
        bool animated = false;
        bool wakeHeld = false;
        int error = 0;
        uint64_t generation = 0;
    };

    using WakeHook = std::function<bool(bool)>;
    // Worker callbacks run without controller locks. Inactive events are terminal.
    // Callbacks may submit/cancel, but must not block or destroy the controller.
    using ChangeCallback = std::function<void(const State&)>;

    Controller(std::shared_ptr<const Catalog> catalog, std::unique_ptr<Aw20072> backend,
               WakeHook wake, ChangeCallback onChanged = {});
    ~Controller();
    Controller(const Controller&) = delete;
    Controller& operator=(const Controller&) = delete;

    // IDs are 0..31, priorities 0..100, leases 0 (persistent) or 1..60000 ms.
    // At most 16 live requests; acceptance can also fail on callback backpressure.
    // Same-effect updates retain phase. Resumable preempted requests restart later.
    // Acceptance is synchronous; rendering and I/O errors are reported asynchronously.
    bool submit(uint64_t owner, int requestId, int effect, const Options& options, int priority,
                int timeoutMs, bool resume);
    bool cancel(uint64_t owner, int requestId);
    size_t clear(uint64_t owner);
    State snapshot() const;

  private:
    struct Impl;
    std::unique_ptr<Impl> mImpl;
};

}  // namespace meizu::aw_light
