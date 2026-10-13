p = "app/src/main/java/com/example/aideclone/compiler/KspRunner.kt"
s = open(p).read()

anchor = '            out.appendLine("RESULT exit=$exit after ${System.currentTimeMillis() - started} ms")\n'
assert s.count(anchor) == 1, "RESULT anchor"
assert "fixContinuationWildcards" not in s, "already patched"

call = '            fixContinuationWildcards(File(tmp, "out/java"), out)\n'
s = s.replace(anchor, anchor + call)

func = '''
// Room 2.6.1 on-device drops the "? super" wildcard on the synthetic
// $completion parameter, which javac rejects as a name clash. Restore it.
private fun fixContinuationWildcards(dir: File, out: StringBuilder) {
    if (!dir.exists()) return
    val pat = Regex("""Continuation<(?!\\?)(.+)>(\\s+)[$]completion\\b""")
    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.forEach { f ->
        val text = f.readText()
        var n = 0
        val fixed = pat.replace(text) { m ->
            n++
            "Continuation<? super " + m.groupValues[1] + ">" + m.groupValues[2] + "\\$completion"
        }
        if (n > 0) {
            f.writeText(fixed)
            out.appendLine("fixed " + n + " continuation params in " + f.name)
        } else if (text.contains("Continuation<") && !text.contains("Continuation<? super")) {
            out.appendLine("WARN: " + f.name + " has Continuation<> but no param matched")
        }
    }
}
'''
s = s.rstrip("\n") + "\n" + func
open(p, "w").write(s)
print("ok")
