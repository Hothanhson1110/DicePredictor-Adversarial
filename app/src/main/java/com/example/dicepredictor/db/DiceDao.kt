package com.example.dicepredictor.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface DiceDao {

    @Insert
    suspend fun insert(r: DiceResult): Long

    @Update
    suspend fun update(r: DiceResult)

    @Delete
    suspend fun delete(r: DiceResult)

    @Query("SELECT * FROM dice_results ORDER BY id ASC")
    suspend fun all(): List<DiceResult>

    @Query("SELECT * FROM dice_results ORDER BY id DESC LIMIT :n")
    suspend fun lastNDesc(n: Int): List<DiceResult>

    @Query("SELECT * FROM dice_results ORDER BY id DESC LIMIT 1")
    suspend fun latest(): DiceResult?

    @Query("SELECT COUNT(*) FROM dice_results")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM dice_results WHERE hasPred = 1")
    suspend fun countWithPrediction(): Int

    @Query("SELECT COUNT(*) FROM dice_results WHERE crowdChoice != '?'")
    suspend fun countWithCrowd(): Int

    @Query(
        "DELETE FROM dice_results WHERE id IN " +
        "(SELECT id FROM dice_results ORDER BY id ASC LIMIT :k)"
    )
    suspend fun deleteOldest(k: Int): Int

    @Query(
        "DELETE FROM dice_results WHERE id IN " +
        "(SELECT id FROM dice_results ORDER BY id DESC LIMIT :k)"
    )
    suspend fun deleteLast(k: Int): Int

    @Query("DELETE FROM dice_results")
    suspend fun clearAll()
}
