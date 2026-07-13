package com.hermes.futuressim.data

import com.hermes.futuressim.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.random.Random

class MarketRepository {
    val contracts = listOf(
        Contract("AU0", "沪金主连", "SHFE", 1000, 0.12, 0.02),
        Contract("RB0", "螺纹钢主连", "SHFE", 10, 0.10, 1.0),
        Contract("AG0", "沪银主连", "SHFE", 15, 0.12, 1.0),
        Contract("CU0", "沪铜主连", "SHFE", 5, 0.12, 10.0),
        Contract("AL0", "沪铝主连", "SHFE", 5, 0.10, 5.0),
        Contract("M0", "豆粕主连", "DCE", 10, 0.08, 1.0),
        Contract("Y0", "豆油主连", "DCE", 10, 0.08, 2.0),
        Contract("CF0", "棉花主连", "CZCE", 5, 0.09, 5.0),
        Contract("TA0", "PTA主连", "CZCE", 5, 0.08, 2.0),
        Contract("IF0", "沪深300股指主连", "CFFEX", 300, 0.12, 0.2)
    )

    private val prices = mutableMapOf(
        "AU0" to 560.0, "RB0" to 3600.0, "AG0" to 8200.0, "CU0" to 79000.0, "AL0" to 20500.0,
        "M0" to 3100.0, "Y0" to 8200.0, "CF0" to 14500.0, "TA0" to 5900.0, "IF0" to 3600.0
    )
    private val candles = mutableMapOf<String, MutableList<Candle>>()

    init { contracts.forEach { candles[it.symbol] = seedCandles(it) } }

    fun contractFor(symbol: String): Contract {
        val code = symbol.trim().uppercase()
        return contracts.firstOrNull { it.symbol == code } ?: Contract(code, "$code 合约", "CUSTOM", 10, 0.10, 1.0).also {
            prices.putIfAbsent(code, 3000.0 + Random.nextDouble(-500.0, 500.0))
            candles.putIfAbsent(code, seedCandles(it))
        }
    }

    suspend fun quote(contract: Contract): Quote = withContext(Dispatchers.IO) {
        runCatching { fetchSina(contract) }.getOrElse { simulatedQuote(contract) }
    }

    fun getCandles(contract: Contract): List<Candle> {
        candles.putIfAbsent(contract.symbol, seedCandles(contract))
        return candles.getValue(contract.symbol)
    }

    fun onTick(quote: Quote) {
        prices[quote.contract.symbol] = quote.last
        candles.putIfAbsent(quote.contract.symbol, seedCandles(quote.contract))
        val list = candles.getValue(quote.contract.symbol)
        val last = list.last()
        val now = System.currentTimeMillis()
        val fiveMin = 5 * 60 * 1000L
        if (now - last.time >= fiveMin) {
            list.add(Candle(now, last.close, max(last.close, quote.last), kotlin.math.min(last.close, quote.last), quote.last, Random.nextLong(100, 9000)))
            if (list.size > 180) list.removeAt(0)
        } else {
            list[list.lastIndex] = last.copy(high = max(last.high, quote.last), low = kotlin.math.min(last.low, quote.last), close = quote.last, volume = last.volume + Random.nextLong(1, 99))
        }
    }

    private fun fetchSina(contract: Contract): Quote {
        val sinaCode = "nf_${contract.symbol}"
        val conn = URL("https://hq.sinajs.cn/list=$sinaCode").openConnection() as HttpURLConnection
        conn.setRequestProperty("Referer", "https://finance.sina.com.cn")
        conn.connectTimeout = 3500
        conn.readTimeout = 3500
        val body = conn.inputStream.readBytes().toString(Charset.forName("GBK"))
        val payload = body.substringAfter("=\"").substringBefore("\";")
        val parts = payload.split(',')
        val nums = parts.mapNotNull { it.toDoubleOrNull() }
        if (nums.isEmpty()) error("empty sina quote")
        val last = nums.firstOrNull { it > 0 } ?: error("bad quote")
        val prev = prices[contract.symbol] ?: last
        val tick = contract.tickSize
        val bid = last - tick
        val ask = last + tick
        val low = nums.filter { it > 0 }.minOrNull() ?: last
        val high = nums.maxOrNull() ?: last
        return Quote(contract, last, last - prev, (last - prev) / prev * 100.0, QuoteSource.SINA_FREE_API, bid, ask, low, high, ((ask - bid) / tick).toInt())
    }

    private fun simulatedQuote(contract: Contract): Quote {
        val prev = prices[contract.symbol] ?: 1000.0
        val drift = Random.nextDouble(-1.0, 1.0) * prev * 0.0015
        val last = max(contract.tickSize, prev + drift)
        val tick = contract.tickSize
        val spreadTicks = if (contract.symbol == "AU0") 2 else 1
        val bid = last - tick * spreadTicks / 2.0
        val ask = last + tick * spreadTicks / 2.0
        val dayLow = last * (1.0 - Random.nextDouble(0.001, 0.012))
        val dayHigh = last * (1.0 + Random.nextDouble(0.001, 0.012))
        return Quote(contract, last, last - prev, (last - prev) / prev * 100.0, QuoteSource.LOCAL_SIMULATION, bid, ask, dayLow, dayHigh, spreadTicks)
    }

    private fun seedCandles(contract: Contract): MutableList<Candle> {
        var p = prices[contract.symbol] ?: 1000.0
        val now = System.currentTimeMillis() - 120 * 5 * 60 * 1000L
        return MutableList(120) { i ->
            val open = p
            val close = max(contract.tickSize, open + Random.nextDouble(-1.0, 1.0) * open * 0.004)
            val high = max(open, close) + Random.nextDouble(0.0, 1.0) * open * 0.002
            val low = kotlin.math.min(open, close) - Random.nextDouble(0.0, 1.0) * open * 0.002
            p = close
            Candle(now + i * 5 * 60 * 1000L, open, high, low, close, Random.nextLong(100, 20000))
        }
    }
}
