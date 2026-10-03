package ai.abbas.app.data

object PaymentConfig {
    // Stripe Configuration
    const val STRIPE_PUBLISHABLE_KEY = "pk_test_your_key_here"
    const val STRIPE_BACKEND_URL = "https://your-backend-api.com/create-payment-intent"

    // PayPal Configuration
    const val PAYPAL_CLIENT_ID = "YOUR_PAYPAL_CLIENT_ID"
    const val PAYPAL_RETURN_URL = "ai.abbas.app://paypalpay"
    const val PAYPAL_BACKEND_URL = "https://your-backend-api.com/create-paypal-order"

    /**
     * True only when a real Stripe publishable key is present. The donation flow
     * never reports success unless the gateway and backend are genuinely wired up.
     */
    val stripeConfigured: Boolean
        get() = STRIPE_PUBLISHABLE_KEY.startsWith("pk_") &&
            !STRIPE_PUBLISHABLE_KEY.contains("your_key", ignoreCase = true)

    /** True only when a real PayPal client id is present (see [stripeConfigured]). */
    val paypalConfigured: Boolean
        get() = PAYPAL_CLIENT_ID.isNotBlank() &&
            !PAYPAL_CLIENT_ID.startsWith("YOUR_", ignoreCase = true)
}

data class StripePaymentConfig(
    val paymentIntent: String,
    val ephemeralKey: String,
    val customer: String,
    val publishableKey: String = PaymentConfig.STRIPE_PUBLISHABLE_KEY
)

data class PayPalPaymentConfig(
    val orderId: String
)
