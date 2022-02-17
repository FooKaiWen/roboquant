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

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Time in force (TiF) allows to put an expiration or fill policy on an order. It determines how long an order remains
 * active before it expires. There are two aspects that can determine if an order expires:
 *
 * - How much time has passed since it was first placed.
 * - Is the order completely filled or not yet
 *
 * When an order expires, the status is typically set [OrderStatus.EXPIRED] to indicate such event occurred.
 *
 */
interface TimeInForce {

    /**
     * Is the order expired given the current time (now) and when it was originally placed and the fill in comparison
     * to total fill
     *
     * @param time
     * @return
     */
    fun isExpired(order: Order, time: Instant, remaining: Double): Boolean

}


/**
 * Good Till Cancelled policy. The order will remain active until fully filled or is cancelled.
 *
 * In practice, most brokers allow such order to remain active for 60-90 days max. 90 days is also what is
 * used a default value for this implementation
 *
 * @constructor Create new GTC tif
 */
class GTC(private val maxDays: Int = 90) : TimeInForce {

    /**
     * @see TimeInForce.isExpired
     *
     */
    override fun isExpired(order: Order, time: Instant, remaining: Double): Boolean {
        if (time == order.state.placed) return false
        val max = order.state.placed.plus(maxDays.toLong(), ChronoUnit.DAYS)
        return time > max
    }

    override fun toString(): String {
        return "GTC"
    }

}


/**
 * Good Till Date policy. The order will remain active until fully filled or a specified date
 *
 * @property date
 * @constructor Create new GTD tif
 */
class GTD(private val date: Instant) : TimeInForce {

    /**
     * @see TimeInForce.isExpired
     */
    override fun isExpired(order: Order, time: Instant, remaining: Double) = time > date

    override fun toString(): String {
        return "GTD($date)"
    }
}


/**
 * Immediate or Cancel (IOC) policy. An immediate or cancel order is an order to buy or sell an asset that attempts
 * to execute all or part immediately and then cancels any unfilled portion of the order.
 *
 * @constructor Create new IOC tif
 */
class IOC : TimeInForce {

    /**
     * @see TimeInForce.isExpired
     */
    override fun isExpired(order: Order, time: Instant, remaining: Double) = time > order.state.placed

    override fun toString(): String {
        return "IOC"
    }
}

/**
 * A DAY order is a stipulation placed on an order to a broker to execute a trade at a specific price
 * that expires at the end of the trading day if it is not completed. The definition of a day is determined by the
 * exchange the asset is traded on.
 *
 * @constructor Create new DAY tif
 */
class DAY : TimeInForce {

    /**
     * @see TimeInForce.isExpired
     */
    override fun isExpired(order: Order, time: Instant, remaining: Double): Boolean {
        val exchange = order.asset.exchange
        return !exchange.sameDay(order.state.placed, time)
    }

    override fun toString(): String {
        return "DAY"
    }

}


/**
 * Fill or Kill (FOK) policy. A Fill or Kill policy is to be executed immediately at the market or a specified price
 * or canceled if not filled.
 *
 * @constructor Create new FOK tif
 */
class FOK : TimeInForce {

    /**
     * @see TimeInForce.isExpired
     *
     */
    override fun isExpired(order: Order, time: Instant, remaining: Double): Boolean {
        return remaining != 0.0
    }

    override fun toString(): String {
        return "FOK"
    }
}