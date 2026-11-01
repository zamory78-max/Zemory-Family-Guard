package com.familyguard.child.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF1F6F5C)

@Composable
fun GuardTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF6FCFB5), secondary = Color(0xFF6FCFB5))
    } else {
        lightColorScheme(primary = Green, secondary = Green)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}

/** Numeric PIN pad usable by touch and by a TV remote (D-pad). */
@Composable
fun PinPad(error: String?, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (pin.isEmpty()) "أدخل رمز الوالد" else "●".repeat(pin.length),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in row) {
                            val mod = Modifier.size(width = 76.dp, height = 56.dp)
                            when (k) {
                                "✓" -> Button(onClick = {
                                    val p = pin
                                    pin = ""
                                    onSubmit(p)
                                }, modifier = mod) { Text(k, fontSize = 22.sp) }
                                "⌫" -> FilledTonalButton(onClick = { pin = pin.dropLast(1) }, modifier = mod) {
                                    Text(k, fontSize = 22.sp)
                                }
                                else -> FilledTonalButton(onClick = { if (pin.length < 8) pin += k }, modifier = mod) {
                                    Text(k, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
