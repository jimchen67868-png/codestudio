package org.sqlite

class JDBC : java.sql.Driver {
    companion object {
        @JvmStatic
        fun createConnection(url: String, p: java.util.Properties): SQLiteConnection {
            throw java.sql.SQLException("sqlite verification unavailable on-device")
        }
    }
    override fun connect(u: String?, i: java.util.Properties?): java.sql.Connection? = null
    override fun acceptsURL(u: String?): Boolean = false
    override fun getPropertyInfo(u: String?, i: java.util.Properties?): Array<java.sql.DriverPropertyInfo> = arrayOf()
    override fun getMajorVersion(): Int = 0
    override fun getMinorVersion(): Int = 0
    override fun jdbcCompliant(): Boolean = false
    override fun getParentLogger(): java.util.logging.Logger = throw java.sql.SQLFeatureNotSupportedException()
}
