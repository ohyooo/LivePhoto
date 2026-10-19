package livephoto.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme

/** UI only: selecting inputs and presenting structured Core results. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LivePhotoScreen(analyze = { input ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).analyze(input)
                }) { image, video, target ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core)
                        .create(image, video, target)
                }
            }
        }
    }
}
