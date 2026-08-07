package io.github.damian1000.visitoranalytics

import io.github.damian1000.visitoranalytics.config.AppConfig
import io.github.damian1000.visitoranalytics.device.UserAgentClassifier
import io.github.damian1000.visitoranalytics.geo.JndiReverseDns
import io.github.damian1000.visitoranalytics.geo.MaxmindGeoLocator
import io.github.damian1000.visitoranalytics.health.Readiness
import io.github.damian1000.visitoranalytics.ingest.CaddyLogTailer
import io.github.damian1000.visitoranalytics.ingest.LogLineParser
import io.github.damian1000.visitoranalytics.ingest.RequestFilter
import io.github.damian1000.visitoranalytics.pipeline.AnalyticsPipeline
import io.github.damian1000.visitoranalytics.privacy.IpHasher
import io.github.damian1000.visitoranalytics.retention.RetentionPruner
import io.github.damian1000.visitoranalytics.store.BufferingVisitStore
import io.github.damian1000.visitoranalytics.store.OracleVisitStore
import io.github.damian1000.visitoranalytics.web.AdminAssets
import io.github.damian1000.visitoranalytics.web.AdminServer
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Composition root: config from the environment, migrate, wire the pipeline, start the tail,
// schedule the pruner, serve the dashboard. All logic lives in the injected, tested classes.
fun main() {
    val config = AppConfig.fromEnv(System.getenv())

    Flyway
        .configure()
        .dataSource(config.dbUrl, config.dbUser, config.dbPassword)
        .load()
        .migrate()

    val connect = { DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword) }
    val store = BufferingVisitStore(OracleVisitStore(connect), config.bufferPath)
    val locator = MaxmindGeoLocator(config.cityDbPath, config.asnDbPath)
    val pipeline =
        AnalyticsPipeline(
            parser = LogLineParser(),
            filter = RequestFilter(config.internalProxies),
            locator = locator,
            reverseDns = JndiReverseDns(),
            classifier = UserAgentClassifier(),
            hasher = IpHasher(config.ipHashSalt),
            store = store,
        )

    val tailer = CaddyLogTailer(config.logPaths, statePath = config.tailerStatePath)
    tailer.start(pipeline::onLine)

    val pruner = RetentionPruner(store, config.retentionDays)
    val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }
    scheduler.scheduleAtFixedRate({
        try {
            pruner.prune()
        } catch (e: Exception) {
            println("visitor-analytics: prune failed, will retry tomorrow (${e.message})")
        }
    }, 1, 24 * 60, TimeUnit.MINUTES)

    val readiness =
        Readiness(
            databaseOk = store::ping,
            ingestAlive = { tailer.threadAlive },
            ingestPollAgeMillis = tailer::pollAgeMillis,
            ingestFailure = tailer::lastFailure,
            ingestOffsets = tailer::offsets,
        )
    val server = AdminServer(store, AdminAssets.load(), config.port, readiness)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            tailer.close()
            scheduler.shutdownNow()
            locator.close()
        },
    )
    server.start()
}
