package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.search.HttpWebSearch
import com.wangzan101.mika.settings.WebSearchConfig
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 高级与其他里的通用搜索设置;仍是原有设置层级中的一个对话框,不增加新页面。 */
@Composable
fun WebSearchDialog(initial: WebSearchConfig, onDismiss: () -> Unit, onSave: (WebSearchConfig) -> Unit) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    val valid = HttpWebSearch.parseEndpoint(endpoint) != null || (!enabled && endpoint.isBlank())
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_search_title)) },
        text = {
            Column {
                Text(stringResource(R.string.web_search_hint), style = MikaType.Caption, color = MikaTheme.colors.ink3)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.web_search_enabled), Modifier.weight(1f))
                    MikaSwitch(enabled, { enabled = it }, Modifier.testTag("web-search-enabled"))
                }
                OutlinedTextField(
                    endpoint, { endpoint = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("web-search-endpoint"),
                    singleLine = true, isError = !valid,
                    label = { Text(stringResource(R.string.web_search_endpoint)) }, placeholder = { Text("https://example.com/search") },
                )
                if (!valid) {
                    Text(stringResource(R.string.web_search_invalid_endpoint), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.bad)
                }
                OutlinedTextField(
                    apiKey, { apiKey = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("web-search-key"),
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.web_search_api_key)) },
                )
            }
        },
        confirmButton = {
            TextButton({ onSave(WebSearchConfig(enabled, endpoint, apiKey)) }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
