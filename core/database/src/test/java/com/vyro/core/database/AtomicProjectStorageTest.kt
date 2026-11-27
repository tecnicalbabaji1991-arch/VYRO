package com.vyro.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.DefaultDispatchersProvider
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.CommandHistory
import com.vyro.core.contracts.RecoverySource
import com.vyro.core.contracts.UndoHistoryPolicy
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.BlendMode
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtomicProjectStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleDoc(title: String, revision: Long = 1L): ProjectDocument = ProjectDocument(
        projectId = "proj_test_1",
        title = title,
        createdAtEpochMs = 1000L,
        updatedAtEpochMs = 2000L,
        revision = revision,
        sequence = Sequence(
            id = "seq_1",
            width = 1920,
            height = 1080,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "t_vid",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 1,
                    clips = listOf(
                        Clip(
                            id = "c_1",
                            trackId = "t_vid",
                            label = "Scene 1",
                            timing = ClipTiming(0L, 0L, 2_000_000L),
                        ),
                    ),
                ),
            ),
        ),
        assets = listOf(
            MediaAsset(
                assetId = "asset_1",
                uriString = "content://media/external/video/media/42",
                displayName = "Clip 42",
                mediaKind = MediaKind.VIDEO,
                mimeType = "video/mp4",
                videoCodec = "video/avc",
                width = 1920,
                height = 1080,
                durationUs = 2_000_000L,
                availability = AssetAvailability.AVAILABLE,
            ),
        ),
    )

    @Test
    fun `atomic write creates backup and recovers automatically when primary is corrupted`() {
        val storage = AtomicProjectStorage(tempFolder.root)
        assertTrue(storage.writeAtomic(sampleDoc("Initial Cut", revision = 1L)).isSuccess)
        assertTrue(storage.writeAtomic(sampleDoc("Second Cut", revision = 2L)).isSuccess)

        val primary = File(storage.projectDir("proj_test_1"), "project.json")
        primary.writeText("{ CORRUPTED_JSON")

        val recovered = storage.readWithRecovery("proj_test_1")
        assertTrue(recovered is EngineResult.Success)
        val outcome = (recovered as EngineResult.Success).value
        assertTrue(outcome.recoveredFromBackup)
        assertEquals(RecoverySource.BACKUP_FILE, outcome.recoverySource)
        assertEquals("Initial Cut", outcome.document.title)
        assertEquals(1, outcome.document.assets.size)
        assertEquals("asset_1", outcome.document.assets.first().assetId)
        assertFalse(outcome.document.sequence.tracks.isEmpty())
    }

    @Test
    fun `tampered checksum triggers automatic backup recovery instead of loading corrupted fields`() {
        val storage = AtomicProjectStorage(tempFolder.root)
        assertTrue(storage.writeAtomic(sampleDoc("Trusted Cut", revision = 1L)).isSuccess)
        assertTrue(storage.writeAtomic(sampleDoc("Clean Rev 2", revision = 2L)).isSuccess)

        val primary = File(storage.projectDir("proj_test_1"), "project.json")
        primary.writeText(primary.readText().replace("Clean Rev 2", "Tampered Cut"))

        val recovered = storage.readWithRecovery("proj_test_1")
        assertTrue(recovered is EngineResult.Success)
        val outcome = (recovered as EngineResult.Success).value
        assertEquals(RecoverySource.BACKUP_FILE, outcome.recoverySource)
        assertEquals("Trusted Cut", outcome.document.title)
    }

    @Test
    fun `forced process termination recovers recovery snapshot cleans temp files and allows baseline revert`() {
        val storage = AtomicProjectStorage(tempFolder.root)
        assertTrue(storage.writeAtomic(sampleDoc("Baseline Save", revision = 1L)).isSuccess)
        assertTrue(
            storage.writeRecoverySnapshot(
                document = sampleDoc("Unsaved Crash Cut", revision = 3L),
                recentCommandLabels = listOf("SplitClip", "UpdateFilter"),
            ).isSuccess,
        )

        // Simulate incomplete temp files left behind by mid-write process kill
        val dir = storage.projectDir("proj_test_1")
        File(dir, "project.json.tmp").writeText("{ partial...")
        File(dir, "recovery_snapshot.json.tmp").writeText("{ incomplete...")

        val recovered = storage.readWithRecovery("proj_test_1")
        assertTrue(recovered is EngineResult.Success)
        val outcome = (recovered as EngineResult.Success).value
        assertEquals(RecoverySource.RECOVERY_SNAPSHOT, outcome.recoverySource)
        assertEquals("Unsaved Crash Cut", outcome.document.title)
        assertEquals(3L, outcome.document.revision)
        assertEquals(listOf("SplitClip", "UpdateFilter"), outcome.recoveredCommandLabels)
        assertTrue(outcome.hasCommittedBaseline)
        assertFalse(File(dir, "project.json.tmp").exists())
        assertFalse(File(dir, "recovery_snapshot.json.tmp").exists())

        // Undo stack policy enforces session reset after process restart
        val restoredSessionHistory = CommandHistory(outcome.document.sequence)
        assertEquals(UndoHistoryPolicy.RESET_ON_PROCESS_RESTART, restoredSessionHistory.undoPolicy)
        assertFalse(restoredSessionHistory.canUndo)

        // Reverting discards crash snapshot and restores clean committed baseline
        val reverted = storage.discardRecoverySnapshot("proj_test_1")
        assertTrue(reverted is EngineResult.Success)
        assertEquals("Baseline Save", (reverted as EngineResult.Success).value.document.title)
    }

    @Test
    fun `schema v1 and v2 documents upgrade cleanly to schema v3`() {
        val storage = AtomicProjectStorage(tempFolder.root)
        val dirV1 = storage.projectDir("proj_v1").apply { mkdirs() }
        File(dirV1, "project.json").writeText(
            """
            {
              "schemaVersion": 1,
              "projectId": "proj_v1",
              "title": "Legacy V1",
              "createdAtEpochMs": 1000,
              "updatedAtEpochMs": 1000,
              "sequence": {
                "id": "seq_v1",
                "width": 1080,
                "height": 1920,
                "fpsNum": 30,
                "fpsDen": 1,
                "tracks": [
                  {
                    "id": "t_1",
                    "name": "Video",
                    "type": "VIDEO",
                    "zIndex": 1,
                    "clips": [
                      {
                        "id": "c_v1",
                        "label": "Old Clip",
                        "timelineStartUs": 0,
                        "sourceInUs": 0,
                        "sourceOutUs": 1500000
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val resV1 = storage.readWithRecovery("proj_v1")
        assertTrue(resV1 is EngineResult.Success)
        val outV1 = (resV1 as EngineResult.Success).value
        assertEquals(1, outV1.migratedFromVersion)
        assertEquals(ProjectDocument.CURRENT_SCHEMA_VERSION, outV1.document.schemaVersion)
        assertEquals("Legacy V1", outV1.document.title)
        assertEquals(1L, outV1.document.revision)
        assertEquals(BlendMode.NORMAL, outV1.document.sequence.tracks.first().clips.first().transform.blendMode)
        assertTrue(outV1.document.assets.isEmpty())

        val dirV2 = storage.projectDir("proj_v2").apply { mkdirs() }
        File(dirV2, "project.json").writeText(
            """
            {
              "schemaVersion": 2,
              "projectId": "proj_v2",
              "title": "Legacy V2",
              "createdAtEpochMs": 1000,
              "updatedAtEpochMs": 2000,
              "assets": [],
              "sequence": {
                "id": "seq_v2",
                "width": 1920,
                "height": 1080,
                "fpsNum": 60,
                "fpsDen": 1,
                "coverLabel": "Cover",
                "coverColorArgb": 4280164906,
                "tracks": []
              }
            }
            """.trimIndent(),
        )
        val resV2 = storage.readWithRecovery("proj_v2")
        assertTrue(resV2 is EngineResult.Success)
        val outV2 = (resV2 as EngineResult.Success).value
        assertEquals(2, outV2.migratedFromVersion)
        assertEquals(ProjectDocument.CURRENT_SCHEMA_VERSION, outV2.document.schemaVersion)
        assertEquals("Legacy V2", outV2.document.title)
    }

    @Test
    fun `unsupported future schema and total corruption fail safely without throwing`() {
        val storage = AtomicProjectStorage(tempFolder.root)
        val futureDir = storage.projectDir("proj_future").apply { mkdirs() }
        File(futureDir, "project.json").writeText("""{"schemaVersion":99,"projectId":"proj_future","title":"Future"}""")
        val futureRes = storage.readWithRecovery("proj_future")
        assertTrue(futureRes is EngineResult.Failure)
        assertEquals(EngineErrorCode.CORRUPT_PROJECT, (futureRes as EngineResult.Failure).error.code)

        val brokenDir = storage.projectDir("proj_broken").apply { mkdirs() }
        File(brokenDir, "project.json").writeText("NOT_JSON_AT_ALL")
        File(brokenDir, "project.backup.json").writeText("ALSO_BROKEN")
        val brokenRes = storage.readWithRecovery("proj_broken")
        assertTrue(brokenRes is EngineResult.Failure)
        assertEquals(EngineErrorCode.CORRUPT_PROJECT, (brokenRes as EngineResult.Failure).error.code)
        val health = storage.inspectProjectHealth("proj_broken")
        assertTrue(health.isCorrupt)
        assertFalse(health.isReadable)
    }

    @Test
    fun `room v3 to v4 migration preserves indexed project rows without data loss`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "vyro_migration_test.db"
        context.deleteDatabase(dbName)

        // Build Room v3 schema database manually, insert row, and close
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS projects (
                                    projectId TEXT NOT NULL PRIMARY KEY,
                                    title TEXT NOT NULL,
                                    width INTEGER NOT NULL,
                                    height INTEGER NOT NULL,
                                    fpsNumerator INTEGER NOT NULL,
                                    fpsDenominator INTEGER NOT NULL,
                                    durationUs INTEGER NOT NULL,
                                    clipCount INTEGER NOT NULL,
                                    updatedAtEpochMs INTEGER NOT NULL,
                                    recoveredFromBackup INTEGER NOT NULL,
                                    previewColorArgb INTEGER NOT NULL,
                                    previewUriString TEXT,
                                    missingAssetCount INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS media_assets (
                                    projectId TEXT NOT NULL,
                                    assetId TEXT NOT NULL,
                                    uriString TEXT,
                                    displayName TEXT NOT NULL,
                                    mediaKind TEXT NOT NULL,
                                    mimeType TEXT NOT NULL,
                                    width INTEGER NOT NULL,
                                    height INTEGER NOT NULL,
                                    durationUs INTEGER NOT NULL,
                                    availability TEXT NOT NULL,
                                    addedAtEpochMs INTEGER NOT NULL,
                                    PRIMARY KEY(projectId, assetId)
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO projects VALUES (
                                    'proj_mig_1', 'Migrated Draft', 1080, 1920, 30, 1,
                                    4000000, 1, 9000, 0, -1, NULL, 0
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.use { db ->
            assertEquals(3, db.version)
            VyroDatabaseMigrations.MIGRATION_3_4.migrate(db)
            db.query("SELECT title, schemaVersion, hasPendingRecoverySnapshot, isCorrupt FROM projects WHERE projectId = 'proj_mig_1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Migrated Draft", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
            }
        }
        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `repository indexes missing assets and relinks replacement URI`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = VyroStorageModule.createProjectRepository(
            context = context,
            rootDir = tempFolder.newFolder("repo_root"),
            dispatchers = DefaultDispatchersProvider,
        )
        val missingAsset = MediaAsset(
            assetId = "asset_lost",
            uriString = "content://media/lost/1",
            displayName = "Lost Shot",
            mediaKind = MediaKind.VIDEO,
            availability = AssetAvailability.MISSING,
        )
        val clip = Clip(
            id = "clip_lost",
            trackId = "track_video",
            label = "Lost Shot",
            timing = ClipTiming(0L, 0L, 3_000_000L),
            mediaSource = MediaSourceRef(
                assetId = "asset_lost",
                mediaKind = MediaKind.VIDEO,
                uriString = "content://media/lost/1",
            ),
        )
        val created = (repo.createProject(
            title = "Relink Test",
            aspectPreset = AspectRatioPreset.PORTRAIT_9_16,
            frameRate = Rational.FPS_30,
            seedStarterTimeline = false,
            initialClips = listOf(clip),
            initialAssets = listOf(missingAsset),
        ) as EngineResult.Success).value

        val initialSummaries = repo.observeProjects().first()
        assertEquals(1, initialSummaries.first().missingAssetCount)

        val replacement = MediaAsset(
            assetId = "ignored_new",
            uriString = "content://media/restored/99",
            displayName = "Restored Shot",
            mediaKind = MediaKind.VIDEO,
            availability = AssetAvailability.AVAILABLE,
        )
        val relinked = repo.relinkProjectAsset(created.projectId, "asset_lost", replacement)
        assertTrue(relinked is EngineResult.Success)
        val updatedDoc = (relinked as EngineResult.Success).value
        assertEquals("content://media/restored/99", updatedDoc.assets.first().uriString)
        assertEquals(
            "content://media/restored/99",
            updatedDoc.sequence.tracks.first { it.type == TrackType.VIDEO }.clips.first().mediaSource.uriString,
        )
        val updatedSummaries = repo.observeProjects().first()
        assertEquals(0, updatedSummaries.first().missingAssetCount)
    }

    @Test
    fun `atomic storage preserves clip transitions and keyframes across save and load`() {
        val storage = AtomicProjectStorage(tempFolder.root)
        val base = sampleDoc("Phase 10 Roundtrip", revision = 4L)
        val enrichedClip = base.sequence.tracks.first().clips.first().copy(
            transitionIn = com.vyro.core.model.TransitionSpec(
                preset = com.vyro.core.model.TransitionPreset.CROSS_DISSOLVE,
                durationUs = 600_000L,
            ),
            keyframesEnabled = true,
            keyframes = listOf(
                com.vyro.core.model.TransformKeyframe(0L, normalizedX = 0.25f, scale = 1.0f),
                com.vyro.core.model.TransformKeyframe(1_200_000L, normalizedX = 0.75f, scale = 1.6f, opacity = 0.8f),
            ),
        )
        val doc = base.copy(
            sequence = base.sequence.copy(
                tracks = listOf(base.sequence.tracks.first().copy(clips = listOf(enrichedClip))),
            ),
        )
        assertTrue(storage.writeAtomic(doc).isSuccess)

        val read = storage.readWithRecovery(doc.projectId)
        assertTrue(read is EngineResult.Success)
        val loadedClip = (read as EngineResult.Success).value.document.sequence.tracks.first().clips.first()
        assertEquals(com.vyro.core.model.TransitionPreset.CROSS_DISSOLVE, loadedClip.transitionIn.preset)
        assertEquals(600_000L, loadedClip.transitionIn.durationUs)
        assertTrue(loadedClip.keyframesEnabled)
        assertEquals(2, loadedClip.keyframes.size)
        assertEquals(1.6f, loadedClip.keyframes[1].scale, 0.001f)
    }
}
