/*
 * Copyright 2021 Neural Layer
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

package org.roboquant

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.roboquant.brokers.Account
import org.roboquant.brokers.Broker
import org.roboquant.brokers.sim.SimBroker
import org.roboquant.common.Background
import org.roboquant.common.Logging
import org.roboquant.common.Summary
import org.roboquant.common.TimeFrame
import org.roboquant.feeds.Event
import org.roboquant.feeds.EventChannel
import org.roboquant.feeds.Feed
import org.roboquant.logging.MemoryLogger
import org.roboquant.logging.MetricsLogger
import org.roboquant.metrics.Metric
import org.roboquant.orders.Order
import org.roboquant.policies.NeverShortPolicy
import org.roboquant.policies.Policy
import org.roboquant.strategies.Strategy
import java.time.Duration
import java.time.Instant

/**
 * Roboquant is the engine of the platform that ties [strategy], [policy] and [broker] together and caters to a wide
 * variety of testing and live trading scenarios. Through [metrics] and a [logger] it provides insights into the
 * performance of a [run].
 *
 * Every instance has it own [name] that is also used when logging details.
 *
 */
class Roboquant<L : MetricsLogger>(
    val strategy: Strategy,
    vararg val metrics: Metric,
    val policy: Policy = NeverShortPolicy(),
    val broker: Broker = SimBroker(),
    val logger: L,
    val name: String = "Roboquant-${instanceCounter++}",
    private val channelCapacity: Int = 100,
) {

    private val kotlinLogger = Logging.getLogger(name)
    private var runInfo = RunInfo(name)
    private val components = listOf(strategy, policy, broker, *metrics, logger)

    companion object {

        private var instanceCounter = 0

        /**
         * Shortcut to create a new roboquant with a default logger.
         *
         * @param strategy
         * @param metrics
         */
        operator fun invoke(
            strategy: Strategy,
            vararg metrics: Metric,
            policy: Policy = NeverShortPolicy(),
            broker: Broker = SimBroker()
        ) = Roboquant(strategy, *metrics, policy = policy, broker = broker, logger = MemoryLogger())

    }

    init {
        kotlinLogger.fine { "Created new roboquant instance" }
    }

    /**
     * Run and evaluate the underlying performance of the strategy and policy. You don't
     * invoke this method directly but rather use the [run] method instead.
     *
     * Under the hood this method replies on the [step] method to take a single step.
     */
    private suspend fun runPhase(feed: Feed, runTime: TimeFrame = TimeFrame.FULL, runPhase: RunPhase) {

        if (!feed.timeFrame.overlap(runTime)) return
        runInfo.timeFrame = runTime.intersect(feed.timeFrame)
        runInfo.runPhase = runPhase

        val channel = EventChannel(channelCapacity, runTime)
        val job = Background.ioJob {
            try {
                feed.play(channel)
            } finally {
                channel.close()
            }
        }

        start(runPhase)
        try {
            var orders = listOf<Order>()
            while (true) {
                val event = channel.receive()
                orders = step(orders, event)
            }
        } catch (exception: ClosedReceiveChannelException) {
            return
        } finally {
            end(runPhase)
            if (job.isActive) job.cancel()
            channel.close()
        }
    }

    /**
     * Inform components of the start of a phase, this provides them with the opportunity to reset state and
     * re-initialize values if required.
     *
     * @param runPhase
     */
    private fun start(runPhase: RunPhase) {
        for (component in components) component.start(runPhase)
    }

    /**
     * Inform components of the end of a phase, this provides them with the opportunity to release resources
     * if required or process aggregated results.
     *
     * @param runPhase
     */
    private fun end(runPhase: RunPhase) {
        for (component in components) component.end(runPhase)
    }

    /**
     * Reset all state including that of the used components. This allows to start with a fresh run with the same
     * configuration as the original instance.
     */
    fun reset() {
        for (component in components) component.reset()
        runInfo.reset()
    }

    /**
     * Start a new run using the provided [feed] as data. If no [timeFrame] is provided all the events in the feed
     * will be used. Optionally you can provide a [validation] timeframe that will trigger a separate validation phase. You
     * can also repeat the run for a number of [episodes].
     *
     *  The following provides a schematic overview of the flow of a run:
     *
     * [Feed] -> [Strategy] -> [Policy] -> [Broker] -> [Metric] -> [MetricsLogger]
     *
     * This is the synchronous (blocking) method of run that is convenient to use. However, if you want to execute runs
     * in parallel have also a look at [runAsync]
     */
    fun run(feed: Feed, timeFrame: TimeFrame = TimeFrame.FULL, validation: TimeFrame? = null, episodes: Int = 1) =
        runBlocking {
            runAsync(feed, timeFrame, validation, episodes)
        }

    /**
     * This is exactly the same method as the [run] method but as the name already suggest, asynchronously.
     *
     * @see [run]
     */
    suspend fun runAsync(
        feed: Feed,
        timeFrame: TimeFrame = TimeFrame.FULL,
        validation: TimeFrame? = null,
        episodes: Int = 1
    ) {
        require(episodes > 0) { "episodes need to be greater than zero"}

        runInfo.startRun()
        kotlinLogger.fine { "Starting run $runInfo for $episodes episodes" }

        repeat(episodes) {
            runInfo.episode++
            runPhase(feed, timeFrame, RunPhase.MAIN)
            if (validation !== null) runPhase(feed, validation, RunPhase.VALIDATE)
        }
        kotlinLogger.fine { "Finished run $runInfo" }
    }


    /**
     * Take a single step in the timeline. The broker is always invoked before the strategy and policy to ensure it is
     * impossible to look ahead in the future.
     */
    private fun step(orders: List<Order>, event: Event): List<Order> {
        runInfo.step++
        runInfo.time = event.now

        val account = broker.place(orders, event)
        runMetrics(account, event)
        val signals = strategy.generate(event)
        return policy.act(signals, account, event)
    }

    /**
     * Run the configured metrics and log the results. This includes any metrics that are recorded by the strategy,
     * policy and broker.
     */
    private fun runMetrics(account: Account, event: Event) {
        val info = runInfo.copy()
        for (metric in metrics) metric.calculate(account, event)

        for (component in components) {
            val metrics = component.getMetrics()
            logger.log(metrics, info)
        }

    }

    /**
     * Provide a short summary of the state of this roboquant.
     */
    fun summary(): Summary {
        val s = Summary("Roboquant")
        s.add("name", name)
        s.add("run", runInfo.run)
        s.add("strategy", strategy::class.simpleName)
        s.add("policy", policy::class.simpleName)
        s.add("logger", logger::class.simpleName)
        s.add("metrics", metrics.size)
        return s
    }

}


/**
 * Run related info provided to metrics loggers together with the metric results.
 *
 * @property name of the roboquant that created this object
 * @property startRun the run
 * @property episode the episode
 * @property step the step
 * @property time the time
 * @property timeFrame the total timeframe of the run
 * @property runPhase the phase of the run
 * @constructor Create new RunInfo object
 */
data class RunInfo internal constructor(
    val name: String,
    var run: Int = 0,
    var episode: Int = 0,
    var step: Int = 0,
    var time: Instant = Instant.MIN,
    var timeFrame: TimeFrame = TimeFrame.FULL,
    var runPhase: RunPhase = RunPhase.MAIN
) {

    /**
     * What is the duration of the run so far
     */
    val duration: Duration
        get() = Duration.between(timeFrame.start, time)

    fun startRun() {
        run++
        episode = 0
        step = 0
    }

    fun reset() {
        run = 0
        episode = 0
        step = 0
    }

}

/**
 * Enumeration of fhe different phases that a run can be in, MAIN and VALIDATE. Especially with self learning
 * strategies, it is important that you evaluate your strategy on yet unseen data, so you don't over-fit.
 *
 * See also [Roboquant.run] how to run your strategy with different phases enabled.
 *
 * @property value String value of the phase
 */
enum class RunPhase(val value: String) {
    MAIN("MAIN"),
    VALIDATE("VALIDATE"),
}
