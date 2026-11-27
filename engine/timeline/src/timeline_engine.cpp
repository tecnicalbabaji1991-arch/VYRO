#include "vyro/timeline/timeline_engine.hpp"

namespace vyro::timeline {

TimelineEngine::TimelineEngine(int64_t handleId) : handleId_(handleId) {}

int64_t TimelineEngine::createProject(const std::string& /*configJson*/) {
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t id = nextProjectId_++;
    vyro::project::ProjectState state;
    state.projectId = id;
    state.tracks.push_back({1, vyro::project::TrackType::VIDEO, {}});
    state.tracks.push_back({2, vyro::project::TrackType::TEXT, {}});
    state.tracks.push_back({3, vyro::project::TrackType::AUDIO, {}});
    projects_[id] = std::move(state);
    activeProjectId_ = id;
    playheadUs_ = 0;
    isPlaying_ = false;
    return id;
}

vyro::core::EngineResult TimelineEngine::openProject(int64_t projectId) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (projectId <= 0) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Project ID must be positive"};
    }
    if (projects_.find(projectId) == projects_.end()) {
        vyro::project::ProjectState state;
        state.projectId = projectId;
        projects_[projectId] = std::move(state);
    }
    activeProjectId_ = projectId;
    playheadUs_ = 0;
    isPlaying_ = false;
    return {};
}

vyro::core::EngineResult TimelineEngine::closeProject(int64_t projectId) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = projects_.find(projectId);
    if (it == projects_.end()) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Project session not open"};
    }
    projects_.erase(it);
    if (activeProjectId_ == projectId) {
        activeProjectId_ = 0;
        isPlaying_ = false;
        playheadUs_ = 0;
    }
    return {};
}

vyro::core::EngineResult TimelineEngine::submitCommand(int64_t projectId, const std::string& commandJson) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (projects_.find(projectId) == projects_.end()) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Cannot submit command to unopened project"};
    }
    if (commandJson.empty()) {
        return {vyro::core::EngineErrorCode::INVALID_TIMELINE_EDIT, "Command payload cannot be empty"};
    }
    commandCount_++;
    return {};
}

vyro::core::EngineResult TimelineEngine::seek(int64_t projectId, int64_t positionUs) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (projects_.find(projectId) == projects_.end()) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Project not active"};
    }
    if (positionUs < 0) {
        return {vyro::core::EngineErrorCode::INVALID_TIMELINE_EDIT, "Seek position cannot be negative"};
    }
    playheadUs_ = positionUs;
    return {};
}

vyro::core::EngineResult TimelineEngine::play(int64_t projectId) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (projects_.find(projectId) == projects_.end()) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Project not active"};
    }
    isPlaying_ = true;
    return {};
}

vyro::core::EngineResult TimelineEngine::pause(int64_t projectId) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (projects_.find(projectId) == projects_.end()) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Project not active"};
    }
    isPlaying_ = false;
    return {};
}

vyro::core::EngineResult TimelineEngine::attachPreviewSurface(int64_t projectId, bool hasSurface) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (projects_.find(projectId) == projects_.end() || !hasSurface) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Invalid surface or project"};
    }
    surfaceAttached_ = true;
    return {};
}

vyro::core::EngineResult TimelineEngine::detachPreviewSurface(int64_t projectId) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (projects_.find(projectId) == projects_.end()) {
        return {vyro::core::EngineErrorCode::INVALID_STATE, "Project not active"};
    }
    surfaceAttached_ = false;
    return {};
}

vyro::contracts::SessionTelemetry TimelineEngine::telemetry() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return {
        handleId_,
        activeProjectId_,
        playheadUs_,
        isPlaying_,
        surfaceAttached_,
        commandCount_
    };
}

vyro::core::EngineResult TimelineEngine::validateClipTiming(const vyro::core::ClipTiming& timing) {
    if (!timing.isValid()) {
        return {vyro::core::EngineErrorCode::INVALID_TIMELINE_EDIT, "Invalid clip timing boundaries"};
    }
    return {};
}

}  // namespace vyro::timeline
