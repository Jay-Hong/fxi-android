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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class SubscriptionManager @Inject constructor(
    private val pushNotificationManager: PushNotificationManager,
    private val premiumAccessCoordinator: PremiumAccessCoordinator
) {

    /**
     * A local purchase or restore reported an **active** entitlement.
     *
     * Forwarded as a signal only — the server stays the authority. This does not touch
     * [isPremium]; RevenueCat's own listener remains its only writer.
     */
    /**
     * Whether the *server* has confirmed premium for [uid], right now.
     *
     * Owner-bound on purpose. Checking the state alone let one account's confirmed grant authorise
     * another account's token registration whenever an auth callback for the second arrived before
     * the first user's grant had been cleared.
     */
    fun onLocalPremiumSignal() {
        premiumAccessCoordinator.onLocalPremiumSignal()
    }

    // ── 기존 State ──

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _isLoadingInitial = MutableStateFlow(true)
    val isLoadingInitial: StateFlow<Boolean> = _isLoadingInitial.asStateFlow()

    /** sign-out 이후 늦게 도착한 customerInfo 업데이트로 premium이 되살아나는 것을 방지 */
    private val isAuthActive = AtomicBoolean(false)

    /** auth session(generation). 늦게 도착한 콜백이 현재 세션이 아니면 무시한다. */
    private val authSession = AtomicLong(0)
    private val authUid = AtomicReference<String?>(null)

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
                if (!isAuthActive.get()) return@UpdatedCustomerInfoListener
                _isPremium.value = info.entitlements[ENTITLEMENT_PREMIUM]?.isActive == true
            }
        } else {
            _isLoadingInitial.value = false
        }
    }

    /**
     * 인증 완료 후 구독 상태를 확정한다.
     * @param customerInfo logIn() 콜백에서 이미 받은 CustomerInfo. null이면 서버에서 재조회.
     * @param session beginAuthSession()으로 받은 세션 토큰. 현재 세션이 아니면 no-op.
     */
    suspend fun onAuthCompleted(customerInfo: CustomerInfo? = null, session: Long? = null) {
        if (!isRevenueCatConfigured()) {
            _isLoadingInitial.value = false
            return
        }

        val expectedSession = session
        if (expectedSession != null && expectedSession != authSession.get()) {
            Log.d(TAG, "Ignore stale onAuthCompleted(session=$expectedSession, current=${authSession.get()})")
            return
        }
        if (!isAuthActive.get()) {
            Log.d(TAG, "Ignore onAuthCompleted while signed out")
            return
        }

        try {
            val info = customerInfo ?: Purchases.sharedInstance.getCustomerInfoSuspend()

            // onAuthCompleted 도중 sign-out / 다른 계정 로그인 등이 발생하면 결과를 버린다.
            if (expectedSession != null && expectedSession != authSession.get()) return
            if (!isAuthActive.get()) return

            _isPremium.value = info.entitlements[ENTITLEMENT_PREMIUM]?.isActive == true

            // The local flag is a *prompt* to try, never the authority. I7 makes RevenueCat a
            // purchase signal; the plan puts FCM registration at zero while access is unconfirmed
            // or refused. Whether a registration is allowed is decided against the identity that is
            // actually captured, inside the push manager — deciding it here for a uid this class
            // happens to be holding is what let one account's grant authorise another's device.
            if (_isPremium.value) pushNotificationManager.rehydratePushTokenIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load subscription", e)
        } finally {
            if (expectedSession == null || expectedSession == authSession.get()) {
                _isLoadingInitial.value = false
            }
        }
    }

    /**
     * RevenueCat logIn()을 호출하기 전에 세션을 시작한다.
     * - 이 세션 토큰을 logIn 콜백에서 onAuthCompleted로 전달해야 late callback race를 막을 수 있다.
     */
    fun beginAuthSession(uid: String): Long {
        if (!isRevenueCatConfigured()) return 0L

        val existingUid = authUid.get()
        if (isAuthActive.get() && existingUid == uid) {
            return authSession.get()
        }

        val session = authSession.incrementAndGet()
        authUid.set(uid)
        isAuthActive.set(true)

        // 계정 전환/재로그인 시 이전 상태가 잠깐 남지 않도록 로딩 상태로 전환
        _isPremium.value = false
        _isLoadingInitial.value = true

        return session
    }

    fun onAuthSignedOut() {
        authSession.incrementAndGet() // in-flight 콜백 무효화
        authUid.set(null)
        isAuthActive.set(false)
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
     * - PaywallScreen 이 표시 직전에 사용
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

    // ── Debug: Google Play ↔ RevenueCat 강제 동기화 (디버깅/테스트용) ──

    /** Google Play 상태를 RevenueCat에 강제 동기화 */
    suspend fun forceSyncPurchases() {
        if (!isRevenueCatConfigured()) return
        try {
            val info = syncPurchasesSuspend()
            _isPremium.value = info.entitlements[ENTITLEMENT_PREMIUM]?.isActive == true
            Log.d(TAG, "Force sync result: isPremium=${_isPremium.value}")
        } catch (e: Exception) {
            Log.e(TAG, "Force sync failed", e)
        }
    }

    private suspend fun syncPurchasesSuspend(): CustomerInfo =
        suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.syncPurchases(
                object : com.revenuecat.purchases.interfaces.SyncPurchasesCallback {
                    override fun onSuccess(customerInfo: CustomerInfo) {
                        cont.resume(customerInfo)
                    }

                    override fun onError(error: PurchasesError) {
                        cont.resumeWithException(PurchasesException(error))
                    }
                }
            )
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
