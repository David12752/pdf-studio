package com.pdfsigner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfsigner.app.R

enum class AppStep(val labelRes: Int) {
    OPEN(R.string.step_open),
    SIGN(R.string.step_sign),
    PLACE(R.string.step_place),
    EXPORT(R.string.step_export)
}

/**
 * Horizontal step progress bar shown at the top of each signing-flow screen.
 * Completed steps are filled with primary color; the active step uses secondary (accent).
 */
@Composable
fun StepProgressIndicator(
    currentStep: AppStep,
    modifier: Modifier = Modifier
) {
    val steps = AppStep.entries
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            val ordinal    = step.ordinal
            val current    = currentStep.ordinal
            val isCompleted = ordinal < current
            val isActive    = ordinal == current

            val circleColor = when {
                isCompleted -> MaterialTheme.colorScheme.primary
                isActive    -> MaterialTheme.colorScheme.secondary
                else        -> MaterialTheme.colorScheme.outlineVariant
            }
            val labelColor = when {
                isCompleted || isActive -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            // Connector line before this step (except the first)
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (isCompleted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }

            // Circle + label
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(circleColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = if (isCompleted) "?" else "${ordinal + 1}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text  = stringResource(step.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
