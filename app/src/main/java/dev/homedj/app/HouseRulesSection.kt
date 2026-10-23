package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

internal class HouseRulesActions(val refresh: () -> Unit, val save: (String) -> Unit)

/** The house-rule editor on both phones; the draft stays here until Save and Samsung checks it again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HouseRulesSection(state: HouseRulesUiState, names: HouseNames, actions: HouseRulesActions) {
    LaunchedEffect(state.mode) { actions.refresh() }
    var draft by rememberSaveable(state.loaded, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.script)) }
    var help by rememberSaveable { mutableStateOf(false) }
    val problem = remember(draft.text, names) {
        val parse = HouseScript.parse(draft.text)
        parse.error ?: HouseScript.unknownName(parse, names)
    }
    fun insert(text: String) {
        val before = draft.text.substring(0, draft.selection.min)
        val after = draft.text.substring(draft.selection.max)
        val piece = (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + text +
            (if (after.firstOrNull()?.isWhitespace() == false) " " else "")
        draft = TextFieldValue(before + piece + after, TextRange(before.length + piece.length))
    }

    Text("House rules", style = MaterialTheme.typography.headlineSmall)
    Text("Use the wall switches of your lights as buttons. The Samsung checks the lights your rules watch " +
        "every 6 seconds while Remote control is on, so a switch takes about 5–15 seconds to act.",
        style = MaterialTheme.typography.bodyMedium)
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.message?.let {
        Text(it, color = if (state.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!state.loaded) {
        if (!state.busy) TextButton(onClick = actions.refresh) { Text("Try again") }
        return
    }
    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), enabled = !state.busy, minLines = 10,
        label = { Text("Rules") }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        isError = problem != null && draft.text.isNotBlank(),
        supportingText = { Text(problem?.message ?: if (draft.text.isBlank()) "No rules yet." else "Looks good.") })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.save(draft.text) },
            enabled = !state.busy && problem == null && draft.text.trimEnd() != state.script) { Text("Save") }
        TextButton(onClick = { draft = TextFieldValue(state.script) }, enabled = !state.busy && draft.text != state.script) {
            Text("Undo edits")
        }
        TextButton(onClick = { draft = TextFieldValue(draft.text.trimEnd().let { if (it.isEmpty()) EXAMPLE else "$it\n\n$EXAMPLE" }) },
            enabled = !state.busy) { Text("Add example") }
        TextButton(onClick = { help = !help }) { Text(if (help) "Hide guide" else "Guide") }
    }
    if (help) Card(Modifier.fillMaxWidth()) {
        Text(GUIDE, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
    Text("Tap to add at the cursor", style = MaterialTheme.typography.titleMedium)
    Chips("Switch", SWITCH_WORDS, state.busy, ::insert)
    Chips("Steps", STEP_WORDS, state.busy, ::insert)
    Chips("Lights", names.lights.map(HouseScript::quote), state.busy, ::insert)
    Chips("Groups", names.groups.map(HouseScript::quote), state.busy, ::insert)
    Chips("Scenes", names.scenes.map { "scene ${HouseScript.quote(it)}" }, state.busy, ::insert)
    Chips("Light schedules", names.schedules.map { "schedule ${HouseScript.quote(it)}" }, state.busy, ::insert)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(title: String, words: List<String>, busy: Boolean, onInsert: (String) -> Unit) {
    if (words.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            words.forEach { word -> SuggestionChip(onClick = { onInsert(word) }, label = { Text(word) }, enabled = !busy) }
        }
    }
}

private val SWITCH_WORDS = listOf("when", "turned off", "turned off for 1m", "turned on", "turned on for 5m", "off and on",
    "on and off", "within 1m", "at 00:00-07:00", "if", "was 0%", "was above 5%", "was below 50%", "was 20%-60%", "is above 50%", "is 0%",
    "and", "not", "else", "end")
private val STEP_WORDS = listOf("all 0%", "all 5%", "all on", "except", "starts off", "starts as before", "on", "off", "50%", "+10%", "-10%", "music off", "music on",
    "schedules now", "schedules on", "schedules off")

private val EXAMPLE = """
    # Bedside, at night: switch off and on at the plug
    when bedside off and on at 23:00-07:00
      if bedside was above 5%
        all 0%          # sleep time
      end
      if bedside was 0%
        all 5%          # washroom
      end

    # Top light by the door: its switch says leaving / home
    top starts off      # stays dark when its switch is turned on
    when top turned off
      all 0%
      music off
    when top turned on
      all on except top
      music on
""".trimIndent()

private val GUIDE = """
    when <light> turned off           off for 2s (almost at once)
    when <light> turned off for 1m    off that long
    when <light> turned on            power came back
    when <light> turned on for 5m     back and still on 5 minutes
    when <light> off and on           off, then on within 1m
    when <light> on and off within 2m on, then off within 2m
    ... at 00:00-07:00                only at these times

    Steps run top to bottom, one per line:
      all 0% | all on | all 30%       0% is off
      all on except top bedside       all but these lights or groups
      <light or group> 0% | on | 30%
      <light or group or all> +10% | -20%   brighter / dimmer
      scene <name> on | off
      schedule <name> on | off
      schedules on | off | now        now: where schedules put lights
      music off                       pauses
      music on                        resumes what a rule paused

    if <light> was 0% | was above 5% | was below 50% | was 20%-60%
       [and <light> is above 50%] [and at 00:00-07:00] [and not ...]
      steps
    else
      steps
    end

    <light> starts off | on | as before
      what the bulb does when its switch gives it power (set in
      the bulb by the Samsung; "off" keeps it dark at the switch)

    # starts a comment. Put names with spaces in "quotes".
    "was" is the brightness HomeDJ last set, before the switch;
    "is" is the brightness when the rule starts (on = 1%-100%).
    Every quick off and on also counts as turned on. A short
    Wi-Fi drop is ignored; a long one looks like turned off.
    "schedules on" turns on every schedule, also ones you paused.
""".trimIndent()
