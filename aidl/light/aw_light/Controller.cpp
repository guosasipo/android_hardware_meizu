/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Controller.h"

#include <errno.h>
#include <time.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <optional>
#include <thread>
#include <utility>

namespace meizu::aw_light {
namespace {

bool bootTime(uint64_t* milliseconds) {
    timespec time{};
    if (clock_gettime(CLOCK_BOOTTIME, &time) != 0) return false;
    *milliseconds = static_cast<uint64_t>(time.tv_sec) * 1000 + time.tv_nsec / 1000000;
    return true;
}

int failureCode() {
    return errno ? errno : EIO;
}

}  // namespace

struct Controller::Impl {
    using Key = std::pair<uint64_t, int>;
    struct Request {
        Key key{};
        int effect = 0;
        Options options;
        int priority = 0;
        uint64_t expires = 0;
        uint64_t sequence = 0;
        uint64_t generation = 0;
        bool resume = false;
        bool removed = false;
        bool running = false;
        int reason = 0;
    };
    struct Active {
        std::shared_ptr<Request> request;
        Request applied;
        uint64_t generation = 0;
        uint64_t due = 0;
        bool animated = false;
    };

    std::shared_ptr<const Catalog> catalog;
    std::unique_ptr<Aw20072> backend;
    WakeHook wake;
    ChangeCallback onChanged;
    AwLight engine;
    mutable std::mutex mutex;
    std::condition_variable changed;
    std::map<Key, std::shared_ptr<Request>> requests;
    std::deque<std::shared_ptr<Request>> terminals;
    size_t callbacksInFlight = 0;
    State state;
    uint64_t sequence = 0;
    uint64_t revision = 0;
    uint64_t generation = 0;
    bool stopping = false;
    bool faulted = false;
    bool recover = false;
    bool awake = false;
    std::optional<Active> active;
    std::thread worker;

    Impl(std::shared_ptr<const Catalog> source, std::unique_ptr<Aw20072> device, WakeHook wakeHook,
         ChangeCallback callback)
        : catalog(std::move(source)),
          backend(std::move(device)),
          wake(std::move(wakeHook)),
          onChanged(std::move(callback)),
          engine(catalog) {}

    void removeLocked(const std::shared_ptr<Request>& request, int reason) {
        if (!request->removed) {
            request->removed = true;
            request->reason = reason;
            terminals.push_back(request);
        }
        auto found = requests.find(request->key);
        if (found != requests.end() && found->second == request) requests.erase(found);
    }

    void expireLocked(uint64_t now) {
        for (auto it = requests.begin(); it != requests.end();) {
            auto request = it->second;
            ++it;
            if (request->expires && request->expires <= now) {
                removeLocked(request, ETIMEDOUT);
            }
        }
    }

    std::shared_ptr<Request> winnerLocked() const {
        std::shared_ptr<Request> winner;
        for (const auto& [key, request] : requests) {
            if (!winner || request->priority > winner->priority ||
                (request->priority == winner->priority && request->sequence > winner->sequence)) {
                winner = request;
            }
        }
        return winner;
    }

    uint64_t expiryLocked() const {
        uint64_t earliest = 0;
        for (const auto& [key, request] : requests) {
            if (request->expires && (!earliest || request->expires < earliest)) {
                earliest = request->expires;
            }
        }
        return earliest;
    }

    void publish(State next, bool report = true) {
        bool notify;
        {
            std::lock_guard lock(mutex);
            next.requestCount = requests.size();
            next.wakeHeld = awake;
            notify = next.owner != state.owner || next.requestId != state.requestId ||
                     next.effect != state.effect || next.active != state.active ||
                     next.animated != state.animated || next.error != state.error ||
                     next.generation != state.generation;
            state = next;
        }
        if (report && notify && onChanged) onChanged(next);
    }

    State describe(const Request& request, uint64_t currentGeneration, bool playing, bool animated,
                   int error) const {
        State next;
        next.owner = request.key.first;
        next.requestId = request.key.second;
        next.effect = request.effect;
        next.priority = request.priority;
        next.generation = currentGeneration;
        next.active = playing;
        next.animated = animated;
        next.error = error;
        return next;
    }

    void drainTerminals() {
        for (size_t delivered = 0; delivered < 64; ++delivered) {
            State ended;
            {
                std::lock_guard lock(mutex);
                auto ready = std::find_if(terminals.begin(), terminals.end(),
                                          [](const auto& request) { return !request->running; });
                if (ready == terminals.end()) return;
                const auto& request = *ready;
                ended = describe(*request, request->generation, false, false, request->reason);
                ended.requestCount = requests.size();
                ended.wakeHeld = awake;
                terminals.erase(ready);
                ++callbacksInFlight;
            }
            if (onChanged) onChanged(ended);
            {
                std::lock_guard lock(mutex);
                --callbacksInFlight;
            }
        }
    }

    int setWake(bool required) {
        if (required == awake) return 0;
        errno = 0;
        if (!wake || !wake(required)) return failureCode();
        awake = required;
        return 0;
    }

    void fail(int error) {
        if (error == ETIMEDOUT) error = EIO;
        State failed;
        if (active) {
            failed = describe(active->applied, active->generation, false, false, error);
        } else {
            std::lock_guard lock(mutex);
            failed = state;
            failed.active = false;
            failed.animated = false;
            failed.error = error;
        }
        engine.stop();
        if (backend) backend->stop();
        setWake(false);
        {
            std::lock_guard lock(mutex);
            if (active) {
                active->request->running = false;
                active->request->reason = error;
                removeLocked(active->request, error);
            }
            while (!requests.empty()) {
                removeLocked(requests.begin()->second, error);
            }
            faulted = true;
            recover = false;
        }
        active.reset();
        publish(failed, failed.requestId < 0);
    }

    bool finish(int reason) {
        State ended = describe(active->applied, active->generation, false, false, reason);
        engine.stop();
        errno = 0;
        if (!backend->stop()) {
            fail(failureCode());
            return false;
        }
        {
            std::lock_guard lock(mutex);
            active->request->running = false;
            if (!active->request->removed) ended = State{};
        }
        active.reset();
        publish(ended, false);
        return true;
    }

    bool eligible(const std::shared_ptr<Request>& request) {
        uint64_t now;
        if (!bootTime(&now)) return false;
        std::lock_guard lock(mutex);
        expireLocked(now);
        return !stopping && !request->removed && winnerLocked() == request;
    }

    void run() {
        errno = 0;
        if (!catalog || !backend || !wake) {
            fail(EINVAL);
        } else if (!backend->stop()) {
            fail(failureCode());
        }

        for (;;) {
            drainTerminals();
            std::shared_ptr<Request> winner;
            Request selected;
            uint64_t now = 0;
            uint64_t expiry = 0;
            uint64_t observed = 0;
            bool recovery = false;
            int reason = ECANCELED;
            {
                std::unique_lock lock(mutex);
                if (stopping) break;
                if (faulted && !recover) {
                    changed.wait(lock, [&] { return stopping || recover; });
                    continue;
                }
                recovery = faulted;
                recover = false;
                if (recovery) faulted = false;
                if (!bootTime(&now)) {
                    lock.unlock();
                    fail(failureCode());
                    continue;
                }
                expireLocked(now);
                winner = winnerLocked();
                if (winner) selected = *winner;
                if (active && active->request != winner) {
                    if (active->request->removed) {
                        reason = active->request->reason;
                    } else if (!active->request->resume) {
                        removeLocked(active->request, ECANCELED);
                    }
                }
                expiry = expiryLocked();
                observed = revision;
            }

            if (recovery) {
                errno = 0;
                if (!backend->stop()) {
                    fail(failureCode());
                    continue;
                }
            }
            if (active && active->request != winner) {
                if (!finish(reason)) continue;
            }

            bool render = winner && (!active || !active->animated || now >= active->due ||
                                     selected.options.strength == 0);
            if (active && !active->animated && selected.sequence == active->applied.sequence)
                render = false;

            if (!render) {
                int error = setWake((active && active->animated) || expiry);
                if (error) {
                    fail(error);
                    continue;
                }
                if (active) {
                    publish(describe(selected, active->generation, true, active->animated, 0));
                } else {
                    State idle;
                    {
                        std::lock_guard lock(mutex);
                        idle = state;
                    }
                    publish(idle);
                }
                uint64_t deadline = active && active->animated ? active->due : 0;
                if (expiry && (!deadline || expiry < deadline)) deadline = expiry;
                drainTerminals();
                std::unique_lock lock(mutex);
                if (revision != observed || stopping) continue;
                if (!deadline) {
                    changed.wait(lock, [&] { return stopping || revision != observed; });
                } else if (bootTime(&now) && deadline > now) {
                    changed.wait_for(lock, std::chrono::milliseconds(deadline - now),
                                     [&] { return stopping || revision != observed; });
                }
                continue;
            }

            if (!eligible(winner)) continue;
            if (!active) {
                {
                    std::lock_guard lock(mutex);
                    winner->running = true;
                }
                active = Active{winner, selected, selected.generation, 0, false};
                if (!engine.start(selected.effect, selected.options)) {
                    fail(EINVAL);
                    continue;
                }
            } else if (selected.sequence != active->applied.sequence) {
                if (!engine.update(selected.options)) {
                    fail(EINVAL);
                    continue;
                }
                active->applied = selected;
            }

            Output output;
            if (!engine.next(&output)) {
                int completion = 0;
                {
                    std::lock_guard lock(mutex);
                    if (bootTime(&now)) expireLocked(now);
                    if (active->request->removed) completion = active->request->reason;
                    removeLocked(active->request, completion);
                }
                finish(completion);
                continue;
            }
            {
                std::lock_guard lock(mutex);
                expiry = expiryLocked();
            }
            if (output.delayMs || expiry) {
                int error = setWake(true);
                if (error) {
                    fail(error);
                    continue;
                }
            }
            if (!eligible(winner)) continue;
            errno = 0;
            if (!backend->apply(output)) {
                fail(failureCode());
                continue;
            }
            if (!bootTime(&now)) {
                fail(failureCode());
                continue;
            }
            active->animated = output.delayMs != 0;
            active->due = output.delayMs ? now + output.delayMs : 0;
            {
                std::lock_guard lock(mutex);
                expiry = expiryLocked();
            }
            int error = setWake(active->animated || expiry);
            if (error) {
                fail(error);
                continue;
            }
            if (eligible(winner)) {
                publish(describe(active->applied, active->generation, true, active->animated, 0));
            }
        }

        if (active) {
            finish(ECANCELED);
        } else if (backend) {
            errno = 0;
            if (!backend->stop()) fail(failureCode());
        }
        int error = setWake(false);
        if (error) fail(error);
        drainTerminals();
    }
};

Controller::Controller(std::shared_ptr<const Catalog> catalog, std::unique_ptr<Aw20072> backend,
                       WakeHook wake, ChangeCallback onChanged)
    : mImpl(std::make_unique<Impl>(std::move(catalog), std::move(backend), std::move(wake),
                                   std::move(onChanged))) {
    mImpl->worker = std::thread([this] { mImpl->run(); });
}

Controller::~Controller() {
    {
        std::lock_guard lock(mImpl->mutex);
        mImpl->stopping = true;
        while (!mImpl->requests.empty()) {
            mImpl->removeLocked(mImpl->requests.begin()->second, ECANCELED);
        }
    }
    mImpl->changed.notify_one();
    mImpl->worker.join();
}

bool Controller::submit(uint64_t owner, int requestId, int effect, const Options& options,
                        int priority, int timeoutMs, bool resume) {
    if (!mImpl->catalog || !mImpl->backend || !mImpl->wake || requestId < 0 || requestId > 31 ||
        priority < 0 || priority > 100 || timeoutMs < 0 || timeoutMs > 60000)
        return false;
    AwLight validator(mImpl->catalog);
    if (!validator.start(effect, options)) return false;
    uint64_t now;
    if (!bootTime(&now)) return false;
    {
        std::lock_guard lock(mImpl->mutex);
        if (mImpl->stopping) return false;
        mImpl->expireLocked(now);
        Impl::Key key{owner, requestId};
        auto found = mImpl->requests.find(key);
        if (found == mImpl->requests.end() && mImpl->requests.size() >= 16) return false;
        bool replace = found == mImpl->requests.end() || found->second->effect != effect;
        if (replace &&
            mImpl->requests.size() + mImpl->terminals.size() + mImpl->callbacksInFlight >= 64)
            return false;
        std::shared_ptr<Impl::Request> request;
        if (found != mImpl->requests.end() && found->second->effect == effect) {
            request = found->second;
        } else {
            if (found != mImpl->requests.end()) mImpl->removeLocked(found->second, ECANCELED);
            request = std::make_shared<Impl::Request>();
            request->key = key;
            request->effect = effect;
            request->generation = ++mImpl->generation;
            mImpl->requests[key] = request;
        }
        request->options = options;
        request->priority = priority;
        request->expires = timeoutMs ? now + timeoutMs : 0;
        request->sequence = ++mImpl->sequence;
        request->resume = resume;
        mImpl->recover = true;
        ++mImpl->revision;
    }
    mImpl->changed.notify_one();
    return true;
}

bool Controller::cancel(uint64_t owner, int requestId) {
    {
        std::lock_guard lock(mImpl->mutex);
        auto found = mImpl->requests.find({owner, requestId});
        if (found == mImpl->requests.end()) return false;
        mImpl->removeLocked(found->second, ECANCELED);
        ++mImpl->revision;
    }
    mImpl->changed.notify_one();
    return true;
}

size_t Controller::clear(uint64_t owner) {
    size_t count = 0;
    {
        std::lock_guard lock(mImpl->mutex);
        for (auto it = mImpl->requests.begin(); it != mImpl->requests.end();) {
            auto request = it->second;
            ++it;
            if (request->key.first == owner) {
                mImpl->removeLocked(request, ECANCELED);
                ++count;
            }
        }
        if (count) ++mImpl->revision;
    }
    if (count) mImpl->changed.notify_one();
    return count;
}

Controller::State Controller::snapshot() const {
    std::lock_guard lock(mImpl->mutex);
    State result = mImpl->state;
    result.requestCount = mImpl->requests.size();
    return result;
}

}  // namespace meizu::aw_light
