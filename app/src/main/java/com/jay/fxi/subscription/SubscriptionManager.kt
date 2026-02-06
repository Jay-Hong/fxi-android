package com.jay.fxi.subscription

import android.util.Log
import com.jay.fxi.BuildConfig
import com.jay.fxi.service.PushNotificationManager
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.Period
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class SubscriptionManager @Inject constructor(
    private val pushNotificationManager: PushNotificationManager
) {

    // ── 기존 State ──

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _isLoadingInitial = MutableStateFlow(true)
    val isLoadingInitial: StateFlow<Boolean> = _isLoadingInitial.asStateFlow()

    // ── Offerings State (iOS SubscriptionManager 파리티) ──

    private val _offerings = MutableStateFlow<Offerings?>(null)
    val offerings: StateFlow<Offerings?> = _offerings.asStateFlow()

    private val _offeringsError = MutableStateFlow<String?>(null)
    val offeringsError: StateFlow<String?> = _offeringsError.asStateFlow()

    private val _isLoadingOfferings = MutableStateFlow(false)
    val isLoadingOfferings: StateFlow<Boolean> = _isLoadingOfferings.asStateFlow()

    private val _trialDurationText = MutableStateFlow<String?>(null)
    val trialDurationText: StateFlow<String?> = _trialDurationText.asStateFlow()

    /** sign-out 등으로 인한 stale 업데이트 방지용 generation */
    private val offeringsGeneration = AtomicLong(0)

    /**
     * loadOfferings() 중복 호출 합침용 in-flight 핸들.
     * - Compose에서 preload(Preview) + Paywall 진입이 겹쳐도 1회만 호출되도록 한다.
     */
    private val offeringsLock = Any()
    private var offeringsInFlight: CompletableDeferred<Unit>? = null

    // ── 패키지 헬퍼 ──

    val annualPackage: Package?
        get() = _offerings.value?.current?.availablePackages
            ?.firstOrNull { it.packageType == PackageType.ANNUAL }

    val monthlyPackage: Package?
        get() = _offerings.value?.current?.availablePackages
            ?.firstOrNull { it.packageType == PackageType.MONTHLY }

    init {
        if (isRevenueCatConfigured()) {
            Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { info ->
                _isPremium.value = info.entitlements[ENTITLEMENT_PREMIUM]?.isActive == true
            }
        } else {
            _isLoadingInitial.value = false
        }
    }

    suspend fun onAuthCompleted() {
        if (!isRevenueCatConfigured()) {
            _isLoadingInitial.value = false
            return
        }

        try {
            val info = Purchases.sharedInstance.getCustomerInfoSuspend()
            _isPremium.value = info.entitlements[ENTITLEMENT_PREMIUM]?.isActive == true

            if (_isPremium.value) {
                pushNotificationManager.rehydratePushTokenIfNeeded(_isPremium.value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load subscription", e)
        } finally {
            _isLoadingInitial.value = false
        }
    }

    fun onAuthSignedOut() {
        _isPremium.value = false
        _isLoadingInitial.value = true

        // offerings 관련 상태 초기화
        offeringsGeneration.incrementAndGet()
        _offerings.value = null
        _offeringsError.value = null
        _isLoadingOfferings.value = false
        _trialDurationText.value = null
    }

    // ── Offerings 로드 (iOS loadOfferings() 파리티) ──

    /**
     * offerings를 로드한다. 이미 로딩 중이면 진행 중인 요청의 완료를 대기한다.
     * - 중복 호출 합침 (iOS의 offeringsTask 공유 패턴과 동일)
     * - LockedPreviewScreen에서 preload, PaywallScreen에서 재사용
     */
    suspend fun loadOfferings(force: Boolean = false) {
        if (!isRevenueCatConfigured()) {
            _offeringsError.value = "구독 기능이 비활성화되어 있습니다."
            return
        }

        // 이미 offerings가 있으면, 기본은 no-op (Paywall/Preview 진입마다 로딩 화면 깜빡임 방지)
        if (!force && _offerings.value != null) {
            return
        }

        while (true) {
            // 1) 이미 진행 중이면 완료까지 await 후, 필요 시 재시도
            val existing: CompletableDeferred<Unit>? = synchronized(offeringsLock) { offeringsInFlight }
            if (existing != null) {
                existing.await()

                // await 이후 상태가 채워졌으면 종료
                if (_offerings.value != null) return
                // force=false면 에러도 상태로 인정(화면에서 에러 UI 표시)
                if (!force && _offeringsError.value != null) return
                // force=true면 에러 상태에서도 재시도 가능
                continue
            }

            // 2) 리더 선점
            val inFlight = CompletableDeferred<Unit>()
            val isLeader = synchronized(offeringsLock) {
                if (offeringsInFlight == null) {
                    offeringsInFlight = inFlight
                    true
                } else {
                    false
                }
            }
            if (!isLeader) continue

            val currentGen = offeringsGeneration.get()

            _isLoadingOfferings.value = true
            _offeringsError.value = null

            try {
                val loadedOfferings = getOfferingsSuspend()

                // sign-out 등으로 generation이 바뀌면 결과를 반영하지 않는다.
                if (offeringsGeneration.get() != currentGen) return

                _offerings.value = loadedOfferings
                _offeringsError.value = null

                // 무료 체험 기간 텍스트 업데이트 (보수적: freePhase 존재 여부만 확인)
                val annual = loadedOfferings.current?.availablePackages
                    ?.firstOrNull { it.packageType == PackageType.ANNUAL }
                val freePhase = annual?.product?.defaultOption?.freePhase
                _trialDurationText.value = freePhase?.billingPeriod?.let { formatTrialPeriod(it) }
            } catch (e: Exception) {
                // sign-out 등으로 generation이 바뀌면 에러도 반영하지 않는다.
                if (offeringsGeneration.get() == currentGen) {
                    _offeringsError.value = e.message ?: "구독 정보를 불러올 수 없습니다."
                    Log.e(TAG, "Offerings 로드 실패", e)
                }
            } finally {
                if (offeringsGeneration.get() == currentGen) {
                    _isLoadingOfferings.value = false
                }
                synchronized(offeringsLock) {
                    if (offeringsInFlight === inFlight) {
                        offeringsInFlight = null
                    }
                }
                inFlight.complete(Unit)
            }
            return
        }
    }

    // ── Helpers ──

    fun isRevenueCatConfigured(): Boolean = BuildConfig.REVENUECAT_API_KEY.isNotBlank()

    /** freePhase Period → "N일" / "N개월" 등 텍스트 변환 */
    private fun formatTrialPeriod(period: Period): String? {
        val value = period.value
        if (value <= 0) return null
        return when (period.unit) {
            Period.Unit.DAY -> "${value}일"
            Period.Unit.WEEK -> "${value * 7}일"
            Period.Unit.MONTH -> "${value}개월"
            Period.Unit.YEAR -> "${value}년"
            else -> null
        }
    }

    private suspend fun getOfferingsSuspend(): Offerings =
        suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.getOfferings(
                object : ReceiveOfferingsCallback {
                    override fun onReceived(offerings: Offerings) {
                        cont.resume(offerings)
                    }

                    override fun onError(error: PurchasesError) {
                        cont.resumeWithException(PurchasesException(error))
                    }
                }
            )
        }

    private suspend fun Purchases.getCustomerInfoSuspend(): CustomerInfo =
        suspendCancellableCoroutine { cont ->
            getCustomerInfo(
                object : ReceiveCustomerInfoCallback {
                    override fun onReceived(customerInfo: CustomerInfo) {
                        cont.resume(customerInfo)
                    }

                    override fun onError(error: PurchasesError) {
                        cont.resumeWithException(PurchasesException(error))
                    }
                }
            )
        }

    companion object {
        private const val TAG = "SubscriptionManager"
        private const val ENTITLEMENT_PREMIUM = "premium"
    }
}
