package com.aiforum.backup

import com.aiforum.config.SqlitePath
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Online, WAL-safe snapshots of the prod SQLite database — the protection the `aiforum.backups.enabled`
 * flag has always implied but never performed (audit T1.1). A single home-directory DB file with no
 * snapshot is the biggest real data-loss risk for a PoC the owner actually cares about.
 *
 * Mechanism: `VACUUM INTO '<dest>'`. Unlike `Files.copy` of a live WAL database (which can capture a
 * torn page set), `VACUUM INTO` produces a **clean, consistent single-file copy** of the committed state
 * through the same connection pool the app uses — no checkpoint dance, no sidecar `-wal`/`-shm` files in
 * the snapshot. It requires the destination NOT already exist; the UTC-timestamped filename guarantees
 * uniqueness.
 *
 * Wiring:
 * - `@Profile("!test")` + `@ConditionalOnProperty(aiforum.backups.enabled=true)` — never wires under the
 *   test profile (which sets it false; [com.aiforum.config.ProfileGuard] enforces that), so a test run
 *   never writes into the real `~/.ai_forum` store.
 * - The injected [Clock] (see `ClockConfig`) is the only time source — no `Instant.now()` — so the
 *   filename timestamp is pinnable in tests.
 * - The destination dir reuses [SqlitePath.expandTilde] for `~` expansion (the second consumer the
 *   sqlite-spring-jdbc skill anticipated) and is created if absent.
 *
 * Schedule: one snapshot on startup ([backupOnStartup]) plus a daily cron ([backupDaily]), enabled by the
 * tiny [SchedulingConfig]. Retention keeps the newest `aiforum.backups.keep` snapshots and deletes older
 * ones each run.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.backups", name = ["enabled"], havingValue = "true")
class SqliteBackup(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val backupDir: Path,
    private val keep: Int,
) {

    @Autowired
    constructor(
        jdbc: JdbcTemplate,
        clock: Clock,
        @Value("\${aiforum.backups.dir:~/.ai_forum/backup}") dir: String,
        @Value("\${aiforum.backups.keep:7}") keep: Int,
    ) : this(
        jdbc,
        clock,
        Path.of(SqlitePath.expandTilde(dir, System.getProperty("user.home") ?: ".")),
        keep,
    )

    private val log = LoggerFactory.getLogger(SqliteBackup::class.java)

    /**
     * Take one snapshot now, then trim old snapshots to the retention limit. Returns the snapshot path.
     * Idempotent enough for repeated calls (the timestamp + a uniquifying suffix avoid a name clash even
     * within the same clock-second).
     *
     * `@Synchronized`: the default `@Scheduled` executor is single-threaded so startup + daily snapshots
     * never overlap today, but `uniqueDestination()`'s `Files.exists` check-then-act is a TOCTOU window —
     * the lock makes the public method safe under any caller (tests, a future multi-threaded scheduler)
     * without relying on that implicit guarantee. (Worst case without it is only a logged `backup.failed`,
     * since `VACUUM INTO` refuses an existing file — no corruption — but the lock removes the foot-gun.)
     */
    @Synchronized
    fun backup(): Path {
        Files.createDirectories(backupDir)
        val dest = uniqueDestination()

        // VACUUM INTO needs a literal, single-quote-escaped path. Backup paths are app-controlled (home
        // dir + timestamp), but escape defensively all the same.
        val escaped = dest.toString().replace("'", "''")
        jdbc.execute("VACUUM INTO '$escaped'")

        log.atInfo().setMessage("sqlite backup written to {}").addArgument(dest)
            .addKeyValue("event", "backup.ok").addKeyValue("dest", dest.toString())
            .log()

        prune()
        return dest
    }

    private fun uniqueDestination(): Path {
        val stamp = TIMESTAMP.format(clock.instant().atZone(ZoneOffset.UTC))
        var candidate = backupDir.resolve("aiforum-$stamp.db")
        var n = 1
        // VACUUM INTO refuses an existing file; if two snapshots land in the same second, suffix them.
        while (Files.exists(candidate)) {
            candidate = backupDir.resolve("aiforum-$stamp-$n.db")
            n++
        }
        return candidate
    }

    /** Delete all but the newest [keep] snapshots (by filename, which sorts chronologically). */
    private fun prune() {
        if (keep <= 0) return
        val snapshots = Files.list(backupDir).use { stream ->
            stream.filter { it.fileName.toString().let { n -> n.startsWith("aiforum-") && n.endsWith(".db") } }
                .sorted()                       // ISO-8601 timestamp in the name => lexical == chronological
                .toList()
        }
        snapshots.dropLast(keep).forEach { stale ->
            Files.deleteIfExists(stale)
            log.atInfo().setMessage("pruned old sqlite backup {}").addArgument(stale)
                .addKeyValue("event", "backup.pruned").addKeyValue("dest", stale.toString())
                .log()
        }
    }

    /** One snapshot shortly after startup, so a freshly-launched app already has a recovery point. */
    @Scheduled(initialDelay = STARTUP_DELAY_MS, fixedDelay = Long.MAX_VALUE)
    fun backupOnStartup() = runSafely()

    /** Daily snapshot at 03:30 local time (a quiet hour for a single-user tool). */
    @Scheduled(cron = "0 30 3 * * *")
    fun backupDaily() = runSafely()

    /** Backups are best-effort: a failure must log loudly but never take the app down. */
    private fun runSafely() {
        try {
            backup()
        } catch (e: Exception) {
            log.atError().setMessage("sqlite backup failed: {}").addArgument(e.message)
                .addKeyValue("event", "backup.failed").addKeyValue("reason", e.message ?: e.javaClass.simpleName)
                .setCause(e)
                .log()
        }
    }

    private companion object {
        const val STARTUP_DELAY_MS = 5_000L
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
