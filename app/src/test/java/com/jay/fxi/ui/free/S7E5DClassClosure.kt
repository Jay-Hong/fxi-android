package com.jay.fxi.ui.free

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * S7 E5d gap hunt r1 G13·G14 (`e5d_gaphunt.claude.r4.md`, 교체 D1): the bytecode closure the structure lock runs over the
 * preview's compiled classes. It is a test tool, not production code.
 *
 * A class file is read in full: constant pool, super class and interfaces, every method's `Code` with each instruction's
 * opcode, and the `BootstrapMethods` and `SourceFile` attributes. From the preview's own classes it follows every reference
 * the instructions make — invoke (with its kind and JVM descriptor), field get/put, `new`, `ldc` of a class or handle, and
 * each `invokedynamic` through its bootstrap — into app classes, whole class at a time, together with every app subclass or
 * implementation of an app type that is called virtually. What lies outside the app is reported as a [Use] key, for the
 * caller to compare with a reviewed list. Anything this reader cannot place — a `CONSTANT_Dynamic`, an app class with no
 * class file, a bootstrap other than the lambda and string-concatenation factories — is reported as unresolved, never
 * dropped.
 */
internal class S7E5DClassClosure(private val roots: List<File>) {

    /** One use of something outside the app: what kind of use, of whose member, under which descriptor. */
    data class Use(val kind: String, val owner: String, val member: String, val descriptor: String) {
        override fun toString() = "$kind $owner.$member $descriptor"
    }

    /** One function value invoked inside an app class: which class and method made the call. */
    data class CallbackSite(val owner: String, val method: String, val target: String)

    class Result(
        val appClasses: Set<String>,
        val uses: Set<Use>,
        val callbackSites: List<CallbackSite>,
        val staticWrites: Set<String>,
        val unresolved: Set<String>,
        val sourceOf: Map<String, String>
    )

    private class ClassFile(
        val name: String,
        val superName: String?,
        val interfaces: List<String>,
        val sourceFile: String?,
        val refs: List<Ref>,
        val unresolved: List<String>
    )

    private sealed interface Ref {
        data class Member(val kind: String, val owner: String, val name: String, val desc: String, val inMethod: String) : Ref
        data class TypeOnly(val owner: String) : Ref
        data class Indy(val bootstrapOwner: String, val bootstrapName: String, val samType: String, val implOwner: String?, val implName: String?, val inMethod: String) : Ref
    }

    /** Every class file under the roots, a directory or a jar (the variant's runtime classes come as a jar), by internal name. */
    private val byName: Map<String, () -> ByteArray> by lazy {
        val out = LinkedHashMap<String, () -> ByteArray>()
        for (root in roots) {
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
                    out.putIfAbsent(f.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class")) { f.readBytes() }
                }
            } else if (root.isFile && root.name.endsWith(".jar")) {
                ZipFile(root).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { e ->
                        val bytes = zip.getInputStream(e).readBytes()
                        out.putIfAbsent(e.name.removeSuffix(".class")) { bytes }
                    }
                }
            }
        }
        out
    }

    private val parsed = HashMap<String, ClassFile>()
    private fun parse(name: String): ClassFile? = parsed[name] ?: byName[name]?.let { bytes -> read(name, bytes()).also { parsed[name] = it } }

    /** Every app class whose super class or interfaces name [type], directly or through another app class. */
    private val subtypes: Map<String, Set<String>> by lazy {
        val direct = HashMap<String, MutableSet<String>>()
        for (n in byName.keys) {
            if (!isApp(n)) continue
            val c = parse(n) ?: continue
            (listOfNotNull(c.superName) + c.interfaces).forEach { direct.getOrPut(it) { mutableSetOf() } += n }
        }
        fun all(t: String, seen: MutableSet<String> = mutableSetOf()): Set<String> {
            for (s in direct[t].orEmpty()) if (seen.add(s)) all(s, seen)
            return seen
        }
        direct.keys.associateWith { all(it) }
    }

    /**
     * The classes compiled from one source: those in [packageDir] whose `SourceFile` is [basename], and every class nested in
     * one of them by name (`Outer$…`). The second part matters: an object an inline function creates keeps the inline
     * function's own `SourceFile` (`…$$inlined$onDispose$1` says `Effects.kt`), so the attribute alone would miss it.
     */
    fun classesOf(packageDir: String, basename: String): List<String> {
        val inDir = byName.keys.filter { it.substringBeforeLast('/') == packageDir }
        val own = inDir.filter { parse(it)?.sourceFile == basename }.toSet()
        return inDir.filter { n -> n in own || own.any { n.startsWith("$it$") } }.sorted()
    }

    /** The source a class belongs to: its outermost class's, when there is one, else its own `SourceFile`. */
    private fun sourceFor(name: String, own: ClassFile): String? {
        val outer = name.substringBefore('$')
        val file = (if (outer != name) parse(outer)?.sourceFile else null) ?: own.sourceFile ?: return null
        return name.substringBeforeLast('/') + "/" + file
    }

    fun close(rootClasses: Collection<String>): Result {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(rootClasses)
        val uses = LinkedHashSet<Use>()
        val callbacks = mutableListOf<CallbackSite>()
        val staticWrites = LinkedHashSet<String>()
        val unresolved = LinkedHashSet<String>()
        val sourceOf = LinkedHashMap<String, String>()
        fun include(owner: String) {
            if (!isApp(owner)) return
            if (owner in seen || owner in queue) return
            queue.addLast(owner)
        }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            if (isResource(name)) continue
            val c = parse(name)
            if (c == null) { unresolved += "no class file for app class $name"; continue }
            sourceFor(name, c)?.let { sourceOf[name] = it }
            unresolved += c.unresolved.map { "$name: $it" }
            for (t in listOfNotNull(c.superName) + c.interfaces) {
                if (isApp(t)) include(t) else uses += Use("extends", t, "", "")
            }
            for (r in c.refs) when (r) {
                is Ref.TypeOnly -> include(r.owner)
                is Ref.Member -> {
                    // A static write anywhere but the class's own initialiser is mutable process-wide state: a top-level var, an
                    // object's property, another class's field. Each one is reported for review, not accepted by default.
                    if (r.kind == "putstatic" && (r.owner != name || !r.inMethod.startsWith("<clinit>"))) {
                        staticWrites += "$name.${r.inMethod} -> ${r.owner}.${r.name}"
                    }
                    if (isResource(r.owner)) uses += Use("resource", r.owner, r.name, r.desc)
                    else if (isApp(r.owner)) {
                        include(r.owner)
                        if (r.kind == "invokevirtual" || r.kind == "invokeinterface") subtypes[r.owner].orEmpty().forEach(::include)
                    } else {
                        uses += Use(r.kind, r.owner, r.name, r.desc)
                        if (r.kind == "invokeinterface" && r.owner.startsWith("kotlin/jvm/functions/Function") && r.name == "invoke") {
                            callbacks += CallbackSite(name, r.inMethod, r.owner)
                        }
                    }
                }
                is Ref.Indy -> {
                    val factory = "${r.bootstrapOwner}.${r.bootstrapName}"
                    when (factory) {
                        "java/lang/invoke/LambdaMetafactory.metafactory", "java/lang/invoke/LambdaMetafactory.altMetafactory" -> {
                            uses += Use("indy-sam", r.samType, "", "")
                            val impl = r.implOwner
                            if (impl == null) unresolved += "$name.${r.inMethod}: lambda without an implementation handle"
                            else if (isApp(impl)) include(impl) else uses += Use("indy-impl", impl, r.implName.orEmpty(), "")
                        }
                        "java/lang/invoke/StringConcatFactory.makeConcatWithConstants" -> uses += Use("indy-concat", "java/lang/invoke/StringConcatFactory", "", "")
                        else -> unresolved += "$name.${r.inMethod}: bootstrap $factory"
                    }
                }
            }
        }
        return Result(seen.filterNot(::isResource).toSet(), uses, callbacks, staticWrites, unresolved, sourceOf)
    }

    private fun isApp(n: String) = n.startsWith("com/jay/fxi/")
    private fun isResource(n: String) = Regex("""com/jay/fxi/R(\$\w+)?""").matches(n)

    private fun read(file: String, bytes: ByteArray): ClassFile {
        val inp = DataInputStream(bytes.inputStream())
        require(inp.readInt() == 0xCAFEBABE.toInt()) { "not a class file: $file" }
        inp.readUnsignedShort(); inp.readUnsignedShort()
        val n = inp.readUnsignedShort()
        val tag = IntArray(n)
        val a = IntArray(n)
        val b = IntArray(n)
        val utf = arrayOfNulls<String>(n)
        val unresolved = mutableListOf<String>()
        var i = 1
        while (i < n) {
            val t = inp.readUnsignedByte()
            tag[i] = t
            when (t) {
                1 -> utf[i] = inp.readUTF()
                3, 4 -> inp.readInt()
                5, 6 -> { inp.readLong(); i++ }
                7, 8, 16, 19, 20 -> a[i] = inp.readUnsignedShort()
                9, 10, 11, 12, 18 -> { a[i] = inp.readUnsignedShort(); b[i] = inp.readUnsignedShort() }
                17 -> { a[i] = inp.readUnsignedShort(); b[i] = inp.readUnsignedShort(); unresolved += "CONSTANT_Dynamic at #$i" }
                15 -> { a[i] = inp.readUnsignedByte(); b[i] = inp.readUnsignedShort() }
                else -> error("unknown constant tag $t in $file")
            }
            i++
        }
        fun cls(idx: Int) = utf[a[idx]]!!
        fun nat(idx: Int) = utf[a[idx]]!! to utf[b[idx]]!!
        inp.readUnsignedShort()
        val name = cls(inp.readUnsignedShort())
        val superIdx = inp.readUnsignedShort()
        val superName = if (superIdx == 0) null else cls(superIdx)
        val interfaces = List(inp.readUnsignedShort()) { cls(inp.readUnsignedShort()) }
        fun skipAttributes() = repeat(inp.readUnsignedShort()) { inp.readUnsignedShort(); inp.skipNBytes(inp.readInt().toLong()) }
        repeat(inp.readUnsignedShort()) { repeat(3) { inp.readUnsignedShort() }; skipAttributes() }
        val codes = mutableListOf<Pair<String, ByteArray>>()
        repeat(inp.readUnsignedShort()) {
            inp.readUnsignedShort()
            val mName = utf[inp.readUnsignedShort()]!!
            val mDesc = utf[inp.readUnsignedShort()]!!
            repeat(inp.readUnsignedShort()) {
                val attr = utf[inp.readUnsignedShort()]
                val len = inp.readInt()
                val body = ByteArray(len).also { inp.readFully(it) }
                if (attr == "Code") codes += "$mName$mDesc" to body
            }
        }
        var sourceFile: String? = null
        val bootstraps = mutableListOf<Pair<Int, List<Int>>>()
        repeat(inp.readUnsignedShort()) {
            val attr = utf[inp.readUnsignedShort()]
            val len = inp.readInt()
            val body = ByteArray(len).also { inp.readFully(it) }
            val d = DataInputStream(body.inputStream())
            when (attr) {
                "SourceFile" -> sourceFile = utf[d.readUnsignedShort()]
                "BootstrapMethods" -> repeat(d.readUnsignedShort()) {
                    val handle = d.readUnsignedShort()
                    bootstraps += handle to List(d.readUnsignedShort()) { d.readUnsignedShort() }
                }
            }
        }
        fun memberOf(idx: Int): Triple<String, String, String> { val (mn, md) = nat(b[idx]); return Triple(cls(a[idx]), mn, md) }
        val refs = mutableListOf<Ref>()
        for ((method, body) in codes) {
            val d = DataInputStream(body.inputStream())
            d.readUnsignedShort(); d.readUnsignedShort()
            val code = ByteArray(d.readInt()).also { d.readFully(it) }
            var pc = 0
            fun u1(at: Int) = code[at].toInt() and 0xFF
            fun u2(at: Int) = (u1(at) shl 8) or u1(at + 1)
            fun s4(at: Int) = (u1(at) shl 24) or (u1(at + 1) shl 16) or (u1(at + 2) shl 8) or u1(at + 3)
            while (pc < code.size) {
                val op = u1(pc)
                when (op) {
                    0xB2, 0xB3, 0xB4, 0xB5 -> { val (o, m, ds) = memberOf(u2(pc + 1)); refs += Ref.Member(listOf("getstatic", "putstatic", "getfield", "putfield")[op - 0xB2], o, m, ds, method) }
                    0xB6, 0xB7, 0xB8, 0xB9 -> { val (o, m, ds) = memberOf(u2(pc + 1)); refs += Ref.Member(listOf("invokevirtual", "invokespecial", "invokestatic", "invokeinterface")[op - 0xB6], o, m, ds, method) }
                    0xBA -> {
                        val idx = u2(pc + 1)
                        val (samName, samDesc) = nat(b[idx])
                        val (handle, args) = bootstraps[a[idx]]
                        val (bo, bn, _) = memberOf(b[handle])
                        val implHandle = args.getOrNull(1)?.takeIf { tag[it] == 15 }
                        val impl = implHandle?.let { memberOf(b[it]) }
                        refs += Ref.Indy(bo, bn, samDesc.substringAfterLast(")").removePrefix("L").removeSuffix(";") + "#" + samName, impl?.first, impl?.second, method)
                    }
                    0xBB, 0xBD, 0xC0, 0xC1 -> refs += Ref.TypeOnly(cls(u2(pc + 1)).trimStart('[').removePrefix("L").removeSuffix(";"))
                    0xC5 -> refs += Ref.TypeOnly(cls(u2(pc + 1)).trimStart('[').removePrefix("L").removeSuffix(";"))
                    0x12, 0x13 -> {
                        val idx = if (op == 0x12) u1(pc + 1) else u2(pc + 1)
                        when (tag[idx]) {
                            7 -> refs += Ref.TypeOnly(cls(idx).trimStart('[').removePrefix("L").removeSuffix(";"))
                            15 -> { val (o, m, ds) = memberOf(b[idx]); refs += Ref.Member("ldc-handle", o, m, ds, method) }
                            17 -> unresolved += "$method: ldc of a dynamic constant"
                        }
                    }
                }
                pc += when (op) {
                    0xAA -> { val p = (pc + 4) and 3.inv(); val low = s4(p + 4); val high = s4(p + 8); p + 12 + (high - low + 1) * 4 - pc }
                    0xAB -> { val p = (pc + 4) and 3.inv(); p + 8 + s4(p + 4) * 8 - pc }
                    0xC4 -> if (u1(pc + 1) == 0x84) 6 else 4
                    else -> 1 + OPERANDS[op]
                }
            }
        }
        return ClassFile(name, superName, interfaces, sourceFile, refs, unresolved)
    }

    private companion object {
        /** Operand bytes per opcode, for the fixed-length instructions (JVMS §6.5). */
        val OPERANDS = IntArray(256).also { o ->
            o[0x10] = 1; o[0x11] = 2; o[0x12] = 1; o[0x13] = 2; o[0x14] = 2
            for (x in 0x15..0x19) o[x] = 1
            for (x in 0x36..0x3A) o[x] = 1
            o[0x84] = 2
            for (x in 0x99..0xA8) o[x] = 2
            o[0xA9] = 1
            for (x in 0xB2..0xB8) o[x] = 2
            o[0xB9] = 4; o[0xBA] = 4; o[0xBB] = 2; o[0xBC] = 1; o[0xBD] = 2; o[0xC0] = 2; o[0xC1] = 2; o[0xC5] = 3
            o[0xC6] = 2; o[0xC7] = 2; o[0xC8] = 4; o[0xC9] = 4
        }
    }
}
