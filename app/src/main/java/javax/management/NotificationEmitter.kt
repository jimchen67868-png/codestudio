package javax.management

interface NotificationEmitter {
    fun addNotificationListener(listener: NotificationListener, filter: NotificationFilter?, handback: Any?)
    fun removeNotificationListener(listener: NotificationListener)
    fun removeNotificationListener(listener: NotificationListener, filter: NotificationFilter?, handback: Any?)
}
