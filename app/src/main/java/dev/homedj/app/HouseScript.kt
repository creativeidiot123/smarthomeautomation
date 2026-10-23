package dev.homedj.app

/** What a watched light's wall switch did, as [BulbWatchPolicy] tells it. */
internal enum class HouseTrigger(val words: String) {
    TURNED_OFF("turned off"), TURNED_ON("turned on"), OFF_AND_ON("off and on"), ON_AND_OFF("on and off")
}

/** Minutes past Samsung's local midnight, [from] inclusive, [to] exclusive; wraps past midnight when from > to. */
internal data class HouseWindow(val from: Int, val to: Int) {
    fun contains(minute: Int) = if (from <= to) minute in from until to else minute >= from || minute < to
}

internal sealed interface HouseTest {
    data class At(val window: HouseWindow) : HouseTest
    /**
     * A light's brightness on HomeDJ's record is within [levels] (0 = off; `on` is 1..100): `was`
     * before the switch, `is` ([current]) when the rule runs. Unknown counts as off; on at an
     * unknown level counts as 100.
     */
    data class Level(val light: String, val levels: IntRange, val current: Boolean = false) : HouseTest
}

internal data class HouseCheck(val test: HouseTest, val negated: Boolean = false)

internal sealed interface HouseStep {
    /** [target] null is every light (minus the [except] lights and groups); otherwise a light or group name. */
    data class Light(val target: String?, val change: LightChange, val except: List<String> = emptyList()) : HouseStep
    /** `+N%`/`-N%`: each target light's recorded brightness moved by [delta], kept within 1..100; off lights only brighten. */
    data class Adjust(val target: String?, val delta: Int) : HouseStep
    /** On applies the scene; off turns its lights off. */
    data class Scene(val name: String, val on: Boolean) : HouseStep
    /** Pauses or resumes one light schedule, or every one when [name] is null. */
    data class Schedule(val name: String?, val on: Boolean) : HouseStep
    /** Each light goes where its latest light schedule of the last day left it. */
    data object SchedulesNow : HouseStep
    /** Off pauses; on resumes only the song a rule paused, if nobody changed it since. */
    data class Music(val on: Boolean) : HouseStep
    /** Every check must hold (`and`). */
    data class If(val checks: List<HouseCheck>, val then: List<HouseStep>, val otherwise: List<HouseStep>) : HouseStep
}

/**
 * `when <light> <trigger> [for|within <time>] [at <window>]` and its steps; [line] is 1-based.
 * [seconds]: how long it stayed off/on (`for`), or the longest quick off/on (`within`).
 */
internal data class HouseRule(val line: Int, val light: String, val trigger: HouseTrigger,
    val seconds: Int = 0, val window: HouseWindow? = null, val steps: List<HouseStep>)

internal data class HouseScriptError(val line: Int, val reason: String) {
    val message: String get() = if (line > 0) "Line $line: $reason" else reason
}

/** A name the script uses on [line]: a watched or `was` light, a light/group target, a scene or a schedule. */
internal data class HouseRef(val line: Int, val kind: Kind, val name: String) {
    enum class Kind { LIGHT, TARGET, SCENE, SCHEDULE }
}

/**
 * `<light> starts off|on|as before`: the bulb's own power-on behaviour (Matter StartUpOnOff) that
 * Samsung writes to it, so a light can stay dark when its wall switch gives it power. [value]: 0
 * off, 1 on, null as before. Not a step: it is in the bulb before any switch is flipped.
 */
internal data class HouseStart(val line: Int, val light: String, val value: Int?)

internal data class HouseParse(val rules: List<HouseRule>, val error: HouseScriptError? = null,
    val refs: List<HouseRef> = emptyList(), val starts: List<HouseStart> = emptyList())

/** Names a script may use; the runner and Samsung's save check resolve against the same lists. */
internal data class HouseNames(val lights: List<String>, val groups: List<String>, val scenes: List<String>,
    val schedules: List<String>)

/**
 * HomeDJ's house-rule language. One rule per `when` line, its steps below it (indent is only for
 * reading). `#` starts a comment; names with spaces go in quotes. Pure: Samsung's [HouseRules]
 * saves the text and the runner parses it again each time it uses it.
 */
internal object HouseScript {
    const val MAX_CHARS = 4_000
    const val MAX_RULES = 20
    const val MAX_WATCHED = 6
    /** Plain `turned off`: 8 missed pings in a row, so one dropped packet never counts as a switch. */
    const val DEFAULT_OFF = 2
    const val DEFAULT_QUICK = 60
    val HOLD = 2..12 * 3600
    val QUICK = 10..600
    private const val MAX_DEPTH = 4
    private val TIME = Regex("""(\d{1,2}):(\d{2})""")
    private val DURATION = Regex("""(\d{1,5})([smh])""")
    private val PERCENT = Regex("""(\d{1,3})%""")
    private val SIGNED = Regex("""([+-])(\d{1,3})%""")
    private val RANGE = Regex("""(\d{1,3})%-(\d{1,3})%""")
    val KEYWORDS = setOf("when", "if", "else", "end", "and", "not", "at", "for", "within", "was", "is", "above", "below", "all", "except", "starts",
        "music", "scene", "schedule", "schedules", "now", "on", "off", "turned")

    /** Whether [event] (from [BulbWatchPolicy]) is what [rule] waits for. */
    fun matches(rule: HouseRule, event: BulbEvent): Boolean = rule.trigger == event.trigger && when (rule.trigger) {
        HouseTrigger.TURNED_OFF, HouseTrigger.TURNED_ON -> event.millis == rule.seconds * 1000L
        HouseTrigger.OFF_AND_ON, HouseTrigger.ON_AND_OFF -> event.millis < rule.seconds * 1000L
    }

    /** A name as the script must spell it: quoted when it has spaces or is a rule word. */
    fun quote(name: String): String =
        if (name.any { it.isWhitespace() || it == '#' } || name.lowercase() in KEYWORDS) "\"$name\"" else name

    private class Word(val text: String, val quoted: Boolean) {
        fun isKey(key: String) = !quoted && text.equals(key, ignoreCase = true)
    }
    private class Line(val number: Int, val words: List<Word>)
    private class Fail(val line: Int, reason: String) : Exception(reason)

    fun parse(text: String): HouseParse {
        if (text.length > MAX_CHARS) return HouseParse(emptyList(), HouseScriptError(0, "Keep house rules under $MAX_CHARS characters."))
        return try {
            val (startLines, lines) = text.lines().mapIndexedNotNull { index, raw ->
                words(raw, index + 1).takeIf { it.isNotEmpty() }?.let { Line(index + 1, it) }
            }.partition { it.words.getOrNull(1)?.isKey("starts") == true }
            val rules = mutableListOf<HouseRule>()
            val refs = mutableListOf<HouseRef>()
            val starts = startLines.map { start(it) }
            if (starts.map { it.light.lowercase() }.distinct().size != starts.size) {
                throw Fail(starts.last().line, "Say how each light starts only once.")
            }
            starts.forEach { refs += HouseRef(it.line, HouseRef.Kind.LIGHT, it.light) }
            var at = 0
            while (at < lines.size) {
                val header = lines[at]
                if (!header.words[0].isKey("when")) {
                    throw Fail(header.number, if (header.words[0].isKey("end") || header.words[0].isKey("else"))
                        "`${header.words[0].text}` has no `if` above it." else "Start each rule with `when`.")
                }
                val rule = header(header, refs)
                val (steps, next) = block(lines, at + 1, 1, refs)
                if (steps.isEmpty()) throw Fail(header.number, "Add at least one step under this `when`.")
                rules += rule.copy(steps = steps)
                at = next
            }
            if (rules.size > MAX_RULES) throw Fail(rules[MAX_RULES].line, "Up to $MAX_RULES rules.")
            if (rules.map { it.light.lowercase() }.distinct().size > MAX_WATCHED) {
                throw Fail(0, "Rules can watch up to $MAX_WATCHED lights.")
            }
            HouseParse(rules, refs = refs, starts = starts)
        } catch (failure: Fail) {
            HouseParse(emptyList(), HouseScriptError(failure.line, failure.message.orEmpty()))
        }
    }

    /** The first name [parse] uses that [names] does not have, on the line it is used. */
    fun unknownName(parse: HouseParse, names: HouseNames): HouseScriptError? = parse.refs.firstNotNullOfOrNull { ref ->
        fun has(list: List<String>) = list.any { it.equals(ref.name, ignoreCase = true) }
        when (ref.kind) {
            HouseRef.Kind.LIGHT -> "No light called \"${ref.name}\".".takeUnless { has(names.lights) }
            HouseRef.Kind.TARGET -> "No light or group called \"${ref.name}\".".takeUnless { has(names.lights) || has(names.groups) }
            HouseRef.Kind.SCENE -> "No scene called \"${ref.name}\".".takeUnless { has(names.scenes) }
            HouseRef.Kind.SCHEDULE -> "No light schedule called \"${ref.name}\".".takeUnless { has(names.schedules) }
        }?.let { HouseScriptError(ref.line, it) }
    }

    /**
     * The steps [rule] takes now: `if`s resolved at [minute] with [level] (0..100 for a light,
     * `current` false = before the switch). All checks are decided before the first step runs.
     */
    fun steps(rule: HouseRule, minute: Int, level: (light: String, current: Boolean) -> Int): List<HouseStep> {
        fun holds(check: HouseCheck) = check.negated != when (val test = check.test) {
            is HouseTest.At -> test.window.contains(minute)
            is HouseTest.Level -> level(test.light, test.current) in test.levels
        }
        fun flatten(steps: List<HouseStep>): List<HouseStep> = steps.flatMap { step ->
            if (step is HouseStep.If) flatten(if (step.checks.all(::holds)) step.then else step.otherwise) else listOf(step)
        }
        return flatten(rule.steps)
    }

    /** Steps from [start] until a line this block does not own; returns them and that line's index. */
    private fun block(lines: List<Line>, start: Int, depth: Int, refs: MutableList<HouseRef>): Pair<List<HouseStep>, Int> {
        val steps = mutableListOf<HouseStep>()
        var at = start
        while (at < lines.size) {
            val line = lines[at]
            val first = line.words[0]
            if (first.isKey("when") || first.isKey("else") || first.isKey("end")) return steps to at
            if (!first.isKey("if")) {
                steps += step(line, refs)
                at++
                continue
            }
            if (depth >= MAX_DEPTH) throw Fail(line.number, "Too many `if`s inside each other.")
            val checks = checks(line, refs)
            val (then, elseAt) = block(lines, at + 1, depth + 1, refs)
            var end = elseAt
            var otherwise = emptyList<HouseStep>()
            if (end < lines.size && lines[end].words[0].isKey("else")) {
                if (lines[end].words.size > 1) throw Fail(lines[end].number, "Put the step under `else` on its own line.")
                val (elseSteps, after) = block(lines, end + 1, depth + 1, refs)
                otherwise = elseSteps
                end = after
            }
            if (end >= lines.size || !lines[end].words[0].isKey("end")) throw Fail(line.number, "This `if` needs an `end`.")
            if (lines[end].words.size > 1) throw Fail(lines[end].number, "Nothing goes after `end`.")
            if (then.isEmpty() && otherwise.isEmpty()) throw Fail(line.number, "Add a step under this `if`.")
            steps += HouseStep.If(checks, then, otherwise)
            at = end + 1
        }
        return steps to at
    }

    /** The rule without its steps, which the caller parses next. */
    private fun header(line: Line, refs: MutableList<HouseRef>): HouseRule {
        val words = line.words
        val light = words.getOrNull(1)?.takeUnless(::keyword)?.text
            ?: throw Fail(line.number, "Name the light to watch, like: when bedside off and on")
        refs += HouseRef(line.number, HouseRef.Kind.LIGHT, light)
        fun key(offset: Int, key: String) = words.getOrNull(2 + offset)?.isKey(key) == true
        val (trigger, used) = when {
            key(0, "turned") && key(1, "off") -> HouseTrigger.TURNED_OFF to 2
            key(0, "turned") && key(1, "on") -> HouseTrigger.TURNED_ON to 2
            key(0, "off") && key(1, "and") && key(2, "on") -> HouseTrigger.OFF_AND_ON to 3
            key(0, "on") && key(1, "and") && key(2, "off") -> HouseTrigger.ON_AND_OFF to 3
            else -> throw Fail(line.number, "After the light write `turned off`, `turned on`, `off and on` or `on and off`.")
        }
        var at = 2 + used
        val quick = trigger == HouseTrigger.OFF_AND_ON || trigger == HouseTrigger.ON_AND_OFF
        var seconds = when (trigger) {
            HouseTrigger.TURNED_OFF -> DEFAULT_OFF
            HouseTrigger.TURNED_ON -> 0
            else -> DEFAULT_QUICK
        }
        val lengthWord = if (quick) "within" else "for"
        if (words.getOrNull(at)?.isKey(lengthWord) == true) {
            seconds = duration(line.number, words.getOrNull(at + 1), if (quick) QUICK else HOLD)
            at += 2
        } else if (words.getOrNull(at)?.isKey(if (quick) "for" else "within") == true) {
            throw Fail(line.number, if (quick) "Quick switches use `within`, like `within 1m`." else "Use `for`, like `for 2m`.")
        }
        var window: HouseWindow? = null
        if (words.getOrNull(at)?.isKey("at") == true) {
            window = window(line.number, words.drop(at + 1))
            at = words.size
        }
        if (at < words.size) throw Fail(line.number, "Didn't understand \"${words[at].text}\".")
        return HouseRule(line.number, light, trigger, seconds, window, emptyList())
    }

    private fun checks(line: Line, refs: MutableList<HouseRef>): List<HouseCheck> {
        val parts = mutableListOf(mutableListOf<Word>())
        line.words.drop(1).forEach { if (it.isKey("and")) parts += mutableListOf<Word>() else parts.last() += it }
        return parts.map { part ->
            val negated = part.firstOrNull()?.isKey("not") == true
            val words = if (negated) part.drop(1) else part
            val verb = words.getOrNull(1)?.takeIf { it.isKey("was") || it.isKey("is") }
            val levels = if (words.size in 3..4 && !keyword(words[0]) && verb != null) levels(words.drop(2)) else null
            val test = when {
                words.firstOrNull()?.isKey("at") == true -> HouseTest.At(window(line.number, words.drop(1)))
                levels != null && verb != null -> HouseTest.Level(words[0].text, levels, current = verb.isKey("is"))
                    .also { refs += HouseRef(line.number, HouseRef.Kind.LIGHT, it.light) }
                else -> throw Fail(line.number, "Check with `at 00:00-07:00` or a light's brightness: `<light> was` " +
                    "(before the switch) or `is` (now), then `on`, `off`, `0%`, `above 5%`, `below 50%` or `20%-60%`.")
            }
            HouseCheck(test, negated)
        }
    }

    private fun start(line: Line): HouseStart {
        val words = line.words
        val light = words[0].takeUnless(::keyword)?.text
            ?: throw Fail(line.number, "Name the light, like: top starts off")
        val value = when {
            words.size == 3 && words[2].isKey("off") -> 0
            words.size == 3 && words[2].isKey("on") -> 1
            words.size == 4 && words[2].isKey("as") && words[3].isKey("before") -> null
            else -> throw Fail(line.number, "Write `$light starts off`, `starts on` or `starts as before`.")
        }
        return HouseStart(line.number, light, value)
    }

    private fun step(line: Line, refs: MutableList<HouseRef>): HouseStep {
        val words = line.words
        val first = words[0]
        val last = words.last()
        val onOff = last.isKey("on") || last.isKey("off")
        fun named(): String = words.getOrNull(1)?.takeIf { words.size == 3 && !keyword(it) }?.text
            ?: throw Fail(line.number, "Write `${first.text.lowercase()} <name> on` or `off`.")
        return when {
            first.isKey("music") && words.size == 2 && onOff -> HouseStep.Music(last.isKey("on"))
            first.isKey("schedules") && words.size == 2 && last.isKey("now") -> HouseStep.SchedulesNow
            first.isKey("schedules") && words.size == 2 && onOff -> HouseStep.Schedule(null, last.isKey("on"))
            first.isKey("scene") && onOff -> HouseStep.Scene(named(), last.isKey("on"))
                .also { refs += HouseRef(line.number, HouseRef.Kind.SCENE, it.name) }
            first.isKey("schedule") && onOff -> named().let { name ->
                refs += HouseRef(line.number, HouseRef.Kind.SCHEDULE, name)
                HouseStep.Schedule(name, last.isKey("on"))
            }
            first.isKey("music") || first.isKey("schedules") || first.isKey("scene") || first.isKey("schedule") ->
                throw Fail(line.number, "Write `music on`/`off`, `scene <name> on`/`off`, `schedule <name> on`/`off`, " +
                    "`schedules on`/`off` or `schedules now`.")
            first.isKey("all") && words.size >= 4 && words[2].isKey("except") -> {
                val except = words.drop(3).map { word ->
                    word.takeUnless(::keyword)?.text ?: throw Fail(line.number, "After `except` name lights or groups.")
                }
                except.forEach { refs += HouseRef(line.number, HouseRef.Kind.TARGET, it) }
                HouseStep.Light(null, change(line, words[1]), except)
            }
            words.size != 2 -> throw Fail(line.number, "A light step is two words, like `all off`, `bedside 5%` " +
                "or `all on except top`. Quote names with spaces.")
            else -> {
                val name = if (first.isKey("all")) null else first.takeUnless(::keyword)?.text
                    ?: throw Fail(line.number, "\"${first.text}\" is a rule word. Put a light called that in quotes.")
                val delta = SIGNED.matchEntire(last.text)?.takeUnless { last.quoted }?.groupValues
                    ?.let { (it[2].toInt() * if (it[1] == "-") -1 else 1) }
                if (delta != null) {
                    if (delta == 0 || delta !in -100..100) throw Fail(line.number, "Change brightness by 1% to 100%, like `+10%` or `-20%`.")
                    name?.let { refs += HouseRef(line.number, HouseRef.Kind.TARGET, it) }
                    return HouseStep.Adjust(name, delta)
                }
                val change = change(line, last)
                name?.let { refs += HouseRef(line.number, HouseRef.Kind.TARGET, it) }
                HouseStep.Light(name, change)
            }
        }
    }

    private fun change(line: Line, value: Word): LightChange {
        val percent = PERCENT.matchEntire(value.text)?.groupValues?.get(1)?.toInt()
        return when {
            value.isKey("on") -> LightChange(on = true)
            value.isKey("off") -> LightChange(on = false)
            percent == 0 && !value.quoted -> LightChange(on = false)
            percent != null && percent in 1..100 && !value.quoted -> LightChange(on = true, level = percent)
            else -> throw Fail(line.number, "Set a light `on`, `off`, a brightness from 0% (off) to 100%, " +
                "or change it with `+10%` / `-10%`.")
        }
    }

    /** `on`, `off`, `N%`, `A%-B%`, `above N%`, `below N%` as brightness ranges; null when it is none of them. */
    private fun levels(words: List<Word>): IntRange? {
        fun percent(word: Word) = word.takeUnless { it.quoted }?.let { PERCENT.matchEntire(it.text) }
            ?.groupValues?.get(1)?.toInt()?.takeIf { it in 0..100 }
        val one = words.singleOrNull()
        return when {
            one?.isKey("on") == true -> 1..100
            one?.isKey("off") == true -> 0..0
            one != null -> percent(one)?.let { it..it } ?: one.takeUnless { it.quoted }?.let { RANGE.matchEntire(it.text) }
                ?.groupValues?.let { it[1].toInt()..it[2].toInt() }?.takeIf { !it.isEmpty() && it.last <= 100 }
            words[0].isKey("above") -> percent(words[1])?.takeIf { it < 100 }?.let { it + 1..100 }
            words[0].isKey("below") -> percent(words[1])?.takeIf { it > 0 }?.let { 0 until it }
            else -> null
        }
    }

    private fun duration(line: Int, word: Word?, range: IntRange): Int {
        val match = word?.takeUnless { it.quoted }?.let { DURATION.matchEntire(it.text.lowercase()) }
            ?: throw Fail(line, "Write how long like `30s`, `5m` or `2h`.")
        val seconds = match.groupValues[1].toLong() * when (match.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 }
        if (seconds !in range.first..range.last) throw Fail(line, "Use between ${length(range.first)} and ${length(range.last)}.")
        return seconds.toInt()
    }

    private fun length(seconds: Int) = when {
        seconds % 3600 == 0 -> "${seconds / 3600}h"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }

    /** `00:00-07:00`, also with spaces around the dash. */
    private fun window(line: Int, words: List<Word>): HouseWindow {
        val parts = words.joinToString("") { it.text }.split('-')
        val minutes = parts.mapNotNull { part ->
            TIME.matchEntire(part)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() }
                ?.takeIf { (h, min) -> h in 0..24 && min in 0..59 && (h < 24 || min == 0) }
                ?.let { (h, min) -> (h * 60 + min) % 1440 }
        }
        if (words.any { it.quoted } || parts.size != 2 || minutes.size != 2) {
            throw Fail(line, "Write times like `at 00:00-07:00` (24-hour).")
        }
        if (minutes[0] == minutes[1]) throw Fail(line, "Start and end times must differ.")
        return HouseWindow(minutes[0], minutes[1])
    }

    private fun keyword(word: Word) = !word.quoted && word.text.lowercase() in KEYWORDS

    /** Splits a line into words; quotes keep spaces, `#` outside quotes ends the line. */
    private fun words(raw: String, line: Int): List<Word> {
        val words = mutableListOf<Word>()
        var at = 0
        while (at < raw.length) {
            val c = raw[at]
            when {
                c == '#' -> return words
                c.isWhitespace() -> at++
                c == '"' -> {
                    val close = raw.indexOf('"', at + 1)
                    if (close < 0) throw Fail(line, "A quote is not closed.")
                    val name = raw.substring(at + 1, close).trim()
                    if (name.isEmpty()) throw Fail(line, "Empty quotes.")
                    words += Word(name, quoted = true)
                    at = close + 1
                }
                else -> {
                    val end = (at until raw.length).firstOrNull { raw[it].isWhitespace() || raw[it] == '#' || raw[it] == '"' } ?: raw.length
                    words += Word(raw.substring(at, end), quoted = false)
                    at = end
                }
            }
        }
        return words
    }
}
