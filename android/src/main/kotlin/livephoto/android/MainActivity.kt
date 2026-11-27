package livephoto.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme

/** UI only: selecting inputs and presenting structured Core results. */
class MainActivity : ComponentActivity() {
    internal lateinit var selections: DocumentReadLeases
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = AndroidDocumentReadPermissions(applicationContext)
        selections = DocumentReadLeases(permissions, savedInstanceState?.getBundle(DOCUMENT_SELECTIONS).documentSelections())
        permissions.discardOrphans(selections.snapshot().retainedReads)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LivePhotoScreen(selections, analyze = { input ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).analyze(input)
                }, extractVideo = { input ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).extractVideo(input)
                }, cleanSplit = { input ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).cleanSplit(input)
                }, saveSplitFile = { file ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).saveSplitFile(file)
                }, convertPhoto = { input, target ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).convertPhoto(input, target)
                }, repairPhoto = { input, review ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).repairPhoto(input, review)
                }, setPhotoKey = { input, requested ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core).setPhotoKey(input, requested)
                }) { image, video, target ->
                    AndroidCreateController(applicationContext, (application as LivePhotoApplication).core)
                        .create(image, video, target)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(DOCUMENT_SELECTIONS, selections.snapshot().toBundle())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) selections.close()
        super.onDestroy()
    }

    private companion object {
        const val DOCUMENT_SELECTIONS = "livephoto.document-selections"
    }
}
