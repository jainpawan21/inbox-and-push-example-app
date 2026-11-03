package co.novu.examples.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage

private val Page = Color(0xFFF3F4F8)
private val CardWhite = Color.White
private val CardUnread = Color(0xFFF4F7FF)
private val Line = Color(0xFFE6E8EE)
private val LineUnread = Color(0xFFD5E2FF)
private val Ink = Color(0xFF16181D)
private val InkMuted = Color(0xFF5E6772)
private val InkFaint = Color(0xFF8B93A0)
private val Blue = Color(0xFF2F6FED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(model: InboxViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Page) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Page),
        ) {
            InboxHeader(model, onBack)
            model.inboxError?.let { message ->
                Text(
                    message,
                    color = Color(0xFFB42318),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            when {
                model.loading && model.notifications.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Blue)
                    }
                }
                model.notifications.isEmpty() -> EmptyInbox(model.showingArchived)
                else -> {
                    PullToRefreshBox(
                        isRefreshing = model.loading,
                        onRefresh = { model.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                        ) {
                            items(model.notifications, key = { it.id }) { notification ->
                                SwipeableNotification(model, notification)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxHeader(model: InboxViewModel, onBack: () -> Unit) {
    Column(Modifier.padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                if (model.showingArchived) "Archived" else "Inbox",
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                letterSpacing = (-0.6).sp,
            )
            Row(
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (model.connected) Color(0xFF12B76A) else Color(0xFFE0A100), CircleShape),
                )
                Text(
                    buildString {
                        append(if (model.connected) "Live" else "Reconnecting")
                        append(" · ")
                        append(headerSubtitle(model))
                    },
                    color = InkMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            FeedSwitch(model)
        }
    }
}

@Composable
private fun FeedSwitch(model: InboxViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFE7E9EF))
            .padding(4.dp),
    ) {
        FeedChoice("Inbox", selected = !model.showingArchived) { model.showArchived(false) }
        FeedChoice("Archived", selected = model.showingArchived) { model.showArchived(true) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FeedChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Ink else InkMuted,
        )
    }
}

@Composable
private fun EmptyInbox(archived: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Blue, modifier = Modifier.size(32.dp))
        }
        Text(
            if (archived) "Nothing archived" else "You're all caught up",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            if (archived) "Archived notifications will show up here." else "New notifications will show up here.",
            color = InkMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNotification(model: InboxViewModel, notification: InboxNotification) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && model.busyId == null) {
                model.toggleArchive(notification)
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Blue),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    modifier = Modifier.padding(end = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (notification.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (notification.isArchived) "Unarchive" else "Archive",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        NotificationCard(model, notification)
    }
}

@Composable
private fun NotificationCard(model: InboxViewModel, notification: InboxNotification) {
    val title = notification.displaySubject ?: notification.plainBody.ifBlank { "New message" }
    val preview = notification.plainBody.takeIf { notification.displaySubject != null && it.isNotEmpty() && it != title }
    val unread = !notification.isRead

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (unread) CardUnread else CardWhite)
            .border(1.dp, if (unread) LineUnread else Line, RoundedCornerShape(22.dp))
            .clickable { model.markRead(notification, read = true) }
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(notification, unread)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        color = Ink,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        notification.relativeTime,
                        color = InkFaint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp),
                    )
                }
                if (preview != null) {
                    Text(
                        preview,
                        color = InkMuted,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                notification.workflow?.name?.takeIf { it.isNotBlank() }?.let { workflow ->
                    Text(
                        workflow,
                        color = Blue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        if (model.busyId == notification.id) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(18.dp)
                    .align(Alignment.CenterHorizontally),
                strokeWidth = 2.dp,
                color = Blue,
            )
        } else {
            Row(
                modifier = Modifier.padding(start = 52.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionIcon(
                    icon = if (unread) Icons.Outlined.Drafts else Icons.Outlined.MarkEmailUnread,
                    label = if (unread) "Mark read" else "Mark unread",
                    tint = if (unread) Blue else InkMuted,
                    onClick = { model.markRead(notification, read = unread) },
                )
                ActionIcon(
                    icon = if (notification.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    label = if (notification.isArchived) "Unarchive" else "Archive",
                    tint = InkMuted,
                    onClick = { model.toggleArchive(notification) },
                )
                ActionIcon(
                    icon = Icons.Outlined.Delete,
                    label = "Delete",
                    tint = Color(0xFFB42318),
                    onClick = { model.delete(notification) },
                )
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFFF1F3F6))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Avatar(notification: InboxNotification, unread: Boolean) {
    Box {
        val url = notification.avatar?.takeIf { it.isNotBlank() }
        if (url == null) {
            Monogram(notification)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                loading = { Monogram(notification) },
                error = { Monogram(notification) },
            )
        }
        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .border(2.dp, if (unread) CardUnread else CardWhite, CircleShape)
                    .padding(2.dp)
                    .background(Blue, CircleShape),
            )
        }
    }
}

@Composable
private fun Monogram(notification: InboxNotification) {
    val letter = (notification.displaySubject ?: notification.plainBody.ifBlank { notification.sourceLabel })
        .firstOrNull()
        ?.uppercase()
        ?: "N"
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(Blue, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun headerSubtitle(model: InboxViewModel): String {
    if (model.showingArchived) return "Notifications you saved for later"
    return when (model.unreadCount) {
        0 -> "You're all caught up"
        1 -> "1 unread"
        else -> "${model.unreadCount} unread"
    }
}
