package com.vyro.core.database

import com.vyro.core.contracts.UndoHistoryPolicy
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.BlendMode
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.CoverSpec
import com.vyro.core.model.CropRect
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.TextAnimationPreset
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import com.vyro.core.model.VisualEffectPreset
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Handles versioned JSON serialization, integrity checksum verification, and
 * stepwise schema migrations (v1 -> v2 -> v3) for [ProjectDocument] and crash snapshots.
 */
object ProjectSchemaMigrator {

    data class ParsedDocument(
        val document: ProjectDocument,
        val migratedFromVersion: Int?,
        val undoPolicy: UndoHistoryPolicy,
        val recentCommandLabels: List<String>,
    )

    data class ParsedSnapshot(
        val document: ProjectDocument,
        val savedAtEpochMs: Long,
        val recentCommandLabels: List<String>,
    )

    fun serializeProject(
        doc: ProjectDocument,
        recentCommandLabels: List<String> = emptyList(),
    ): JSONObject {
        val root = serializePayload(doc, recentCommandLabels)
        val digest = computeIntegritySha256(root)
        root.put("integritySha256", digest)
        return root
    }

    fun serializeRecoverySnapshot(
        doc: ProjectDocument,
        savedAtEpochMs: Long,
        recentCommandLabels: List<String>,
    ): JSONObject {
        val docJson = serializePayload(doc, recentCommandLabels)
        val cmds = JSONArray().apply {
            recentCommandLabels.takeLast(UndoHistoryPolicy.MAX_PERSISTED_COMMAND_TRAIL).forEach { put(it) }
        }
        val wrapper = JSONObject().apply {
            put("snapshotSchemaVersion", ProjectDocument.CURRENT_SCHEMA_VERSION)
            put("savedAtEpochMs", savedAtEpochMs)
            put("undoPolicy", UndoHistoryPolicy.RESET_ON_PROCESS_RESTART.name)
            put("recentCommands", cmds)
            put("document", docJson)
        }
        wrapper.put("integritySha256", computeIntegritySha256(wrapper))
        return wrapper
    }

    fun deserializeProject(rawJson: String): ParsedDocument {
        require(rawJson.isNotBlank()) { "Project document JSON is empty" }
        val root = JSONObject(rawJson)
        val rawVersion = root.getInt("schemaVersion")
        if (rawVersion < 1 || rawVersion > ProjectDocument.CURRENT_SCHEMA_VERSION) {
            error("Unsupported schemaVersion $rawVersion")
        }

        // Verify SHA-256 integrity seal if present on document
        val storedDigest = if (root.has("integritySha256") && !root.isNull("integritySha256")) {
            root.optString("integritySha256", "")
        } else {
            ""
        }
        if (storedDigest.isNotBlank()) {
            val copyWithoutSeal = JSONObject(root.toString()).apply { remove("integritySha256") }
            val actualDigest = computeIntegritySha256(copyWithoutSeal)
            require(actualDigest == storedDigest) {
                "Project integrity checksum mismatch (expected $storedDigest, got $actualDigest)"
            }
        }

        val migratedFrom = if (rawVersion < ProjectDocument.CURRENT_SCHEMA_VERSION) rawVersion else null
        val migratedRoot = applyStepMigrations(root, rawVersion)
        val document = parseMigratedV3Document(migratedRoot)
        val recentCmds = buildList {
            val arr = migratedRoot.optJSONArray("recentCommands")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val undoPolicy = runCatching {
            UndoHistoryPolicy.valueOf(
                migratedRoot.optString("undoPolicy", UndoHistoryPolicy.RESET_ON_PROCESS_RESTART.name),
            )
        }.getOrDefault(UndoHistoryPolicy.RESET_ON_PROCESS_RESTART)

        return ParsedDocument(
            document = document,
            migratedFromVersion = migratedFrom,
            undoPolicy = undoPolicy,
            recentCommandLabels = recentCmds,
        )
    }

    fun deserializeRecoverySnapshot(rawJson: String): ParsedSnapshot {
        require(rawJson.isNotBlank()) { "Recovery snapshot JSON is empty" }
        val wrapper = JSONObject(rawJson)
        val storedDigest = wrapper.optString("integritySha256", "")
        require(storedDigest.isNotBlank()) { "Snapshot missing integrity checksum" }
        val withoutSeal = JSONObject(wrapper.toString()).apply { remove("integritySha256") }
        require(computeIntegritySha256(withoutSeal) == storedDigest) {
            "Snapshot integrity checksum mismatch"
        }
        val docObj = wrapper.getJSONObject("document")
        val parsed = deserializeProject(docObj.toString())
        val cmds = buildList {
            val arr = wrapper.optJSONArray("recentCommands")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        return ParsedSnapshot(
            document = parsed.document,
            savedAtEpochMs = wrapper.optLong("savedAtEpochMs", parsed.document.updatedAtEpochMs),
            recentCommandLabels = cmds.ifEmpty { parsed.recentCommandLabels },
        )
    }

    private fun applyStepMigrations(root: JSONObject, sourceVersion: Int): JSONObject {
        var current = JSONObject(root.toString())
        var version = sourceVersion
        if (version == 1) {
            current = migrateV1ToV2(current)
            version = 2
        }
        if (version == 2) {
            current = migrateV2ToV3(current)
        }
        return current
    }

    internal fun migrateV1ToV2(root: JSONObject): JSONObject {
        if (!root.has("assets") || root.isNull("assets")) {
            root.put("assets", JSONArray())
        }
        val seq = root.getJSONObject("sequence")
        if (!seq.has("coverLabel")) {
            seq.put("coverLabel", "Cover")
        }
        if (!seq.has("coverColorArgb")) {
            seq.put("coverColorArgb", 0xFF1E222AL)
        }
        val tracks = seq.optJSONArray("tracks") ?: JSONArray().also { seq.put("tracks", it) }
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            val clips = track.optJSONArray("clips") ?: JSONArray().also { track.put("clips", it) }
            for (j in 0 until clips.length()) {
                val clip = clips.getJSONObject(j)
                if (!clip.has("filterPreset")) clip.put("filterPreset", ColorFilterPreset.ORIGINAL.name)
                if (!clip.has("effectPreset")) clip.put("effectPreset", VisualEffectPreset.NONE.name)
                if (!clip.has("blendMode")) clip.put("blendMode", BlendMode.NORMAL.name)
                clip.optJSONObject("audioSpec")?.let { audio ->
                    if (!audio.has("pan")) audio.put("pan", 0.0)
                }
            }
        }
        root.put("schemaVersion", 2)
        return root
    }

    internal fun migrateV2ToV3(root: JSONObject): JSONObject {
        if (!root.has("revision")) {
            root.put("revision", 1L)
        }
        if (!root.has("undoPolicy")) {
            root.put("undoPolicy", UndoHistoryPolicy.RESET_ON_PROCESS_RESTART.name)
        }
        if (!root.has("recentCommands")) {
            root.put("recentCommands", JSONArray())
        }
        val seq = root.getJSONObject("sequence")
        val tracks = seq.optJSONArray("tracks") ?: JSONArray().also { seq.put("tracks", it) }
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            val trackId = track.optString("id", "track_$i")
            val clips = track.optJSONArray("clips") ?: JSONArray().also { track.put("clips", it) }
            for (j in 0 until clips.length()) {
                val clip = clips.getJSONObject(j)
                if (!clip.has("trackId")) clip.put("trackId", trackId)
                clip.optJSONObject("audioSpec")?.let { audio ->
                    if (!audio.has("pan")) audio.put("pan", 0.0)
                }
            }
        }
        root.put("schemaVersion", 3)
        return root
    }

    private fun parseMigratedV3Document(root: JSONObject): ProjectDocument {
        val projectId = root.getString("projectId").also {
            require(it.isNotBlank()) { "Missing projectId" }
        }
        val title = root.getString("title").also {
            require(it.isNotBlank()) { "Missing project title" }
        }
        val seqObj = root.getJSONObject("sequence")
        val seqWidth = seqObj.getInt("width")
        val seqHeight = seqObj.getInt("height")
        require(seqWidth > 0 && seqHeight > 0) { "Sequence dimensions must be positive" }

        val tracksJson = seqObj.getJSONArray("tracks")
        val tracks = buildList {
            for (i in 0 until tracksJson.length()) {
                val tObj = tracksJson.getJSONObject(i)
                val trackId = tObj.getString("id")
                val trackType = TrackType.valueOf(tObj.getString("type"))
                val clipsJson = tObj.getJSONArray("clips")
                val clips = buildList {
                    for (j in 0 until clipsJson.length()) {
                        val cObj = clipsJson.getJSONObject(j)
                        val timelineStart = cObj.getLong("timelineStartUs").coerceAtLeast(0L)
                        val sourceIn = cObj.getLong("sourceInUs").coerceAtLeast(0L)
                        val sourceOut = cObj.getLong("sourceOutUs")
                        require(sourceOut > sourceIn) {
                            "Clip ${cObj.optString("id")} has invalid source range ($sourceIn..$sourceOut)"
                        }
                        val mediaSource = cObj.optJSONObject("mediaSource")?.let { mObj ->
                            val rawAssetId = if (mObj.isNull("assetId")) null else mObj.optString("assetId", "")
                            val rawUri = if (mObj.isNull("uriString")) null else mObj.optString("uriString", "")
                            val rawMat = if (mObj.isNull("materialId")) null else mObj.optString("materialId", "")
                            MediaSourceRef(
                                assetId = rawAssetId?.takeIf { it.isNotBlank() },
                                mediaKind = runCatching {
                                    MediaKind.valueOf(mObj.optString("mediaKind", "MATERIAL"))
                                }.getOrDefault(MediaKind.MATERIAL),
                                uriString = rawUri?.takeIf { it.isNotBlank() },
                                materialId = rawMat?.takeIf { it.isNotBlank() },
                                backdropColorArgb = mObj.optLong("backdropColorArgb", 0xFFFFFFFFL),
                            )
                        } ?: MediaSourceRef()
                        val textSpec = cObj.optJSONObject("textSpec")?.let { txt ->
                            TextClipSpec(
                                text = txt.getString("text"),
                                fontId = txt.optString("fontId", "space_grotesk"),
                                colorArgb = txt.optLong("colorArgb", 0xFFFFFFFFL),
                                fontSizeSp = txt.optDouble("fontSizeSp", 28.0).toFloat(),
                                animationPreset = runCatching {
                                    TextAnimationPreset.valueOf(txt.optString("animationPreset", "NONE"))
                                }.getOrDefault(TextAnimationPreset.NONE),
                            )
                        }
                        val audioSpec = cObj.optJSONObject("audioSpec")?.let { audio ->
                            AudioClipSpec(
                                gainDb = audio.optDouble("gainDb", 0.0).toFloat(),
                                pan = audio.optDouble("pan", 0.0).toFloat().coerceIn(-1.0f, 1.0f),
                                fadeInUs = audio.optLong("fadeInUs", 0L).coerceAtLeast(0L),
                                fadeOutUs = audio.optLong("fadeOutUs", 0L).coerceAtLeast(0L),
                            )
                        }
                        val transitionIn = cObj.optJSONObject("transitionIn")?.let { trObj ->
                            com.vyro.core.model.TransitionSpec(
                                preset = runCatching {
                                    com.vyro.core.model.TransitionPreset.valueOf(trObj.optString("preset", "NONE"))
                                }.getOrDefault(com.vyro.core.model.TransitionPreset.NONE),
                                durationUs = trObj.optLong("durationUs", 500_000L).coerceIn(100_000L, 2_000_000L),
                            )
                        } ?: com.vyro.core.model.TransitionSpec()
                        val keyframes = cObj.optJSONArray("keyframes")?.let { kfArr ->
                            buildList {
                                for (k in 0 until kfArr.length()) {
                                    val kObj = kfArr.optJSONObject(k) ?: continue
                                    add(
                                        com.vyro.core.model.TransformKeyframe(
                                            offsetUs = kObj.optLong("offsetUs", 0L).coerceAtLeast(0L),
                                            normalizedX = kObj.optDouble("normX", 0.5).toFloat().coerceIn(0.05f, 0.95f),
                                            normalizedY = kObj.optDouble("normY", 0.5).toFloat().coerceIn(0.05f, 0.95f),
                                            scale = kObj.optDouble("scale", 1.0).toFloat().coerceIn(0.35f, 3.0f),
                                            rotationDegrees = kObj.optDouble("rotation", 0.0).toFloat(),
                                            opacity = kObj.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                                        ),
                                    )
                                }
                            }.sortedBy { it.offsetUs }
                        } ?: emptyList()
                        add(
                            Clip(
                                id = cObj.getString("id"),
                                trackId = cObj.optString("trackId", trackId),
                                label = cObj.getString("label"),
                                timing = ClipTiming(
                                    timelineStartUs = timelineStart,
                                    sourceInUs = sourceIn,
                                    sourceOutUs = sourceOut,
                                    playbackSpeed = Rational(
                                        cObj.optLong("speedNum", 1L).coerceAtLeast(1L),
                                        cObj.optLong("speedDen", 1L).coerceAtLeast(1L),
                                    ),
                                ),
                                mediaSource = mediaSource,
                                transform = LayerTransform(
                                    normalizedX = cObj.optDouble("normX", 0.5).toFloat(),
                                    normalizedY = cObj.optDouble("normY", 0.5).toFloat(),
                                    scale = cObj.optDouble("scale", 1.0).toFloat(),
                                    rotationDegrees = cObj.optDouble("rotation", 0.0).toFloat(),
                                    opacity = cObj.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                                    crop = CropRect(
                                        left = cObj.optDouble("cropLeft", 0.0).toFloat(),
                                        top = cObj.optDouble("cropTop", 0.0).toFloat(),
                                        right = cObj.optDouble("cropRight", 1.0).toFloat(),
                                        bottom = cObj.optDouble("cropBottom", 1.0).toFloat(),
                                    ).normalized(),
                                    blendMode = runCatching {
                                        BlendMode.valueOf(cObj.optString("blendMode", "NORMAL"))
                                    }.getOrDefault(BlendMode.NORMAL),
                                ),
                                textSpec = textSpec,
                                audioSpec = audioSpec,
                                filterPreset = runCatching {
                                    ColorFilterPreset.valueOf(cObj.optString("filterPreset", "ORIGINAL"))
                                }.getOrDefault(ColorFilterPreset.ORIGINAL),
                                effectPreset = runCatching {
                                    VisualEffectPreset.valueOf(cObj.optString("effectPreset", "NONE"))
                                }.getOrDefault(VisualEffectPreset.NONE),
                                transitionIn = transitionIn,
                                keyframesEnabled = cObj.optBoolean("keyframesEnabled", false),
                                keyframes = keyframes,
                                accentColorArgb = cObj.optLong("accentColorArgb", 0xFFFFFFFFL),
                            ),
                        )
                    }
                }
                add(
                    Track(
                        id = trackId,
                        name = tObj.getString("name"),
                        type = trackType,
                        zIndex = tObj.getInt("zIndex"),
                        isMuted = tObj.optBoolean("isMuted", false),
                        isLocked = tObj.optBoolean("isLocked", false),
                        clips = clips,
                    ),
                )
            }
        }

        val assetsJson = root.optJSONArray("assets")
        val assets = buildList {
            if (assetsJson != null) {
                for (i in 0 until assetsJson.length()) {
                    val aObj = assetsJson.getJSONObject(i)
                    val rawUri = if (aObj.isNull("uriString")) null else aObj.optString("uriString", "")
                    val rawMat = if (aObj.isNull("materialId")) null else aObj.optString("materialId", "")
                    val rawVCodec = if (aObj.isNull("videoCodec")) null else aObj.optString("videoCodec", "")
                    val rawACodec = if (aObj.isNull("audioCodec")) null else aObj.optString("audioCodec", "")
                    add(
                        MediaAsset(
                            assetId = aObj.getString("assetId"),
                            uriString = rawUri?.takeIf { it.isNotBlank() },
                            materialId = rawMat?.takeIf { it.isNotBlank() },
                            displayName = aObj.optString("displayName", "Asset"),
                            mediaKind = runCatching {
                                MediaKind.valueOf(aObj.optString("mediaKind", "MATERIAL"))
                            }.getOrDefault(MediaKind.MATERIAL),
                            mimeType = aObj.optString("mimeType", ""),
                            containerFormat = aObj.optString("containerFormat", ""),
                            videoCodec = rawVCodec?.takeIf { it.isNotBlank() },
                            audioCodec = rawACodec?.takeIf { it.isNotBlank() },
                            width = aObj.optInt("width", 0),
                            height = aObj.optInt("height", 0),
                            rotationDegrees = aObj.optInt("rotationDegrees", 0),
                            durationUs = aObj.optLong("durationUs", 0L),
                            nominalFps = aObj.optDouble("nominalFps", 30.0).toFloat(),
                            isVfrSuspected = aObj.optBoolean("isVfrSuspected", false),
                            audioSampleRateHz = aObj.optInt("audioSampleRateHz", 0),
                            audioChannelCount = aObj.optInt("audioChannelCount", 0),
                            bitrateBps = aObj.optLong("bitrateBps", 0L),
                            fileSizeBytes = aObj.optLong("fileSizeBytes", 0L),
                            colorInfo = aObj.optString("colorInfo", "SDR"),
                            isHdr = aObj.optBoolean("isHdr", false),
                            codecSupported = aObj.optBoolean("codecSupported", true),
                            availability = runCatching {
                                AssetAvailability.valueOf(aObj.optString("availability", "AVAILABLE"))
                            }.getOrDefault(AssetAvailability.AVAILABLE),
                            diagnosticNote = aObj.optString("diagnosticNote", ""),
                            addedAtEpochMs = aObj.optLong("addedAtEpochMs", 0L),
                        ),
                    )
                }
            }
        }

        return ProjectDocument(
            schemaVersion = ProjectDocument.CURRENT_SCHEMA_VERSION,
            projectId = projectId,
            title = title,
            createdAtEpochMs = root.getLong("createdAtEpochMs"),
            updatedAtEpochMs = root.getLong("updatedAtEpochMs"),
            sequence = Sequence(
                id = seqObj.getString("id"),
                width = seqWidth,
                height = seqHeight,
                frameRate = Rational(
                    seqObj.getLong("fpsNum").coerceAtLeast(1L),
                    seqObj.getLong("fpsDen").coerceAtLeast(1L),
                ),
                tracks = tracks,
                coverSpec = CoverSpec(
                    label = seqObj.optString("coverLabel", "Cover"),
                    accentColorArgb = seqObj.optLong("coverColorArgb", 0xFF1E222AL),
                ),
            ),
            assets = assets,
            revision = root.optLong("revision", 1L).coerceAtLeast(1L),
        )
    }

    private fun serializePayload(
        doc: ProjectDocument,
        recentCommandLabels: List<String>,
    ): JSONObject {
        val tracksArray = JSONArray()
        doc.sequence.tracks.forEach { track ->
            val clipsArray = JSONArray()
            track.clips.forEach { clip ->
                val clipJson = JSONObject().apply {
                    put("id", clip.id)
                    put("trackId", clip.trackId)
                    put("label", clip.label)
                    put("timelineStartUs", clip.timing.timelineStartUs)
                    put("sourceInUs", clip.timing.sourceInUs)
                    put("sourceOutUs", clip.timing.sourceOutUs)
                    put("speedNum", clip.timing.playbackSpeed.numerator)
                    put("speedDen", clip.timing.playbackSpeed.denominator)
                    put("normX", clip.transform.normalizedX.toDouble())
                    put("normY", clip.transform.normalizedY.toDouble())
                    put("scale", clip.transform.scale.toDouble())
                    put("rotation", clip.transform.rotationDegrees.toDouble())
                    put("opacity", clip.transform.opacity.toDouble())
                    put("cropLeft", clip.transform.crop.left.toDouble())
                    put("cropTop", clip.transform.crop.top.toDouble())
                    put("cropRight", clip.transform.crop.right.toDouble())
                    put("cropBottom", clip.transform.crop.bottom.toDouble())
                    put("blendMode", clip.transform.blendMode.name)
                    put("keyframesEnabled", clip.keyframesEnabled)
                    put(
                        "transitionIn",
                        JSONObject().apply {
                            put("preset", clip.transitionIn.preset.name)
                            put("durationUs", clip.transitionIn.durationUs)
                        },
                    )
                    if (clip.keyframes.isNotEmpty()) {
                        put(
                            "keyframes",
                            JSONArray().apply {
                                clip.keyframes.forEach { kf ->
                                    put(
                                        JSONObject().apply {
                                            put("offsetUs", kf.offsetUs)
                                            put("normX", kf.normalizedX.toDouble())
                                            put("normY", kf.normalizedY.toDouble())
                                            put("scale", kf.scale.toDouble())
                                            put("rotation", kf.rotationDegrees.toDouble())
                                            put("opacity", kf.opacity.toDouble())
                                        },
                                    )
                                }
                            },
                        )
                    }
                    put("accentColorArgb", clip.accentColorArgb)
                    put("filterPreset", clip.filterPreset.name)
                    put("effectPreset", clip.effectPreset.name)
                    put(
                        "mediaSource",
                        JSONObject().apply {
                            put("assetId", clip.mediaSource.assetId ?: JSONObject.NULL)
                            put("mediaKind", clip.mediaSource.mediaKind.name)
                            put("uriString", clip.mediaSource.uriString ?: JSONObject.NULL)
                            put("materialId", clip.mediaSource.materialId ?: JSONObject.NULL)
                            put("backdropColorArgb", clip.mediaSource.backdropColorArgb)
                        },
                    )
                    clip.textSpec?.let { txt ->
                        put(
                            "textSpec",
                            JSONObject().apply {
                                put("text", txt.text)
                                put("fontId", txt.fontId)
                                put("colorArgb", txt.colorArgb)
                                put("fontSizeSp", txt.fontSizeSp.toDouble())
                                put("animationPreset", txt.animationPreset.name)
                            },
                        )
                    }
                    clip.audioSpec?.let { audio ->
                        put(
                            "audioSpec",
                            JSONObject().apply {
                                put("gainDb", audio.gainDb.toDouble())
                                put("pan", audio.pan.toDouble())
                                put("fadeInUs", audio.fadeInUs)
                                put("fadeOutUs", audio.fadeOutUs)
                            },
                        )
                    }
                }
                clipsArray.put(clipJson)
            }
            tracksArray.put(
                JSONObject().apply {
                    put("id", track.id)
                    put("name", track.name)
                    put("type", track.type.name)
                    put("zIndex", track.zIndex)
                    put("isMuted", track.isMuted)
                    put("isLocked", track.isLocked)
                    put("clips", clipsArray)
                },
            )
        }

        val assetsArray = JSONArray()
        doc.assets.forEach { asset ->
            assetsArray.put(
                JSONObject().apply {
                    put("assetId", asset.assetId)
                    put("uriString", asset.uriString ?: JSONObject.NULL)
                    put("materialId", asset.materialId ?: JSONObject.NULL)
                    put("displayName", asset.displayName)
                    put("mediaKind", asset.mediaKind.name)
                    put("mimeType", asset.mimeType)
                    put("containerFormat", asset.containerFormat)
                    put("videoCodec", asset.videoCodec ?: JSONObject.NULL)
                    put("audioCodec", asset.audioCodec ?: JSONObject.NULL)
                    put("width", asset.width)
                    put("height", asset.height)
                    put("rotationDegrees", asset.rotationDegrees)
                    put("durationUs", asset.durationUs)
                    put("nominalFps", asset.nominalFps.toDouble())
                    put("isVfrSuspected", asset.isVfrSuspected)
                    put("audioSampleRateHz", asset.audioSampleRateHz)
                    put("audioChannelCount", asset.audioChannelCount)
                    put("bitrateBps", asset.bitrateBps)
                    put("fileSizeBytes", asset.fileSizeBytes)
                    put("colorInfo", asset.colorInfo)
                    put("isHdr", asset.isHdr)
                    put("codecSupported", asset.codecSupported)
                    put("availability", asset.availability.name)
                    put("diagnosticNote", asset.diagnosticNote)
                    put("addedAtEpochMs", asset.addedAtEpochMs)
                },
            )
        }

        val seqJson = JSONObject().apply {
            put("id", doc.sequence.id)
            put("width", doc.sequence.width)
            put("height", doc.sequence.height)
            put("fpsNum", doc.sequence.frameRate.numerator)
            put("fpsDen", doc.sequence.frameRate.denominator)
            put("coverLabel", doc.sequence.coverSpec.label)
            put("coverColorArgb", doc.sequence.coverSpec.accentColorArgb)
            put("tracks", tracksArray)
        }

        val cmdArray = JSONArray().apply {
            recentCommandLabels.takeLast(UndoHistoryPolicy.MAX_PERSISTED_COMMAND_TRAIL).forEach { put(it) }
        }

        return JSONObject().apply {
            put("schemaVersion", ProjectDocument.CURRENT_SCHEMA_VERSION)
            put("projectId", doc.projectId)
            put("title", doc.title)
            put("createdAtEpochMs", doc.createdAtEpochMs)
            put("updatedAtEpochMs", doc.updatedAtEpochMs)
            put("revision", doc.revision.coerceAtLeast(1L))
            put("undoPolicy", UndoHistoryPolicy.RESET_ON_PROCESS_RESTART.name)
            put("recentCommands", cmdArray)
            put("sequence", seqJson)
            put("assets", assetsArray)
        }
    }

    internal fun computeIntegritySha256(json: JSONObject): String {
        val canonical = canonicalString(json)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalString(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                    "${JSONObject.quote(key)}:${canonicalString(value.opt(key))}"
                }
            }
            is JSONArray -> {
                (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { idx ->
                    canonicalString(value.opt(idx))
                }
            }
            is String -> JSONObject.quote(value)
            is Int, is Long -> value.toString()
            is Float, is Double -> {
                val d = value.toDouble()
                if (d.isFinite() && d >= -9e15 && d <= 9e15 && d == d.toLong().toDouble()) {
                    d.toLong().toString()
                } else {
                    d.toString()
                }
            }
            else -> value.toString()
        }
    }
}
