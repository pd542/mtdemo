package com.hermes.futuressim.trade

import com.hermes.futuressim.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.random.Random

class TradingEngine {
    private val _account = MutableStateFlow(AccountState())
    val account: StateFlow<AccountState> = _account

    fun markToMarket(symbol: String, lastPrice: Double) {
        _account.value = _account.value.copy(positions = _account.value.positions.map {
            if (it.contract.symbol == symbol) it.copy(lastPrice = lastPrice) else it
        })
    }

    fun submit(contract: Contract, direction: Direction, offset: Offset, type: OrderType, price: Double, volume: Int, lastPrice: Double) {
        if (volume <= 0) return reject(contract, direction, offset, type, price, volume, "手数必须大于0")
        val id = "O" + System.currentTimeMillis().toString().takeLast(8) + Random.nextInt(10,99)
        val order = Order(id, contract, direction, offset, type, price, volume)
        val state = _account.value
        val shouldFill = type == OrderType.MARKET || (direction == Direction.LONG && price >= lastPrice) || (direction == Direction.SHORT && price <= lastPrice)
        if (!shouldFill) {
            _account.value = state.copy(orders = listOf(order) + state.orders)
            return
        }
        fill(order, if (type == OrderType.MARKET) lastPrice else price)
    }

    fun cancel(orderId: String) {
        val s = _account.value
        _account.value = s.copy(orders = s.orders.map { if (it.id == orderId && it.status == OrderStatus.PENDING) it.copy(status = OrderStatus.CANCELLED, message = "用户撤单") else it })
    }

    fun closePosition(position: Position, price: Double) {
        val direction = if (position.direction == Direction.LONG) Direction.SHORT else Direction.LONG
        val order = Order(
            id = "C" + System.currentTimeMillis().toString().takeLast(8) + Random.nextInt(10,99),
            contract = position.contract,
            direction = direction,
            offset = Offset.CLOSE,
            type = OrderType.MARKET,
            price = price,
            volume = position.volume
        )
        fill(order, price)
    }

    fun tryMatchPending(symbol: String, lastPrice: Double) {
        val pending = _account.value.orders.filter { it.status == OrderStatus.PENDING && it.contract.symbol == symbol }
        pending.forEach { o ->
            val hit = (o.direction == Direction.LONG && o.price >= lastPrice) || (o.direction == Direction.SHORT && o.price <= lastPrice)
            if (hit) fill(o, o.price)
        }
    }

    private fun fill(order: Order, fillPrice: Double) {
        val s = _account.value
        var cash = s.cash
        var frozen = s.frozenMargin
        var realized = s.realizedPnl
        val positions = s.positions.toMutableList()
        val margin = fillPrice * order.volume * order.contract.multiplier * order.contract.marginRate
        if (order.offset == Offset.OPEN) {
            if (cash < margin) { reject(order.contract, order.direction, order.offset, order.type, order.price, order.volume, "可用资金不足") ; return }
            cash -= margin
            frozen += margin
            val idx = positions.indexOfFirst { it.contract.symbol == order.contract.symbol && it.direction == order.direction }
            if (idx >= 0) {
                val old = positions[idx]
                val vol = old.volume + order.volume
                positions[idx] = old.copy(volume = vol, avgPrice = (old.avgPrice * old.volume + fillPrice * order.volume) / vol, lastPrice = fillPrice)
            } else positions.add(Position(order.contract, order.direction, order.volume, fillPrice, fillPrice))
        } else {
            val closeDir = if (order.direction == Direction.LONG) Direction.SHORT else Direction.LONG
            val idx = positions.indexOfFirst { it.contract.symbol == order.contract.symbol && it.direction == closeDir }
            if (idx < 0 || positions[idx].volume < order.volume) { reject(order.contract, order.direction, order.offset, order.type, order.price, order.volume, "可平仓位不足") ; return }
            val old = positions[idx]
            val pnlPer = if (old.direction == Direction.LONG) fillPrice - old.avgPrice else old.avgPrice - fillPrice
            val pnl = pnlPer * order.volume * order.contract.multiplier
            realized += pnl
            val release = old.avgPrice * order.volume * order.contract.multiplier * order.contract.marginRate
            frozen -= release
            cash += release + pnl
            if (old.volume == order.volume) positions.removeAt(idx) else positions[idx] = old.copy(volume = old.volume - order.volume, lastPrice = fillPrice)
        }
        val tradePnl = if (order.offset == Offset.CLOSE) realized - s.realizedPnl else 0.0
        val trade = Trade("T" + System.currentTimeMillis().toString().takeLast(8), order.id, order.contract.symbol, order.direction, order.offset, fillPrice, order.volume, tradePnl)
        val orders = listOf(order.copy(status = OrderStatus.FILLED, filled = order.volume, message = "全部成交")) + s.orders.filterNot { it.id == order.id }
        _account.value = s.copy(cash = cash, frozenMargin = frozen.coerceAtLeast(0.0), realizedPnl = realized, positions = positions, orders = orders, trades = listOf(trade) + s.trades)
    }

    private fun reject(contract: Contract, direction: Direction, offset: Offset, type: OrderType, price: Double, volume: Int, msg: String) {
        val order = Order("R" + System.currentTimeMillis().toString().takeLast(8), contract, direction, offset, type, price, volume, status = OrderStatus.REJECTED, message = msg)
        _account.value = _account.value.copy(orders = listOf(order) + _account.value.orders)
    }
}
