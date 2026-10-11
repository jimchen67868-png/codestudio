package javax.tools

interface JavaFileManager {
    interface Location {
        fun getName(): String
        fun isOutputLocation(): Boolean
    }
}
