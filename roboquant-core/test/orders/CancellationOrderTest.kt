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

package org.roboquant.orders

import org.junit.Test
import org.roboquant.TestData
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFails


internal class CancellationOrderTest {

    @Test
    fun test() {
        val asset = TestData.usStock()

        val openOrder = MarketOrder(asset, 100.0)
        openOrder.state.placed = Instant.now()

        val oc = CancellationOrder(openOrder)
        oc.state.placed = Instant.now()
        assertEquals(OrderStatus.INITIAL, oc.status)
        assertEquals(openOrder, oc.order)


    }


    @Test
    fun testFailure() {
        val asset = TestData.usStock()

        val openOrder = MarketOrder(asset, 100.0)
        openOrder.state.placed = Instant.now()
        openOrder.status = OrderStatus.COMPLETED

        assertFails {
            CancellationOrder(openOrder)
        }

    }
}