#pragma once

#include <cstdint>
#include <string>
#include "vyro/core/types.hpp"

namespace vyro::contracts {

struct SessionTelemetry {
    int64_t engineHandle{0};
    int64_t activeProjectId{0};
    int64_t playheadUs{0};
    bool isPlaying{false};
    bool surfaceAttached{false};
    int64_t commandCount{0};
};

class IEngineSession {
public:
    virtual ~IEngineSession() = default;

    virtual int64_t createProject(const std::string& configJson) = 0;
    virtual vyro::core::EngineResult openProject(int64_t projectId) = 0;
    virtual vyro::core::EngineResult closeProject(int64_t projectId) = 0;
    virtual vyro::core::EngineResult submitCommand(int64_t projectId, const std::string& commandJson) = 0;
    virtual vyro::core::EngineResult seek(int64_t projectId, int64_t positionUs) = 0;
    virtual vyro::core::EngineResult play(int64_t projectId) = 0;
    virtual vyro::core::EngineResult pause(int64_t projectId) = 0;
    virtual SessionTelemetry telemetry() const = 0;
};

}  // namespace vyro::contracts
