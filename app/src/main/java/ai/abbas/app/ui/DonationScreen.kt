package ai.abbas.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import ai.abbas.app.ui.theme.AbbasBlue
import ai.abbas.app.data.PaymentConfig
import ai.abbas.app.data.StripePaymentConfig
import ai.abbas.app.data.PayPalPaymentConfig
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import com.paypal.android.corepayments.CoreConfig
import com.paypal.android.corepayments.Environment
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutClient
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutListener
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutRequest
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutResult
import com.paypal.android.corepayments.PayPalSDKError
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonationScreen(
    onBack: () -> Unit,
    onStripeDonate: (Double, (Boolean, String) -> Unit) -> Unit,
    onPayPalDonate: (Double, (Boolean, String) -> Unit) -> Unit,
    stripePaymentEvent: SharedFlow<StripePaymentConfig>,
    payPalPaymentEvent: SharedFlow<PayPalPaymentConfig>
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    var selectedAmount by remember { mutableStateOf(10.0) }
    var customAmount by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

    // Stripe Integration
    val paymentSheet = rememberPaymentSheet { result ->
        isProcessing = false
        when (result) {
            is PaymentSheetResult.Canceled -> { message = "Stripe payment canceled" }
            is PaymentSheetResult.Failed -> { message = "Stripe payment failed: ${result.error.localizedMessage}" }
            is PaymentSheetResult.Completed -> {
                isSuccess = true
                message = "Thank you! Your donation via Stripe was successful."
            }
        }
    }

    // PayPal Integration
    val payPalClient: PayPalWebCheckoutClient = remember {
        val config = CoreConfig(PaymentConfig.PAYPAL_CLIENT_ID, environment = Environment.SANDBOX)
        PayPalWebCheckoutClient(activity, config, PaymentConfig.PAYPAL_RETURN_URL)
    }

    DisposableEffect(payPalClient) {
        payPalClient.listener = object : PayPalWebCheckoutListener {
            override fun onPayPalWebSuccess(result: PayPalWebCheckoutResult) {
                isProcessing = false
                isSuccess = true
                message = "Thank you! Your donation via PayPal was successful."
            }
            override fun onPayPalWebFailure(error: PayPalSDKError) {
                isProcessing = false
                message = "PayPal payment failed: ${error.errorDescription}"
            }
            override fun onPayPalWebCanceled() {
                isProcessing = false
                message = "PayPal payment canceled"
            }
        }
        onDispose { payPalClient.listener = null }
    }

    LaunchedEffect(stripePaymentEvent) {
        stripePaymentEvent.collect { config ->
            val configuration = PaymentSheet.Configuration(
                merchantDisplayName = "Abbas AI",
                customer = PaymentSheet.CustomerConfiguration(
                    id = config.customer,
                    ephemeralKeySecret = config.ephemeralKey
                ),
                googlePay = PaymentSheet.GooglePayConfiguration(
                    environment = PaymentSheet.GooglePayConfiguration.Environment.Test,
                    countryCode = "US"
                )
            )
            paymentSheet.presentWithPaymentIntent(config.paymentIntent, configuration)
        }
    }

    LaunchedEffect(payPalPaymentEvent) {
        payPalPaymentEvent.collect { config ->
            val request = PayPalWebCheckoutRequest(config.orderId)
            payPalClient.start(request)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Support Abbas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = AbbasBlue,
                modifier = Modifier.size(80.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                "Support the Development",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                "Select Amount",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(5.0, 10.0, 20.0).forEach { amount ->
                    val isSelected = selectedAmount == amount && customAmount.isEmpty()
                    OutlinedButton(
                        onClick = { 
                            selectedAmount = amount
                            customAmount = ""
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, if (isSelected) AbbasBlue else Color.Gray.copy(alpha = 0.3f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) AbbasBlue.copy(alpha = 0.1f) else Color.Transparent,
                            contentColor = if (isSelected) AbbasBlue else Color.Gray
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("\$$amount", fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = customAmount,
                onValueChange = { 
                    if (it.isEmpty() || it.toDoubleOrNull() != null) {
                        customAmount = it
                    }
                },
                label = { Text("Custom Amount ($)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AbbasBlue,
                    focusedLabelColor = AbbasBlue
                )
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            if (message != null) {
                Text(
                    text = message!!,
                    color = if (isSuccess) AbbasBlue else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            
            val finalAmount = customAmount.toDoubleOrNull() ?: selectedAmount

            // Stripe Button
            Button(
                onClick = {
                    isProcessing = true
                    message = "Initializing Stripe checkout..."
                    onStripeDonate(finalAmount) { success, msg ->
                        if (!success) { isProcessing = false; message = msg }
                    }
                },
                enabled = !isProcessing && finalAmount > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue),
                shape = RoundedCornerShape(28.dp)
            ) {
                if (isProcessing) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Donate with Card / Google Pay", fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            // PayPal Button
            Button(
                onClick = {
                    isProcessing = true
                    message = "Initializing PayPal checkout..."
                    onPayPalDonate(finalAmount) { success, msg ->
                        if (!success) { isProcessing = false; message = msg }
                    }
                },
                enabled = !isProcessing && finalAmount > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC439)), // PayPal Gold
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Donate with PayPal", color = Color(0xFF003087), fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "Secure payments processed by Stripe and PayPal",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray.copy(alpha = 0.6f)
            )
        }
    }
}
