package dev.homedj.app

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The live dashboard at the top of Home. [build] maps the owners' current state to rows at the
 * given `elapsedRealtime` and wall-clock times; it is called again every second so countdowns move.
 */
@Composable
internal fun LiveDashboard(build: (now: Long, wallNow: Long) -> DashboardUi, modifier: Modifier = Modifier) {
    val clock by produceState(SystemClock.elapsedRealtime() to System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime() to System.currentTimeMillis()
        }
    }
    val ui = build(clock.first, clock.second)
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Live", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceTile("Samsung", ui.samsung, Modifier.weight(1f))
                DeviceTile("OnePlus", ui.onePlus, Modifier.weight(1f))
            }
            StatusLine(R.drawable.ic_music_note, "Now playing", ui.song)
            StatusLine(R.drawable.ic_schedule, "Music break", ui.breaks)
            StatusLine(R.drawable.ic_home, "OnePlus at home", ui.home)
        }
    }
}

@Composable
private fun DeviceTile(name: String, row: DashboardRow, modifier: Modifier = Modifier) {
    Surface(
        modifier.semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(20.dp),
        color = if (row.good == true) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(row.good)
                Text(name, style = MaterialTheme.typography.labelLarge)
            }
            Text(row.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(row.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(@DrawableRes icon: Int, label: String, row: DashboardRow) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.good != null) Dot(row.good)
                Text(row.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (row.detail.isNotBlank()) {
                Text(row.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Primary for online/home, error for offline, outline when neither is known. */
@Composable
private fun Dot(good: Boolean?) {
    val color = when (good) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}
