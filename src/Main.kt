@file:OptIn(ExperimentalTime::class)

package io.github.andreypfau.tondhtcrawler

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.uint
import io.github.andreypfau.tondhtcrawler.clikt.path
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.ton.kotlin.adnl.Adnl
import org.ton.kotlin.adnl.AdnlIdShort
import org.ton.kotlin.crypto.PrivateKeyEd25519
import org.ton.kotlin.dht.*
import org.ton.kotlin.dht.DhtLocalNode.Companion.BOOTSTRAP_NODES
import org.ton.kotlin.dht.bucket.Distance
import org.ton.kotlin.dht.bucket.KBucketConfig
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.*

fun main(args: Array<String>) = TonDhtCrawler()
    .subcommands(Crawl())
    .main(args)

class TonDhtCrawler : CliktCommand("ton-dht-crawler") {
    init {
        versionOption(VERSION)
    }

    override fun run() = Unit
}

class Crawl : CliktCommand() {
    override fun help(context: Context): String = "Start crawling TON DHT nodes"

    val worker: UInt by option(
        "--worker", "-w",
        help = "Workers to use for processing, default is 1000"
    ).uint().default(1000u)

    val outputFile by option(
        "--output", "-o",
        help = "Output file for crawled nodes"
    )
        .path()
        .required()

    @OptIn(ExperimentalAtomicApi::class)
    override fun run(): Unit = runBlocking {
        SystemFileSystem.createDirectories(outputFile)
        val outputFile = if (SystemFileSystem.metadataOrNull(outputFile)?.isRegularFile == true) {
            outputFile
        } else {
            Path(outputFile, "crawl-result.json")
        }

        val adnl = Adnl(
            aSocket(SelectorManager()).udp().let {
                runBlocking { it.bind() }
            }
        )
        val localNode = adnl.localNode(PrivateKeyEd25519.random())
        val dht = DhtLocalNode(
            localNode, config = DhtConfig(
                kBucketConfig = KBucketConfig(bucketSize = 20)
            )
        )

        val nodes = HashSet<AdnlIdShort>()
        val results = ArrayList<DhtCrawlResult>()
        val inFlight = AtomicInt(0)
        dht.crawl(BOOTSTRAP_NODES.shuffled(), worker.toInt(), inFlight)
            .collect { result ->
                nodes += result.info.id.idShort
                nodes.addAll(result.drainResult.map { it.id.idShort })
                results.add(
                    result.copy(
                        drainResult = result.drainResult.map {
                            DhtNode(it.id)
                        }
                    ))
                echo(buildString {
                    append("known_nodes:")
                    append(nodes.size)
                    append(" total_results:")
                    append(results.size)
                    append(" in_flight:")
                    append(inFlight.load())
                    append(" crawled:")
                    if (result.successConnection) {
                        append(result.drainResult.size)
                    } else {
                        append("?")
                    }
                    append(" source_pub:")
                    append(result.info.id.publicKey)
                    append(" source_address:")
                    append(result.info.addrList.addrs.firstOrNull())
                })
            }
        echo("saving...")

        SystemFileSystem.sink(outputFile).buffered().use { sink ->
            sink.writeString(
                TL_JSON.encodeToString(results.sortedBy { it.info.id.idShort })
            )
        }
        echo("done, total crawled nodes: ${results.size}, saved to file: $outputFile")
    }
}

@OptIn(ExperimentalAtomicApi::class)
fun DhtLocalNode.crawl(nodes: List<DhtNode>, parallelJobs: Int, inFlightMetric: AtomicInt) = channelFlow {
    coroutineScope {
        val queries = mutableSetOf<DhtKeyId>()
        val nodes = nodes.asSequence().map {
            DhtKeyId(it.id) to it
        }.toMutableList()
        val inFlight = mutableSetOf<Deferred<DhtCrawlResult>>()

        fun schedule() {
            nodes.asSequence()
                .filter { (key, _) -> key !in queries }
                .firstOrNull()
                ?.let { (key, info) ->
                    queries += key
                    inFlight += async {
                        crawl(info)
                    }
                    inFlightMetric.store(inFlight.size)
                }
        }
        repeat(minOf(parallelJobs, nodes.size)) {
            schedule()
        }
        while (inFlight.isNotEmpty()) {
            val result = select {
                inFlight.map { d ->
                    d.onAwait { result ->
                        inFlight.remove(d)
                        inFlightMetric.store(inFlight.size)
                        result
                    }
                }
            }
            send(result)
            result.drainResult.forEach {
                val key = DhtKeyId(it.id)
                if (!queries.contains(key)) {
                    nodes += key to it
                }
            }
            repeat(minOf(nodes.size, parallelJobs, parallelJobs - inFlight.size)) {
                schedule()
            }
        }
        close()
    }
}

suspend fun DhtLocalNode.crawl(info: DhtNode): DhtCrawlResult {
    val peer = peer(info)
    val now = Clock.System.now()
    val startTime = TimeSource.Monotonic.markNow()
    if (!peer.checkConnection()) {
        return DhtCrawlResult(info, false, now, startTime.elapsedNow(), emptyList())
    }
    val drainBuckets = peer.drainBuckets()
    return DhtCrawlResult(info, true, now, startTime.elapsedNow(), drainBuckets)
}

suspend fun DhtPeer.drainBuckets(
    maxBuckets: Int = 256,
    parallelism: Int = 3,
    maxTriesPerBucket: Int = 32,
    noNewWindow: Int = 2,
    epsilon: Double = 0.10,
    farToNear: Boolean = true,
): List<DhtNode> {
    val store = mutableMapOf<DhtKeyId, DhtNode>()

    data class BStat(
        var newTotal: Int = 0,
        var lastNew: Int = 0,
        var retTotal: Int = 0,
        var emptyStreak: Int = 0,
        var stopped: Boolean = false
    )

    val stats = Array(maxBuckets) { BStat() }
    val buckets = if (farToNear) (maxBuckets - 1 downTo 0) else (0 until maxBuckets)

    var emptyBucketsStreak = 0

    bucketLoop@ for (bucket in buckets) {
        if (bucket == 0) continue

        val s = stats[bucket]
        val inflight = mutableListOf<Deferred<Result<List<DhtNode>>>>()
        val targets = ArrayDeque<DhtKeyId>()

        fun enqueue() {
            if (s.retTotal >= maxTriesPerBucket) return
            val dist = Distance.randomDistanceForBucket(bucket)
            val targetKey = key.forDistance(dist)
            targets += targetKey
        }

        repeat(minOf(parallelism, maxTriesPerBucket)) { enqueue() }

        while (!s.stopped && (inflight.isNotEmpty() || targets.isNotEmpty())) {
            while (inflight.size < parallelism && targets.isNotEmpty()) {
                val t = targets.removeFirst()
                inflight += coroutineScope {
                    async {
                        runCatching { findNode(t.hash, K_VALUE) }
                    }
                }
                s.retTotal++
            }

            val res = select<Result<List<DhtNode>>> {
                inflight.map { d ->
                    d.onAwait {
                        inflight.remove(d)
                        it
                    }
                }.first()
            }

            res.onFailure {
                s.emptyStreak++
                s.lastNew = 0
            }.onSuccess { list ->
                val before = store.size
                list.forEach { n ->
                    val keyId = DhtKeyId(n.id)
                    val old = store[keyId]
                    if (old == null || n.version > old.version) {
                        store[keyId] = n
                    }
                }
                val newCnt = store.size - before
                s.lastNew = newCnt
                s.newTotal += newCnt

                if (newCnt == 0) s.emptyStreak++ else s.emptyStreak = 0
            }

            val lastRatio = s.lastNew.toDouble() / K_VALUE.coerceAtLeast(1)
            if (s.emptyStreak >= noNewWindow || lastRatio < epsilon || s.retTotal >= maxTriesPerBucket) {
                s.stopped = true
            } else {
                enqueue()
            }
        }

        if (s.newTotal == 0) {
            emptyBucketsStreak++
            if (emptyBucketsStreak >= 2) break@bucketLoop // глобальный стоп
        } else emptyBucketsStreak = 0
    }

    return store.values.sortedBy {
        Distance(key.hash, it.id.idShort.hash)
    }
}

@Serializable
data class DhtCrawlResult(
    val info: DhtNode,
    val successConnection: Boolean,
    val time: Instant,
    val duration: Duration,
    val drainResult: List<DhtNode>
) {
    override fun toString(): String = "${info.id} -> $successConnection, $duration, $drainResult"
}

@Suppress("OPT_IN_USAGE")
internal val TL_JSON = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "@type"
    useArrayPolymorphism = false
    namingStrategy = JsonNamingStrategy.SnakeCase
    prettyPrint = true
}
