package com.damianhoward.visitoranalytics

import com.damianhoward.visitoranalytics.config.AppConfig
import com.damianhoward.visitoranalytics.device.UserAgentClassifier
import com.damianhoward.visitoranalytics.geo.JndiReverseDns
import com.damianhoward.visitoranalytics.geo.MaxmindGeoLocator
import com.damianhoward.visitoranalytics.health.Readiness
import com.damianhoward.visitoranalytics.ingest.CaddyLogTailer
import com.damianhoward.visitoranalytics.ingest.LogLineParser
import com.damianhoward.visitoranalytics.ingest.RequestFilter
import com.damianhoward.visitoranalytics.pipeline.AnalyticsPipeline
import com.damianhoward.visitoranalytics.privacy.IpHasher
import com.damianhoward.visitoranalytics.retention.RetentionPruner
import com.damianhoward.visitoranalytics.store.BufferingVisitStore
import com.damianhoward.visitoranalytics.store.OracleVisitStore
import com.damianhoward.visitoranalytics.web.AdminAssets
import com.damianhoward.visitoranalytics.web.AdminServer
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
