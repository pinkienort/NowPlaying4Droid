package com.geckour.nowplaying4gpm.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.support.annotation.RequiresApi
import com.geckour.nowplaying4gpm.BuildConfig
import com.geckour.nowplaying4gpm.R
import com.geckour.nowplaying4gpm.api.LastFmApiClient
import com.geckour.nowplaying4gpm.api.TwitterApiClient
import com.geckour.nowplaying4gpm.domain.model.TrackCoreElement
import com.geckour.nowplaying4gpm.domain.model.TrackInfo
import com.geckour.nowplaying4gpm.receiver.ShareWidgetProvider
import com.geckour.nowplaying4gpm.ui.SharingActivity
import com.geckour.nowplaying4gpm.util.*
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.cancelAndJoin
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream

class NotificationService : NotificationListenerService() {

    enum class Channel(val id: Int) {
        NOTIFICATION_CHANNEL_SHARE(180)
    }

    companion object {
        const val ACTION_DESTROY_NOTIFICATION = "com.geckour.nowplaying4gpm.destroynotification"
        const val ACTION_INVOKE_UPDATE = "com.geckour.nowplaying4gpm.invokeupdate"
        private const val BUNDLE_KEY_TRACK_INFO = "bundle_key_track_info"
        private const val WEAR_PATH_TRACK_INFO_POST = "/track_info/post"
        private const val WEAR_PATH_TRACK_INFO_GET = "/track_info/get"
        private const val WEAR_PATH_POST_TWITTER = "/post/twitter"
        private const val WEAR_PATH_POST_SUCCESS = "/post/success"
        private const val WEAR_PATH_POST_FAILURE = "/post/failure"
        private const val WEAR_PATH_SHARE_DELEGATE = "/share/delegate"
        private const val WEAR_PATH_SHARE_SUCCESS = "/share/success"
        private const val WEAR_PATH_SHARE_FAILURE = "/share/failure"
        private const val WEAR_KEY_SUBJECT = "key_subject"
        private const val WEAR_KEY_ARTWORK = "key_artwork"

        private const val timeMargin: Long = 300

        fun sendRequestInvokeUpdate(context: Context, trackInfo: TrackInfo?) {
            context.checkStoragePermission {
                it.sendBroadcast(Intent().apply {
                    action = ACTION_INVOKE_UPDATE
                    putExtra(BUNDLE_KEY_TRACK_INFO, trackInfo)
                })
            }
        }
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.apply {
                when (action) {
                    ACTION_DESTROY_NOTIFICATION -> {
                        getSystemService(NotificationManager::class.java).destroyNotification()
                    }

                    ACTION_INVOKE_UPDATE -> {
                        if (context == null) return

                        val trackInfo =
                                if (this.extras != null && extras.containsKey(BUNDLE_KEY_TRACK_INFO))
                                    extras.get(BUNDLE_KEY_TRACK_INFO) as? TrackInfo?
                                            ?: TrackInfo.empty
                                else TrackInfo.empty

                        async {
                            onUpdate(trackInfo)
                        }
                    }

                    Intent.ACTION_USER_PRESENT -> {
                        val nodeId = sharedPreferences.getReceivedDelegateShareNodeId() ?: return
                        onRequestDelegateShareFromWear(nodeId, true)
                        sharedPreferences.setReceivedDelegateShareNodeId(null)
                    }
                }
            }
        }
    }

    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
    }
    private val lastFmApiClient: LastFmApiClient = LastFmApiClient()
    private val twitterApiClient: TwitterApiClient =
            TwitterApiClient(BuildConfig.TWITTER_CONSUMER_KEY,
                    BuildConfig.TWITTER_CONSUMER_SECRET)
    private val jobs: ArrayList<Job> = ArrayList()

    private var currentTrack: TrackCoreElement = TrackCoreElement.empty
    private var currentTrackClearJob: Job? = null
    private var currentTrackSetJob: Job? = null

    private val onMessageReceived: (MessageEvent) -> Unit = {
        when (it.path) {
            WEAR_PATH_TRACK_INFO_GET -> onPulledFromWear()

            WEAR_PATH_POST_TWITTER -> {
                if (it.sourceNodeId != null)
                    async { onRequestPostToTwitterFromWear(it.sourceNodeId) }
            }

            WEAR_PATH_SHARE_DELEGATE -> {
                if (it.sourceNodeId != null) onRequestDelegateShareFromWear(it.sourceNodeId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_DESTROY_NOTIFICATION)
            addAction(ACTION_INVOKE_UPDATE)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, intentFilter)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createDefaultChannel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }

        getSystemService(NotificationManager::class.java).destroyNotification()
        jobs.cancelAll()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        activeNotifications.forEach { onNotificationPosted(it) }

        Wearable.getMessageClient(this).addListener(onMessageReceived)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()

        Wearable.getMessageClient(this).removeListener(onMessageReceived)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn != null && sbn.packageName != packageName) {
            fetchMetadata(sbn.packageName)?.apply {
                onMetadataChanged(this, sbn.packageName, sbn.notification)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        val currentTrackInfo = sharedPreferences.getCurrentTrackInfo() ?: return
        if (sbn.packageName == currentTrackInfo.playerPackageName) onMetadataCleared()
    }

    private fun fetchMetadata(packageName: String): MediaMetadata? =
            getSystemService(MediaSessionManager::class.java).let {
                val componentName =
                        ComponentName(this@NotificationService,
                                NotificationService::class.java)

                return@let it.getActiveSessions(componentName)
                        .firstOrNull { it.packageName == packageName }
                        ?.metadata
            }

    private fun onMetadataCleared() {
        currentTrackClearJob?.cancel()

        sharedPreferences.refreshTempArtwork(null)

        val info = TrackInfo.empty
        currentTrack = info.coreElement
        currentTrackClearJob = launch { reflectTrackInfo(info) }

        getSystemService(NotificationManager::class.java).destroyNotification()
    }

    private fun onMetadataChanged(metadata: MediaMetadata,
                                  packageName: String, notification: Notification? = null) {
        val coreElement = metadata.getTrackCoreElement()

        if (coreElement != currentTrack) {
            currentTrackSetJob?.cancel()

            currentTrackSetJob = launch {
                delay(timeMargin)

                currentTrackClearJob?.cancelAndJoin()

                onQuickUpdate(coreElement, packageName)
                val artworkUri = metadata.storeArtworkUri(coreElement,
                        notification?.getArtworkBitmap())
                onUpdate(TrackInfo(coreElement, artworkUri?.toString(), packageName))
            }
        }
    }

    private fun MediaMetadata.getTrackCoreElement(): TrackCoreElement =
            this.let {
                val track: String? =
                        if (it.containsKey(MediaMetadata.METADATA_KEY_TITLE))
                            it.getString(MediaMetadata.METADATA_KEY_TITLE)
                        else null
                val artist: String? =
                        if (it.containsKey(MediaMetadata.METADATA_KEY_ARTIST))
                            it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        else null
                val album: String? =
                        if (it.containsKey(MediaMetadata.METADATA_KEY_ALBUM))
                            it.getString(MediaMetadata.METADATA_KEY_ALBUM)
                        else null

                TrackCoreElement(track, artist, album)
            }

    private suspend fun onQuickUpdate(coreElement: TrackCoreElement, packageName: String) {
        sharedPreferences.refreshTempArtwork(null)
        currentTrack = coreElement
        reflectTrackInfo(TrackInfo(coreElement, null, packageName), false)
    }

    private suspend fun onUpdate(trackInfo: TrackInfo) {
        reflectTrackInfo(trackInfo)
    }

    private suspend fun reflectTrackInfo(info: TrackInfo, withArtwork: Boolean = true) {
        updateSharedPreference(info)
        updateWidget(info)
        updateNotification(info)
        if (withArtwork) updateWear(info)
    }

    private fun updateSharedPreference(trackInfo: TrackInfo) {
        sharedPreferences.refreshCurrentTrackInfo(trackInfo)
    }

    private fun updateNotification(trackInfo: TrackInfo) {
        getSystemService(NotificationManager::class.java).showNotification(trackInfo)
    }

    private suspend fun updateWidget(trackInfo: TrackInfo) {
        AppWidgetManager.getInstance(this).apply {
            val ids = getAppWidgetIds(
                    ComponentName(this@NotificationService, ShareWidgetProvider::class.java))

            ids.forEach { id ->
                val widgetOptions = this.getAppWidgetOptions(id)
                updateAppWidget(
                        id,
                        getShareWidgetViews(this@NotificationService,
                                ShareWidgetProvider.isMin(widgetOptions), trackInfo)
                )
            }
        }
    }

    private suspend fun updateWear(trackInfo: TrackInfo) {
        val subject =
                if (trackInfo.coreElement.isAllNonNull) {
                    sharedPreferences.getFormatPattern(this)
                            .getSharingText(trackInfo.coreElement)
                } else null
        val artwork = trackInfo.artworkUriString
                ?.getUri()
                ?.let {
                    ByteArrayOutputStream().apply {
                        getBitmapFromUri(this@NotificationService, it)
                                ?.compress(
                                        Bitmap.CompressFormat.JPEG,
                                        100,
                                        this
                                )
                    }.toByteArray()
                }

        Wearable.getDataClient(this)
                .putDataItem(
                        PutDataMapRequest.create(WEAR_PATH_TRACK_INFO_POST)
                                .apply {
                                    dataMap.apply {
                                        putString(WEAR_KEY_SUBJECT, subject)
                                        if (artwork != null) {
                                            putAsset(WEAR_KEY_ARTWORK,
                                                    Asset.createFromBytes(artwork))
                                        }
                                    }
                                }.asPutDataRequest()
                )
    }

    private fun deleteWearTrackInfo(onComplete: () -> Unit = {}) {
        Wearable.getDataClient(this)
                .deleteDataItems(Uri.parse("wear://$WEAR_PATH_TRACK_INFO_POST"))
                .addOnSuccessListener { onComplete() }
                .addOnFailureListener {
                    Timber.e(it)
                    onComplete()
                }
                .addOnCompleteListener { onComplete() }
    }

    private fun onPulledFromWear() {
        val trackInfo = sharedPreferences.getCurrentTrackInfo()
        if (trackInfo != null) {
            deleteWearTrackInfo {
                async { updateWear(trackInfo) }
            }
        }
    }

    private fun onRequestDelegateShareFromWear(sourceNodeId: String,
                                               invokeOnReleasedLock: Boolean = false) {
        val keyguardManager =
                try {
                    getSystemService(KeyguardManager::class.java)
                } catch (t: Throwable) {
                    Timber.e(t)
                    null
                }

        if (keyguardManager?.isDeviceLocked?.not() == true) {
            startActivity(SharingActivity.getIntent(this@NotificationService)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } else {
            sharedPreferences.setReceivedDelegateShareNodeId(sourceNodeId)
        }

        if (invokeOnReleasedLock.not())
            Wearable.getMessageClient(this@NotificationService)
                    .sendMessage(sourceNodeId, WEAR_PATH_SHARE_SUCCESS, null)
    }

    private suspend fun onRequestPostToTwitterFromWear(sourceNodeId: String) {
        FirebaseAnalytics.getInstance(application)
                .logEvent(
                        FirebaseAnalytics.Event.SELECT_CONTENT,
                        Bundle().apply {
                            putString(FirebaseAnalytics.Param.ITEM_NAME, "Invoked direct share to twitter")
                        }
                )

        val trackInfo = sharedPreferences.getCurrentTrackInfo() ?: run {
            onFailureShareToTwitter(sourceNodeId)
            return
        }

        val subject = sharedPreferences.getFormatPattern(this)
                .getSharingText(trackInfo.coreElement)
        val artwork =
                trackInfo.artworkUriString?.let {
                    getBitmapFromUriString(this@NotificationService, it)
                }

        val accessToken = sharedPreferences.getTwitterAccessToken() ?: run {
            sharedPreferences.setAlertTwitterAuthFlag(true)
            onFailureShareToTwitter(sourceNodeId)
            return
        }

        twitterApiClient.post(accessToken, subject, artwork, trackInfo.coreElement.title)

        Wearable.getMessageClient(this@NotificationService)
                .sendMessage(sourceNodeId, WEAR_PATH_POST_SUCCESS, null)
    }

    private fun onFailureShareToTwitter(sourceNodeId: String) {
        Wearable.getMessageClient(this@NotificationService)
                .sendMessage(sourceNodeId, WEAR_PATH_POST_FAILURE, null)
    }

    private suspend fun MediaMetadata.storeArtworkUri(coreElement: TrackCoreElement,
                                                      bitmap: Bitmap?): Uri? {
        // Check whether arg metadata and current metadata are the same or not
        val cacheInfo = sharedPreferences.getCurrentTrackInfo()
        if (coreElement.isAllNonNull
                && cacheInfo?.artworkUriString != null
                && coreElement == cacheInfo.coreElement) {
            return sharedPreferences.getTempArtworkUri(this@NotificationService)
        }

        // Find from ContentResolver
        getArtworkUriFromDevice(this@NotificationService, coreElement)?.apply {
            sharedPreferences.refreshTempArtwork(this)
            return this
        }

        // Fetch from MediaMetadata's Bitmap field
        val metadataBitmap =
                (if (this.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART))
                    this.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                else null) ?: bitmap

        if (metadataBitmap != null) {
            refreshArtworkUriFromBitmap(this@NotificationService, metadataBitmap, true)?.apply {
                return this
            }
        }

        // Fetch from MediaMetadata's Uri field
        if (this.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)) {
            this.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.getUri()?.apply {
                getBitmapFromUri(this@NotificationService, this)?.apply {
                    refreshArtworkUriFromBitmap(this@NotificationService, this, true)?.apply {
                        return this
                    }
                }
            }
        }

        // Find from Last.fm API
        if (sharedPreferences.getSwitchState(PrefKey.PREF_KEY_WHETHER_USE_API)) {
            refreshArtworkUriFromLastFmApi(this@NotificationService,
                    lastFmApiClient, coreElement)?.apply {
                return this
            }
        }

        return null
    }

    private suspend fun Notification.getArtworkBitmap(): Bitmap? {
        val notificationBitmap =
                (this.getLargeIcon()
                        ?.loadDrawable(this@NotificationService) as? BitmapDrawable?)
                        ?.bitmap
                        ?.let {
                            try {
                                it.copy(it.config, false)
                            } catch (t: Throwable) {
                                Timber.e(t)
                                null
                            }
                        }

        if (notificationBitmap != null) {
            val placeholderBitmap =
                    (getDrawable(R.mipmap.bg_default_album_art) as BitmapDrawable).bitmap

            return if ((notificationBitmap.similarity(placeholderBitmap).await() ?: 1f) > 0.9) null
            else notificationBitmap
        }

        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createDefaultChannel() {
        val name = getString(R.string.notification_channel_name_share)
        val description = getString(R.string.notification_channel_description_share)

        val channel =
                NotificationChannel(
                        Channel.NOTIFICATION_CHANNEL_SHARE.name,
                        name,
                        NotificationManager.IMPORTANCE_LOW
                ).apply { this.description = description }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun NotificationManager.showNotification(trackInfo: TrackInfo) {
        if (sharedPreferences.getSwitchState(PrefKey.PREF_KEY_WHETHER_RESIDE)
                && trackInfo.coreElement.isAllNonNull) {
            checkStoragePermission {
                ui(jobs) {
                    getNotification(this@NotificationService, trackInfo)?.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForeground(Channel.NOTIFICATION_CHANNEL_SHARE.id, this)
                        } else {
                            this@showNotification.notify(
                                    Channel.NOTIFICATION_CHANNEL_SHARE.id, this)
                        }
                    }
                }
            }
        } else this.destroyNotification()
    }

    private fun NotificationManager.destroyNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        } else {
            this.cancel(Channel.NOTIFICATION_CHANNEL_SHARE.id)
        }
    }
}