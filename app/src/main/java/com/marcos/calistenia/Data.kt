package com.marcos.calistenia
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Exercise(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val isTime: Boolean)
@Entity data class SetEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val exerciseId: Long, val day: Long,
    val value: Int, val extraKg: Float, val note: String, val ts: Long = System.currentTimeMillis())
@Entity data class BodyWeight(@PrimaryKey val day: Long, val kg: Float)

@Dao interface D {
    @Query("SELECT * FROM Exercise ORDER BY name") fun exercises(): Flow<List<Exercise>>
    @Query("SELECT * FROM SetEntry ORDER BY ts") fun sets(): Flow<List<SetEntry>>
    @Query("SELECT * FROM BodyWeight ORDER BY day") fun weights(): Flow<List<BodyWeight>>
    @Insert suspend fun addEx(e: Exercise)
    @Insert suspend fun addSet(s: SetEntry)
    @Delete suspend fun delSet(s: SetEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putW(w: BodyWeight)
}
@Database(entities = [Exercise::class, SetEntry::class, BodyWeight::class], version = 1, exportSchema = false)
abstract class DB : RoomDatabase() { abstract fun d(): D }
