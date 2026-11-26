package app.amethyst.core.insert

import app.amethyst.core.text.FieldKind
import app.amethyst.core.text.FieldState
import app.amethyst.core.text.SpliceOptions
import app.amethyst.core.text.TextSplicer
import app.amethyst.core.text.TranscriptNormalizer

/**
 * The focused editable field of some app, as the platform layer can see it.
 *
 * Android's accessibility node is behind this interface so the insertion chain can be tested
 * against fields that misbehave the way real ones do - reporting a successful write and
 * then ignoring it.
 */
interface EditableTarget {
    /** Owner of the field; the key under which the working strategy is remembered. */
    val packageName: String

    /** False when the focused node is not an editable field (a button, a WebView canvas). */
    val isEditable: Boolean

    /**
     * Current contents and selection, or null when they cannot be read. Of a field reached
     * only through its input connection, the part around the cursor ([WindowedTarget]).
     */
    fun readField(): FieldState?

    /** Replace the whole field. Returns what the platform claims, which may be a lie. */
    fun setText(text: String): Boolean

    fun setSelection(cursor: Int)

    /**
     * The field's text and selection right after a write, to see where the cursor ended up.
     * Null where that cannot be told: a window onto the field ([WindowedTarget]) is not the
     * field, and a position in it is none in the field.
     */
    fun readCursor(): FieldState? = readField()

    /**
     * What stands before the cursor as the editor itself holds it - read now through the input
     * connection the words are typed through, which never holds a placeholder. Null when there
     * is no editor to ask. Text a commit took away is typed back ([retype]) only when the editor
     * had it ([InsertionEngine]): a field's node may hand out its placeholder as text, and typed
     * in front of the words it would become text nobody said - "Message Testnachricht." in
     * WhatsApp (Pixel 9a, 2026-11-26).
     */
    fun editorHead(): String? = null

    /** Paste whatever is on the clipboard into the field. */
    fun paste(): Boolean

    /**
     * Insert [text] at the cursor through an input connection bound to this very field.
     * False when there is none - below Android 13, or when the field never started input.
     */
    fun commitText(text: String): Boolean = false

    /**
     * Types [text] through the input connection in place of [shown], which must stand right
     * before the cursor with nothing selected: the words just typed, now with what the field
     * dropped in front of them ([Landed.seam]). False - and nothing typed - when [shown] is not
     * there or there is no connection.
     */
    fun retype(shown: String, text: String): Boolean = false

    /**
     * The last [readBack] began where [readField]'s text began, so what stands before the words
     * can be held against what stood there ([Landed.seam]). Always so for a field read whole; a
     * window onto a long field may not say where it begins ([WindowedTarget]).
     */
    val readBackAligned: Boolean get() = true

    /**
     * The field takes line breaks the way the user could type them: Enter makes a new line
     * there (multi-line, no "send", "next" or "search" action). Anywhere else - a title whose
     * Enter jumps to the body, a search box, a messenger set to "Enter sends" - a dictation
     * goes in as one line, as it would from the keyboard.
     */
    val takesLineBreaks: Boolean get() = false

    /**
     * A password or PIN field, by everything that says so about the very field that is about
     * to be written ([app.amethyst.core.bubble.SecretField]). Asked once, right before the
     * write; such a field gets nothing ([TargetCheck.atWrite]).
     */
    val isSecret: Boolean get() = false

    /**
     * Writing the whole field back would lose what it holds besides plain text, or the field
     * is no EditText ([FieldText.setTextFlattens]): pasting at the cursor goes before
     * SET_TEXT, where the clipboard may be used.
     */
    val setTextFlattens: Boolean get() = false

    /**
     * A search box, a web or an e-mail address ([FieldKind]): the words go in without capital and
     * full stop. Plain text where nothing says otherwise.
     */
    val fieldKind: FieldKind get() = FieldKind.TEXT

    /** Contents after an attempt, for verification. Null when unreadable. */
    fun readBack(): String?
}

fun interface Clipboard {
    /** Returns false if the clipboard could not be written at all. */
    fun copy(text: String): Boolean

    /**
     * Takes [text] - copied a moment ago only to paste it - off the clipboard again, now that it
     * is in the field, while the clip is still the app's own ([OwnClip.clearAfterPaste]). True
     * when it was cleared.
     */
    fun forget(text: String): Boolean = false
}

data class InsertionOutcome(
    val strategy: InsertionStrategy,
    /** The text is in the field. This is the only thing that counts as inserted. */
    val insertedIntoField: Boolean,
    /** The text is at least on the clipboard, so the user can paste it by hand. */
    val copiedToClipboard: Boolean,
    val targetPackage: String?,
    /**
     * Typed through the input connection but never seen in the field. It may still arrive -
     * so nothing else was tried, which would risk the words twice - or it may be lost.
     */
    val uncertain: Boolean = false,
    /**
     * The characters the insertion put directly before the cursor - seam included - when it is
     * known, for "Undo last" ([UndoLast]). Null when the field could not be read or the splice
     * also changed text before it.
     */
    val fragment: String? = null,
    /**
     * Why nothing was written, or even tried: the field found for writing is not one the
     * words may go into ([TargetCheck.atWrite]). Nothing went to the clipboard either - the
     * words are still the caller's to keep.
     */
    val refused: TargetCheck.Verdict? = null,
    /**
     * The field had the cursor somewhere other than right behind the words after taking them
     * in, and it was put there ([InsertionEngine]) - for the diagnostics.
     */
    val cursorPutBack: Boolean = false,
    /**
     * Typing the words took away text that stood before them - the word the keyboard was still
     * writing - and what was done about it ([InsertionEngine.PrefixRepair]), for the diagnostics.
     */
    val prefixRepair: InsertionEngine.PrefixRepair? = null,
)

/**
 * Puts dictated text into a field, trying the methods in the order that worked last time.
 *
 * The important part is not the chain, it is the verification: `performAction` returning true
 * is not evidence that anything happened. Many WebViews, games and cross-platform text fields
 * accept ACTION_SET_TEXT, report success, and leave the field untouched. So every attempt is
 * followed by reading the field back, and only the read decides whether to move on.
 */
class InsertionEngine(
    private val memory: StrategyMemory,
    private val clipboard: Clipboard,
    /** Called whenever the remembered per-app strategy changed, so it can be persisted. */
    private val onMemoryChanged: (StrategyMemory) -> Unit = {},
    /** Waits between read-backs; injectable so tests do not sleep. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * [allowClipboard] false keeps every attempt off the clipboard: no paste, no copy as a
     * fallback. Users with a clipboard history would otherwise collect every dictation there.
     * [expectedPackage]: the app the words are for ([TargetCheck.expectedPackage]); a [target]
     * of another app is refused, as a password field always is ([TargetCheck.atWrite]).
     */
    fun insert(
        target: EditableTarget?,
        text: String,
        spliceOptions: SpliceOptions = SpliceOptions(),
        allowClipboard: Boolean = true,
        expectedPackage: String? = null,
    ): InsertionOutcome {
        val packageName = target?.packageName ?: UNKNOWN_PACKAGE
        var copied = false
        // What went on the clipboard last, to take exactly that off again: a paste copies the
        // words with their seam, and after a failed paste another method may have put them in.
        var clip: String? = null
        committed = null
        setFragment = null
        pasted = null
        cursorPutBack = false
        prefixRepair = null

        if (target == null) {
            // Nothing is focused, so there is nothing to write into. The clipboard keeps the
            // dictation from being lost - if the user lets us use it.
            if (allowClipboard) copied = clipboard.copy(text)
            return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, copied, null)
        }

        // The last look, at the field itself: whoever let the words through looked a moment
        // ago, and maybe at another field.
        val verdict = TargetCheck.atWrite(expectedPackage, packageName, secret = target.isSecret)
        if (verdict != TargetCheck.Verdict.INSERT) {
            return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, false, packageName, refused = verdict)
        }

        // Typing comes first whenever an input connection is there: it is how every keyboard
        // writes, so apps keep it. SET_TEXT can show the words and still lose them - Google
        // Keep's note body displayed a SET_TEXT write and dropped it on closing (Pixel 9a,
        // 2026-10-29). What worked last time only orders the fallbacks.
        // What kind of field it is, the field says - unless the caller already knew.
        val options = if (spliceOptions.fieldKind == FieldKind.TEXT) spliceOptions.copy(fieldKind = target.fieldKind) else spliceOptions
        val pasteFirst = allowClipboard && target.setTextFlattens
        val order = fallbacks(memory.order(packageName), allowClipboard, pasteFirst)
        val words = if ('\n' in text && !target.takesLineBreaks) TranscriptNormalizer.oneLine(text) else text

        for (strategy in order) {
            val inserted = when (strategy) {
                InsertionStrategy.COMMIT_TEXT -> when (tryCommit(target, words, options)) {
                    Commit.LANDED -> true
                    Commit.NOT_POSSIBLE -> false
                    Commit.LOST -> {
                        memory.recordFailure(packageName, strategy)
                        onMemoryChanged(memory)
                        if (allowClipboard) copied = clipboard.copy(words) || copied
                        val shown = if (copied) InsertionStrategy.CLIPBOARD_ONLY else strategy
                        return InsertionOutcome(shown, false, copied, packageName, uncertain = true)
                    }
                }

                InsertionStrategy.SET_TEXT -> trySetText(target, words, options)

                InsertionStrategy.PASTE -> {
                    // Pasting needs the text on the clipboard first; that is also why a
                    // failed paste still leaves the dictation recoverable. What typing would
                    // type goes there - space and capital at the seam included - for a field
                    // that gets the paste before SET_TEXT ([EditableTarget.setTextFlattens]).
                    val fragment = pasteFragment(target, words, options)
                    pasted = fragment
                    val onClipboard = clipboard.copy(fragment)
                    if (onClipboard) {
                        copied = true
                        clip = fragment
                    }
                    onClipboard && tryPaste(target, words, options)
                }

                InsertionStrategy.CLIPBOARD_ONLY -> {
                    if (clipboard.copy(words)) {
                        copied = true
                        clip = words
                    }
                    false
                }
            }

            if (inserted) {
                // A paste that went first only because this one field would be flattened says
                // nothing about the app: remembered, it would go before SET_TEXT in every plain
                // field of it too, and every dictation there would pass the clipboard.
                if (!(pasteFirst && strategy == InsertionStrategy.PASTE)) {
                    memory.recordSuccess(packageName, strategy)
                    onMemoryChanged(memory)
                }
                // The clip was only the way in: left there, every dictation pasted would stay
                // on the clipboard - for the next paste anywhere, and for any app allowed to
                // read it. A clip that is the fallback ("copied, paste it yourself") never
                // gets here: then nothing went into the field.
                if (copied && clip != null && clipboard.forget(clip)) copied = false
                val fragment = when (strategy) {
                    InsertionStrategy.COMMIT_TEXT -> committed
                    InsertionStrategy.SET_TEXT -> setFragment
                    InsertionStrategy.PASTE -> pasted ?: words
                    InsertionStrategy.CLIPBOARD_ONLY -> null
                }
                return InsertionOutcome(
                    strategy, true, copied, packageName,
                    fragment = fragment, cursorPutBack = cursorPutBack, prefixRepair = prefixRepair,
                )
            }

            memory.recordFailure(packageName, strategy)
            onMemoryChanged(memory)
        }

        return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, copied, packageName)
    }

    private enum class Commit { LANDED, NOT_POSSIBLE, LOST }

    /**
     * Typed words that arrived in place of text before them: [lost] characters of it, and [how]
     * they came back. Lengths only - the diagnostics never hold the field's text.
     */
    data class PrefixRepair(val lost: Int, val how: How) {
        enum class How {
            /** Typed again in front of the words, through the input connection. */
            TYPED_AGAIN,

            /** The field written back whole (SET_TEXT): typing again was not possible. */
            WRITTEN_BACK,

            /** Neither worked, or neither was safe: the words are in, the text before them is not. */
            NOT_RESTORED,
        }
    }

    /** What the last commit did about text it took away before the words. */
    private var prefixRepair: PrefixRepair? = null

    /** What the last commit typed: the fragment, for [InsertionOutcome.fragment]. */
    private var committed: String? = null

    /** What the last SET_TEXT added before the cursor, if it only added. */
    private var setFragment: String? = null

    /** What the last paste put on the clipboard: the fragment, for [InsertionOutcome.fragment]. */
    private var pasted: String? = null

    /** The last insertion found the cursor elsewhere and put it behind the words. */
    private var cursorPutBack = false

    /**
     * A commit travels to the app asynchronously and the accessibility input connection reports
     * nothing back. So the field is read until the words show up - patiently, since an
     * immediate read often still shows the old text. When they never do, the connection was
     * stale (an app that rebuilt its field mid-dictation: WhatsApp, now and then) and the text
     * is LOST: reported, kept on the clipboard if allowed - but not typed again another way,
     * because a commit arriving late would then put the words in twice. A long text gets longer
     * to show up, and is compared loosely ([Landed]).
     */
    private fun tryCommit(target: EditableTarget, text: String, options: SpliceOptions): Commit {
        if (!target.isEditable) return Commit.NOT_POSSIBLE
        val field = target.readField()
        val fragment = field?.let { commitFragment(it, text, options) } ?: unread(text, options)
        // What the editor held before the words, for text the commit takes away ([keepPrefix]).
        val editorHead = field?.let { target.editorHead() }
        if (fragment.isEmpty() || !target.commitText(fragment)) return Commit.NOT_POSSIBLE
        committed = fragment
        // A field that cannot be read cannot be checked, and got the words without a seam: a
        // connection that hands out no text is all there is of it.
        if (field == null) return Commit.LANDED
        val attempts = Landed.attempts(COMMIT_VERIFY_ATTEMPTS, text.length)
        repeat(attempts) { attempt ->
            val after = target.readBack() ?: return Commit.LANDED
            if (Landed.inField(field, after, expected(field, text, options))) {
                cursorPutBack = cursorBehindWords(target, tail = textAfterSelection(field))
                if (target.readBackAligned) prefixRepair = keepPrefix(target, field, after, fragment, editorHead)
                return Commit.LANDED
            }
            if (attempt < attempts - 1) pause(VERIFY_INTERVAL_MS)
        }
        return Commit.LOST
    }

    /**
     * Android's input connection types over the word the keyboard is still writing - the one it
     * underlines - and not only over the selection: "Hallo" not yet finished, "wie geht's"
     * typed, and the field reads " wie geht's". The words arrived, so [Landed.inField] is
     * content; what stood before them is what [Landed.seam] looks at, in the field read after
     * the commit ([after]). Lost text comes back the way it went: typed again in front of the
     * words, the cursor behind them. Where that is not possible, nothing was typed, and the
     * field is written back whole - unless that would flatten it. Never both: words typed again
     * that only show up late would land on top of a written field. Null when nothing was lost.
     *
     * Only what the editor itself held before the words ([editorHead]) comes back: a field's
     * node may hand out its placeholder as text, and "lost" by the commit it would be typed in
     * front of the words - "Message Testnachricht." (WhatsApp, Pixel 9a, 2026-11-26). Text the
     * editor cannot vouch for stays out, reported as not restored.
     */
    private fun keepPrefix(target: EditableTarget, field: FieldState, after: String, typed: String, editorHead: String?): PrefixRepair? {
        val seam = Landed.seam(field, after, typed) as? Landed.Seam.PrefixLost ?: return null
        if (editorHead == null || !editorHead.endsWith(seam.lost)) return PrefixRepair(seam.lost.length, PrefixRepair.How.NOT_RESTORED)
        val again = seam.lost + typed
        val how = when {
            target.retype(seam.shown, again) ->
                if (seamKept(target, field, typed, again.length)) PrefixRepair.How.TYPED_AGAIN else PrefixRepair.How.NOT_RESTORED
            !target.setTextFlattens && writeBack(target, field, seam.restored(after, typed), typed) -> PrefixRepair.How.WRITTEN_BACK
            else -> PrefixRepair.How.NOT_RESTORED
        }
        return PrefixRepair(seam.lost.length, how)
    }

    /** The field reads with the text before the words whole again, read until it does. */
    private fun seamKept(target: EditableTarget, field: FieldState, typed: String, length: Int): Boolean {
        val attempts = Landed.attempts(COMMIT_VERIFY_ATTEMPTS, length)
        repeat(attempts) { attempt ->
            val now = target.readBack() ?: return false
            if (Landed.seam(field, now, typed) == Landed.Seam.Kept) return true
            if (attempt < attempts - 1) pause(VERIFY_INTERVAL_MS)
        }
        return false
    }

    /** [restored] written as the whole field, the cursor behind the words - before what stood behind them. */
    private fun writeBack(target: EditableTarget, field: FieldState, restored: String, typed: String): Boolean {
        if (!target.setText(restored)) return false
        if (!seamKept(target, field, typed, restored.length)) return false
        val tail = textAfterSelection(field)
        val now = target.readCursor()
        target.setSelection(now?.takeIf { it.text.endsWith(tail) }?.let { it.text.length - tail.length } ?: (restored.length - tail.length))
        return true
    }

    /**
     * Typing asks the app to put the cursor behind the words (commitText with position 1), and
     * the next dictation goes on from there. An app that leaves it elsewhere - in WhatsApp it
     * stood before a final "?" (Pixel 9a, 2026-11-16) - gets it put there: the text that stood
     * behind the selection, [tail], is all that may follow it. Counted from the field as it now
     * reads, so a field that wrote the words a little differently still gets it right. Left
     * alone where the end of the field is no longer [tail]: then the field changed in another
     * way, and nobody can say where "behind the words" is. True when it was moved.
     */
    private fun cursorBehindWords(target: EditableTarget, tail: String): Boolean {
        val now = target.readCursor() ?: return false
        if (!now.text.endsWith(tail)) return false
        val behind = now.text.length - tail.length
        if (now.selectionStart == behind && now.selectionEnd == behind) return false
        target.setSelection(behind)
        return true
    }

    private fun textAfterSelection(field: FieldState): String =
        field.text.substring(maxOf(field.selectionStart, field.selectionEnd).coerceIn(0, field.text.length))

    /**
     * What to type so the field ends up as [TextSplicer] would have spliced it: the seam
     * (leading space, capital) included, the untouched text around it excluded. Where the
     * splice changed text before the cursor - pulling punctuation back - typing cannot
     * express that, so the plain words are used instead.
     */
    private fun commitFragment(field: FieldState, text: String, options: SpliceOptions): String {
        val limit = field.text.length
        val start = minOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)
        val end = maxOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)
        val before = field.text.substring(0, start)
        val after = field.text.substring(end)

        val spliced = TextSplicer.splice(field, text, options).text
        if (!spliced.startsWith(before) || !spliced.endsWith(after)) return text.trim()
        return spliced.substring(before.length, spliced.length - after.length)
    }

    /**
     * The order to try: typing first whenever an input connection is there, then what worked
     * last time ([memory]). [pasteFirst]: a paste goes before SET_TEXT, which would flatten
     * the field ([EditableTarget.setTextFlattens]) - typing keeps the field as it is, so it
     * stays first. Without [allowClipboard] neither paste nor the clipboard is tried.
     */
    private fun fallbacks(memory: List<InsertionStrategy>, allowClipboard: Boolean, pasteFirst: Boolean): List<InsertionStrategy> {
        val usesClipboard = setOf(InsertionStrategy.PASTE, InsertionStrategy.CLIPBOARD_ONLY)
        val rest = memory.filter { it != InsertionStrategy.COMMIT_TEXT && (allowClipboard || it !in usesClipboard) }
        val ordered = if (pasteFirst && InsertionStrategy.PASTE in rest) {
            listOf(InsertionStrategy.PASTE) + rest.filter { it != InsertionStrategy.PASTE }
        } else {
            rest
        }
        return listOf(InsertionStrategy.COMMIT_TEXT) + ordered
    }

    /**
     * What a paste puts in: the words with their seam to the text before the cursor, as typing
     * would ([commitFragment]); the plain words where the field cannot be read.
     */
    private fun pasteFragment(target: EditableTarget, text: String, options: SpliceOptions): String =
        target.readField()?.let { commitFragment(it, text, options) }?.takeIf { it.isNotEmpty() }
            ?: if (options.fieldKind == FieldKind.TEXT) text else unread(text, options)

    /**
     * The words for a field that could not be read: as they are - nothing to fit them to - but
     * for a search box or an address, which gets them without capital and full stop either way.
     */
    private fun unread(text: String, options: SpliceOptions): String =
        if (options.fieldKind == FieldKind.TEXT) text.trim() else TextSplicer.words(FieldState.EMPTY, text, options)

    /**
     * What to look for in the field once the words are in: as [TextSplicer] fits them to [field]
     * - a full stop it dropped is not there to be found ([TextSplicer.words]).
     */
    private fun expected(field: FieldState?, text: String, options: SpliceOptions): String =
        field?.let { TextSplicer.words(it, text, options) } ?: unread(text, options)

    private fun trySetText(target: EditableTarget, text: String, options: SpliceOptions): Boolean {
        if (!target.isEditable) return false
        val field = target.readField() ?: return false

        val spliced = TextSplicer.splice(field, text, options)
        if (!target.setText(spliced.text)) return false
        setFragment = UndoLast.added(field, spliced.text, spliced.cursor)

        // Verify before moving the cursor. A field that accepted the write and kept its old
        // contents would otherwise be handed a caret position that does not exist in it, and
        // the following paste would land somewhere arbitrary.
        if (!verify(target, field, expected(field, text, options))) return false

        // Where the splice put the cursor, counted from the end: a field that reformats what
        // it is given (a line break written as two characters) would otherwise have it land
        // inside the words - one before a final "?".
        val tail = spliced.text.substring(spliced.cursor.coerceIn(0, spliced.text.length))
        val now = target.readCursor()
        val cursor = now?.takeIf { it.text.endsWith(tail) }?.let { it.text.length - tail.length } ?: spliced.cursor
        target.setSelection(cursor)
        return true
    }

    private fun tryPaste(target: EditableTarget, text: String, options: SpliceOptions): Boolean {
        if (!target.isEditable) return false
        val before = target.readField()
        if (!target.paste()) return false
        return verify(target, before, expected(before, text, options))
    }

    /**
     * Compared loosely ([Landed]) on purpose: fields legitimately reformat what they are given
     * (phone numbers, amounts, line breaks), and the splicer may have capitalised the first
     * letter. A strict comparison would report working insertions as failures - and hand the
     * words to the next method, which puts them in twice. [before] is the field before
     * the write, null when unknown.
     */
    private fun verify(target: EditableTarget, before: FieldState?, text: String): Boolean {
        // Compose and many cross-platform fields apply a write on their next frame, so the
        // first read can still show the old text. Giving up on it would hand the same words
        // to the next strategy and insert them twice.
        val attempts = Landed.attempts(VERIFY_ATTEMPTS, text.length)
        repeat(attempts) { attempt ->
            val after = target.readBack() ?: return false
            if (Landed.inField(before, after, text)) return true
            if (attempt < attempts - 1) pause(VERIFY_INTERVAL_MS)
        }
        return false
    }

    companion object {
        const val UNKNOWN_PACKAGE = "unknown"

        /**
         * Five reads 60 ms apart: about four frames at 60 Hz, well past one recomposition. A
         * long text gets more, up to about 3 s ([Landed.attempts]).
         */
        private const val VERIFY_ATTEMPTS = 5
        private const val VERIFY_INTERVAL_MS = 60L

        /**
         * About 0.7 s: an input connection's round trip through the app, with room to spare.
         * A long text gets more, up to about 3 s ([Landed.attempts]).
         */
        private const val COMMIT_VERIFY_ATTEMPTS = 12
    }
}
