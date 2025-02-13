/*
 * Copyright 2020-2022 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("KotlinConstantConditions")

package org.roboquant.samples

import org.roboquant.Roboquant
import org.roboquant.brokers.Account
import org.roboquant.brokers.FixedExchangeRates
import org.roboquant.brokers.fee
import org.roboquant.brokers.sim.SimBroker
import org.roboquant.brokers.summary
import org.roboquant.common.*
import org.roboquant.feeds.Event
import org.roboquant.feeds.PriceBar
import org.roboquant.feeds.AvroFeed
import org.roboquant.feeds.csv.CSVConfig
import org.roboquant.feeds.csv.CSVFeed
import org.roboquant.feeds.filter
import org.roboquant.feeds.timeseries
import org.roboquant.loggers.LastEntryLogger
import org.roboquant.loggers.MemoryLogger
import org.roboquant.loggers.toDoubleArray
import org.roboquant.metrics.AccountMetric
import org.roboquant.metrics.PNLMetric
import org.roboquant.metrics.ProgressMetric
import org.roboquant.orders.Order
import org.roboquant.policies.*
import org.roboquant.strategies.CombinedStrategy
import org.roboquant.strategies.EMAStrategy
import org.roboquant.strategies.NoSignalStrategy
import org.roboquant.strategies.Signal
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.math.absoluteValue
import kotlin.system.measureTimeMillis


fun multiCurrency() {
    val feed = CSVFeed("data/US") {
        priceAdjust = true
    }
    val feed2 = CSVFeed("data/EU") {
        priceAdjust = true
        template = Asset("TEMPLATE", currencyCode = "EUR")
    }
    feed.merge(feed2)

    val euro = Currency.getInstance("EUR")
    val usd = Currency.getInstance("USD")
    val currencyConverter = FixedExchangeRates(usd, euro to 1.2)
    Config.exchangeRates = currencyConverter

    val cash = Wallet(100_000.USD)
    val broker = SimBroker(cash)

    val strategy = EMAStrategy.PERIODS_12_26
    val policy = FlexPolicy(orderPercentage = 0.02)

    val roboquant = Roboquant(strategy, AccountMetric(), policy = policy, broker = broker, logger = MemoryLogger())
    roboquant.run(feed)
    println(broker.account.openOrders.summary())
}

fun multiRun() {
    val feed = AvroFeed.sp500()
    val logger = LastEntryLogger()

    for (fast in 10..20..2) {
        for (slow in fast * 2..fast * 4..4) {
            val strategy = EMAStrategy(fast, slow)
            val roboquant = Roboquant(strategy, AccountMetric(), logger = logger)
            roboquant.run(feed, name = "run $fast-$slow")
        }
    }
    val maxEntry = logger.getMetric("account.equity").max()
    println(maxEntry.info.run)
}

suspend fun walkForwardParallel() {
    val feed = AvroFeed.sp500()
    val logger = LastEntryLogger()
    val jobs = ParallelJobs()

    feed.timeframe.split(2.years).forEach {
        val strategy = EMAStrategy()
        val roboquant = Roboquant(strategy, AccountMetric(), logger = logger)
        jobs.add {
            roboquant.runAsync(feed, runName = "run-$it")
        }
    }

    jobs.joinAll() // Make sure we wait for all jobs to finish
    val avgEquity = logger.getMetric("account.equity").toDoubleArray().mean()
    println(avgEquity)
}

fun testingStrategies() {
    val strategy = EMAStrategy()
    val roboquant = Roboquant(strategy)
    val feed = CSVFeed("data/US")

    // Basic use case
    roboquant.run(feed)

    // Walk forward
    feed.split(2.years).forEach {
        roboquant.run(feed, it)
    }

    // Walk forward learning
    feed.split(2.years).map { it.splitTrainTest(0.2) }.forEach { (train, test) ->
        roboquant.run(feed, train, test, episodes = 100)
    }

}

fun calcCorrelation() {
    val feed = AvroFeed.sp500()
    val data = feed.filter<PriceBar>(Timeframe.coronaCrash2020)
    val timeseries = data.timeseries()
    val result = timeseries.correlation()

    val mostUncorrelated = result.toList().sortedBy { it.second.absoluteValue }.take(50)
    for ((assets, corr) in mostUncorrelated) {
        println("${assets.first.symbol} ${assets.second.symbol} = $corr")
    }

}

fun beta() {
    val feed = CSVFeed("/data/assets/stock-market/stocks/")
    val market = CSVFeed("/data/assets/stock-market/market/")
    feed.merge(market)
    val strategy = NoSignalStrategy()
    val marketAsset = feed.assets.getBySymbol("SPY")

    val policy = BettingAgainstBetaPolicy(feed.assets, marketAsset, maxPositions = 10)
    policy.recording = true
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), policy = policy, logger = logger)
    roboquant.run(feed)
    println(logger.summary())
    println(roboquant.broker.account.summary())

}

fun beta2() {
    val feed = CSVFeed("/data/assets/us-stocks/Stocks") {
        fileExtension = ".us.txt"
    }
    val market = CSVFeed("/data/assets/us-stocks/ETFs") {
        fileExtension = ".us.txt"
        filePattern = "spy.us.txt"

    }
    feed.merge(market)
    val strategy = NoSignalStrategy()
    val marketAsset = feed.assets.getBySymbol("SPY")
    val policy = BettingAgainstBetaPolicy(feed.assets, marketAsset, 60, maxPositions = 10)
    policy.recording = true
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, ProgressMetric(), PNLMetric(), policy = policy, logger = logger)
    roboquant.run(feed)
    println(logger.summary())
    println(roboquant.broker.account.summary())
    println(roboquant.broker.account.trades.fee)

}


fun signalsOnly() {
    class MyPolicy : BasePolicy(recording = true, prefix = "") {

        override fun act(signals: List<Signal>, account: Account, event: Event): List<Order> {
            for (signal in signals) {
                record("signal.${signal.asset.symbol}", signal.rating.value)
            }
            return emptyList()
        }

    }
    val feed = AvroFeed("/tmp/us_full_v3.0.avro")
    val logger = MemoryLogger()

    val strategy = CombinedStrategy(
        EMAStrategy.PERIODS_50_200,
        EMAStrategy.PERIODS_12_26
    )

    val policy = MyPolicy().resolve(SignalResolution.NO_CONFLICTS)

    val roboquant = Roboquant(strategy, policy = policy, logger = logger)
    roboquant.run(feed, Timeframe.past(5.years))
    println(logger.summary(1))
}


fun csv2Avro(pathStr: String = "path") {

    val path = Path(pathStr)
    val nasdaq = Exchange.getInstance("NASDAQ")
    val nyse = Exchange.getInstance("NYSE")

    fun CSVConfig.file2Symbol(file: File): String {
        return file.name.removeSuffix(fileExtension).replace('-', '.').uppercase()
    }

    val feed = CSVFeed(path / "nasdaq stocks") {
        fileExtension = ".us.txt"
        parsePattern = "??T?OHLCV?"
        assetBuilder = { file: File -> Asset(file2Symbol(file), exchange = nasdaq) }
    }

    val tmp = CSVFeed(path / "nyse stocks") {
        fileExtension = ".us.txt"
        parsePattern = "??T?OHLCV?"
        assetBuilder = { file : File -> Asset(file2Symbol(file), exchange = nyse) }
    }
    feed.merge(tmp)

    val sp500File = "/tmp/sp500_all_v3.0.avro"

    AvroFeed.record(
        feed,
        sp500File
    )

}



fun simple() {
    val strategy = EMAStrategy()
    val feed = AvroFeed.sp500()
    val roboquant = Roboquant(strategy)
    roboquant.run(feed)
    println(roboquant.broker.account.fullSummary())
}

suspend fun main() {
    Config.printInfo()

    when ("CSV2AVRO") {
        "SIMPLE" -> simple()
        "BETA" -> beta()
        "CSV2AVRO" -> csv2Avro()
        "CORR" -> calcCorrelation()
        "BETA2" -> beta2()
        "MULTI_RUN" -> multiRun()
        "WALKFORWARD_PARALLEL" -> println(measureTimeMillis { walkForwardParallel() })
        "MC" -> multiCurrency()
        "TESTING" -> testingStrategies()
        "SIGNALS" -> signalsOnly()
    }

}