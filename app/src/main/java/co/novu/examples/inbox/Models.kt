package co.novu.examples.inbox

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

const val INBOX_LINK = "novu-example://notifications"
const val DEEPLINK_EXTRA = "deeplink"

fun isInboxLink(value: String?): Boolean = value == INBOX_LINK

@Serializable
data class InboxBootstrap(
    val subscriberId: String,
    val subscriberHash: String,
    val applicationIdentifier: String,
    val inboxApiUrl: String,
    val socketUrl: String,
)

@Serializable
data class InboxNotification(
    val id: String,
    val subject: String? = null,
    val body: String = "",
    val isRead: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: String = "",
    val avatar: String? = null,
    val severity: String = "none",
    val workflow: WorkflowSnippet? = null,
) {
    val sourceLabel: String
        get() = workflow?.name?.takeIf { it.isNotBlank() } ?: "Novu"

    val displaySubject: String?
        get() = subject?.trim()?.takeIf { it.isNotEmpty() }

    val plainBody: String
        get() = body.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    val relativeTime: String
        get() {
            val instant = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return createdAt
            val seconds = Duration.between(instant, Instant.now()).seconds
            return when {
                seconds < 45 -> "now"
                seconds < 3600 -> "${seconds / 60}m ago"
                seconds < 86_400 -> "${seconds / 3600}h ago"
                else -> "${seconds / 86_400}d ago"
            }
        }
}

@Serializable
data class WorkflowSnippet(val name: String = "")

@Serializable
data class NotificationsPage(
    val data: List<InboxNotification> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
data class UnreadCountPage(val data: List<UnreadCountItem> = emptyList())

@Serializable
data class UnreadCountItem(val count: Int = 0)

@Serializable
data class DataEnvelope<T>(val data: T)

@Serializable
data class SessionToken(val token: String)
