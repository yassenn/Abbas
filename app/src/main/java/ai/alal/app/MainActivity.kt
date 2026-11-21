package ai.alal.app

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.alal.app.ui.ChatScreen
import ai.alal.app.ui.theme.GemmaAndroidAppTheme
import ai.alal.app.viewmodel.ChatViewModel
import ai.alal.app.viewmodel.ChatViewModelFactory
import ai.alal.app.data.PaymentConfig
import com.stripe.android.PaymentConfiguration

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep chat content and knowledge snippets out of screenshots and the
        // Recents thumbnail (L-02).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

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
