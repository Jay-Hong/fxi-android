package com.jay.fxi

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** HEAD-compatible byte locator. Masking finds boundaries only; no masked text enters frozen bytes. */
internal object S6Krx3cShellSourceContract {
    const val HEAD = "0302b582e5aa22ca1223bee229a5c9d44947ef22"
    const val HEAD_TREE = "5fca04cdcb25376f93bf6b383dfb7fcbf6cfab22"
    data class Span(val source: String, val start: Int, val end: Int, val raw: String)
    val HEAD_SOURCE_SHA = mapOf(
        "MainActivity.kt" to "151553f6c6cfdc8bc56349b51d49f8cdf60d14ba742ce2d21e90e476f09f95c6",
        "ui/screen/RootScreen.kt" to "dec9cdd3b3e43800b4b46b672e5305ad9dc3c9efa32485f58a1fe609410ab743",
        "service/FXiMessagingService.kt" to "ec3f37863b38219e14fa40a09d84bdaa837db5c5a39e08b32e8a023888ad51c5")
    val FILES = listOf("MainActivity.kt", "ui/screen/RootScreen.kt", "service/FXiMessagingService.kt")
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    /** Kotlin comments nest; braces in ordinary/triple strings, escaped chars and comments are inert. */
    fun mask(source: String, literals: Boolean = true): String {
        val out = source.toCharArray(); var i = 0
        fun blank(from: Int, to: Int) { for (n in from until to) if (out[n] != '\n' && out[n] != '\r') out[n] = ' ' }
        while (i < source.length) {
            val start = i
            when {
                source.startsWith("//", i) -> { while (i < source.length && source[i] != '\n') i++; blank(start, i) }
                source.startsWith("/*", i) -> {
                    i += 2; var depth = 1
                    while (i < source.length && depth > 0) {
                        when { source.startsWith("/*", i) -> { depth++; i += 2 }; source.startsWith("*/", i) -> { depth--; i += 2 }; else -> i++ }
                    }
                    require(depth == 0) { "unclosed Kotlin comment" }; blank(start, i)
                }
                source[i] == '"' || source[i] == '\'' || source[i] == '`' -> {
                    i = literalEnd(source, i)
                    if (literals) blank(start, i)
                }
                else -> i++
            }
        }
        return String(out)
    }
    private fun commentEnd(source: String, begin: Int): Int {
        var i = begin + 2; var depth = 1
        while (i < source.length && depth > 0) {
            when { source.startsWith("/*", i) -> { depth++; i += 2 }; source.startsWith("*/", i) -> { depth--; i += 2 }; else -> i++ }
        }
        require(depth == 0) { "unclosed template comment" }; return i
    }
    private fun expressionEnd(source: String, begin: Int): Int {
        var i = begin + 1; var depth = 1
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> { while (i < source.length && source[i] != '\n') i++ }
                source.startsWith("/*", i) -> i = commentEnd(source, i)
                source[i] == '"' || source[i] == '\'' || source[i] == '`' -> i = literalEnd(source, i)
                source[i] == '{' -> { depth++; i++ }
                source[i] == '}' -> { depth--; i++; if (depth == 0) return i }
                else -> i++
            }
        }
        error("unclosed Kotlin template expression")
    }
    private fun literalEnd(source: String, begin: Int): Int {
        val quote = source[begin]
        val triple = quote == '"' && source.startsWith("\"\"\"", begin)
        val width = if (triple) 3 else 1
        var i = begin + width
        while (i < source.length) {
            if (triple && source.startsWith("\"\"\"", i)) return i + 3
            if (!triple && source[i] == quote) return i + 1
            if (!triple && quote != '`' && source[i] == '\\') { i += 2; continue }
            if (quote == '"' && source[i] == '$' && source.getOrNull(i + 1) == '{') { i = expressionEnd(source, i + 1); continue }
            i++
        }
        error("unclosed Kotlin literal")
    }
    fun close(source: String, open: Int, left: Char = '{', right: Char = '}'): Int {
        val code = mask(source); require(open in code.indices && code[open] == left) { "opening delimiter missing" }
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == left) depth++
            // Explicit decrement only on the right delimiter.
            if (code[i] == right) { depth--; if (depth == 0) return i }
        }
        error("unclosed delimiter")
    }
    private fun block(source: String, open: Int, path: String): Span {
        val end = close(source, open)
        return Span(path, open + 1, end, source.substring(open + 1, end))
    }
    fun function(source: String, name: String, path: String = name): Span {
        val code = mask(source)
        val matches = Regex("\\bfun\\s+${Regex.escape(name)}\\s*\\(").findAll(code).toList()
        require(matches.size == 1) { "expected one function $name, found ${matches.size}" }
        val paren = code.indexOf('(', matches.single().range.first)
        val argsEnd = close(source, paren, '(', ')')
        val open = code.indexOf('{', argsEnd)
        require(open >= 0 && !code.substring(argsEnd + 1, open).contains('=')) { "expected block function $name" }
        return block(source, open, path)
    }
    private fun effect(source: String): Span {
        val code = mask(source)
        val match = Regex("LaunchedEffect\\s*\\(\\s*pendingEvent\\s*\\)").find(code) ?: error("pending effect missing")
        return block(source, code.indexOf('{', match.range.last), "ui/screen/RootScreen.kt")
    }
    private fun off(source: String, span: Span, root: Boolean): Span {
        val code = mask(span.raw)
        val guard = if (root) Regex("if\\s*\\(\\s*alertEventBus\\?\\.pendingTapEnabled\\s*==\\s*true\\s*\\)")
            else Regex("if\\s*\\(\\s*krxContestSwitch\\.enabled\\s*\\)")
        val hits = guard.findAll(code).toList()
        if (hits.isEmpty()) return span // clean HEAD, and unchanged shared fragments.
        require(hits.size == 1) { "mode branch multiplicity" }
        val onOpen = code.indexOf('{', hits.single().range.last)
        val onEnd = close(span.raw, onOpen)
        val rest = code.substring(onEnd + 1)
        val alternate = Regex("^\\s*else\\s*\\{").find(rest) ?: error("explicit OFF branch missing")
        val offOpen = onEnd + 1 + alternate.range.last
        val local = block(span.raw, offOpen, span.source)
        return local.copy(start = span.start + local.start, end = span.start + local.end)
    }
    fun spans(sources: Map<String, String>, candidate: Boolean = false): Map<String, Span> {
        val activity = sources.getValue(FILES[0]); val root = sources.getValue(FILES[1]); val fcm = sources.getValue(FILES[2])
        val cold = function(activity, "parsePendingAlertEvent", FILES[0])
        val warm = function(activity, "handleNotificationIntent", FILES[0])
        val effect = effect(root)
        val builderStart = fcm.indexOf("        val settingId = message.data[\"setting_id\"]")
        require(builderStart >= 0) { "FCM original builder start missing" }
        val apply = mask(fcm).indexOf('{', mask(fcm).indexOf(".apply", builderStart))
        val builderEnd = close(fcm, apply) + 1
        val slotPattern = "{ _pendingAlertEvent.value = null }"
        val slotStart = activity.indexOf(slotPattern); require(slotStart >= 0) { "original slot callback missing" }
        fun textSpan(path: String, s: String, pattern: Regex): Span {
            val m = pattern.find(s) ?: error("missing auxiliary $pattern")
            return Span(path, m.range.first, m.range.last + 1, m.value)
        }
        return linkedMapOf(
            "cold" to if (candidate) off(activity, cold, false) else cold,
            "warm" to if (candidate) off(activity, warm, false) else warm,
            "Root" to if (candidate) off(root, effect, true) else effect,
            "FCM" to Span(FILES[2], builderStart, builderEnd, fcm.substring(builderStart, builderEnd)),
            "parser" to function(activity, "parseAlertEventFromIntent", FILES[0]),
            "clear" to function(activity, "clearNotificationExtras", FILES[0]),
            "slot" to Span(FILES[0], slotStart, slotStart + slotPattern.length, slotPattern),
            "saved-admission" to textSpan(FILES[0], activity, Regex("if \\(ReleaseAdmission.isOpen && savedInstanceState == null\\) \\{\\n            parsePendingAlertEvent\\(intent\\)\\n        }")),
            "warm-admission" to textSpan(FILES[0], activity, Regex("if \\(!ReleaseAdmission.isOpen\\) return"))
        )
    }
    fun extract(sourceRoot: File, destination: File) {
        val sources = FILES.associateWith { File(sourceRoot, it).readText(Charsets.UTF_8) }
        require(sources.all { (name, text) -> sha(text.toByteArray(Charsets.UTF_8)) == HEAD_SOURCE_SHA.getValue(name) }) { "source bytes are not exact0302 HEAD" }
        val selected = spans(sources)
        require(!destination.exists()) { "immutable output already exists" }
        Files.createDirectories(destination.toPath())
        val index = buildJsonObject {
            put("schema", 1); put("head", HEAD); put("evidence_kind", "STATIC_ORIGINAL")
            put("sources", buildJsonObject { sources.forEach { (name, source) -> put(name, sha(source.toByteArray(Charsets.UTF_8))) } })
            put("spans", buildJsonObject {
                selected.forEach { (name, span) ->
                    val bytes = span.raw.toByteArray(Charsets.UTF_8)
                    Files.write(File(destination, "$name.raw").toPath(), bytes, StandardOpenOption.CREATE_NEW)
                    put(name, buildJsonObject {
                        put("source", span.source); put("offset", sources.getValue(span.source).substring(0, span.start).toByteArray(Charsets.UTF_8).size)
                        put("length", bytes.size); put("sha256", sha(bytes))
                    })
                }
            })
        }
        Files.write(File(destination, "index.json").toPath(), index.toString().toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW)
    }
    fun load(root: File = File("src/main/java/com/jay/fxi")): Map<String, String> = FILES.associateWith { File(root, it).readText() }
    private fun compact(source: String) = mask(source, false).replace(Regex("\\s+"), "")
    private fun calls(source: String, name: String): Int = Regex("\\b${Regex.escape(name)}\\s*\\(").findAll(mask(source)).count()
    /** Narrow source grammar: mode branch, exact production call, typed adapter, no hidden side effects. */
    fun wiring(sources: Map<String, String>): List<String> {
        val issues = mutableListOf<String>()
        fun check(ok: Boolean, issue: String) { if (!ok) issues += issue }
        try {
            val activity = sources.getValue(FILES[0]); val root = sources.getValue(FILES[1]); val fcm = sources.getValue(FILES[2])
            val cold = function(activity, "parsePendingAlertEvent").raw
            val warm = function(activity, "handleNotificationIntent").raw
            val effect = effect(root).raw
            for ((label, body, call) in listOf(Triple("cold", cold, "intakeNew"), Triple("warm", warm, "intakeWarm"), Triple("Root", effect, "forwardRoot"))) {
                val guard = if (label == "Root") "if(alertEventBus?.pendingTapEnabled==true)" else "if(krxContestSwitch.enabled)"
                check(compact(body).startsWith(guard), "$label exact enabled guard")
                check(calls(body, call) == 1 && compact(body).contains("AlertTapEntry.$call("), "$label production pure call exactly once")
                val masked = mask(body); val firstOpen = masked.indexOf('{')
                if (firstOpen >= 0) {
                    val on = body.substring(firstOpen + 1, close(body, firstOpen))
                    check(calls(on, call) == 1, "$label call must be in ON branch")
                    val callNames = Regex("""\b([A-Za-z_][A-Za-z_0-9]*)\s*\(""").findAll(mask(on)).map { it.groupValues[1] }.toList()
                    val allowed = if (label == "Root") setOf("forwardRoot", "forwardPendingTap", "clearSlot", "onPendingAlertEventConsumed", "if", "let", "when")
                        else setOf(call, "AndroidAlertTapExtras", "pendingTapIntake", "get", "PendingTap", "if", "let", "when")
                    check(callNames.all { it in allowed }, "$label closed ON call inventory: ${callNames.filter { it !in allowed }}")
                    check(!Regex("""\.(?:accessSnapshot|currentIdentityFence|krxPermitSeq|krxContestView)\b""").containsMatchIn(mask(on)), "$label no extra live/snapshot predicate")
                    if (label != "Root") {
                        check(compact(on).contains("AndroidAlertTapExtras(intent)"), "$label exact intent adapter")
                        check(compact(on).contains(".pendingTapIntake()"), "$label bound singleton bus intake")
                        check(calls(on, "emit") == 0 && calls(on, "clearNotificationExtras") == 0, "$label no emit/clear bypass")
                        if (label == "cold") {
                            check(compact(on).contains("AlertTapCaptureResult.CAPTURED"), "cold uses custody result")
                            check(compact(on).contains("_pendingAlertEvent.value=AlertEvent.PendingTap("), "cold slot holds ticket only")
                            check(Regex("intakeNew\\s*\\(\\s*AndroidAlertTapExtras\\(intent\\)\\s*,\\s*false\\s*,").containsMatchIn(mask(on)), "cold restored argument false under saved==null")
                        }
                    } else {
                        check(compact(on).contains("pendingEvent"), "Root forwards actual current event")
                        check(compact(on).contains(".forwardPendingTap(") && compact(on).contains(".clearSlot("), "Root uses custody dispatch and conditional slot operation")
                        check(calls(on, "emit") == 0, "Root no direct emit bypass")
                    }
                }
                off(if (label == "Root") root else activity, if (label == "Root") effect(root) else function(activity, if (label == "cold") "parsePendingAlertEvent" else "handleNotificationIntent"), label == "Root")
            }
            val show = function(fcm, "showAlertNotification").raw
            val buildEnd = close(show, mask(show).indexOf('{', mask(show).indexOf(".apply")))
            val copier = mask(show).indexOf("copyForegroundClassification")
            check(calls(show, "copyForegroundClassification") == 1 && copier > buildEnd && copier < mask(show).indexOf("PendingIntent.getActivity"), "FCM copier after original builder before PendingIntent")
            check(compact(show).contains("if(krxContestSwitch.enabled){AlertTapEntry.copyForegroundClassification(message.data,AndroidAlertTapExtras(intent))}"), "FCM exact guarded raw Map copier")
            check(calls(function(fcm, "onMessageReceived").raw, "emit") == 1, "foreground immediate receive remains exactly one emit")
            val all = sources.values.joinToString("\n")
            check(!Regex("\\b(?:cancelAll|cancel|currentSnapshot|fetchToken|refreshAfterUnauthorized|refresh|acquireKrxUse|route)\\s*\\(").containsMatchIn(mask(cold + warm + effect)), "native shell no cancellation/lookup/classification")
            check(calls(all, "intakeNew") == 1 && calls(all, "intakeWarm") == 1 && calls(all, "forwardRoot") == 1 && calls(all, "copyForegroundClassification") == 1, "no extra helper outside guarded shells")
            check(!Regex("\\b(?:cancelAll|cancel)\\s*\\(").containsMatchIn(mask(activity + fcm)), "no untapped tray cancellation")
            // Auxiliary conditions and callbacks are also required by the raw-span locator.
            spans(sources, true)
        } catch (failure: Throwable) { issues += "shell parse: ${failure.message}" }
        return issues
    }
    fun consumer(source: String): Boolean {
        val code = mask(source)
        val names = listOf("consumePendingTap", "registerPendingTapConsumer")
        return names.all { calls(code, it) == 1 } && code.contains("finally") &&
            Regex("""\.close\s*\(""").containsMatchIn(code) && code.contains("pendingTapEnabled") &&
            code.contains("HANDLED") && code.contains("UNHANDLED") &&
            !Regex("""null\s*->\s*AlertTapReceipt\.HANDLED""").containsMatchIn(code)
    }
    fun adapter(source: String): Boolean {
        val s = compact(source)
        val mappings = listOf("intent.getIntExtra(key,default)", "intent.getStringExtra(key)", "intent.hasExtra(key)",
            "intent.extras?.get(key)", "intent.removeExtra(key)", "intent.putExtra(key,value)")
        val methods = Regex("""\bfun\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(""").findAll(mask(source)).map { it.groupValues[1] }.toList()
        return methods == listOf("readInt", "readString", "kind", "remove", "putString") && mappings.all(s::contains) && !Regex("\\b(?:AlertPushRouting|PendingAlertTapOwner|capture|classify|emit|Provider|http|repository)\\b").containsMatchIn(mask(source)) &&
            listOf("MISSING", "NULL", "STRING", "INT", "OTHER").all { s.contains("AlertTapExtraKind.$it") }
    }
}
