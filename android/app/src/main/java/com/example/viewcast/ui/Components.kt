package com.example.viewcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Carte arrondie (conteneur de base des maquettes). */
@Composable
fun VcCard(
    modifier: Modifier = Modifier,
    color: Color = Vc.SurfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun Pill(text: String, icon: ImageVector? = null, tint: Color = Vc.Tertiary, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 13.sp, lineHeight = 18.sp, color = Vc.OnSurfaceVariant)
}

/** Bouton principal dégradé corail → ambre. */
@Composable
fun GradientButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    val brush = if (danger) Brush.horizontalGradient(listOf(Vc.ErrorContainer, Color(0xFFB3261E)))
    else Brush.horizontalGradient(listOf(Vc.PrimaryContainer, Vc.Secondary))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) brush else Brush.horizontalGradient(listOf(Vc.SurfaceHigh, Vc.SurfaceHigh)))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) Color.White else Vc.OnPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = if (danger) Color.White else Vc.OnPrimary
        )
    }
}

/** Bouton secondaire (contour discret). */
@Composable
fun SoftButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Vc.SurfaceBright)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) Vc.Primary else Vc.Outline, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Vc.OnSurface else Vc.Outline)
    }
}

/** Choix segmenté (résolution, fréquence d'images, langue...). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Vc.SurfaceLow)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Vc.PrimaryContainer else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Vc.OnPrimary else if (enabled) Vc.OnSurface else Vc.Outline
                )
            }
        }
    }
}

@Composable
fun ToggleRow(
    icon: ImageVector, title: String, description: String,
    checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Vc.OnSurface)
            Muted(description)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Vc.PrimaryContainer, checkedThumbColor = Vc.OnPrimary
            )
        )
    }
}

/** Tuile de mesure (débit, définition, audio). */
@Composable
fun StatTile(icon: ImageVector, label: String, value: String, unit: String = "", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Vc.SurfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Vc.OnSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = Vc.Primary, modifier = Modifier.size(16.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Vc.OnSurface, fontFamily = FontFamily.Monospace)
        }
        if (unit.isNotEmpty()) Text(unit, fontSize = 11.sp, color = Vc.OnSurfaceVariant)
    }
}

@Composable
fun StatusDot(color: Color = Vc.Tertiary) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
