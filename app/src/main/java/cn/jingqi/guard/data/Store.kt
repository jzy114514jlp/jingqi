package cn.jingqi.guard.data

import android.content.Context
import androidx.room.*
import cn.jingqi.core.SkipRule
import java.io.File
import java.util.concurrent.Executors

@Entity(tableName = "events")
data class GuardEvent(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val kind: String,
    val source: String,
    val target: String = "",
    val detail: String,
    val estimatedSeconds: Int = 0,
    val screenshot: String = "",
    val demo: Boolean = source == "cn.jingqi.demo"
)

@Entity(tableName = "rules", primaryKeys = ["packageName", "viewId"])
data class RuleEntity(val packageName: String, val viewId: String, val label: String, val enabled: Boolean = true) {
    fun toRule() = SkipRule(packageName, viewId, label, enabled)
}

@Dao
interface GuardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(event: GuardEvent)
    @Query("SELECT * FROM events ORDER BY time DESC LIMIT 1000") fun events(): List<GuardEvent>
    @Query("UPDATE events SET screenshot = :path WHERE id = :id") fun attach(id: String, path: String)
    @Query("DELETE FROM events") fun clear()
    @Query("SELECT * FROM rules") fun rules(): List<RuleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun rules(rules: List<RuleEntity>)
    @Query("DELETE FROM rules") fun clearRules()
    @Query("DELETE FROM events WHERE time < :cutoff") fun prune(cutoff: Long)
}

@Database(entities = [GuardEvent::class, RuleEntity::class], version = 1, exportSchema = false)
abstract class GuardDatabase : RoomDatabase() { abstract fun dao(): GuardDao }

object Store {
    @Volatile var generation: Long = 0
        private set
    val io = Executors.newSingleThreadExecutor()
    private lateinit var db: GuardDatabase
    lateinit var context: android.app.Application
        private set
    @Volatile var rules: List<SkipRule> = emptyList()
        private set
    private var prunedAt = 0L
    fun init(context: Context) {
        this.context = context.applicationContext as android.app.Application
        db = Room.databaseBuilder(context, GuardDatabase::class.java, "jingqi.db").build()
        io.execute {
            db.dao().rules(builtIn)
            rules = db.dao().rules().map { it.toRule() }
            prune()
        }
    }
    private val builtIn = listOf(RuleEntity("cn.jingqi.demo", "cn.jingqi.demo:id/skip_ad", "跳过"))
    /** Rules learned on this device from a verified OCR skip; built-in rules are not counted. */
    val learnedCount get() = rules.count { rule -> builtIn.none { it.packageName == rule.packageName && it.viewId == rule.viewId } }
    fun learn(rule: SkipRule) {
        if (rules.any { it.packageName == rule.packageName && it.viewId == rule.viewId }) return
        io.execute {
            db.dao().rules(listOf(RuleEntity(rule.packageName, rule.viewId, rule.label)))
            rules = db.dao().rules().map { it.toRule() }
        }
    }
    fun forgetLearned(callback: () -> Unit) {
        io.execute {
            db.dao().clearRules(); db.dao().rules(builtIn)
            rules = db.dao().rules().map { it.toRule() }
            callback()
        }
    }
    /** Runs on [io]. The accessibility service keeps the process alive for weeks, so pruning only at start is not enough. */
    private fun prune() {
        prunedAt = System.currentTimeMillis()
        val cutoff = prunedAt - 30L * 86_400_000
        db.dao().prune(cutoff)
        File(context.filesDir, "evidence").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        File(context.cacheDir, "reports").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
    private fun pruneIfDue() { if (System.currentTimeMillis() - prunedAt > 6 * 3_600_000L) prune() }
    fun log(event: GuardEvent) { io.execute { db.dao().insert(event); pruneIfDue() } }
    fun attach(id: String, path: String) { io.execute { db.dao().attach(id, path) } }
    fun events(callback: (List<GuardEvent>) -> Unit) { io.execute { pruneIfDue(); callback(db.dao().events()) } }
    fun clear(callback: () -> Unit) {
        generation++
        io.execute {
            db.dao().clear()
            File(context.filesDir, "evidence").listFiles()?.forEach { it.delete() }
            File(context.cacheDir, "reports").listFiles()?.forEach { it.delete() }
            callback()
        }
    }
}

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
    init {
        // Retire only the removed platform's configuration; retain protection settings and local reports.
        if (prefs.contains("server") || prefs.contains("uploadKey") || prefs.contains("uploaded")) {
            prefs.edit().remove("server").remove("uploadKey").remove("uploaded").apply()
        }
    }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }
    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(v) { prefs.edit().putBoolean("consent", v).apply() }
    var elder: Boolean
        get() = prefs.getBoolean("elder", false)
        set(v) { prefs.edit().putBoolean("elder", v).apply() }
    var returnGuard: Boolean
        get() = prefs.getBoolean("return", true)
        set(v) { prefs.edit().putBoolean("return", v).apply() }
    var returnOnAdClick: Boolean
        get() = prefs.getBoolean("returnOnAdClick", true)
        set(v) { prefs.edit().putBoolean("returnOnAdClick", v).apply() }
    var screenshots: Boolean
        get() = prefs.getBoolean("screenshots", false)
        set(v) { prefs.edit().putBoolean("screenshots", v).apply() }
    var ocr: Boolean
        get() = prefs.getBoolean("ocr", false)
        set(v) { prefs.edit().putBoolean("ocr", v).apply() }
    var excluded: Set<String>
        get() = prefs.getStringSet("excluded", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("excluded", v).apply() }
}
