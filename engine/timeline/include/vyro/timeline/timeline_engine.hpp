#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include "vyro/contracts/engine_contract.hpp"
#include "vyro/project/project_state.hpp"

namespace vyro::timeline {

class TimelineEngine final : public vyro::contracts::IEngineSession {
public:
    explicit TimelineEngine(int64_t handleId);
    ~TimelineEngine() override = default;

    int64_t createProject(const std::string& configJson) override;
    vyro::core::EngineResult openProject(int64_t projectId) override;
    vyro::core::EngineResult closeProject(int64_t projectId) override;
    vyro::core::EngineResult submitCommand(int64_t projectId, const std::string& commandJson) override;
    vyro::core::EngineResult seek(int64_t projectId, int64_t positionUs) override;
    vyro::core::EngineResult play(int64_t projectId) override;
    vyro::core::EngineResult pause(int64_t projectId) override;
    vyro::contracts::SessionTelemetry telemetry() const override;

    vyro::core::EngineResult attachPreviewSurface(int64_t projectId, bool hasSurface);
    vyro::core::EngineResult detachPreviewSurface(int64_t projectId);

    static vyro::core::EngineResult validateClipTiming(const vyro::core::ClipTiming& timing);

private:
    mutable std::mutex mutex_;
    int64_t handleId_{0};
    int64_t nextProjectId_{1};
    int64_t activeProjectId_{0};
    int64_t playheadUs_{0};
    bool isPlaying_{false};
    bool surfaceAttached_{false};
    int64_t commandCount_{0};
    std::unordered_map<int64_t, vyro::project::ProjectState> projects_;
};

}  // namespace vyro::timeline
