#include <cassert>
#include <iostream>
#include "vyro/timeline/timeline_engine.hpp"

int main() {
    for (int i = 1; i <= 50; ++i) {
        vyro::timeline::TimelineEngine engine(i);
        const int64_t pid = engine.createProject("{\"fps\":30}");
        assert(pid > 0);
        assert(engine.submitCommand(pid, "{\"type\":\"AddClip\"}").ok());
        assert(engine.seek(pid, 1000000).ok());
        assert(!engine.seek(pid, -1).ok());
        assert(engine.play(pid).ok());
        assert(engine.pause(pid).ok());
        assert(engine.closeProject(pid).ok());
    }
    vyro::core::ClipTiming validTiming{0, 0, 3000000, {1, 1}};
    assert(vyro::timeline::TimelineEngine::validateClipTiming(validTiming).ok());

    vyro::core::ClipTiming invalidTiming{0, 3000000, 1000000, {1, 1}};
    assert(!vyro::timeline::TimelineEngine::validateClipTiming(invalidTiming).ok());

    std::cout << "vyro_engine_smoke_test passed" << std::endl;
    return 0;
}
