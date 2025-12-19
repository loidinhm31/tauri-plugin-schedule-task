package com.plugin.scheduletask

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import app.tauri.Logger
import java.util.*
import java.util.concurrent.TimeUnit

class ScheduledTaskWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val taskName = inputData.getString("taskName") ?: return Result.failure()
        val packageName = inputData.getString("packageName") ?: return Result.failure()

        return try {
            Logger.info("[WORKER] Trying to execute the task $taskName with Id $taskId")
            executeScheduledTask(taskId, taskName, packageName)
            Logger.info("[WORKER] Executed the task $taskName successfully")
            Result.success()
        } catch (e: Exception) {
            Logger.error("Couldn't execute the task $taskName")
            Logger.error("Error: ${e.message}", e)
            Result.failure()
        }
    }

    private fun executeScheduledTask(taskId: String, taskName: String, packageName: String) {
        // Check if app is in foreground
        val isAppInForeground = isAppInForeground(packageName)
        Logger.info("[WORKER] App in foreground: $isAppInForeground")

        if (isAppInForeground) {
            // App is running - try to send intent to MainActivity
            Logger.info("[WORKER] Creating intent for foreground app")
            val intent = Intent().apply {
                setClassName(packageName, "$packageName.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("run_task", taskName)
                putExtra("task_id", taskId)

                Logger.info("[WORKER] Running task $taskName with Id $taskId")
                // Add task parameters
                for (key in inputData.keyValueMap.keys) {
                    if (key.startsWith("param_")) {
                        val paramName = key.removePrefix("param_")
                        val paramValue = inputData.getString(key)
                        putExtra("task_param_$paramName", paramValue)
                        Logger.info("[WORKER] Param $paramName for the task $taskName: $paramValue")
                    }
                }
            }

            try {
                applicationContext.startActivity(intent)
                Logger.info("[WORKER] Intent sent to foreground app")
            } catch (e: Exception) {
                Logger.error("[WORKER] Failed to start activity (will send notification instead): ${e.message}")
                sendNotificationDirectly(taskId, taskName, packageName)
                handleDailyRescheduling(taskName, packageName)
            }
        } else {
            // App is in background - send notification directly
            Logger.info("[WORKER] App in background, sending notification directly")
            sendNotificationDirectly(taskId, taskName, packageName)
            handleDailyRescheduling(taskName, packageName)
        }
    }

    private fun sendNotificationDirectly(taskId: String, taskName: String, packageName: String) {
        val title = inputData.getString("param_title") ?: "Scheduled Notification"
        val body = inputData.getString("param_body") ?: "You have a scheduled notification"

        Logger.info("[WORKER] Sending notification: title='$title', body='$body'")

        // Create notification channel (required for Android 8.0+)
        val channelId = "scheduled_tasks"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Scheduled Tasks",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications from scheduled tasks"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create intent to open app when notification is tapped
        val intent = Intent().apply {
            setClassName(packageName, "$packageName.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("from_notification", true)
            putExtra("task_name", taskName)
            putExtra("task_id", taskId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // Build and show notification
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(taskId.hashCode(), notification)

        Logger.info("[WORKER] Notification sent successfully")
    }

    private fun handleDailyRescheduling(taskName: String, packageName: String) {
        val isDaily = inputData.getString("param_is_daily") == "true"

        if (isDaily && taskName == "daily_reminder") {
            val timeStr = inputData.getString("param_time")
            if (timeStr != null) {
                Logger.info("[WORKER] Rescheduling daily reminder for tomorrow at $timeStr")

                try {
                    // Parse time string (format: "HH:MM")
                    val timeParts = timeStr.split(":")
                    if (timeParts.size == 2) {
                        val hour = timeParts[0].toInt()
                        val minute = timeParts[1].toInt()

                        // Calculate next occurrence (tomorrow at the same time)
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)

                        val delayMs = calendar.timeInMillis - System.currentTimeMillis()

                        // Cancel any existing daily_reminder tasks to avoid duplicates
                        // This is important when user changes the reminder time
                        WorkManager.getInstance(applicationContext).cancelAllWorkByTag("daily_reminder")
                        Logger.info("[WORKER] Cancelled existing daily_reminder tasks")

                        // Create new work request for tomorrow
                        val workData = Data.Builder()
                            .putString("taskId", UUID.randomUUID().toString())
                            .putString("taskName", taskName)
                            .putString("packageName", packageName)
                            .putString("param_title", inputData.getString("param_title"))
                            .putString("param_body", inputData.getString("param_body"))
                            .putString("param_is_daily", "true")
                            .putString("param_time", timeStr)
                            .build()

                        val workRequest = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                            .setInputData(workData)
                            .addTag("daily_reminder") // Use task name as tag for consistent cancellation
                            .build()

                        WorkManager.getInstance(applicationContext).enqueue(workRequest)
                        Logger.info("[WORKER] Successfully rescheduled daily reminder for ${calendar.time}")
                    }
                } catch (e: Exception) {
                    Logger.error("[WORKER] Failed to reschedule daily reminder: ${e.message}")
                }
            }
        }
    }

    private fun isAppInForeground(packageName: String): Boolean {
        val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses ?: return false

        return runningAppProcesses.any { processInfo ->
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            processInfo.processName == packageName
        }
    }
}
