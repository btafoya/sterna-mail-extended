package app.sterna.core.data.filter

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

sealed interface SieveParse {
    data class Readable(val script: SieveScript) : SieveParse

    /** The one unreadable case: [opener] at [offset] is never closed, so the script cannot be cut. */
    data class Unreadable(val opener: String, val offset: Int) : SieveParse
}

/** One rule of a script as the filters screen lists it. [id] is valid for the script it came from and its successors. */
sealed interface ScriptRule {
    val id: Int
    val name: String

    /**
     * [handCondition]: the block also tests something the form cannot show, kept as written. [bulwark]:
     * Bulwark regenerates it from its JSON, so it holds only what [BulwarkFormat.holds] says.
     */
    data class Form(
        override val id: Int,
        val rule: FilterRule,
        val handCondition: Boolean = false,
        val bulwark: Boolean = false,
    ) : ScriptRule {
        override val name: String get() = rule.name
    }

    /** A block the form cannot show, edited as Sieve. A [lockedByBulwark] one can only be deleted. */
    data class Text(
        override val id: Int,
        override val name: String,
        val text: String,
        val lockedByBulwark: Boolean,
    ) : ScriptRule
}

enum class BulwarkMetadata { ABSENT, READABLE, UNREADABLE }

internal data class BulwarkEntry(val id: Int, val json: JsonObject, val blockId: Int?)

private data class LineAt(val segment: Int, val line: Int)

private class Responder(val marker: LineAt, val blockId: Int?)

/**
 * A Sieve script as ordered segments. What no operation touched is emitted byte for byte: [emit] of a
 * parsed script is its source, and an edit rewrites only the segments it names, plus the `require`.
 */
class SieveScript internal constructor(
    internal val segments: List<Segment>,
    val lineEnding: String,
    val bulwarkMetadata: BulwarkMetadata,
    internal val entries: List<BulwarkEntry>,
    private val v1Pending: Boolean,
    private val v1Unnamed: Set<Int>,
    private val nextId: Int,
) {
    fun emit(): String = segments.joinToString("") { it.text }

    /** Bulwark's rules first, in the order of its JSON, then every other block in script order. */
    val rules: List<ScriptRule>
        get() {
            val owned = entries.mapNotNullTo(HashSet()) { it.blockId }
            val responder = responderOf(segments)?.blockId
            return buildList {
                for (entry in entries) {
                    val simple = BulwarkFormat.simpleRule(entry.json)
                    add(
                        simple?.let { ScriptRule.Form(entry.id, it, bulwark = true) } ?: ScriptRule.Text(
                            id = entry.id,
                            name = BulwarkFormat.name(entry.json),
                            text = entry.blockId?.let { b -> commandOrNull(b)?.let { source(it) } }.orEmpty(),
                            lockedByBulwark = true,
                        ),
                    )
                }
                for (seg in segments) {
                    when (seg) {
                        is Unwritten -> add(ScriptRule.Form(seg.id, seg.rule))
                        is Command -> if (seg.id !in owned && seg.id != responder && seg.keyword != "require") {
                            add(
                                seg.form?.let { ScriptRule.Form(seg.id, it.rule, handCondition = it.guard != null) }
                                    ?: ScriptRule.Text(seg.id, ruleName(seg.header), source(seg), false),
                            )
                        }
                        is Gap -> Unit
                    }
                }
            }
        }

    val responder: VacationMarker get() = SieveCodec.readVacation(emit())

    /** The rule [id] rewritten to [rule]. What did not change keeps its text: a rename touches only the comment. */
    fun update(id: Int, edited: FilterRule): SieveScript {
        val rule = asWritten(edited)
        val s = migrated()
        s.entryIndex(id)?.let { return s.bulwarkUpdated(it, rule) }
        val i = s.indexOf(id)
        val c = s.segments[i] as Command
        val old = requireNotNull(c.form) { "rule $id is Sieve text" }
        // `conditionTest` uses an `address` or `header` only where it applies.
        val pairs = pairedConditions(old.rule.conditions, rule.conditions)
        val words = rule.conditions.mapIndexed { k, cond ->
            pairs[k]?.let { old.words.getOrNull(it) }?.takeIf { it == "address" || it == "header" } ?: defaultWord(cond.field)
        }
        val order = actionOrder(c.statement)
        val kept = keptStop(rule, order)
        val sameBlock = old.rule.copy(name = "") == kept.copy(name = "")
        val statement = if (sameBlock) c.statement else formStatement(kept, words, old.guard, c.indent, lineEnding, order)
        val header = if (old.rule.name == rule.name) c.header else renamedHeader(c.header, rule.name, c.indent, lineEnding)
        val updated = c.copy(header = header, statement = statement, form = FormBlock(kept, words, old.guard))
        return s.copy(segments = s.segments.replaced(i, updated))
            .requiring(if (sameBlock) emptySet() else capabilitiesOf(kept))
    }

    /** [rule] added after the last block, or as a Bulwark entry when the script's Bulwark JSON is readable. */
    fun add(added: FilterRule, newBulwarkId: () -> String = ::randomBulwarkId): SieveScript {
        val rule = asWritten(added)
        val s = migrated()
        if (bulwarkMetadata == BulwarkMetadata.READABLE) return s.bulwarkAdded(rule, newBulwarkId())
        val command = newFormCommand(s.nextId, rule)
        val responder = responderOf(s.segments)?.blockId
        val lastBlock = s.segments.indexOfLast { it is Command && it.id != responder }
        val responderAt = s.segments.indexOfFirst { it is Command && it.id == responder }
        val (wanted, blankAfter) = when {
            lastBlock >= 0 -> lastBlock + 1 to false
            responderAt >= 0 -> responderAt to true
            else -> s.segments.size to false
        }
        val at = outsideNextcloud(s.segments, wanted)
        val segs = s.segments.withBlock(at, command, blankAfter && at == wanted)
        return s.copy(segments = segs, nextId = s.nextId + 1).requiring(capabilitiesOf(rule))
    }

    fun remove(id: Int): SieveScript {
        val s = migrated()
        s.entryIndex(id)?.let { return s.bulwarkRemoved(it) }
        return s.copy(segments = s.segments.withoutSegment(s.indexOf(id)))
    }

    /** The block [id] replaced by [text], or null when [text] cannot be cut; blank [text] removes the block. */
    fun replaceText(id: Int, text: String): SieveScript? {
        val s = migrated()
        require(s.entryIndex(id) == null) { "rule $id is managed by Bulwark" }
        val i = s.indexOf(id)
        if (text.isBlank()) return s.copy(segments = s.segments.withoutSegment(i))
        val c = s.segments[i] as Command
        val source = if (text.endsWith("\n")) text else text + c.trailer.substring(c.trailer.trimEnd('\r', '\n').length)
        val parts = try {
            SieveScanner(source).split(firstId = s.nextId)
        } catch (_: Undelimited) {
            return null
        }
        val single = parts.count { it is Command } == 1
        val placed = classify(parts.map { if (single && it is Command) it.copy(id = id) else it })
        return s.copy(segments = s.segments.take(i) + placed + s.segments.drop(i + 1), nextId = s.nextId + parts.size)
    }

    /**
     * The rule [id] placed just before the rule [before] of [rules], or after the last one when null;
     * null when that place is refused. A block moves with the comments glued above it, its name among
     * them; a marker glued there stays, with what is above it. Refused: a Bulwark rule among the
     * others or the reverse, a place inside a Nextcloud region or a block out of one, a place above
     * the first `require`, and a move across Bulwark's separator, the auto-reply's marker or its block.
     */
    fun move(id: Int, before: Int?): SieveScript? {
        if (id == before) return null
        val s = migrated()
        val order = s.rules.map { it.id }
        if (id !in order || (before != null && before !in order)) return null
        val rest = order - id
        val at = before?.let(rest::indexOf) ?: rest.size
        val landed = rest.take(at) + id + rest.drop(at)
        if (landed == order) return s
        if (s.entryIndex(id) != null) return s.bulwarkMoved(landed)
        if (before != null && s.entryIndex(before) != null) return null
        return s.blockMoved(id, before)
    }

    /** Bulwark's blocks keep their places in the script and take the order of [landed]'s entries. */
    private fun bulwarkMoved(landed: List<Int>): SieveScript? {
        val byId = entries.associateBy { it.id }
        val moved = landed.take(entries.size).map { byId[it] ?: return null }
        val slots = entries.mapNotNull { it.blockId }.map(::indexOf)
        val blocks = moved.mapNotNull { it.blockId }.map { (segments[indexOf(it)] as Command).cut().second }
        var segs = segments
        slots.forEachIndexed { k, at ->
            val kept = (segments[at] as Command).cut().first
            val block = blocks[k].copy(header = kept + blocks[k].header)
            segs = segs.replaced(at, if (at == segments.lastIndex) block else ended(block))
        }
        return copy(segments = withMetadata(segs, moved), entries = moved)
    }

    private fun blockMoved(id: Int, before: Int?): SieveScript? {
        val target = before ?: rules.lastOrNull { it.id != id && entryIndex(it.id) == null }?.id ?: return null
        var segs = segments.opened(id)
        val whole = segs.size
        if (before != null) segs = segs.opened(before)
        fun at(block: Int) = segs.indexOfFirst { it is Command && it.id == block }
        val from = at(id)
        // Below the markers glued above [before] first, as its name is; above them when that place
        // is refused, as before a Nextcloud region whose opening marker [before] carries.
        val to = (
            if (before == null) {
                (at(target) + 1).takeIf { movable(segs, from, it) }
            } else {
                val under = at(before)
                when {
                    movable(segs, from, under) -> under
                    segs.size > whole && movable(segs, from, under - 1) -> under - 1
                    else -> null
                }
            }
            ) ?: return null
        fun blank(i: Int) = (segs.getOrNull(i) as? Gap)?.text?.isBlank() == true
        val gapAt = when {
            blank(from - 1) -> from - 1
            blank(from + 1) -> from + 1
            else -> null
        }
        val block = ended(segs[from] as Command)
        val gap = gapAt?.let { segs[it] }
        val taken = setOfNotNull(from, gapAt)
        val left = segs.filterIndexed { i, _ -> i !in taken }
        val into = to - taken.count { it < to }
        // Between two rules the blank line follows the block, after the last one it precedes it.
        val unit = if (before != null) listOfNotNull(block, gap) else listOfNotNull(gap, block)
        val head = left.take(into)
        val lead = head.joinToString("") { it.text }.let { if (it.isNotEmpty() && !it.endsWith("\n")) listOf(Gap(lineEnding)) else emptyList() }
        return copy(segments = head + lead + unit + left.drop(into))
    }

    /** Whether the block at [from] may be inserted before the segment [to] of [segs]. */
    private fun movable(segs: List<Segment>, from: Int, to: Int): Boolean {
        fun inNextcloud(n: Int) = segs.take(n).sumOf { seg -> linesIn(seg).count(::isNextcloudMarker) } % 2 == 1
        if (inNextcloud(from) || inNextcloud(to)) return false
        val require = segs.indexOfFirst { it is Command && it.keyword == "require" }
        if (require >= 0 && to <= require) return false
        val separator = segs.indexOfFirst { seg -> linesIn(seg).any(::isBulwarkExternalSeparator) }
        if (separator >= 0 && (from > separator) != (to > separator)) return false
        val responder = responderOf(segs)
        val barriers = listOfNotNull(responder?.marker?.segment, responder?.blockId?.let { b -> segs.indexOfFirst { it is Command && it.id == b } })
        return barriers.all { b -> (from < b) == (to <= b) }
    }

    /** A block taken from the end of a script, where its last line may have no line ending, set before something else. */
    private fun ended(c: Command): Command = if (c.text.endsWith("\n")) c else c.copy(trailer = c.trailer + lineEnding)

    /**
     * The script with its auto-reply written as [vacation], or removed when null: the marker glued
     * above its block, so a client that keeps only commented blocks keeps it too.
     */
    fun withResponder(vacation: SieveVacation?): SieveScript {
        val s = migrated()
        var segs = s.segments
        val found = responderOf(segs)
        val marker = vacation?.let { SieveCodec.vacationMarkerLine(it) + lineEnding }
        val active = s.rules.filterIsInstance<ScriptRule.Form>().filter { it.rule.isRunnable }
        val block = vacation?.takeIf { it.isEnabled }?.let { v ->
            SieveCodec.vacationToSieve(v, active.map { it.rule }, lineEnding, active.map { s.writtenTest(it.id) })
        }
        var next = s.nextId
        if (found?.blockId != null) {
            val i = segs.indexOfFirst { it is Command && it.id == found.blockId }
            val c = segs[i] as Command
            val kept = c.header.dropLast(1)
            segs = when {
                block != null -> segs.replaced(i, c.copy(header = kept + (c.indent + marker!!), statement = block, form = null))
                kept.isEmpty() && marker == null -> segs.withoutSegment(i)
                else -> segs.replaced(i, Gap(kept.joinToString("") + marker.orEmpty()))
            }
        } else if (found != null && block == null) {
            val line = lineAt(segs, found.marker)
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            segs = withLine(segs, found.marker, marker?.let { indent + it })
        } else if (block != null) {
            if (found != null) segs = withLine(segs, found.marker, null)
            segs = segs.withBlock(segs.size, Command(next++, listOf(marker!!), "", block, lineEnding), blankAfter = false)
        } else if (marker != null) {
            segs = segs.withBlock(segs.size, Gap(marker), blankAfter = false)
        }
        val needs = if (block == null || vacation == null) emptySet() else buildSet {
            add("vacation")
            if (vacation.fromDate != null || vacation.toDate != null) addAll(listOf("date", "relational"))
        }
        return s.copy(segments = segs, nextId = next).requiring(needs)
    }

    /**
     * The test the block of rule [id] runs, words and guard as written, what the auto-reply leaves out;
     * null when an `anyof` cannot hold it, a comment or a text literal in it, and the block is not left out.
     */
    private fun writtenTest(id: Int): String? {
        val blockId = entries.firstOrNull { it.id == id }?.let { it.blockId ?: return null } ?: id
        val c = commandOrNull(blockId) ?: return null
        val toks = sieveTokens(c.statement) ?: return null
        val open = toks.indexOfFirst { it.isPunct('{') }
        if (open < 2 || !toks[0].isWord("if")) return null
        val test = toks.subList(1, open)
        if (test.any { it.kind == TokenKind.COMMENT || it.kind == TokenKind.LITERAL }) return null
        return c.statement.substring(test.first().start, test.last().end)
    }

    // ---- V1 migration and the require line, applied before any edit ----

    /**
     * The script as its first rewrite leaves it: V1 names written as comments, V1 disabled rules as
     * `allof(false, …)` blocks, every V1 rules marker gone, the auto-reply marker glued to its block.
     */
    private fun migrated(): SieveScript {
        var segs = segments
        val needs = mutableSetOf<String>()
        if (v1Pending) {
            while (true) segs = withLine(segs, locate(segs, ::isRulesMarker) ?: break, null)
            val out = mutableListOf<Segment>()
            for (seg in segs) {
                when {
                    seg is Command && seg.id in v1Unnamed ->
                        out += seg.copy(header = seg.header + (seg.indent + "# " + flatName(seg.form!!.rule.name) + lineEnding))
                    seg is Unwritten -> {
                        if (out.joinToString("") { it.text }.let { it.isNotEmpty() && !it.endsWith("\n") }) out += Gap(lineEnding)
                        out += newFormCommand(seg.id, seg.rule)
                        needs += capabilitiesOf(seg.rule)
                    }
                    else -> out += seg
                }
            }
            segs = out
        }
        return copy(segments = gluedResponderMarker(segs), v1Pending = false, v1Unnamed = emptySet()).requiring(needs)
    }

    private fun gluedResponderMarker(segs: List<Segment>): List<Segment> {
        val found = responderOf(segs) ?: return segs
        val blockId = found.blockId ?: return segs
        val at = segs.indexOfFirst { it is Command && it.id == blockId }
        if (found.marker.segment == at && found.marker.line == (segs[at] as Command).header.lastIndex) return segs
        val line = lineAt(segs, found.marker).trimStart().let { if (it.endsWith("\n")) it else it + lineEnding }
        return withLine(segs, found.marker, null).map {
            if (it is Command && it.id == blockId) it.copy(header = it.header + (it.indent + line)) else it
        }
    }

    /** The script with every capability of [needs] named by a `require`: appended to the first one, or a new one before the first command. */
    private fun requiring(needs: Set<String>): SieveScript {
        val requires = segments.withIndex().filter { (_, seg) -> seg is Command && seg.keyword == "require" }
        val present = requires.flatMap { requiredCapabilities((it.value as Command).statement) }.toSet()
        val missing = CAPABILITY_ORDER.filter { it in needs && it !in present } + (needs - CAPABILITY_ORDER.toSet() - present).sorted()
        if (missing.isEmpty()) return this
        val first = requires.firstOrNull()
        if (first != null) {
            val c = first.value as Command
            requireWith(c.statement, missing)?.let { return copy(segments = segments.replaced(first.index, c.copy(statement = it))) }
            return copy(segments = segments.withBlock(first.index + 1, newRequire(missing), blankAfter = false), nextId = nextId + 1)
        }
        val firstCommand = segments.indexOfFirst { it is Command }.takeIf { it >= 0 } ?: segments.size
        return copy(segments = segments.withBlock(firstCommand, newRequire(missing), blankAfter = true), nextId = nextId + 1)
    }

    private fun newRequire(capabilities: List<String>) =
        Command(nextId, emptyList(), "", "require [${capabilities.joinToString(", ") { "\"$it\"" }}];", lineEnding)

    private fun newFormCommand(id: Int, rule: FilterRule): Command {
        val words = rule.conditions.map { defaultWord(it.field) }
        return Command(
            id = id,
            header = renamedHeader(emptyList(), rule.name, "", lineEnding),
            indent = "",
            statement = formStatement(rule, words, null, "", lineEnding),
            trailer = lineEnding,
            form = FormBlock(rule, words, null),
        )
    }

    // ---- Bulwark: its JSON is the source of its rules, each block follows its entry ----

    private fun bulwarkUpdated(index: Int, edited: FilterRule): SieveScript {
        // Bulwark's reader compares the JSON name and the `# Rule:` name as they are: both get the same string.
        val rule = edited.copy(name = flatName(edited.name))
        val entry = entries[index]
        val oldRule = requireNotNull(BulwarkFormat.simpleRule(entry.json)) { "rule ${entry.id} is managed by Bulwark" }
        require(BulwarkFormat.holds(rule)) { "Bulwark's JSON cannot hold $rule" }
        val json = BulwarkFormat.updated(entry.json, rule)
        var segs = segments
        var blockId = entry.blockId
        var next = nextId
        val at = blockId?.let { indexOf(it) }
        val wantsBlock = BulwarkFormat.hasBlock(json)
        if (at != null && wantsBlock) {
            val c = segs[at] as Command
            val sameTest = oldRule.copy(name = "", enabled = true) == rule.copy(name = "", enabled = true)
            val header = if (oldRule.name == rule.name) c.header else bulwarkHeader(c.header, rule.name, c.indent)
            val guard = guardOf(c.statement) ?: BulwarkFormat.spamGuard(json, required())
            val statement = if (sameTest) c.statement else BulwarkFormat.statement(json, guard, c.indent, lineEnding)
            segs = segs.replaced(at, c.copy(header = header, statement = statement))
        } else if (at != null) {
            segs = segs.withoutSegment(at)
            blockId = null
        } else if (wantsBlock) {
            blockId = next++
            segs = bulwarkBlockPlaced(segs, index, bulwarkCommand(blockId, json, rule.name))
        }
        val updated = entries.replaced(index, entry.copy(json = json, blockId = blockId))
        return copy(segments = withMetadata(segs, updated), entries = updated, nextId = next)
            .requiring(if (wantsBlock) capabilitiesOf(rule) + guardCapabilities(json) else emptySet())
    }

    private fun bulwarkAdded(added: FilterRule, bulwarkId: String): SieveScript {
        val rule = added.copy(name = flatName(added.name))
        require(BulwarkFormat.holds(rule)) { "Bulwark's JSON cannot hold $rule" }
        val json = BulwarkFormat.created(bulwarkId, rule)
        var next = nextId
        val entryId = next++
        val blockId = if (BulwarkFormat.hasBlock(json)) next++ else null
        val updated = entries + BulwarkEntry(entryId, json, blockId)
        var segs = segments
        if (blockId != null) segs = bulwarkBlockPlaced(segs, entries.size, bulwarkCommand(blockId, json, rule.name))
        return copy(segments = withMetadata(segs, updated), entries = updated, nextId = next)
            .requiring(if (blockId != null) capabilitiesOf(rule) + guardCapabilities(json) else emptySet())
    }

    private fun bulwarkRemoved(index: Int): SieveScript {
        val entry = entries[index]
        val segs = entry.blockId?.let { segments.withoutSegment(indexOf(it)) } ?: segments
        val updated = entries.filterIndexed { i, _ -> i != index }
        return copy(segments = withMetadata(segs, updated), entries = updated)
    }

    private fun bulwarkCommand(id: Int, json: JsonObject, name: String) = Command(
        id = id,
        header = listOf("# Rule: ${flatName(name)}$lineEnding"),
        indent = "",
        statement = BulwarkFormat.statement(json, BulwarkFormat.spamGuard(json, required()), "", lineEnding),
        trailer = lineEnding,
    )

    private fun required(): Set<String> = segments.filterIsInstance<Command>()
        .filter { it.keyword == "require" }.flatMap { requiredCapabilities(it.statement) }.toSet()

    private fun guardCapabilities(json: JsonObject): Set<String> =
        if (BulwarkFormat.spamGuard(json, required()) != null) BulwarkFormat.spamGuardCapabilities else emptySet()

    private fun bulwarkHeader(header: List<String>, name: String, indent: String): List<String> =
        header.dropLast(1) + "$indent# Rule: ${flatName(name)}$lineEnding"

    /** Blocks stay in the order of their entries, which is what Bulwark's reader and ours both check. */
    private fun bulwarkBlockPlaced(segs: List<Segment>, entryIndex: Int, block: Command): List<Segment> {
        fun at(id: Int) = segs.indexOfFirst { it is Command && it.id == id }
        val nextBlock = entries.drop(entryIndex + 1).firstNotNullOfOrNull { it.blockId }
        val previousBlock = entries.take(entryIndex).lastOrNull { it.blockId != null }?.blockId
        return when {
            nextBlock != null -> segs.withBlock(at(nextBlock), block, blankAfter = true)
            previousBlock != null -> segs.withBlock(at(previousBlock) + 1, block, blankAfter = false)
            else -> {
                val require = segs.indexOfLast { it is Command && it.keyword == "require" }
                segs.withBlock(if (require >= 0) require + 1 else 1, block, blankAfter = false)
            }
        }
    }

    private fun withMetadata(segs: List<Segment>, updated: List<BulwarkEntry>): List<Segment> {
        val comment = (segs[0] as Gap).text
        val meta = requireNotNull(BulwarkFormat.read(comment))
        val rewritten = JsonObject(meta + ("rules" to JsonArray(updated.map { it.json })))
        return segs.replaced(0, Gap(BulwarkFormat.written(comment, rewritten, lineEnding)))
    }

    // ---- segment helpers ----

    private fun entryIndex(id: Int): Int? = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    private fun indexOf(id: Int): Int = segments.indexOfFirst {
        (it is Command && it.id == id) || (it is Unwritten && it.id == id)
    }.also { require(it >= 0) { "no rule $id in this script" } }

    private fun commandOrNull(id: Int): Command? = segments.firstOrNull { it is Command && it.id == id } as Command?

    /** What the screen shows and [replaceText] replaces: the block and the rest of its last line, without the line ending. */
    private fun source(c: Command) = c.header.joinToString("") + c.indent + c.statement + c.trailer.trimEnd('\r', '\n')

    /** [this] with [block] inserted at [at], separated by a blank line from what precedes, or follows when [blankAfter]. */
    private fun List<Segment>.withBlock(at: Int, block: Segment, blankAfter: Boolean): List<Segment> {
        val before = take(at).joinToString("") { it.text }
        val lead = buildString {
            if (before.isNotEmpty() && !before.endsWith("\n")) append(lineEnding)
            if (!blankAfter && before.isNotBlank() && !before.endsWith("\n\n") && !before.endsWith("\n\r\n")) append(lineEnding)
        }
        val inserted = listOfNotNull(Gap(lead).takeIf { lead.isNotEmpty() }, block, Gap(lineEnding).takeIf { blankAfter })
        return take(at) + inserted + drop(at)
    }

    private fun copy(
        segments: List<Segment> = this.segments,
        entries: List<BulwarkEntry> = this.entries,
        v1Pending: Boolean = this.v1Pending,
        v1Unnamed: Set<Int> = this.v1Unnamed,
        nextId: Int = this.nextId,
    ) = SieveScript(segments, lineEnding, bulwarkMetadata, entries, v1Pending, v1Unnamed, nextId)

    companion object {
        private val CAPABILITY_ORDER = listOf("fileinto", "imap4flags", "vacation", "date", "relational")

        fun parse(text: String): SieveParse {
            val raw = try {
                SieveScanner(text).split(firstId = 0)
            } catch (e: Undelimited) {
                return SieveParse.Unreadable(e.opener, e.offset)
            }
            var nextId = raw.size
            var segs = classify(raw)

            val comment = (segs.firstOrNull() as? Gap)?.text?.takeIf { it.startsWith(SieveScanner.BULWARK_BEGIN) }
            var bulwark = if (comment == null) BulwarkMetadata.ABSENT else BulwarkMetadata.UNREADABLE
            var entries = emptyList<BulwarkEntry>()
            val meta = comment?.let(BulwarkFormat::read)
            if (meta != null) {
                val external = segs.indexOfFirst { linesIn(it).any(::isBulwarkExternalSeparator) }.takeIf { it >= 0 } ?: segs.size
                val blocks = segs.take(external).filterIsInstance<Command>()
                    .mapNotNull { c -> bulwarkRuleName(c.header)?.let { c to it } }
                val rules = BulwarkFormat.rules(meta)
                val written = rules.filter(BulwarkFormat::hasBlock)
                if (BulwarkFormat.blocksMatch(written.map { flatName(BulwarkFormat.name(it)) }, blocks.map { it.second })) {
                    var k = 0
                    entries = rules.map { r -> BulwarkEntry(nextId++, r, if (BulwarkFormat.hasBlock(r)) blocks[k++].first.id else null) }
                    bulwark = BulwarkMetadata.READABLE
                }
            }

            var v1Pending = false
            var unnamed = emptySet<Int>()
            val v1 = if (bulwark == BulwarkMetadata.READABLE) null
            else locate(segs, ::isRulesMarker)?.let { SieveCodec.parseRulesOrNull(lineAt(segs, it)) }
            if (v1 != null) {
                val forms = segs.filterIsInstance<Command>().filter { it.form != null }
                val written = v1.filter { it.enabled && it.value.isNotBlank() }
                // The marker names the blocks `generate` wrote, in its order: any other block there means it no longer does.
                val married = written.size <= forms.size && written.indices.all { k ->
                    forms[k].form!!.let { it.guard == null && it.rule.copy(name = "") == written[k].copy(name = "") }
                }
                if (married) {
                    val names = HashMap<Int, String>()
                    val after = HashMap<Int?, MutableList<Unwritten>>()
                    var anchor: Int? = null
                    var k = 0
                    for (r in v1) {
                        if (r.enabled && r.value.isNotBlank()) {
                            anchor = forms[k++].id
                            names[anchor] = r.name
                        } else if (!r.isEmpty) {
                            // A blank value never ran under V1, and `:contains ""` would match every message.
                            after.getOrPut(anchor) { mutableListOf() } += Unwritten(nextId++, r.copy(enabled = false))
                        }
                    }
                    val firstMarried = forms.firstOrNull()?.id?.takeIf { written.isNotEmpty() }
                    segs = buildList {
                        for (seg in segs) {
                            if (seg is Command && seg.id == firstMarried) addAll(after[null].orEmpty())
                            val name = (seg as? Command)?.id?.let(names::get)
                            add(if (name != null) (seg as Command).let { it.copy(form = it.form!!.copy(rule = it.form.rule.copy(name = name))) } else seg)
                            if (seg is Command) after[seg.id]?.let(::addAll)
                        }
                    }
                    if (firstMarried == null) {
                        val at = segs.indexOfLast { it is Command } + 1
                        segs = segs.take(if (at > 0) at else segs.size) + after[null].orEmpty() + segs.drop(if (at > 0) at else segs.size)
                    }
                    unnamed = names.filterValues { it.isNotBlank() }.keys
                    v1Pending = true
                } else {
                    // The names are lost with the pairing; the disabled rules live only in the marker, so they are kept.
                    val kept = v1.filter { !(it.enabled && it.value.isNotBlank()) && !it.isEmpty }
                        .map { Unwritten(nextId++, it.copy(enabled = false)) }
                    val at = (segs.indexOfLast { it is Command } + 1).takeIf { it > 0 } ?: segs.size
                    segs = segs.take(at) + kept + segs.drop(at)
                    v1Pending = true
                }
            }
            val script = SieveScript(segs, dominantLineEnding(text), bulwark, entries, v1Pending, unnamed, nextId)
            return SieveParse.Readable(script)
        }

        private fun classify(segs: List<Segment>): List<Segment> {
            var markers = 0
            return segs.map { seg ->
                when (seg) {
                    is Gap -> seg.also { markers += linesOf(it.text).count(::isNextcloudMarker) }
                    is Unwritten -> seg
                    is Command -> {
                        markers += seg.header.count(::isNextcloudMarker)
                        // Nextcloud rewrites the blocks between its markers from its own JSON: an edit there would not last.
                        val form = if (markers % 2 == 0 && seg.keyword == "if") recognizeForm(seg.statement) else null
                        seg.copy(form = form?.let { it.copy(rule = it.rule.copy(name = ruleName(seg.header))) })
                    }
                }
            }
        }

        /** [at], or just past the marker that closes the Nextcloud region [at] falls in. */
        private fun outsideNextcloud(segs: List<Segment>, at: Int): Int {
            if (segs.take(at).sumOf { seg -> linesIn(seg).count(::isNextcloudMarker) } % 2 == 0) return at
            val close = (at until segs.size).firstOrNull { i -> linesIn(segs[i]).any(::isNextcloudMarker) } ?: return segs.size
            return close + 1
        }

        private fun isRulesMarker(line: String) = line.trim().startsWith(SieveCodec.MARKER)

        private fun isVacationMarker(line: String) = line.trim().startsWith(SieveCodec.VACATION_MARKER)

        private fun linesIn(seg: Segment): List<String> = when (seg) {
            is Gap -> linesOf(seg.text)
            is Command -> seg.header
            is Unwritten -> emptyList()
        }

        private fun locate(segs: List<Segment>, predicate: (String) -> Boolean): LineAt? {
            segs.forEachIndexed { i, seg ->
                val line = linesIn(seg).indexOfFirst(predicate)
                if (line >= 0) return LineAt(i, line)
            }
            return null
        }

        private fun lineAt(segs: List<Segment>, at: LineAt): String = linesIn(segs[at.segment])[at.line]

        /** [segs] with the line [at] replaced by [replacement], or removed when null. */
        private fun withLine(segs: List<Segment>, at: LineAt, replacement: String?): List<Segment> {
            val lines = linesIn(segs[at.segment]).toMutableList()
            if (replacement == null) lines.removeAt(at.line) else lines[at.line] = replacement
            val seg = when (val old = segs[at.segment]) {
                is Gap -> Gap(lines.joinToString(""))
                is Command -> old.copy(header = lines)
                is Unwritten -> old
            }
            return segs.replaced(at.segment, seg)
        }

        private fun responderOf(segs: List<Segment>): Responder? {
            val marker = locate(segs, ::isVacationMarker) ?: return null
            val glued = (segs[marker.segment] as? Command)?.takeIf { isVacationBlock(it.statement) }
            val block = glued ?: segs.filterIsInstance<Command>().firstOrNull { isVacationBlock(it.statement) }
            return Responder(marker, block?.id)
        }

        /** Without the blank line that set the block apart, so a removal leaves no hole behind. */
        private fun List<Segment>.withoutSegment(index: Int): List<Segment> {
            val out = toMutableList()
            val removed = out.removeAt(index)
            // A region marker glued to the block belongs to the region: dropping it would unpair the next one.
            val kept = (removed as? Command)?.header.orEmpty().filter { isNextcloudMarker(it) || isBulwarkExternalSeparator(it) }
            if (kept.isNotEmpty()) return out.also { it.add(index, Gap(kept.joinToString(""))) }
            val previous = out.getOrNull(index - 1)
            val next = out.getOrNull(index)
            when {
                previous is Gap && previous.text.isBlank() -> out.removeAt(index - 1)
                index == 0 && next is Gap && next.text.isBlank() -> out.removeAt(0)
            }
            return out
        }

        private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

        /** A line that holds a place in the script rather than naming a block: it never moves with one. */
        private fun isPlaceMarker(line: String) =
            isNextcloudMarker(line) || isBulwarkExternalSeparator(line) || isRulesMarker(line) || isVacationMarker(line)

        /** The header lines down to the last place marker among them, and the block with the comments glued below it. */
        private fun Command.cut(): Pair<List<String>, Command> {
            val kept = header.indexOfLast(::isPlaceMarker) + 1
            return header.take(kept) to copy(header = header.drop(kept))
        }

        /** [this] with the block [id] split from the place markers of its header, which then stand alone before it. */
        private fun List<Segment>.opened(id: Int): List<Segment> {
            val i = indexOfFirst { it is Command && it.id == id }
            val (kept, block) = (this[i] as Command).cut()
            if (kept.isEmpty()) return this
            return take(i) + Gap(kept.joinToString("")) + block + drop(i + 1)
        }

        private fun randomBulwarkId(): String = "sterna-" + UUID.randomUUID().toString().substringBefore('-')
    }
}
