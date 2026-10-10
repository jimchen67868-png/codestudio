package javax.management

interface NotificationFilter {
    fun isNotificationEnabled(notification: Notification): Boolean
}
