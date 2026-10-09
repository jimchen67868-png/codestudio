package javax.swing

/** Headless stub: nothing is ever on a Swing event thread; work runs inline. */
object SwingUtilities {
    @JvmStatic fun isEventDispatchThread(): Boolean = false
    @JvmStatic fun invokeLater(r: Runnable) { r.run() }
    @JvmStatic fun invokeAndWait(r: Runnable) { r.run() }
}
