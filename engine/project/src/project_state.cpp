#include "vyro/project/project_state.hpp"
#include <algorithm>

namespace vyro::project {

int64_t ProjectState::computeDurationUs() const noexcept {
    int64_t maxEndUs = 0;
    for (const auto& track : tracks) {
        for (const auto& clip : track.clips) {
            const int64_t endUs = clip.timing.timelineStartUs + clip.timing.durationUs();
            if (endUs > maxEndUs) {
                maxEndUs = endUs;
            }
        }
    }
    return maxEndUs;
}

}  // namespace vyro::project
