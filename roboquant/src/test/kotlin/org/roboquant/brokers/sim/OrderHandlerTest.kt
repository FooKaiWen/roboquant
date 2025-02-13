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

package org.roboquant.brokers.sim

import org.junit.jupiter.api.Test
import org.roboquant.TestData
import org.roboquant.common.Size
import org.roboquant.feeds.TradePrice
import org.roboquant.orders.*
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

internal class OrderHandlerTest {

    private val asset = TestData.usStock()

    private fun pricing(price: Number): Pricing {
        val engine = NoCostPricingEngine()
        return engine.getPricing(TradePrice(asset, price.toDouble()), Instant.now())
    }

    @Test
    fun testMarketOrder() {
        val order = MarketOrder(asset, 100)
        val handler = MarketOrderHandler(order)
        var executions = handler.execute(pricing(100), Instant.now())
        assertEquals(1, executions.size)

        val execution = executions.first()
        assertTrue(execution.value != 0.0)
        assertEquals(execution.order.asset.currency, execution.amount.currency)

        assertFails {
            executions = handler.execute(pricing(100), Instant.now())
        }
    }

    @Test
    fun testStopOrder() {
        val order = StopOrder(asset, Size(-10), 99.0)
        val handler = StopOrderHandler(order)
        var executions = handler.execute(pricing(100), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(98), Instant.now())
        assertEquals(1, executions.size)
    }

    @Test
    fun testLimitOrder() {
        val order = LimitOrder(asset, Size(10), 99.0)
        val handler = LimitOrderHandler(order)
        var executions = handler.execute(pricing(100), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(98), Instant.now())
        assertEquals(1, executions.size)
    }

    @Test
    fun testStopLimitOrder() {
        val order = StopLimitOrder(asset, Size(-10), 100.0, 98.0)
        val handler = StopLimitOrderHandler(order)

        var executions = handler.execute(pricing(101), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(97), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(99), Instant.now())
        assertEquals(1, executions.size)
    }

    @Test
    fun testTrailOrder() {
        val order = TrailOrder(asset, Size(-10), 0.01)
        val handler = TrailOrderHandler(order)
        var executions = handler.execute(pricing(90), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(100), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(98), Instant.now())
        assertEquals(1, executions.size)
    }

    @Test
    fun testTrailLimitOrder() {
        val order = TrailLimitOrder(asset, Size(-10), 0.01, -1.0)
        val handler = TrailLimitOrderHandler(order)
        var executions = handler.execute(pricing(90), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(100), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(95), Instant.now())
        assertEquals(0, executions.size)

        executions = handler.execute(pricing(99), Instant.now())
        assertEquals(1, executions.size)
    }

}