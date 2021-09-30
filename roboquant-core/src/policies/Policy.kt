package org.roboquant.policies

import org.roboquant.brokers.Account
import org.roboquant.common.Component
import org.roboquant.feeds.Event
import org.roboquant.orders.Order
import org.roboquant.strategies.Signal

/**
 * A policy is responsible for creating [Order]s, typically based on the [Signal]s generated by a strategy.
 *
 * Besides, turning signals into orders, a policy also can take care of:
 *
 * * signal conflicts, for example receive both a SELL and BUY signal for the same asset at the same time
 * * order management, for example how to deal with open orders
 * * portfolio construction, for example re-balancing of the portfolio based on some pre-defined risk parameters
 * * risk management, for example limit exposure to certain sectors
 *
 * Please note that the brokers who receive the orders that a Policy generate, might not support all the different
 * order types.
 */
interface Policy : Component {

    /**
     * Act on the received [signals], the latest state of the [account] and the last [event] and create zero or more
     * orders for the broker to process.
     */
    fun act(signals: List<Signal>, account: Account, event: Event): List<Order>

}