#pragma once

#include <atomic>
#include <cstdint>

namespace RuntimeGameGraphicsOptions {

inline std::atomic<uint32_t>& DisabledPostProcessingPathsState() noexcept {
    static std::atomic<uint32_t> disabledMask{0};
    return disabledMask;
}

inline uint32_t DisabledPostProcessingPaths() noexcept {
    return DisabledPostProcessingPathsState().load(std::memory_order_relaxed);
}

inline void SetDisabledPostProcessingPaths(uint32_t disabledMask) noexcept {
    DisabledPostProcessingPathsState().store(disabledMask, std::memory_order_relaxed);
}

inline uint32_t FilterScnRendererPathMask(uint32_t pathMask) noexcept {
    return pathMask & ~(DisabledPostProcessingPaths() | 0x20u);
}

inline std::atomic<bool>& Force30FpsState() noexcept {
    static std::atomic<bool> force30Fps{false};
    return force30Fps;
}

inline bool Force30Fps() noexcept {
    return Force30FpsState().load(std::memory_order_relaxed);
}

inline void SetForce30Fps(bool enabled) noexcept {
    Force30FpsState().store(enabled, std::memory_order_relaxed);
}

} // namespace RuntimeGameGraphicsOptions
