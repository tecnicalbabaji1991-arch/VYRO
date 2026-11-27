package com.vyro.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.contracts.ProjectRepository
import com.vyro.core.model.ProjectDocument
import kotlinx.coroutines.flow.Flow
import java.io.File

@Entity(tableName = "projects")
data class ProjectIndexEntity(
    @PrimaryKey val projectId: String,
    val title: String,
    val width: Int,
    val height: Int,
    val fpsNumerator: Long,
    val fpsDenominator: Long,
    val durationUs: Long,
    val clipCount: Int,
    val updatedAtEpochMs: Long,
    val recoveredFromBackup: Boolean = false,
    val previewColorArgb: Long = 0xFF1C2029L,
    val previewUriString: String? = null,
    val missingAssetCount: Int = 0,
    val schemaVersion: Int = ProjectDocument.CURRENT_SCHEMA_VERSION,
    val hasPendingRecoverySnapshot: Boolean = false,
    val isCorrupt: Boolean = false,
)

@Entity(
    tableName = "media_assets",
    primaryKeys = ["projectId", "assetId"],
)
data class MediaAssetEntity(
    val projectId: String,
    val assetId: String,
    val uriString: String?,
    val displayName: String,
    val mediaKind: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val availability: String,
    val addedAtEpochMs: Long,
)

@Dao
interface ProjectIndexDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    fun observeAllProjects(): Flow<List<ProjectIndexEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    suspend fun getAllProjectsSnapshot(): List<ProjectIndexEntity>

    @Query("SELECT * FROM projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getById(projectId: String): ProjectIndexEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProjectIndexEntity)

    @Query("UPDATE projects SET title = :newTitle, updatedAtEpochMs = :updatedAt WHERE projectId = :projectId")
    suspend fun updateTitle(projectId: String, newTitle: String, updatedAt: Long)

    @Query(
        "UPDATE projects SET hasPendingRecoverySnapshot = :hasSnapshot, isCorrupt = :isCorrupt, " +
            "recoveredFromBackup = :recoveredFromBackup WHERE projectId = :projectId",
    )
    suspend fun updateRecoveryHealth(
        projectId: String,
        hasSnapshot: Boolean,
        isCorrupt: Boolean,
        recoveredFromBackup: Boolean,
    )

    @Query("DELETE FROM projects WHERE projectId = :projectId")
    suspend fun deleteById(projectId: String)
}

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets WHERE projectId = :projectId ORDER BY addedAtEpochMs ASC")
    suspend fun getAssetsForProject(projectId: String): List<MediaAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<MediaAssetEntity>)

    @Query("DELETE FROM media_assets WHERE projectId = :projectId")
    suspend fun deleteAssetsForProject(projectId: String)

    @Transaction
    suspend fun replaceAssetsForProject(projectId: String, assets: List<MediaAssetEntity>) {
        deleteAssetsForProject(projectId)
        if (assets.isNotEmpty()) {
            upsertAll(assets)
        }
    }
}

@Database(
    entities = [ProjectIndexEntity::class, MediaAssetEntity::class],
    version = 4,
    exportSchema = false,
)
internal abstract class VyroDatabase : RoomDatabase() {
    abstract fun projectIndexDao(): ProjectIndexDao
    abstract fun mediaAssetDao(): MediaAssetDao
}

object VyroDatabaseMigrations {
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE projects ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 3",
            )
            db.execSQL(
                "ALTER TABLE projects ADD COLUMN hasPendingRecoverySnapshot INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE projects ADD COLUMN isCorrupt INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
}

object VyroStorageModule {
    fun createProjectRepository(
        context: Context,
        rootDir: File,
        dispatchers: DispatchersProvider,
    ): ProjectRepository {
        val db = Room.databaseBuilder(
            context.applicationContext,
            VyroDatabase::class.java,
            "vyro_projects.db",
        )
            .addMigrations(VyroDatabaseMigrations.MIGRATION_3_4)
            .build()
        return LocalProjectRepository(
            dao = db.projectIndexDao(),
            assetDao = db.mediaAssetDao(),
            storage = AtomicProjectStorage(rootDir),
            dispatchers = dispatchers,
        )
    }
}
