#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include "vyro/core/types.hpp"

namespace vyro::project {

enum class TrackType {
    VIDEO,
    TEXT,
    AUDIO
};

struct ClipRecord {
    int64_t id{0};
    int64_t trackId{0};
    std::string label;
    vyro::core::ClipTiming timing{};
};

struct TrackRecord {
    int64_t id{0};
    TrackType type{TrackType::VIDEO};
    std::vector<ClipRecord> clips;
};

struct ProjectState {
    int64_t projectId{0};
    int32_t width{1920};
    int32_t height{1080};
    vyro::core::Rational frameRate{30, 1};
    std::vector<TrackRecord> tracks;

    [[nodiscard]] int64_t computeDurationUs() const noexcept;
};

}  // namespace vyro::project
