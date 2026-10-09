package java.awt

/** Headless stub: Android has no AWT. There is never an AWT dispatch thread. */
object EventQueue {
    @JvmStatic fun isDispatchThread(): Boolean = false
    @JvmStatic fun invokeLater(r: Runnable) { r.run() }
    @JvmStatic fun invokeAndWait(r: Runnable) { r.run() }
}
