package com.jay.fxi.ui.subscription

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.BankDisplayConfig
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.GraphSourceData
import com.jay.fxi.domain.model.RatesDisplayState
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.domain.model.displayState
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * LockedPreviewScreen 전용 상태 홀더 (remember 기반, Hilt 미사용)
 * iOS SamplePreviewViewModel 동일 구조/동작
 *
 * 사용법:
 * ```
 * val viewModel = remember { SamplePreviewViewModel() }
 * DisposableEffect(Unit) {
 *     viewModel.start()
 *     onDispose { viewModel.stop() }
 * }
 * ```
 */
class SamplePreviewViewModel {

    // ── Published State (Compose) ──

    var selectedCurrency by mutableStateOf(SupportedCurrency.USD_KRW)
        private set

    private var allRates by mutableStateOf<List<ExchangeRate>>(emptyList())
        private set

    private var allGraphData by mutableStateOf<Map<SupportedCurrency, Map<GraphPeriod, GraphSourceData>>>(emptyMap())
        private set

    var alertSettings by mutableStateOf<List<SampleAlertSetting>>(emptyList())
        private set

    var showAlertBanner by mutableStateOf(false)
        private set

    var triggeredAlert by mutableStateOf<SampleAlertSetting?>(null)
        private set

    var triggeredCurrentRate by mutableStateOf<Double?>(null)
        private set

    var activePeriod by mutableStateOf(GraphPeriod.ONE_DAY)
        private set

    var selectedSources by mutableStateOf(GraphSource.realtimeSources.toSet())
        private set

    var dxyVisible by mutableStateOf(false)
        private set

    val rates: List<ExchangeRate>
        get() = allRates.filter { it.currency == selectedCurrency.code }

    val graphDataByPeriod: Map<GraphPeriod, GraphSourceData>
        get() = allGraphData[selectedCurrency].orEmpty()

    val graphData: GraphSourceData
        get() = graphDataByPeriod[activePeriod].orEmpty()

    val filteredAlertSettings: List<SampleAlertSetting>
        get() = alertSettings.filter { it.currency == selectedCurrency }

    // ── Computed Properties ──

    val canAddAlert: Boolean
        get() = alertSettings.size < SampleAlertConfig.MAX_COUNT

    val remainingAlertCount: Int
        get() = max(0, SampleAlertConfig.MAX_COUNT - alertSettings.size)

    val currentCurrencyAlertCount: Int
        get() = filteredAlertSettings.size

    // ── Private State ──

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var animationJob: Job? = null
    private var bannerJob: Job? = null
    private var pendingTriggers = mutableListOf<PendingTrigger>()

    private data class PendingTrigger(
        val alertId: String,
        val rate: Double,
        val skipHaptic: Boolean
    )

    /** 햅틱 콜백 (LockedPreviewScreen에서 설정) */
    var onHapticFeedback: (() -> Unit)? = null

    // ── 초기화 (iOS init 동일) ──

    init {
        allRates = SampleData.initialRatesAll()
        allGraphData = SupportedCurrency.entries.associateWith { currency ->
            SampleData.generateAllPeriods(currency)
        }

        val investingRate = rates.firstOrNull { it.bank == Bank.INVESTING.code }?.rate ?: 0.0
        val kbRate = rates.firstOrNull { it.bank == Bank.KB.code }?.rate ?: 0.0
        val hanaRate = rates.firstOrNull { it.bank == Bank.HANA.code }?.rate ?: 0.0

        alertSettings = listOf(
            SampleAlertSetting(
                bank = Bank.INVESTING,
                condition = AlertCondition.ABOVE,
                threshold = investingRate + 0.15
            ),
            SampleAlertSetting(
                bank = Bank.KB,
                condition = AlertCondition.BELOW,
                threshold = kbRate - 0.20
            ),
            SampleAlertSetting(
                bank = Bank.HANA,
                condition = AlertCondition.ABOVE,
                threshold = hanaRate + 0.10
            )
        )
    }

    fun displayState(config: BankDisplayConfig): RatesDisplayState {
        return allRates.displayState(selectedCurrency, config)
    }

    fun displayState(
        currency: SupportedCurrency,
        config: BankDisplayConfig
    ): RatesDisplayState {
        return allRates.displayState(currency, config)
    }

    // ── Lifecycle ──

    fun start() {
        startRateAnimation()
    }

    fun stop() {
        scope.cancel()
        animationJob = null
        bannerJob = null
        pendingTriggers.clear()
        triggeredAlert = null
        triggeredCurrentRate = null
        showAlertBanner = false
        onHapticFeedback = null
    }

    fun pause() {
        animationJob?.cancel()
        animationJob = null
        bannerJob?.cancel()
        bannerJob = null

        if (showAlertBanner) {
            showAlertBanner = false
            val alert = triggeredAlert
            val rate = triggeredCurrentRate
            if (alert != null && rate != null) {
                val alreadyInQueue = pendingTriggers.any { it.alertId == alert.id }
                if (!alreadyInQueue) {
                    pendingTriggers.add(0, PendingTrigger(alert.id, rate, skipHaptic = true))
                }
            }
            triggeredAlert = null
            triggeredCurrentRate = null
        }
    }

    fun resume() {
        if (animationJob != null) return
        startRateAnimation()

        if (pendingTriggers.isNotEmpty() && !showAlertBanner) {
            processNextTrigger()
        }
    }

    // ── Rate Animation (4초 주기) ──

    private fun startRateAnimation() {
        animationJob?.cancel()
        animationJob = scope.launch {
            while (isActive) {
                delay(4000)
                if (!isActive) break

                val result = SampleData.randomizeAll(allRates)
                allRates = result.rates
                checkAlertTriggers(result.changedBanksByCurrency)
            }
        }
    }

    // ── Alert CRUD ──

    fun addAlert(
        bank: Bank,
        condition: AlertCondition,
        threshold: Double,
        isEnabled: Boolean = true
    ) {
        if (!canAddAlert) return
        alertSettings = alertSettings + SampleAlertSetting(
            bank = bank,
            currency = selectedCurrency,
            condition = condition,
            threshold = threshold,
            isEnabled = isEnabled
        )
    }

    fun toggleAlert(id: String) {
        alertSettings = alertSettings.map { alert ->
            if (alert.id == id) {
                if (!alert.isEnabled) {
                    alert.copy(isEnabled = true, triggered = false)
                } else {
                    alert.copy(isEnabled = false)
                }
            } else alert
        }
    }

    fun deleteAlert(id: String) {
        alertSettings = alertSettings.filter { it.id != id }
    }

    fun updateAlert(
        id: String,
        bank: Bank? = null,
        condition: AlertCondition? = null,
        threshold: Double? = null,
        isEnabled: Boolean? = null
    ) {
        alertSettings = alertSettings.map { alert ->
            if (alert.id != id) return@map alert

            val updated = alert.copy(
                bank = bank ?: alert.bank,
                condition = condition ?: alert.condition,
                threshold = threshold ?: alert.threshold,
                isEnabled = isEnabled ?: alert.isEnabled
            )

            // triggered 초기화 (조건/활성화 변경 시)
            if (bank != null || condition != null || threshold != null || isEnabled == true) {
                updated.copy(triggered = false)
            } else {
                updated
            }
        }
    }

    // ── Alert Trigger Simulation ──

    private fun checkAlertTriggers(changedBanksByCurrency: Map<SupportedCurrency, Set<Bank>>) {
        val triggered = mutableListOf<PendingTrigger>()
        val updatedSettings = alertSettings.toMutableList()

        for (i in updatedSettings.indices) {
            val alert = updatedSettings[i]
            if (!alert.isEnabled || alert.triggered) continue
            val changedBanks = changedBanksByCurrency[alert.currency].orEmpty()
            if (alert.bank !in changedBanks) continue

            val rate = allRates.firstOrNull {
                it.currency == alert.currency.code && it.bank == alert.bank.code
            }?.rate ?: continue

            val shouldTrigger = when (alert.condition) {
                AlertCondition.BELOW -> rate <= alert.threshold
                AlertCondition.ABOVE -> rate >= alert.threshold
            }

            if (shouldTrigger) {
                updatedSettings[i] = alert.copy(triggered = true, isEnabled = false)
                triggered.add(PendingTrigger(alert.id, rate, skipHaptic = false))
            }
        }

        if (triggered.isNotEmpty()) {
            alertSettings = updatedSettings
            pendingTriggers.addAll(triggered)
            processNextTrigger()
        }
    }

    private fun processNextTrigger() {
        if (showAlertBanner || pendingTriggers.isEmpty()) return

        val trigger = pendingTriggers.removeAt(0)
        val alert = alertSettings.firstOrNull { it.id == trigger.alertId }
        if (alert == null) {
            processNextTrigger()
            return
        }

        showTriggerBanner(alert, trigger.rate, trigger.skipHaptic)
    }

    private fun showTriggerBanner(alert: SampleAlertSetting, currentRate: Double, skipHaptic: Boolean) {
        bannerJob?.cancel()

        if (!skipHaptic) {
            onHapticFeedback?.invoke()
        }

        triggeredAlert = alert
        triggeredCurrentRate = currentRate
        showAlertBanner = true

        bannerJob = scope.launch {
            delay(5000)
            if (!isActive) return@launch

            showAlertBanner = false

            delay(300)
            if (!isActive) return@launch
            processNextTrigger()
        }
    }

    fun dismissAlertBanner() {
        bannerJob?.cancel()
        showAlertBanner = false

        scope.launch {
            delay(300)
            if (isActive) processNextTrigger()
        }
    }

    // ── Graph Helpers ──

    fun toggleSource(source: GraphSource) {
        if (activePeriod != GraphPeriod.ONE_DAY) return
        if (source !in GraphSource.realtimeSources) return

        if (source in selectedSources) {
            if (selectedSources.size > 1) {
                selectedSources = selectedSources - source
            }
        } else {
            selectedSources = selectedSources + source
        }
    }

    fun selectCurrency(currency: SupportedCurrency) {
        if (selectedCurrency == currency) return
        selectedCurrency = currency
        activePeriod = GraphPeriod.ONE_DAY
        selectedSources = GraphSource.realtimeSources.toSet()
        dxyVisible = false
    }

    fun selectPeriod(period: GraphPeriod) {
        activePeriod = period
        if (period != GraphPeriod.ONE_DAY) {
            selectedSources = GraphSource.realtimeSources.toSet()
        }
        if (!hasDxyData(selectedCurrency)) {
            dxyVisible = false
        }
    }

    fun hasDxyData(currency: SupportedCurrency = selectedCurrency): Boolean {
        return allGraphData[currency]
            ?.get(activePeriod)
            ?.containsKey(GraphSource.DXY.code) == true &&
            currency == SupportedCurrency.USD_KRW
    }

    fun toggleDxy() {
        if (!hasDxyData(selectedCurrency)) return
        dxyVisible = !dxyVisible
    }

    fun latestRateFor(source: GraphSource): Double? {
        val bank = when (source) {
            GraphSource.INVESTING -> Bank.INVESTING
            GraphSource.KB -> Bank.KB
            GraphSource.HANA -> Bank.HANA
            GraphSource.REFERENCE -> Bank.INVESTING
            GraphSource.DXY -> null
        }
        return bank?.let { target -> rates.firstOrNull { it.bank == target.code }?.rate }
    }

    fun latestDxyRate(currency: SupportedCurrency = selectedCurrency): Double? {
        return allGraphData[currency]
            ?.get(activePeriod)
            ?.get(GraphSource.DXY.code)
            ?.lastOrNull()
            ?.close
    }
}
