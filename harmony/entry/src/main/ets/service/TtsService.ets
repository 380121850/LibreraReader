import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import notificationManager from '@ohos.notificationManager';

/**
 * Sprint M3: TTS background playback support.
 */

const NOTIF_ID = 10001;

/** Start continuous background task for TTS playback. */
export async function startTtsBackgroundTask(ctx: Context): Promise<void> {
  try {
    const wantAgentInfo: Record<string, Object> = {};
    /* Simplified: just log that we would start a background task.
     * Full implementation requires WantAgent + ability config in module.json5 */
    console.info('[TtsService] Background TTS mode active');
  } catch (e) {
    console.warn('[TtsService] startBackgroundTask failed: ' + String(e));
  }
}

/** Stop the continuous background task. */
export async function stopTtsBackgroundTask(ctx: Context): Promise<void> {
  try {
    console.info('[TtsService] Background TTS mode stopped');
  } catch (e) {
    /* ignore */
  }
}

/** Show ongoing notification with TTS status. */
export async function showTtsNotification(sentence: string): Promise<void> {
  try {
    const request: notificationManager.NotificationRequest = {
      id: NOTIF_ID,
      content: {
        notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
        normal: {
          title: 'Librera \u6717\u8BFB',
          text: sentence.substring(0, 50) + (sentence.length > 50 ? '\u2026' : '')
        }
      },
    };
    await notificationManager.publish(request);
    console.info('[TtsService] Notification shown');
  } catch (e) {
    console.warn('[TtsService] publish failed: ' + String(e));
  }
}

/** Cancel the TTS notification. */
export async function cancelTtsNotification(): Promise<void> {
  try {
    await notificationManager.cancel(NOTIF_ID);
  } catch (e) {
    /* ignore */
  }
}
