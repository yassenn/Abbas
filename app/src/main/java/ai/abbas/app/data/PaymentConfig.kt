package ai.abbas.app.data

object PaymentConfig {
    // Stripe Configuration
    const val STRIPE_PUBLISHABLE_KEY = "pk_test_your_key_here"
    const val STRIPE_BACKEND_URL = "https://your-backend-api.com/create-payment-intent"
    
    // PayPal Configuration
    const val PAYPAL_CLIENT_ID = "YOUR_PAYPAL_CLIENT_ID"
    const val PAYPAL_RETURN_URL = "ai.abbas.app://paypalpay"
    const val PAYPAL_BACKEND_URL = "https://your-backend-api.com/create-paypal-order"
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
