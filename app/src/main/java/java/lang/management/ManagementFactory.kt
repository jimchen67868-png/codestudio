package java.lang.management

/**
 * Android's runtime has never shipped java.lang.management.* — it's the
 * JDK's JMX-based management/monitoring API (thread stats, memory pools,
 * GC beans, etc.), irrelevant to running apps and excluded from
 * Android's stripped-down core library.
 *
 * The Kotlin compiler's PerformanceManager uses ManagementFactory purely
 * to measure per-thread CPU time for its own internal build-performance
 * reporting — unrelated to actual compilation correctness. Without this
 * class, PerformanceManager's constructor throws NoClassDefFoundError
 * before any source file is compiled, the same category of issue as the
 * javax.lang.model.SourceVersion stub elsewhere in this package.
 *
 * The stub ThreadMXBean below reports CPU-time tracking as unsupported
 * (a real, valid state on genuine JVMs too — e.g. certain restricted/
 * embedded environments), which is expected to make PerformanceManager
 * skip the deeper time-querying calls gracefully rather than needing
 * every method to return a meaningful value.
 */
class ManagementFactory {
    companion object {
        @JvmStatic
        fun getThreadMXBean(): ThreadMXBean = StubThreadMXBean()
    }
}

interface PlatformManagedObject {
    fun getObjectName(): javax.management.ObjectName?
}

class ThreadInfo

interface ThreadMXBean : PlatformManagedObject {
    fun getThreadCount(): Int
    fun getPeakThreadCount(): Int
    fun getTotalStartedThreadCount(): Long
    fun getDaemonThreadCount(): Int
    fun getAllThreadIds(): LongArray
    fun getThreadInfo(id: Long): ThreadInfo?
    fun getThreadInfo(ids: LongArray): Array<ThreadInfo?>
    fun getThreadInfo(id: Long, maxDepth: Int): ThreadInfo?
    fun getThreadInfo(ids: LongArray, maxDepth: Int): Array<ThreadInfo?>
    fun isThreadContentionMonitoringSupported(): Boolean
    fun isThreadContentionMonitoringEnabled(): Boolean
    fun setThreadContentionMonitoringEnabled(enable: Boolean)
    fun getCurrentThreadCpuTime(): Long
    fun getCurrentThreadUserTime(): Long
    fun getThreadCpuTime(id: Long): Long
    fun getThreadUserTime(id: Long): Long
    fun isThreadCpuTimeSupported(): Boolean
    fun isCurrentThreadCpuTimeSupported(): Boolean
    fun isThreadCpuTimeEnabled(): Boolean
    fun setThreadCpuTimeEnabled(enable: Boolean)
    fun findMonitorDeadlockedThreads(): LongArray?
    fun resetPeakThreadCount()
    fun findDeadlockedThreads(): LongArray?
    fun isObjectMonitorUsageSupported(): Boolean
    fun isSynchronizerUsageSupported(): Boolean
    fun getThreadInfo(ids: LongArray, lockedMonitors: Boolean, lockedSynchronizers: Boolean): Array<ThreadInfo?>
    fun dumpAllThreads(lockedMonitors: Boolean, lockedSynchronizers: Boolean): Array<ThreadInfo?>
}

private class StubThreadMXBean : ThreadMXBean {
    override fun getObjectName(): javax.management.ObjectName? = null
    override fun getThreadCount() = 0
    override fun getPeakThreadCount() = 0
    override fun getTotalStartedThreadCount() = 0L
    override fun getDaemonThreadCount() = 0
    override fun getAllThreadIds(): LongArray = LongArray(0)
    override fun getThreadInfo(id: Long): ThreadInfo? = null
    override fun getThreadInfo(ids: LongArray): Array<ThreadInfo?> = arrayOfNulls(ids.size)
    override fun getThreadInfo(id: Long, maxDepth: Int): ThreadInfo? = null
    override fun getThreadInfo(ids: LongArray, maxDepth: Int): Array<ThreadInfo?> = arrayOfNulls(ids.size)
    override fun isThreadContentionMonitoringSupported() = false
    override fun isThreadContentionMonitoringEnabled() = false
    override fun setThreadContentionMonitoringEnabled(enable: Boolean) {}
    override fun getCurrentThreadCpuTime() = -1L
    override fun getCurrentThreadUserTime() = -1L
    override fun getThreadCpuTime(id: Long) = -1L
    override fun getThreadUserTime(id: Long) = -1L
    override fun isThreadCpuTimeSupported() = false
    override fun isCurrentThreadCpuTimeSupported() = false
    override fun isThreadCpuTimeEnabled() = false
    override fun setThreadCpuTimeEnabled(enable: Boolean) {}
    override fun findMonitorDeadlockedThreads(): LongArray? = null
    override fun resetPeakThreadCount() {}
    override fun findDeadlockedThreads(): LongArray? = null
    override fun isObjectMonitorUsageSupported() = false
    override fun isSynchronizerUsageSupported() = false
    override fun getThreadInfo(
        ids: LongArray,
        lockedMonitors: Boolean,
        lockedSynchronizers: Boolean
    ): Array<ThreadInfo?> = arrayOfNulls(ids.size)
    override fun dumpAllThreads(lockedMonitors: Boolean, lockedSynchronizers: Boolean): Array<ThreadInfo?> = arrayOfNulls(0)
}
