package com.bunah.kasir

import android.content.Context
import androidx.room.*

@Entity(tableName = "transactions")
data class Tx(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNo: String = "",
    val date: String, val time: String,
    val customer: String = "",
    val orderType: String = "Take Away", val tableNo: String = "",
    val discountType: String = "", val discountValue: Long = 0L,
    val itemsJson: String, val subtotal: Long, val total: Long,
    val paymentStatus: String
)

@Entity(tableName = "services")
data class Svc(
    @PrimaryKey val name: String,
    val desc: String, val price: Long
)

@Dao
interface TxDao {
    @Query("SELECT * FROM transactions ORDER BY id DESC") suspend fun all(): List<Tx>
    @Insert suspend fun insert(t: Tx)
    @Query("SELECT COALESCE(SUM(total),0) FROM transactions WHERE date=:date") suspend fun dailyTotal(date: String): Long
}

@Dao
interface SvcDao {
    @Query("SELECT * FROM services ORDER BY name") suspend fun all(): List<Svc>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: Svc)
    @Query("DELETE FROM services WHERE name=:n") suspend fun delete(n: String)
}

@Database(entities = [Tx::class, Svc::class], version = 3)
abstract class AppDb : RoomDatabase() {
    abstract fun tx(): TxDao
    abstract fun svc(): SvcDao

    companion object {
        @Volatile private var instance: AppDb? = null

        /** One connection per process, so rotating the screen doesn't open a new DB. */
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDb::class.java, "bunah.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
