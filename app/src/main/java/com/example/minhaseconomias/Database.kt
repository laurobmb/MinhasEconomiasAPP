package com.example.minhaseconomias

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- 1. MODELO DE DADOS PARA O BANCO DE DADOS LOCAL ---
@Entity(tableName = "movimentacoes")
data class Movimentacao(
    @PrimaryKey(autoGenerate = true) val localId: Int = 0,
    @ColumnInfo(index = true) var serverId: Int?,
    var dataOcorrencia: String,
    var descricao: String,
    var valor: Double,
    var categoria: String,
    var conta: String,
    var isSynced: Boolean = false,
    var isDeleted: Boolean = false
)

// --- 2. DAO (DATA ACCESS OBJECT) ---
@Dao
interface MovimentacaoDao {
    @Query("SELECT * FROM movimentacoes WHERE isDeleted = 0 ORDER BY dataOcorrencia DESC, localId DESC")
    fun getAll(): Flow<List<Movimentacao>>

    @Query("SELECT * FROM movimentacoes WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsynced(): List<Movimentacao>

    @Query("SELECT * FROM movimentacoes WHERE isDeleted = 1 AND serverId IS NOT NULL")
    suspend fun getDeleted(): List<Movimentacao>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movimentacoes: List<Movimentacao>)

    @Update
    suspend fun update(movimentacao: Movimentacao)

    @Query("DELETE FROM movimentacoes WHERE localId = :localId")
    suspend fun deleteByLocalId(localId: Int)

    @Query("DELETE FROM movimentacoes")
    suspend fun deleteAll()
}

// Adicione estas duas data classes no arquivo Database.kt
@Entity(tableName = "categorias_sugeridas")
data class CategoriaSugerida(
    @PrimaryKey val nome: String
)

@Entity(tableName = "contas_sugeridas")
data class ContaSugerida(
    @PrimaryKey val nome: String
)

// Adicione estas duas interfaces de DAO no arquivo Database.kt
@Dao
interface CategoriaDao {
    @Query("SELECT * FROM categorias_sugeridas ORDER BY nome")
    fun getAll(): Flow<List<CategoriaSugerida>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categorias: List<CategoriaSugerida>)
}

@Dao
interface ContaDao {
    @Query("SELECT * FROM contas_sugeridas ORDER BY nome")
    fun getAll(): Flow<List<ContaSugerida>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(contas: List<ContaSugerida>)
}

// --- 3. DEFINIÇÃO DO BANCO DE DADOS ---
@Database(entities = [Movimentacao::class, CategoriaSugerida::class, ContaSugerida::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun movimentacaoDao(): MovimentacaoDao
    abstract fun categoriaDao(): CategoriaDao
    abstract fun contaDao(): ContaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minhas_economias.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
