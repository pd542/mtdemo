package com.hermes.futuressim.model

import kotlin.math.abs

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class Contract(
    val symbol: String,
    val name: String,
    val exchange: String,
    val multiplier: Int,
    val marginRate: Double,
    val tickSize: Double
)

data class Quote(
    val contract: Contract,
    val last: Double,
    val change: Double,
    val changePct: Double,
    val source: QuoteSource,
    val bid: Double = last,
    val ask: Double = last,
    val low: Double = last,
    val high: Double = last,
    val spreadTicks: Int = 1,
    val time: Long = System.currentTimeMillis()
)

enum class QuoteSource { SINA_FREE_API, LOCAL_SIMULATION }
enum class Direction { LONG, SHORT }
enum class Offset { OPEN, CLOSE }
enum class OrderType { MARKET, LIMIT, STOP }
enum class OrderStatus { PENDING, FILLED, CANCELLED, REJECTED }

data class Order(
    val id: String,
    val contract: Contract,
    val direction: Direction,
    val offset: Offset,
    val type: OrderType,
    val price: Double,
    val volume: Int,
    val filled: Int = 0,
    val status: OrderStatus = OrderStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val message: String = ""
)

data class Trade(
    val id: String,
    val orderId: String,
    val symbol: String,
    val direction: Direction,
    val offset: Offset,
    val price: Double,
    val volume: Int,
    val pnl: Double = 0.0,
    val time: Long = System.currentTimeMillis()
)

data class Position(
    val contract: Contract,
    val direction: Direction,
    val volume: Int,
    val avgPrice: Double,
    val lastPrice: Double
) {
    val floatingPnl: Double get() {
        val diff = if (direction == Direction.LONG) lastPrice - avgPrice else avgPrice - lastPrice
        return diff * volume * contract.multiplier
    }
}

data class AccountState(
    val initialCash: Double = 1_000_000.0,
    val cash: Double = 1_000_000.0,
    val frozenMargin: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val positions: List<Position> = emptyList(),
    val orders: List<Order> = emptyList(),
    val trades: List<Trade> = emptyList()
) {
    val floatingPnl: Double get() = positions.sumOf { it.floatingPnl }
    val equity: Double get() = cash + frozenMargin + floatingPnl
    val riskRatio: Double get() = if (equity == 0.0) 0.0 else abs(frozenMargin / equity)
}
