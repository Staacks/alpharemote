package org.staacks.alpharemote.ui.about

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import org.staacks.alpharemote.BuildConfig
import org.staacks.alpharemote.R
import org.staacks.alpharemote.ui.theme.BluetoothRemoteForSonyCamerasTheme
import org.staacks.alpharemote.ui.theme.FragmentMargin

class AboutFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // Assuming you have a MaterialTheme set up in your app
                BluetoothRemoteForSonyCamerasTheme {
                    AboutScreen(
                        onOpenUrl = { url -> openURL(url) }
                    )
                }
            }
        }
    }

    // openURL remains the same
    fun openURL(target: String) {
        startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
    }
}

@Composable
fun AboutScreen(onOpenUrl: (String) -> Unit) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing) // Handles system bars and cutouts
                .verticalScroll(rememberScrollState())
                .padding(FragmentMargin)
        ) {
            Text(
                text = stringResource(id = R.string.title_about),
                style = MaterialTheme.typography.displaySmall
            )

            // Author Section
            AboutSection(
                title = stringResource(id = R.string.about_author_title),
                text = stringResource(id = R.string.about_author_text)
            ) {
                ActionButtonsRow(
                    buttons = listOf(
                        ActionButtonInfo(
                            text = stringResource(id = R.string.about_blog_label),
                            iconResId = R.drawable.baseline_text_snippet_24,
                            url = stringResource(id = R.string.about_blog_url),
                            onClick = onOpenUrl
                        ),
                        ActionButtonInfo(
                            text = stringResource(id = R.string.about_youtube_label),
                            iconResId = R.drawable.yt_icon_mono_dark,
                            url = stringResource(id = R.string.about_youtube_url),
                            onClick = onOpenUrl
                        )
                    )
                )
            }

            // Support Me Section
            AboutSection(
                title = stringResource(id = R.string.about_support_title),
                text = stringResource(id = R.string.about_support_text)
            ) {
                ActionButtonsRow(
                    buttons = listOf(
                        ActionButtonInfo(
                            text = stringResource(id = R.string.about_coffee_label),
                            iconResId = R.drawable.buymeacoffee, // Replace with your actual drawable
                            url = stringResource(id = R.string.about_coffee_url),
                            onClick = onOpenUrl
                        )
                    )
                )
            }

            // Github Section
            AboutSection(
                title = stringResource(id = R.string.about_github_title),
                text = stringResource(id = R.string.about_github_text)
            ) {
                ActionButtonsRow(
                    buttons = listOf(
                        ActionButtonInfo(
                            text = stringResource(id = R.string.about_github_code_label),
                            iconResId = R.drawable.github_mark_white, // Replace with your actual drawable
                            url = stringResource(id = R.string.about_github_code_url),
                            onClick = onOpenUrl
                        ),
                        ActionButtonInfo(
                            text = stringResource(id = R.string.about_github_issues_label),
                            iconResId = R.drawable.baseline_bug_report_24, // Replace with your actual drawable
                            url = stringResource(id = R.string.about_github_issues_url),
                            onClick = onOpenUrl
                        )
                    )
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
            Text(
                text = stringResource(
                    id = R.string.about_version_info,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                ),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun AboutSection(title: String, text: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    content()
}

data class ActionButtonInfo(
    val text: String,
    val iconResId: Int, // Use Int for drawable resource IDs
    val url: String,
    val onClick: (String) -> Unit
)

@Composable
fun ActionButtonsRow(buttons: List<ActionButtonInfo>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.forEach { buttonInfo ->
            TextButton (
                onClick = { buttonInfo.onClick(buttonInfo.url) },
            ) {
                    Icon(
                        painter = painterResource(id = buttonInfo.iconResId),
                        contentDescription = null, // Decorative icon
                        modifier = Modifier.size(24.dp),
                        tint=MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(text = buttonInfo.text)
            }
        }
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun AboutScreenPreviewLight() {
    BluetoothRemoteForSonyCamerasTheme {
        AboutScreen(onOpenUrl = {})
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AboutScreenPreviewDark() {
    BluetoothRemoteForSonyCamerasTheme {
        AboutScreen(onOpenUrl = {})
    }
}