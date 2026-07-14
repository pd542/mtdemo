package com.hermes.futuressim

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.futuressim.data.MarketRepository
import com.hermes.futuressim.model.*
import com.hermes.futuressim.trade.TradingEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val market = MarketRepository()
    val engine = TradingEngine()
    private val prefs = app.getSharedPreferences("watchlist", Context.MODE_PRIVATE)
    var tab by mutableStateOf(0)
    var selected by mutableStateOf(market.contractFor("AU0"))
    var quote by mutableStateOf<Quote?>(null)
    var quotes by mutableStateOf<Map<String, Quote>>(emptyMap())
    var watchSymbols by mutableStateOf(loadSymbols())
    var editMode by mutableStateOf(false)
    var showAddDialog by mutableStateOf(false)
    var showOrderPage by mutableStateOf(false)
    var showOrderTypeMenu by mutableStateOf(false)
    var selectedOrderType by mutableStateOf("市场执行")
    var stopLossTicks by mutableStateOf(0)
    var takeProfitTicks by mutableStateOf(0)
    var showTradeSortMenu by mutableStateOf(false)
    var tradeSort by mutableStateOf("时间")
    var historyTab by mutableStateOf("成交")
    var showHistorySymbolMenu by mutableStateOf(false)
    var historySymbolFilter by mutableStateOf("全部交易品种")
    var showHistorySortMenu by mutableStateOf(false)
    var historySort by mutableStateOf("时间")
    var showHistoryPeriodMenu by mutableStateOf(false)
    var historyPeriod by mutableStateOf("今天")
    var showCustomDateDialog by mutableStateOf(false)
    var customStart by mutableStateOf("")
    var customEnd by mutableStateOf("")
    var showChartOrderTools by mutableStateOf(false)
    var chartOrderType by mutableStateOf("买入限价")
    var chartOrderVolume by mutableStateOf(1)
    var showChartVolumeMenu by mutableStateOf(false)
    var draftEntryPrice by mutableStateOf(0.0)
    var draftSlPrice by mutableStateOf(0.0)
    var draftTpPrice by mutableStateOf(0.0)
    var chartSlEnabled by mutableStateOf(false)
    var chartTpEnabled by mutableStateOf(false)
    var chartSheetExpanded by mutableStateOf(false)
    var chartOrderMode by mutableStateOf("限价")
    var slTpInputMode by mutableStateOf("价格")
    var chartExpiration by mutableStateOf("直到取消")
    var chartComment by mutableStateOf("")
    var showExpirationMenu by mutableStateOf(false)
    val account: StateFlow<AccountState> = engine.account

    private fun loadSymbols(): List<String> {
        val raw = prefs.getString("symbols", null)
        return raw?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotBlank() }?.distinct()?.takeIf { it.isNotEmpty() } ?: listOf("AU0", "RB0")
    }

    private fun saveSymbols() {
        prefs.edit().putString("symbols", watchSymbols.joinToString(",")).apply()
    }

    fun addSymbol(symbol: String) {
        val code = symbol.trim().uppercase()
        if (code.isNotBlank() && !watchSymbols.contains(code)) {
            market.contractFor(code)
            watchSymbols = watchSymbols + code
            saveSymbols()
        }
    }

    fun removeSymbol(symbol: String) {
        if (watchSymbols.size <= 1) return
        watchSymbols = watchSymbols.filterNot { it == symbol }
        if (selected.symbol == symbol) selected = market.contractFor(watchSymbols.first())
        saveSymbols()
    }

    fun moveSymbol(symbol: String, delta: Int) {
        val list = watchSymbols.toMutableList()
        val from = list.indexOf(symbol)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from >= 0 && from != to) {
            Collections.swap(list, from, to)
            watchSymbols = list
            saveSymbols()
        }
    }

    fun submitOpen(isBuy: Boolean, price: Double, volume: Int) {
        val last = quote?.last ?: price
        val direction = if (isBuy) com.hermes.futuressim.model.Direction.LONG else com.hermes.futuressim.model.Direction.SHORT
        val type = when (selectedOrderType) {
            "买入限价", "卖出限价", "买入止损限价", "卖出止损限价" -> OrderType.LIMIT
            "买入止损", "卖出止损" -> OrderType.STOP
            else -> OrderType.MARKET
        }
        engine.submit(selected, direction, com.hermes.futuressim.model.Offset.OPEN, type, price, volume.coerceAtLeast(1), last)
    }

    fun closePosition(position: Position) {
        val last = quotes[position.contract.symbol]?.last ?: quote?.last ?: position.lastPrice
        engine.closePosition(position, last)
    }

    private fun checkStopLossTakeProfit() {
        val q = quote ?: return
        val slTicks = stopLossTicks
        val tpTicks = takeProfitTicks
        if (slTicks <= 0 && tpTicks <= 0) return
        val tick = selected.tickSize
        val positions = engine.account.value.positions.filter { it.contract.symbol == selected.symbol }
        positions.forEach { p ->
            val slHit = slTicks > 0 && ((p.direction == com.hermes.futuressim.model.Direction.LONG && q.last <= p.avgPrice - slTicks * tick) || (p.direction == com.hermes.futuressim.model.Direction.SHORT && q.last >= p.avgPrice + slTicks * tick))
            val tpHit = tpTicks > 0 && ((p.direction == com.hermes.futuressim.model.Direction.LONG && q.last >= p.avgPrice + tpTicks * tick) || (p.direction == com.hermes.futuressim.model.Direction.SHORT && q.last <= p.avgPrice - tpTicks * tick))
            if (slHit || tpHit) engine.closePosition(p, q.last)
        }
    }

    fun sortedTrades(): List<Trade> {
        val trades = engine.account.value.trades
        return when (tradeSort) {
            "订单" -> trades.sortedBy { it.orderId }
            "交易品种" -> trades.sortedBy { it.symbol }
            "获利" -> trades.sortedByDescending { it.price }
            else -> trades.sortedBy { it.time }
        }
    }

    fun historySymbols(): List<String> {
        val fromTrades = engine.account.value.trades.map { it.symbol }
        val fromOrders = engine.account.value.orders.map { it.contract.symbol }
        val fromPositions = engine.account.value.positions.map { it.contract.symbol }
        return (watchSymbols + fromTrades + fromOrders + fromPositions).distinct()
    }

    fun periodStartEnd(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance(Locale.CHINA)
        cal.timeInMillis = now
        val end = now
        when (historyPeriod) {
            "上一周" -> cal.add(Calendar.DAY_OF_YEAR, -7)
            "上个月" -> cal.add(Calendar.MONTH, -1)
            "最近3个月" -> cal.add(Calendar.MONTH, -3)
            "自定义周期" -> {
                val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                val start = runCatching { parser.parse(customStart)?.time ?: 0L }.getOrDefault(0L)
                val customEndTime = runCatching { (parser.parse(customEnd)?.time ?: now) + 24*60*60*1000L - 1 }.getOrDefault(now)
                return start to customEndTime
            }
            else -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
        }
        return cal.timeInMillis to end
    }

    fun symbolAllowed(symbol: String): Boolean = historySymbolFilter == "全部交易品种" || historySymbolFilter == symbol

    fun filteredTrades(): List<Trade> {
        val (start, end) = periodStartEnd()
        val base = engine.account.value.trades.filter { it.time in start..end && symbolAllowed(it.symbol) }
        return when (historySort) {
            "交易品种" -> base.sortedBy { it.symbol }
            "获利" -> base.sortedByDescending { it.pnl }
            "交易量" -> base.sortedByDescending { it.volume }
            else -> base.sortedByDescending { it.time }
        }
    }

    fun filteredOrders(): List<Order> {
        val (start, end) = periodStartEnd()
        val base = engine.account.value.orders.filter { it.createdAt in start..end && symbolAllowed(it.contract.symbol) }
        return when (historySort) {
            "交易品种" -> base.sortedBy { it.contract.symbol }
            "获利" -> base.sortedByDescending { it.price }
            "交易量" -> base.sortedByDescending { it.volume }
            else -> base.sortedByDescending { it.createdAt }
        }
    }

    fun filteredPositions(): List<Position> {
        val base = engine.account.value.positions.filter { symbolAllowed(it.contract.symbol) }
        return when (historySort) {
            "交易品种" -> base.sortedBy { it.contract.symbol }
            "获利" -> base.sortedByDescending { it.floatingPnl }
            "交易量" -> base.sortedByDescending { it.volume }
            else -> base.sortedBy { it.contract.symbol }
        }
    }

    fun normalizeChartOrderAroundMarket() {
        val last = quote?.last ?: return
        chartOrderType = when (chartOrderMode) {
            "止损" -> if (draftEntryPrice >= last) "买入止损" else "卖出止损"
            "止损限价" -> if (draftEntryPrice >= last) "买入止损限价" else "卖出止损限价"
            "市场" -> "市场执行"
            else -> if (draftEntryPrice >= last) "卖出限价" else "买入限价"
        }
        val tick = selected.tickSize
        if (chartSlEnabled || chartTpEnabled) {
            val isBuy = chartOrderType.startsWith("买入") || chartOrderType == "市场执行"
            if (isBuy) {
                if (chartSlEnabled && draftSlPrice >= draftEntryPrice) draftSlPrice = draftEntryPrice - tick * 20
                if (chartTpEnabled && draftTpPrice <= draftEntryPrice) draftTpPrice = draftEntryPrice + tick * 30
            } else {
                if (chartSlEnabled && draftSlPrice <= draftEntryPrice) draftSlPrice = draftEntryPrice + tick * 20
                if (chartTpEnabled && draftTpPrice >= draftEntryPrice) draftTpPrice = draftEntryPrice - tick * 30
            }
        }
    }

    fun ensureChartOrderDraft() {
        val last = quote?.last ?: 0.0
        if (last <= 0.0) return
        if (draftEntryPrice <= 0.0) draftEntryPrice = last - selected.tickSize * 8
        if (draftSlPrice <= 0.0) draftSlPrice = draftEntryPrice - selected.tickSize * 20
        if (draftTpPrice <= 0.0) draftTpPrice = draftEntryPrice + selected.tickSize * 30
        normalizeChartOrderAroundMarket()
    }

    fun toggleChartSl() {
        ensureChartOrderDraft()
        chartSlEnabled = !chartSlEnabled
        if (chartSlEnabled) {
            val isBuy = !chartOrderType.startsWith("卖出")
            draftSlPrice = if (isBuy) draftEntryPrice - selected.tickSize * 20 else draftEntryPrice + selected.tickSize * 20
        }
        normalizeChartOrderAroundMarket()
    }

    fun toggleChartTp() {
        ensureChartOrderDraft()
        chartTpEnabled = !chartTpEnabled
        if (chartTpEnabled) {
            val isBuy = !chartOrderType.startsWith("卖出")
            draftTpPrice = if (isBuy) draftEntryPrice + selected.tickSize * 30 else draftEntryPrice - selected.tickSize * 30
        }
        normalizeChartOrderAroundMarket()
    }

    fun submitChartOrder() {
        ensureChartOrderDraft()
        val last = quote?.last ?: draftEntryPrice
        val isBuy = !chartOrderType.startsWith("卖出")
        val direction = if (isBuy) com.hermes.futuressim.model.Direction.LONG else com.hermes.futuressim.model.Direction.SHORT
        val type = when {
            chartOrderType.contains("止损") -> OrderType.STOP
            chartOrderType.contains("限价") -> OrderType.LIMIT
            else -> OrderType.MARKET
        }
        engine.submit(selected, direction, com.hermes.futuressim.model.Offset.OPEN, type, draftEntryPrice, chartOrderVolume.coerceAtLeast(1), last)
        val tick = selected.tickSize
        stopLossTicks = if (chartSlEnabled) kotlin.math.max(0, kotlin.math.round(kotlin.math.abs(draftEntryPrice - draftSlPrice) / tick).toInt()) else 0
        takeProfitTicks = if (chartTpEnabled) kotlin.math.max(0, kotlin.math.round(kotlin.math.abs(draftTpPrice - draftEntryPrice) / tick).toInt()) else 0
    }

    suspend fun tick() {
        val current = market.quote(selected)
        market.onTick(current)
        quote = current
        val updated = quotes.toMutableMap()
        updated[current.contract.symbol] = current
        watchSymbols.forEach { s ->
            if (s != selected.symbol) {
                val c = market.contractFor(s)
                val q = market.quote(c)
                market.onTick(q)
                updated[s] = q
            }
        }
        quotes = updated
        engine.markToMarket(current.contract.symbol, current.last)
        engine.tryMatchPending(current.contract.symbol, current.last)
        checkStopLossTakeProfit()
    }
}

@Composable fun App(vm: AppViewModel = viewModel()) {
    val account by vm.account.collectAsState()
    LaunchedEffect(vm.selected, vm.watchSymbols) { while (true) { vm.tick(); delay(2500) } }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1565C0))) {
        Scaffold(bottomBar = { BottomNav(vm.tab, account) { vm.tab = it } }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().background(Color.White)) {
                when (vm.tab) {
                    0 -> MarketScreen(vm)
                    1 -> ChartScreen(vm)
                    2 -> TradeScreen(vm)
                    3 -> HistoryScreen(vm)
                    else -> InfoScreen(vm)
                }
            }
        }
    }
}

@Composable fun BottomNav(tab: Int, account: AccountState, onTab: (Int)->Unit) {
    val labels = listOf("行情" to "⇅", "图表" to "▥", "交易" to "⌁", "历史" to "◷", "信息" to "☷")
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        labels.forEachIndexed { i, p ->
            NavigationBarItem(
                selected = tab == i,
                onClick = { onTab(i) },
                icon = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.second, fontSize = if (i == 2) 25.sp else 22.sp, color = if (tab == i) Color(0xFF1976D2) else Color(0xFF333333))
                        if (i == 2 && tab == i) {
                            Box(Modifier.background(Color(0xFFEAEAEA), shape = MaterialTheme.shapes.large).padding(horizontal = 12.dp, vertical = 2.dp)) {
                                Text("%.2f".format(account.equity), color = Color(0xFF555555), fontSize = 12.sp)
                            }
                        }
                    }
                },
                label = { if (!(i == 2 && tab == i)) Text(p.first) }
            )
        }
    }
}

@Composable fun MarketScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        MarketTopBar(vm)
        LazyColumn(Modifier.weight(1f)) {
            items(vm.watchSymbols, key = { it }) { symbol ->
                val c = vm.market.contractFor(symbol)
                val q = vm.quotes[symbol]
                MarketQuoteRow(
                    contract = c,
                    quote = q,
                    editMode = vm.editMode,
                    onClick = { vm.selected = c; vm.tab = 1 },
                    onRemove = { vm.removeSymbol(symbol) },
                    onUp = { vm.moveSymbol(symbol, -1) },
                    onDown = { vm.moveSymbol(symbol, 1) }
                )
            }
        }
    }
    if (vm.showAddDialog) AddSymbolDialog(vm)
}

@Composable fun MarketTopBar(vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("☰", fontSize = 28.sp, color = Color.Black)
            Text("行情", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 24.dp))
            Text("+", fontSize = 32.sp, color = Color(0xFF1E88E5), modifier = Modifier.clickable { vm.showAddDialog = true }.padding(horizontal = 12.dp))
            Text(if (vm.editMode) "✓" else "✎", fontSize = 25.sp, color = Color(0xFF1E88E5), modifier = Modifier.clickable { vm.editMode = !vm.editMode }.padding(start = 12.dp))
        }
        Divider(color = Color(0xFFECECEC), thickness = 1.dp)
    }
}

@Composable fun AddSymbolDialog(vm: AppViewModel) {
    var input by remember { mutableStateOf("") }
    val candidates = vm.market.contracts.filterNot { vm.watchSymbols.contains(it.symbol) }
    AlertDialog(
        onDismissRequest = { vm.showAddDialog = false },
        title = { Text("添加品种") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(input, { input = it.uppercase() }, label = { Text("手动输入代码，如 AU0/RB0") }, singleLine = true)
                Text("候选品种", fontWeight = FontWeight.Bold)
                candidates.take(8).forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { vm.addSymbol(c.symbol); vm.showAddDialog = false }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.symbol, fontWeight = FontWeight.Bold)
                        Text(c.name, color = Color.Gray)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.addSymbol(input); vm.showAddDialog = false }) { Text("添加") } },
        dismissButton = { TextButton(onClick = { vm.showAddDialog = false }) { Text("取消") } }
    )
}

@Composable fun MarketQuoteRow(contract: Contract, quote: Quote?, editMode: Boolean, onClick: () -> Unit, onRemove: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    val q = quote ?: Quote(contract, 0.0, 0.0, 0.0, QuoteSource.LOCAL_SIMULATION)
    val up = q.change >= 0
    val accent = if (up) Color(0xFF1976D2) else Color(0xFFE53935)
    val bidText = if (q.last == 0.0) "--" else priceFmt(q.bid, contract)
    val askText = if (q.last == 0.0) "--" else priceFmt(q.ask, contract)
    Column(Modifier.fillMaxWidth().clickable { if (!editMode) onClick() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (editMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(42.dp)) {
                    Text("✕", color = Color(0xFFE53935), fontSize = 22.sp, modifier = Modifier.clickable { onRemove() })
                    Row { Text("↑", color = Color.Gray, modifier = Modifier.clickable { onUp() }); Spacer(Modifier.width(6.dp)); Text("↓", color = Color.Gray, modifier = Modifier.clickable { onDown() }) }
                }
            }
            Column(Modifier.weight(0.88f)) {
                Text("${if (up) "+" else ""}${"%.2f".format(q.change)}   ${if (up) "+" else ""}${"%.2f".format(q.changePct)}%", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(contract.symbol, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(" ${contract.exchange}", color = Color.Gray, fontSize = 11.sp)
                }
                Text(fmtTime(q.time), color = Color(0xFF8A8A8A), fontSize = 12.sp)
                Text(q.spreadTicks.toString(), color = Color(0xFF777777), fontSize = 13.sp)
            }
            Column(Modifier.weight(1.35f), horizontalAlignment = Alignment.End) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                    Text(bidText, color = Color(0xFFE53935), fontSize = 29.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    Spacer(Modifier.width(18.dp))
                    Text(askText, color = Color(0xFF1976D2), fontSize = 29.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("L: ${if (q.last == 0.0) "--" else priceFmt(q.low, contract)}", color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.width(22.dp))
                    Text("H: ${if (q.last == 0.0) "--" else priceFmt(q.high, contract)}", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable fun ContractSelector(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.watchSymbols.forEach { s ->
            val c = vm.market.contractFor(s)
            val sel = c.symbol == vm.selected.symbol
            AssistChip(onClick = { vm.selected = c }, label = { Text(c.name) }, colors = AssistChipDefaults.assistChipColors(containerColor = if (sel) Color(0xFFE3F2FD) else Color.White))
        }
    }
}

@Composable fun ChartScreen(vm: AppViewModel) {
    val q = vm.quote
    LaunchedEffect(q?.last, vm.selected.symbol, vm.showChartOrderTools) { if (vm.showChartOrderTools) vm.ensureChartOrderDraft() }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        ChartToolbar(vm)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CandleChart(vm.market.getCandles(vm.selected), q?.last)
            if (vm.showChartOrderTools) {
                ChartOrderLines(vm, Modifier.matchParentSize())
            }
        }
        if (vm.showChartOrderTools) ChartOrderPanel(vm)
        else Text("价格：${q?.last?.let { priceFmt(it, vm.selected) } ?: "--"}    来源：${q?.source ?: "--"}", Modifier.padding(12.dp), color = Color.DarkGray)
    }
}

@Composable fun ChartToolbar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().height(52.dp).background(Color.White).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("☰", fontSize = 28.sp, color = Color.Black)
        Text("⌖", fontSize = 26.sp)
        Text("〽", fontSize = 26.sp)
        Text("M5", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("◌", fontSize = 24.sp, color = Color(0xFFE53935))
        Text("▣", fontSize = 25.sp, color = if (vm.showChartOrderTools) Color(0xFF1976D2) else Color.Gray, modifier = Modifier.clickable { vm.showChartOrderTools = !vm.showChartOrderTools; vm.ensureChartOrderDraft() })
    }
}

@Composable fun CandleChart(candles: List<Candle>, last: Double?) {
    Canvas(Modifier.fillMaxSize().background(Color.White).padding(4.dp)) {
        if (candles.isEmpty()) return@Canvas
        val maxP = candles.maxOf { it.high }
        val minP = candles.minOf { it.low }
        val span = (maxP - minP).coerceAtLeast(1.0)
        fun y(p: Double) = size.height - ((p - minP) / span * size.height).toFloat()
        repeat(12) { i ->
            val yy = size.height * i / 11f
            drawLine(Color(0xFFE6E6E6), Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f)
        }
        val w = size.width / candles.size
        candles.forEachIndexed { i, c ->
            val x = i * w + w/2
            val color = if (c.close >= c.open) Color(0xFF009688) else Color(0xFFE53935)
            drawLine(color, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 2f)
            drawRect(color, topLeft = Offset(x - w*0.32f, min(y(c.open), y(c.close))), size = androidx.compose.ui.geometry.Size(w*0.64f, max(2f, kotlin.math.abs(y(c.close)-y(c.open)))))
        }
        val path = Path()
        candles.mapIndexed { i, c -> i * w + w/2 to c.close }.forEachIndexed { i, pair -> if (i==0) path.moveTo(pair.first, y(pair.second)) else path.lineTo(pair.first, y(pair.second)) }
        drawPath(path, Color(0xFFC62828), style = Stroke(5f, cap = StrokeCap.Round))
        last?.let { drawLine(Color(0xFF00A6A6), Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 2f) }
    }
}

@Composable fun ChartOrderLines(vm: AppViewModel, modifier: Modifier = Modifier) {
    val candles = vm.market.getCandles(vm.selected)
    val q = vm.quote
    val prices = mutableListOf(vm.draftEntryPrice, q?.last ?: vm.draftEntryPrice)
    if (vm.chartTpEnabled) prices.add(vm.draftTpPrice)
    if (vm.chartSlEnabled) prices.add(vm.draftSlPrice)
    val maxP = max(candles.maxOfOrNull { it.high } ?: prices.maxOrNull()!!, prices.maxOrNull()!!)
    val minP = min(candles.minOfOrNull { it.low } ?: prices.minOrNull()!!, prices.minOrNull()!!)
    val span = (maxP - minP).coerceAtLeast(vm.selected.tickSize * 80)
    fun priceToY(price: Double, height: Float): Float = height - ((price - minP) / span * height).toFloat()
    fun yToPrice(y: Float, height: Float): Double = minP + (height - y) / height * span
    BoxWithConstraints(modifier) {
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        if (vm.chartTpEnabled) ChartDraggableLine("TP, ${priceDiffText(vm.draftTpPrice, vm.draftEntryPrice, vm.selected)}", vm.draftTpPrice, Color(0xFF2EAD4E), priceToY(vm.draftTpPrice, hPx), { dy -> vm.draftTpPrice = yToPrice((priceToY(vm.draftTpPrice, hPx)+dy).coerceIn(0f,hPx), hPx); vm.normalizeChartOrderAroundMarket() }, vm)
        ChartDraggableLine("${vm.chartOrderType.uppercase()} ${vm.chartOrderVolume}", vm.draftEntryPrice, Color(0xFF1976D2), priceToY(vm.draftEntryPrice, hPx), { dy -> vm.draftEntryPrice = yToPrice((priceToY(vm.draftEntryPrice, hPx)+dy).coerceIn(0f,hPx), hPx); vm.normalizeChartOrderAroundMarket() }, vm)
        if (vm.chartSlEnabled) ChartDraggableLine("SL, ${priceDiffText(vm.draftSlPrice, vm.draftEntryPrice, vm.selected)}", vm.draftSlPrice, Color(0xFFC47B21), priceToY(vm.draftSlPrice, hPx), { dy -> vm.draftSlPrice = yToPrice((priceToY(vm.draftSlPrice, hPx)+dy).coerceIn(0f,hPx), hPx); vm.normalizeChartOrderAroundMarket() }, vm)
        q?.let {
            val cy = priceToY(it.last, hPx)
            Row(Modifier.offset(y = cy.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${priceFmt(it.last, vm.selected)}\n03:14", color = Color.White, fontSize = 11.sp, modifier = Modifier.background(Color(0xFF00A6A6)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable fun ChartDraggableLine(label: String, price: Double, color: Color, y: Float, onDragY: (Float) -> Unit, vm: AppViewModel) {
    Box(Modifier.fillMaxWidth().offset(y = y.dp).height(32.dp).pointerInput(label, price) { detectDragGestures { change, dragAmount -> change.consume(); onDragY(dragAmount.y) } }) {
        Canvas(Modifier.fillMaxWidth().height(32.dp)) { drawLine(color, Offset(0f, 16f), Offset(size.width, 16f), strokeWidth = 3f); drawCircle(color, radius = 9f, center = Offset(size.width*0.52f, 16f)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(priceFmt(price, vm.selected), color = Color.White, fontSize = 12.sp, modifier = Modifier.background(color).padding(horizontal = 5.dp, vertical = 2.dp))
        }
    }
}

@Composable fun ChartOrderPanel(vm: AppViewModel) {
    if (vm.chartSheetExpanded) {
        ExpandedChartOrderTicket(vm)
    } else {
        Column(Modifier.fillMaxWidth().background(Color(0xFFF8F8F8)).padding(top = 6.dp).pointerInput(Unit) { detectDragGestures(onDragEnd = { vm.chartSheetExpanded = true }) { change, dragAmount -> if (dragAmount.y < -8f) vm.chartSheetExpanded = true; change.consume() } }) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(42.dp).height(4.dp).background(Color(0xFFCFCFCF)).clickable { vm.chartSheetExpanded = true })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(vm.selected.symbol, color = Color(0xFF1976D2), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Box {
                    Text("  ${vm.chartOrderVolume}手 ▾", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.clickable { vm.showChartVolumeMenu = true })
                    DropdownMenu(expanded = vm.showChartVolumeMenu, onDismissRequest = { vm.showChartVolumeMenu = false }) {
                        listOf(1,2,3,5,10).forEach { v -> DropdownMenuItem(text = { Text("$v 手") }, onClick = { vm.chartOrderVolume = v; vm.showChartVolumeMenu = false }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("SL", color = Color.White, modifier = Modifier.background(if (vm.chartSlEnabled) Color(0xFFE53935) else Color(0xFFBDBDBD), shape = MaterialTheme.shapes.large).clickable { vm.toggleChartSl() }.padding(8.dp))
                Spacer(Modifier.width(10.dp))
                Text("TP", color = Color.White, modifier = Modifier.background(if (vm.chartTpEnabled) Color(0xFF2EAD4E) else Color(0xFFBDBDBD), shape = MaterialTheme.shapes.large).clickable { vm.toggleChartTp() }.padding(8.dp))
                Spacer(Modifier.width(12.dp))
                Text("➜", color = Color.White, fontSize = 22.sp, modifier = Modifier.background(Color(0xFF1976D2), shape = MaterialTheme.shapes.large).clickable { vm.submitChartOrder() }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            LazyRow(Modifier.fillMaxWidth().padding(start = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(listOf("市场", "限价", "止损", "止损限价")) { mode ->
                    Text(mode, color = if (mode == vm.chartOrderMode) Color(0xFF1976D2) else Color.Gray, fontSize = 14.sp, fontWeight = if (mode == vm.chartOrderMode) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable { vm.chartOrderMode = mode; vm.normalizeChartOrderAroundMarket() })
                }
            }
        }
    }
}

@Composable fun ExpandedChartOrderTicket(vm: AppViewModel) {
    val q = vm.quote
    val account by vm.account.collectAsState()
    val bid = q?.bid ?: vm.draftEntryPrice
    val ask = q?.ask ?: vm.draftEntryPrice
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFFFAFAFA)).padding(horizontal = 18.dp, vertical = 8.dp).pointerInput(Unit) { detectDragGestures(onDragEnd = {}) { change, dragAmount -> if (dragAmount.y > 10f) vm.chartSheetExpanded = false; change.consume() } }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(70.dp).height(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFFC8C8C8)).clickable { vm.chartSheetExpanded = false })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) { Text(vm.selected.symbol, color = Color(0xFF1976D2), fontSize = 28.sp, fontWeight = FontWeight.Bold); Text(vm.selected.name, color = Color.Gray, fontSize = 14.sp) }
            Text(priceFmt(bid, vm.selected), color = Color(0xFF1976D2), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp)); Text(priceFmt(ask, vm.selected), color = Color(0xFF1976D2), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(listOf("市场", "限价", "止损", "止损限价")) { mode ->
                Text(mode, color = if (mode == vm.chartOrderMode) Color(0xFFD32F2F) else Color.Gray, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { vm.chartOrderMode = mode; vm.normalizeChartOrderAroundMarket() })
            }
        }
        TicketStepperBox("交易量", vm.chartOrderVolume.toString(), "手数", onMinus = { vm.chartOrderVolume = (vm.chartOrderVolume - 1).coerceAtLeast(1) }, onPlus = { vm.chartOrderVolume += 1 })
        Slider(value = vm.chartOrderVolume.toFloat(), onValueChange = { vm.chartOrderVolume = it.toInt().coerceIn(1, 10) }, valueRange = 1f..10f, steps = 8, colors = SliderDefaults.colors(thumbColor = Color(0xFF1976D2), activeTrackColor = Color(0xFFD9E8FA), inactiveTrackColor = Color(0xFFD9E8FA)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("预付款 ≈ ${"%.2f".format(vm.chartOrderVolume * vm.selected.marginRate * vm.draftEntryPrice * vm.selected.multiplier)}", color = Color(0xFF1976D2), fontSize = 13.sp); Text("可用: ${"%.2f".format(account.cash)}", color = Color.DarkGray, fontSize = 13.sp) }
        PriceEditBox("价格", vm.draftEntryPrice, vm.selected, onMinus = { vm.draftEntryPrice -= vm.selected.tickSize; vm.normalizeChartOrderAroundMarket() }, onPlus = { vm.draftEntryPrice += vm.selected.tickSize; vm.normalizeChartOrderAroundMarket() }, onText = { vm.draftEntryPrice = it; vm.normalizeChartOrderAroundMarket() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("价格", "点数", "USD").forEach { m -> Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(7.dp)).background(if (m == vm.slTpInputMode) Color.White else Color(0xFFEAF3FF)).clickable { vm.slTpInputMode = m }, contentAlignment = Alignment.Center) { Text(m, color = Color(0xFF1976D2), fontWeight = FontWeight.Bold) } } }
        Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SlTpTicketCell("SL", vm.chartSlEnabled, vm.draftSlPrice, Color(0xFFE15850), vm, Modifier.weight(1f), onToggle = { vm.toggleChartSl() }, onMinus = { vm.draftSlPrice -= vm.selected.tickSize; vm.normalizeChartOrderAroundMarket() }, onPlus = { vm.draftSlPrice += vm.selected.tickSize; vm.normalizeChartOrderAroundMarket() }, onText = { vm.draftSlPrice = it; vm.normalizeChartOrderAroundMarket() })
            Divider(Modifier.width(1.dp).fillMaxHeight(), color = Color(0xFFE0E0E0))
            SlTpTicketCell("TP", vm.chartTpEnabled, vm.draftTpPrice, Color(0xFF10B83A), vm, Modifier.weight(1f), onToggle = { vm.toggleChartTp() }, onMinus = { vm.draftTpPrice -= vm.selected.tickSize; vm.normalizeChartOrderAroundMarket() }, onPlus = { vm.draftTpPrice += vm.selected.tickSize; vm.normalizeChartOrderAroundMarket() }, onText = { vm.draftTpPrice = it; vm.normalizeChartOrderAroundMarket() })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (vm.chartSlEnabled) priceDiffText(vm.draftSlPrice, vm.draftEntryPrice, vm.selected) else "未设置SL", color = Color(0xFFC00000), fontSize = 12.sp); Text(if (vm.chartTpEnabled) priceDiffText(vm.draftTpPrice, vm.draftEntryPrice, vm.selected) else "未设置TP", color = Color(0xFF1A8F35), fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("超期:", fontSize = 16.sp); Box { Text("  ${vm.chartExpiration}⌄", color = Color(0xFF1976D2), fontSize = 16.sp, modifier = Modifier.clickable { vm.showExpirationMenu = true }); DropdownMenu(vm.showExpirationMenu, { vm.showExpirationMenu = false }) { listOf("直到取消", "当日有效", "指定日期").forEach { e -> DropdownMenuItem(text={Text(e)}, onClick={ vm.chartExpiration=e; vm.showExpirationMenu=false }) } } } }
        OutlinedTextField(vm.chartComment, { vm.chartComment = it }, label = { Text("评论") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 14.sp))
        Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp)).background(if (vm.chartOrderType.startsWith("卖出")) Color(0xFFF0441C) else Color(0xFF1976D2)).clickable { vm.submitChartOrder() }, contentAlignment = Alignment.Center) { Text("下单 ${vm.chartOrderType}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable fun TicketStepperBox(label: String, value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("−", color = Color(0xFF1976D2), fontSize = 22.sp, modifier = Modifier.clickable { onMinus() }.padding(8.dp))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("+", color = Color(0xFF1976D2), fontSize = 22.sp, modifier = Modifier.clickable { onPlus() }.padding(8.dp))
        Divider(Modifier.padding(horizontal=10.dp).width(1.dp).height(36.dp), color = Color(0xFFE0E0E0))
        Text(unit, fontSize = 16.sp)
    }
}

@Composable fun PriceEditBox(label: String, price: Double, contract: Contract, onMinus: () -> Unit, onPlus: () -> Unit, onText: (Double) -> Unit) {
    var text by remember(price) { mutableStateOf(priceFmt(price, contract)) }
    Row(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        BasicTextField(value = text, onValueChange = { text = it; it.toDoubleOrNull()?.let(onText) }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End), modifier = Modifier.width(110.dp))
        Text("−", color = Color(0xFF1976D2), fontSize = 22.sp, modifier = Modifier.clickable { onMinus() }.padding(8.dp))
        Text("+", color = Color(0xFF1976D2), fontSize = 22.sp, modifier = Modifier.clickable { onPlus() }.padding(8.dp))
    }
}

@Composable fun SlTpTicketCell(label: String, enabled: Boolean, price: Double, color: Color, vm: AppViewModel, modifier: Modifier, onToggle: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit, onText: (Double) -> Unit) {
    var text by remember(price, enabled) { mutableStateOf(if (enabled) priceFmt(price, vm.selected) else "") }
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.background(if (enabled) color else Color(0xFFBDBDBD), RoundedCornerShape(6.dp)).clickable { onToggle() }.padding(horizontal = 7.dp, vertical = 7.dp))
        Spacer(Modifier.width(3.dp))
        if (enabled) {
            BasicTextField(value = text, onValueChange = { text = it; it.toDoubleOrNull()?.let(onText) }, singleLine = true, textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(62.dp))
            Text("−", color = Color(0xFF1976D2), fontSize = 18.sp, modifier = Modifier.clickable { onMinus() }.padding(horizontal = 3.dp))
            Text("+", color = Color(0xFF1976D2), fontSize = 18.sp, modifier = Modifier.clickable { onPlus() }.padding(horizontal = 3.dp))
        } else {
            Text("未设置", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable fun TradeScreen(vm: AppViewModel) {
    val account by vm.account.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize()) {
            TradeTopBar(vm)
            AccountSummaryRows(account)
            Divider(color = Color(0xFFE6E6E6), thickness = 1.dp)
            val positions = account.positions
            if (positions.isNotEmpty()) {
                Text("持仓", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                positions.forEach { PositionRow(it, vm) }
                Divider(color = Color(0xFFE6E6E6), thickness = 1.dp)
            }
            val trades = vm.sortedTrades()
            if (trades.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    Text("暂无成交记录", color = Color(0xFFB0B0B0), fontSize = 15.sp, modifier = Modifier.padding(top = 34.dp))
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    items(trades) { t -> TradeRecordRow(t) }
                }
            }
        }
    }
    if (vm.showOrderPage) OrderEntryPage(vm)
}

@Composable fun TradeTopBar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("☰", fontSize = 28.sp, color = Color.Black)
        Text("交易", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 24.dp))
        Box {
            Text("⇅", fontSize = 25.sp, color = Color(0xFF222222), modifier = Modifier.clickable { vm.showTradeSortMenu = true }.padding(horizontal = 10.dp))
            DropdownMenu(expanded = vm.showTradeSortMenu, onDismissRequest = { vm.showTradeSortMenu = false }, modifier = Modifier.width(170.dp).background(Color.White)) {
                listOf("订单", "时间", "交易品种", "获利").forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(item, fontSize = 22.sp, color = Color.Black)
                                if (item == vm.tradeSort) Text("↑", color = Color(0xFF1976D2), fontSize = 20.sp)
                            }
                        },
                        onClick = { vm.tradeSort = item; vm.showTradeSortMenu = false }
                    )
                    if (item != "获利") Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                }
            }
        }
        Text("▣+", fontSize = 24.sp, color = Color(0xFF222222), modifier = Modifier.clickable { vm.showOrderPage = true }.padding(start = 12.dp))
    }
}

@Composable fun AccountSummaryRows(account: AccountState) {
    val balance = account.cash + account.frozenMargin
    val rows = listOf("结余:" to balance, "净值:" to account.equity, "可用预付款:" to account.cash)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                DottedLeader(Modifier.weight(1f).padding(horizontal = 8.dp))
                Text("%.2f".format(value), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable fun DottedLeader(modifier: Modifier = Modifier) {
    Canvas(modifier.height(10.dp)) {
        val y = size.height / 2f
        var x = 0f
        while (x < size.width) {
            drawCircle(Color(0xFFD6D6D6), radius = 1.4f, center = Offset(x, y))
            x += 8f
        }
    }
}

@Composable fun PositionRow(position: Position, vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${position.contract.symbol} ${position.direction} ${position.volume}手", fontWeight = FontWeight.Bold)
            Text("均价 ${priceFmt(position.avgPrice, position.contract)}  浮盈 ${"%.2f".format(position.floatingPnl)}", color = Color.Gray, fontSize = 13.sp)
        }
        Text("平仓", color = Color.White, modifier = Modifier.background(Color(0xFF1976D2), shape = MaterialTheme.shapes.small).clickable { vm.closePosition(position) }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable fun OrderEntryPage(vm: AppViewModel) {
    val q = vm.quote
    var volume by remember { mutableStateOf(1) }
    var price by remember(q?.last, vm.selected) { mutableStateOf(q?.last ?: 0.0) }
    val bid = q?.bid ?: price
    val ask = q?.ask ?: price
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 42.sp, color = Color.Black, modifier = Modifier.clickable { vm.showOrderPage = false })
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(vm.selected.symbol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(vm.selected.name, color = Color.Gray, fontSize = 14.sp)
                }
                Text("\$⇄", fontSize = 24.sp, color = Color(0xFF444444))
            }
            Box(Modifier.fillMaxWidth().clickable { vm.showOrderTypeMenu = true }.padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vm.selectedOrderType, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Divider(color = Color(0xFFBBBBBB), modifier = Modifier.width(150.dp).padding(top = 10.dp), thickness = 1.dp)
                }
                DropdownMenu(expanded = vm.showOrderTypeMenu, onDismissRequest = { vm.showOrderTypeMenu = false }, modifier = Modifier.width(230.dp).background(Color.White)) {
                    listOf("市场执行", "买入限价", "卖出限价", "买入止损", "卖出止损", "买入止损限价", "卖出止损限价").forEach { type ->
                        DropdownMenuItem(text = { Text(type, fontSize = 20.sp, modifier = Modifier.padding(vertical = 8.dp)) }, onClick = { vm.selectedOrderType = type; vm.showOrderTypeMenu = false })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                listOf(-5, -1).forEach { d -> Text(d.toString(), color = Color(0xFFB0B0B0), fontSize = 16.sp, modifier = Modifier.clickable { volume = (volume + d).coerceAtLeast(1) }) }
                Text(volume.toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                listOf(1, 5).forEach { d -> Text("+$d", color = Color(0xFF1976D2), fontSize = 16.sp, modifier = Modifier.clickable { volume += d }) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                BigPrice(priceFmt(bid, vm.selected), Color(0xFF1976D2))
                BigPrice(priceFmt(ask, vm.selected), Color(0xFF1976D2))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                SlTpControl("SL", vm.stopLossTicks, Color(0xFFF2992E), onMinus = { vm.stopLossTicks = (vm.stopLossTicks - 1).coerceAtLeast(0) }, onPlus = { vm.stopLossTicks += 1 })
                SlTpControl("TP", vm.takeProfitTicks, Color(0xFF37A757), onMinus = { vm.takeProfitTicks = (vm.takeProfitTicks - 1).coerceAtLeast(0) }, onPlus = { vm.takeProfitTicks += 1 })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("成交指令", color = Color.Gray, fontSize = 16.sp)
                Text("全部或部分", color = Color.Black, fontSize = 16.sp)
            }
            Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
            BidAskMiniChart(bid, ask, vm.selected, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 8.dp))
            Text("注意：市价交易模式下的实际成交价格，可能会和请求价格有一定差异！", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().height(76.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { vm.submitOpen(false, price, volume); vm.showOrderPage = false }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("SELL", color = Color(0xFFE53935), fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("通过市场", color = Color(0xFFE53935), fontSize = 14.sp) }
                }
                Divider(color = Color(0xFFE0E0E0), modifier = Modifier.width(1.dp).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight().clickable { vm.submitOpen(true, price, volume); vm.showOrderPage = false }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("BUY", color = Color(0xFF1976D2), fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("通过市场", color = Color(0xFF1976D2), fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable fun BigPrice(text: String, color: Color) {
    Text(text, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold)
}

@Composable fun SlTpControl(label: String, value: Int, lineColor: Color, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(130.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("−", color = Color(0xFF1976D2), fontSize = 26.sp, modifier = Modifier.clickable { onMinus() })
            Text(if (value == 0) label else "$label $value", color = Color.Gray, fontSize = 15.sp)
            Text("+", color = Color(0xFF1976D2), fontSize = 24.sp, modifier = Modifier.clickable { onPlus() })
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(lineColor))
    }
}

@Composable fun BidAskMiniChart(bid: Double, ask: Double, contract: Contract, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val mid = size.height / 2f
        repeat(8) { i ->
            val y = size.height * (i + 1) / 9f
            var x = 0f
            while (x < size.width) { drawCircle(Color(0xFFE0E0E0), radius = 1.1f, center = Offset(x, y)); x += 12f }
        }
        val redY = mid - size.height * 0.17f
        val blueY = mid + size.height * 0.17f
        val red = Path().apply { moveTo(0f, redY + 30f); lineTo(size.width*0.18f, redY - 10f); lineTo(size.width*0.34f, redY + 12f); lineTo(size.width*0.52f, redY - 4f); lineTo(size.width, redY) }
        val blue = Path().apply { moveTo(0f, blueY + 20f); lineTo(size.width*0.16f, blueY - 16f); lineTo(size.width*0.34f, blueY + 10f); lineTo(size.width*0.50f, blueY - 5f); lineTo(size.width, blueY) }
        drawPath(red, Color(0xFFE53935), style = Stroke(4f, cap = StrokeCap.Round))
        drawPath(blue, Color(0xFF1976D2), style = Stroke(4f, cap = StrokeCap.Round))
    }
}

@Composable fun TradeRecordRow(t: Trade) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t.symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("${t.direction}/${t.offset}", color = if (t.direction == com.hermes.futuressim.model.Direction.LONG) Color(0xFF1976D2) else Color(0xFFE53935), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${t.volume}手 @ ${"%.2f".format(t.price)}", color = Color(0xFF666666), fontSize = 14.sp)
            Text(fmt(t.time), color = Color(0xFF888888), fontSize = 13.sp)
        }
        Divider(color = Color(0xFFEDEDED), thickness = 1.dp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable fun HistoryScreen(vm: AppViewModel) {
    val account by vm.account.collectAsState()
    Column(Modifier.fillMaxSize().background(Color.White)) {
        HistoryTopBar(vm)
        HistoryTabs(vm)
        Divider(color = Color(0xFFE6E6E6), thickness = 1.dp)
        when (vm.historyTab) {
            "价位" -> HistoryPositionsList(vm, account)
            "订单" -> HistoryOrdersList(vm, account)
            else -> HistoryDealsList(vm, account)
        }
    }
    if (vm.showCustomDateDialog) CustomDateDialog(vm)
}

@Composable fun HistoryTopBar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("☰", fontSize = 28.sp, color = Color.Black)
        Box(Modifier.weight(1f).padding(start = 22.dp)) {
            Column(Modifier.clickable { vm.showHistorySymbolMenu = true }) {
                Text("历史", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(vm.historySymbolFilter, color = Color.Gray, fontSize = 13.sp)
            }
            DropdownMenu(expanded = vm.showHistorySymbolMenu, onDismissRequest = { vm.showHistorySymbolMenu = false }, modifier = Modifier.width(210.dp).background(Color.White)) {
                listOf("全部交易品种").plus(vm.historySymbols()).forEach { sym ->
                    DropdownMenuItem(text = { Text(sym, fontSize = 18.sp, color = if (sym == vm.historySymbolFilter) Color(0xFF1976D2) else Color.Black) }, onClick = { vm.historySymbolFilter = sym; vm.showHistorySymbolMenu = false })
                }
            }
        }
        Text("\$↻", color = Color(0xFF333333), fontSize = 22.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Box {
            Text("⇅", color = Color(0xFF333333), fontSize = 24.sp, modifier = Modifier.clickable { vm.showHistorySortMenu = true }.padding(horizontal = 8.dp))
            DropdownMenu(expanded = vm.showHistorySortMenu, onDismissRequest = { vm.showHistorySortMenu = false }, modifier = Modifier.width(170.dp).background(Color.White)) {
                listOf("时间", "交易品种", "获利", "交易量").forEach { item ->
                    DropdownMenuItem(text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(item, fontSize = 20.sp); if (item == vm.historySort) Text("↑", color = Color(0xFF1976D2)) } }, onClick = { vm.historySort = item; vm.showHistorySortMenu = false })
                    if (item != "交易量") Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                }
            }
        }
        Box {
            Text("▣", color = Color(0xFF333333), fontSize = 24.sp, modifier = Modifier.clickable { vm.showHistoryPeriodMenu = true }.padding(start = 8.dp))
            DropdownMenu(expanded = vm.showHistoryPeriodMenu, onDismissRequest = { vm.showHistoryPeriodMenu = false }, modifier = Modifier.width(190.dp).background(Color.White)) {
                listOf("今天", "上一周", "上个月", "最近3个月", "自定义周期").forEach { item ->
                    DropdownMenuItem(text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(item, fontSize = 18.sp); if (item == vm.historyPeriod) Text("✓", color = Color(0xFF1976D2)) } }, onClick = { vm.historyPeriod = item; vm.showHistoryPeriodMenu = false; if (item == "自定义周期") vm.showCustomDateDialog = true })
                }
            }
        }
    }
}

@Composable fun HistoryTabs(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.Bottom) {
        listOf("价位", "订单", "成交").forEach { tab ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).fillMaxHeight().clickable { vm.historyTab = tab }, verticalArrangement = Arrangement.Bottom) {
                Text(tab, color = if (vm.historyTab == tab) Color.Black else Color.Gray, fontSize = 17.sp, fontWeight = if (vm.historyTab == tab) FontWeight.Bold else FontWeight.Normal)
                Spacer(Modifier.height(9.dp))
                Box(Modifier.height(3.dp).width(56.dp).background(if (vm.historyTab == tab) Color(0xFF1976D2) else Color.Transparent))
            }
        }
    }
}

@Composable fun HistoryPositionsList(vm: AppViewModel, account: AccountState) {
    val positions = vm.filteredPositions()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            if (positions.isEmpty()) item { EmptyHistoryText("暂无价位记录") }
            items(positions) { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${p.contract.symbol}, ${if (p.direction == com.hermes.futuressim.model.Direction.LONG) "buy" else "sell"}", color = if (p.direction == com.hermes.futuressim.model.Direction.LONG) Color(0xFF1976D2) else Color(0xFFE53935), fontWeight = FontWeight.Bold)
                        Text("${p.volume} 手", color = Color.Black)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("均价 ${priceFmt(p.avgPrice, p.contract)}  现价 ${priceFmt(p.lastPrice, p.contract)}", color = Color.Gray, fontSize = 13.sp)
                        Text("${"%.2f".format(p.floatingPnl)}", color = if (p.floatingPnl >= 0) Color(0xFF1976D2) else Color(0xFFE53935), fontSize = 13.sp)
                    }
                    Divider(color = Color(0xFFEDEDED), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        HistorySummaryPanel(listOf(
            "持仓" to positions.sumOf { it.volume }.toString(),
            "浮动盈利" to "%.2f".format(positions.filter { it.floatingPnl > 0 }.sumOf { it.floatingPnl }),
            "浮动亏损" to "%.2f".format(positions.filter { it.floatingPnl < 0 }.sumOf { it.floatingPnl }),
            "净值" to "%.2f".format(account.equity)
        ))
    }
}

@Composable fun HistoryOrdersList(vm: AppViewModel, account: AccountState) {
    val orders = vm.filteredOrders()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            if (orders.isEmpty()) item { EmptyHistoryText("暂无订单记录") }
            items(orders) { o ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${o.contract.symbol}, ${if (o.direction == com.hermes.futuressim.model.Direction.LONG) "buy" else "sell"}", color = if (o.direction == com.hermes.futuressim.model.Direction.LONG) Color(0xFF1976D2) else Color(0xFFE53935), fontWeight = FontWeight.Bold)
                        Text(o.status.name, color = Color.Gray, fontSize = 13.sp)
                    }
                    Text("#${o.id}  ${fmt(o.createdAt)}", color = Color.Gray, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${o.offset}/${o.type}  ${o.volume}手 @ ${priceFmt(o.price, o.contract)}", color = Color.Black, fontSize = 14.sp)
                        Text(o.message, color = Color.Gray, fontSize = 13.sp)
                    }
                    Divider(color = Color(0xFFEDEDED), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        HistorySummaryPanel(listOf(
            "订单" to orders.size.toString(),
            "成交" to orders.count { it.status == OrderStatus.FILLED }.toString(),
            "取消" to orders.count { it.status == OrderStatus.CANCELLED }.toString(),
            "拒单" to orders.count { it.status == OrderStatus.REJECTED }.toString()
        ))
    }
}

@Composable fun HistoryDealsList(vm: AppViewModel, account: AccountState) {
    val trades = vm.filteredTrades()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            if (trades.isEmpty()) item { EmptyHistoryText("暂无成交记录") }
            items(trades) { t ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${t.symbol}, ${if (t.direction == com.hermes.futuressim.model.Direction.LONG) "buy" else "sell"}", color = if (t.direction == com.hermes.futuressim.model.Direction.LONG) Color(0xFF1976D2) else Color(0xFFE53935), fontWeight = FontWeight.Bold)
                        Text("${"%.2f".format(t.pnl)}", color = if (t.pnl >= 0) Color(0xFF1976D2) else Color(0xFFE53935), fontSize = 13.sp)
                    }
                    Text("#${t.orderId}  ${fmt(t.time)}", color = Color.Gray, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${t.offset}  ${t.volume}手 @ ${"%.2f".format(t.price)}", color = Color.Black, fontSize = 14.sp)
                        Text("成交", color = Color.Gray, fontSize = 13.sp)
                    }
                    Divider(color = Color(0xFFEDEDED), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        val profit = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
        val loss = trades.filter { it.pnl < 0 }.sumOf { it.pnl }
        HistorySummaryPanel(listOf(
            "入金" to "%.2f".format(account.initialCash),
            "取款" to "0.00",
            "盈利" to "%.2f".format(profit),
            "亏损" to "%.2f".format(loss),
            "余额" to "%.2f".format(account.equity)
        ))
    }
}

@Composable fun EmptyHistoryText(text: String) {
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Text(text, color = Color(0xFFAAAAAA)) }
}

@Composable fun HistorySummaryPanel(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$label:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                DottedLeader(Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable fun CustomDateDialog(vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.showCustomDateDialog = false },
        title = { Text("自定义周期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(vm.customStart, { vm.customStart = it }, label = { Text("开始日期 yyyy-MM-dd") }, singleLine = true)
                OutlinedTextField(vm.customEnd, { vm.customEnd = it }, label = { Text("结束日期 yyyy-MM-dd") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { vm.historyPeriod = "自定义周期"; vm.showCustomDateDialog = false }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { vm.showCustomDateDialog = false }) { Text("取消") } }
    )
}

@Composable fun InfoScreen(vm: AppViewModel) {
    val account by vm.account.collectAsState()
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("期货模拟交易", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("行情页已按截图风格改造：顶部菜单/行情标题/+添加/编辑，列表显示涨跌幅、代码、时间、点差、买价/卖价、L/H。")
        Text("自选品种默认 AU0、RB0，支持添加、删除、上下排序，并通过 SharedPreferences 本地保存。")
        Text("点击行情品种会切换当前交易品种并跳转到图表页。")
        Text("交易页已按截图风格改造：账户摘要点线、排序菜单、右上角新建下单、成交记录列表。")
        Text("+ 按钮打开全屏下单页：7种订单类型、整数手数调节、SL/TP模拟触发、SELL/BUY开仓。")
        Text("图表页右侧第二个工具按钮可打开订单面板，支持7种类型、手数菜单、可拖动BUY/SL/TP线并点箭头确认挂单。")
        Text("风险度：${"%.2f%%".format(account.riskRatio*100)}")
        Text("注意：这是模拟交易 App，不连接真实柜台，不发送真实订单。", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
    }
}


fun priceDiffText(price: Double, entry: Double, c: Contract): String {
    val diff = price - entry
    val points = kotlin.math.round(kotlin.math.abs(diff / c.tickSize)).toInt()
    val money = diff * c.multiplier
    val sign = if (money >= 0) "+" else "-"
    return "$sign${"%.2f".format(kotlin.math.abs(money))} USD, $points points"
}

fun fmt(t: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(t))
fun fmtTime(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(t))
fun priceFmt(p: Double, c: Contract): String = if (c.tickSize < 0.1) "%.2f".format(p) else "%.0f".format(p)
