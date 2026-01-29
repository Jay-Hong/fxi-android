package com.jay.fxi.subscription

import android.util.Log
import com.jay.fxi.BuildConfig
import com.jay.fxi.service.PushNotificationManager
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class SubscriptionManager @Inject constructor(
    private val pushNotificationManager: PushNotificationManager
) {

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _isLoadingInitial = MutableStateFlow(true)
    val isLoadingInitial: StateFlow<Boolean> = _isLoadingInitial.asStateFlow()

    private val _isEligibleForIntro = MutableStateFlow(false)
    val isEligibleForIntro: StateFlow<Boolean> = _isEligibleForIntro.asStateFlow()

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
        _isEligibleForIntro.value = false
    }

    private fun isRevenueCatConfigured(): Boolean = BuildConfig.REVENUECAT_API_KEY.isNotBlank()

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
