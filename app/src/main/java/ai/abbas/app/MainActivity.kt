package ai.abbas.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.abbas.app.ui.ChatScreen
import ai.abbas.app.ui.theme.GemmaAndroidAppTheme
import ai.abbas.app.viewmodel.ChatViewModel
import ai.abbas.app.viewmodel.ChatViewModelFactory
import ai.abbas.app.data.PaymentConfig
import com.stripe.android.PaymentConfiguration

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Stripe SDK
        PaymentConfiguration.init(applicationContext, PaymentConfig.STRIPE_PUBLISHABLE_KEY)
        
        enableEdgeToEdge()
        setContent {
            GemmaAndroidAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val chatViewModel: ChatViewModel = viewModel(
                        factory = ChatViewModelFactory(applicationContext)
                    )
                    ChatScreen(viewModel = chatViewModel)
                }
            }
        }
    }
}
