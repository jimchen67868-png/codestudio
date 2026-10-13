B = "app/src/main/java/com/example/aideclone/"

def edit(path, pairs):
    s = open(path).read()
    for a, b in pairs:
        assert s.count(a) == 1, "anchor: " + a[:60]
        s = s.replace(a, b)
    open(path, "w").write(s)

# 1. CompileEngine: optional source root + keep existing output
edit(B + "compiler/CompileEngine.kt", [
 ("fun compileProject(projectRoot: File, androidJar: File?, extraLibraries: List<File> = emptyList()): CompileResult {",
  "fun compileProject(projectRoot: File, androidJar: File?, extraLibraries: List<File> = emptyList(),\n        sourceRoot: File = projectRoot, keepOutput: Boolean = false): CompileResult {"),
 ("val sourceFiles = collectJavaFiles(projectRoot)", "val sourceFiles = collectJavaFiles(sourceRoot)"),
 ("            deleteRecursively()\n            mkdirs()\n", "            if (!keepOutput) deleteRecursively()\n            mkdirs()\n"),
])

# 2. KspRunner.runProject
RUN = r'''    class KspResult(val ran: Boolean, val success: Boolean, val log: String, val javaDir: File)

    fun runProject(context: Context, projectRoot: File, androidJar: File, stdlib: File, extraLibs: List<File>): KspResult {
        val javaDir = File(projectRoot, "build/generated/ksp/java")
        javaDir.deleteRecursively()
        val libs = (listOf(androidJar, stdlib) + extraLibs).distinctBy { it.name }
        if (libs.none { it.name.startsWith("room-runtime") }) return KspResult(false, true, "", javaDir)
        val out = StringBuilder()
        var ok = false
        try {
            val l = kspLoader(context)
            val tmp = File(context.cacheDir, "ksp-build").apply { deleteRecursively(); mkdirs() }
            val src = File(tmp, "src").apply { mkdirs() }
            projectRoot.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && !it.relativeTo(projectRoot).path.startsWith("build/") }
                .forEach { it.copyTo(File(src, it.relativeTo(projectRoot).path), true) }
            val builderClass = l.loadClass("com.google.devtools.ksp.processing.KSPJvmConfig\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            fun set(name: String, argType: Class<*>, value: Any?) {
                builderClass.getMethod(name, argType).invoke(builder, value)
            }
            set("setModuleName", String::class.java, "project")
            set("setSourceRoots", List::class.java, listOf(src))
            set("setLibraries", List::class.java, libs)
            set("setProjectBaseDir", File::class.java, tmp)
            set("setOutputBaseDir", File::class.java, File(tmp, "out"))
            set("setCachesDir", File::class.java, File(tmp, "caches"))
            set("setClassOutputDir", File::class.java, File(tmp, "out/classes"))
            set("setKotlinOutputDir", File::class.java, File(tmp, "out/kotlin"))
            set("setJavaOutputDir", File::class.java, File(tmp, "out/java"))
            set("setResourceOutputDir", File::class.java, File(tmp, "out/res"))
            set("setLanguageVersion", String::class.java, "2.3")
            set("setApiVersion", String::class.java, "2.3")
            set("setJvmTarget", String::class.java, "17")
            val config = builderClass.getMethod("build").invoke(builder)
            val loggerIface = l.loadClass("com.google.devtools.ksp.processing.KSPLogger")
            val logger = Proxy.newProxyInstance(l, arrayOf(loggerIface)) { _, m, args ->
                when (m.name) {
                    "hashCode" -> 0
                    "equals" -> false
                    "toString" -> "ksp-logger"
                    else -> { out.appendLine("KSP." + m.name + ": " + (args?.joinToString { java.util.Objects.toString(it) } ?: "")); null }
                }
            }
            val spClass = l.loadClass("com.google.devtools.ksp.impl.KotlinSymbolProcessing")
            val cfgClass = l.loadClass("com.google.devtools.ksp.processing.KSPConfig")
            val ctor = spClass.getConstructor(cfgClass, List::class.java, loggerIface)
            val pc = l.loadClass("androidx.room.RoomKspProcessor\$Provider")
            val ksp = ctor.newInstance(config, listOf(pc.getDeclaredConstructor().newInstance()), logger)
            val exit = spClass.getMethod("execute").invoke(ksp)
            out.appendLine("KSP exit=$exit")
            ok = exit.toString() == "OK"
            if (ok) {
                fixContinuationWildcards(File(tmp, "out/java"), out)
                File(tmp, "out/java").takeIf { it.exists() }?.copyRecursively(javaDir, true)
            }
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) (t.cause ?: t) else t
            out.appendLine("KSP FAILED: $cause")
            out.appendLine(cause.stackTraceToString().lines().take(30).joinToString("\n"))
        }
        return KspResult(true, ok, out.toString(), javaDir)
    }

'''
edit(B + "compiler/KspRunner.kt", [
 ("    fun selfTest(context: Context): String {", RUN + "    fun selfTest(context: Context): String {"),
])

# 3. MainActivity sequencing
OLD = '''                val result = if (hasKotlin) {
                    val stdlib = ensureKotlinStdlib()
                    KotlinCompileEngine.compileProject(applicationContext, project.rootDir, androidJar, stdlib, extraLibraries)
                } else {'''
NEW = r'''                val result = if (hasKotlin) {
                    val stdlib = ensureKotlinStdlib()
                    val ksp = androidJar?.takeIf { it.exists() }?.let {
                        com.example.aideclone.compiler.KspRunner.runProject(applicationContext, project.rootDir, it, stdlib, extraLibraries)
                    }
                    val kspPrefix = if (ksp != null && ksp.ran) "--- KSP ---\n" + ksp.log + "\n" else ""
                    if (ksp != null && ksp.ran && !ksp.success) {
                        com.example.aideclone.compiler.CompileResult(false, emptyList(), kspPrefix)
                    } else {
                        var r = KotlinCompileEngine.compileProject(applicationContext, project.rootDir, androidJar, stdlib, extraLibraries)
                        if (r.success && ksp != null && ksp.ran && ksp.javaDir.walkTopDown().any { it.isFile && it.extension == "java" }) {
                            val j = CompileEngine.compileProject(project.rootDir, androidJar, extraLibraries + File(project.rootDir, "build/classes"), ksp.javaDir, true)
                            r = com.example.aideclone.compiler.CompileResult(j.success, r.diagnostics + j.diagnostics,
                                r.rawOutput + "\n--- KSP generated Java ---\n" + j.rawOutput)
                        }
                        com.example.aideclone.compiler.CompileResult(r.success, r.diagnostics, kspPrefix + r.rawOutput)
                    }
                } else {'''
edit(B + "MainActivity.kt", [(OLD, NEW)])
print("ok")
