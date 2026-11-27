#pragma once

#include <cstdint>
#include <string>

namespace vyro::core {

struct Rational {
    int64_t numerator{30};
    int64_t denominator{1};

    [[nodiscard]] bool isValid() const noexcept {
        return numerator > 0 && denominator > 0;
    }

    [[nodiscard]] int64_t frameDurationUs() const noexcept {
        if (!isValid()) return 33333;
        return (1000000LL * denominator) / numerator;
    }
};

struct TimeRange {
    int64_t startUs{0};
    int64_t durationUs{0};

    [[nodiscard]] bool isValid() const noexcept {
        return startUs >= 0 && durationUs >= 0;
    }
};

struct ClipTiming {
    int64_t timelineStartUs{0};
    int64_t sourceInUs{0};
    int64_t sourceOutUs{0};
    Rational playbackSpeed{1, 1};

    [[nodiscard]] int64_t durationUs() const noexcept {
        return sourceOutUs > sourceInUs ? (sourceOutUs - sourceInUs) : 0;
    }

    [[nodiscard]] bool isValid() const noexcept {
        return timelineStartUs >= 0 &&
               sourceInUs >= 0 &&
               sourceOutUs > sourceInUs &&
               playbackSpeed.isValid();
    }
};

enum class EngineErrorCode : int32_t {
    OK = 0,
    INVALID_STATE = 1001,
    INVALID_TIMELINE_EDIT = 1002,
    CORRUPT_PROJECT = 1003,
    STORAGE_FAILURE = 1004,
    UNSUPPORTED_CAPABILITY = 1005,
    NATIVE_BRIDGE_FAILURE = 1006
};

struct EngineResult {
    EngineErrorCode code{EngineErrorCode::OK};
    std::string message{"OK"};

    [[nodiscard]] bool ok() const noexcept { return code == EngineErrorCode::OK; }
    [[nodiscard]] int32_t rawCode() const noexcept { return static_cast<int32_t>(code); }
};

}  // namespace vyro::core
