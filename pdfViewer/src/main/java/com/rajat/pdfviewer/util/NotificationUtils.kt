package at.gv.brz.attachmentViewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat

private const val MIME_TYPE_PDF = "application/pdf"

class NotificationUtils {

    fun registerNotification(context: Context){
        val channel = NotificationChannel(NotificationConstants.CHANNEL_ID, NotificationConstants.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun showCompletedNotification(fileName: String?, fileUri: Uri, context: Context){
        val notIntent = Intent()
        notIntent.setDataAndType(fileUri, MIME_TYPE_PDF)
        val pendingIntent = PendingIntent
            .getActivity(
                context,
                0, notIntent, PendingIntent.FLAG_IMMUTABLE
            )

        val builder = Notification.Builder(context, NotificationConstants.CHANNEL_ID)
            .setSmallIcon(getResource(context, "mipmap", "ic_launcher"))
            .setContentTitle(fileName)
            .setContentText("Download abgeschlossen")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        NotificationManagerCompat.from(context).notify(NotificationConstants.NOTIFICATION_ID,builder.build())

    }

    fun getResource(context: Context, defType: String?, name: String?): Int {
        return context.resources.getIdentifier(name, defType, context.packageName)
    }

    object NotificationConstants{
        const val CHANNEL_NAME = "download_file_channel"
        const val CHANNEL_ID = "download_file_channel_123456"
        const val NOTIFICATION_ID = 1
    }
}