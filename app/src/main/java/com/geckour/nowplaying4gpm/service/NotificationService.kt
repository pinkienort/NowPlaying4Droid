package com.geckour.nowplaying4gpm.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.geckour.nowplaying4gpm.BuildConfig
import com.geckour.nowplaying4gpm.api.LastFmApiClient
import com.geckour.nowplaying4gpm.api.OkHttpProvider
import com.geckour.nowplaying4gpm.api.SpotifyApiClient
import com.geckour.nowplaying4gpm.api.TwitterApiClient
import com.geckour.nowplaying4gpm.domain.model.TrackInfo
import com.geckour.nowplaying4gpm.receiver.ShareWidgetProvider
import com.geckour.nowplaying4gpm.ui.sharing.SharingActivity
import com.geckour.nowplaying4gpm.util.*
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.gson.Gson
import com.sys1yagi.mastodon4j.MastodonClient
import com.sys1yagi.mastodon4j.api.entity.Status
import com.sys1yagi.mastodon4j.api.method.Media
import com.sys1yagi.mastodon4j.api.method.Statuses
import kotlinx.coroutines.*
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.coroutines.CoroutineContext

class NotificationService : NotificationListenerService(), CoroutineScope {

    enum class Channel(val id: Int) {
        NOTIFICATION_CHANNEL_SHARE(180),
        NOTIFICATION_CHANNEL_NOTIFY(190)
    }

    companion object {
        const val ACTION_DESTROY_NOTIFICATION = "com.geckour.nowplaying4gpm.destroynotification"
        const val ACTION_INVOKE_UPDATE = "com.geckour.nowplaying4gpm.invokeupdate"
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

        fun sendRequestInvokeUpdate(context: Context) {
            context.checkStoragePermission {
                it.sendBroadcast(Intent().apply {
                    action = ACTION_INVOKE_UPDATE
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

                        launch {
                            reflectTrackInfo(
                                sharedPreferences.getCurrentTrackInfo()
                                    ?: return@launch
                            )
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
    private val spotifyApiClient: SpotifyApiClient = SpotifyApiClient()
    private val twitterApiClient: TwitterApiClient by lazy {
        TwitterApiClient(BuildConfig.TWITTER_CONSUMER_KEY, BuildConfig.TWITTER_CONSUMER_SECRET)
    }

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + getExceptionHandler() + Dispatchers.IO

    private var refreshMetadataJob: Job? = null
    private var currentTrackClearJob: Job? = null
    private var currentTrackSetJob: Job? = null
    private var postMastodonJob: Job? = null

    private var currentSbn: StatusBarNotification? = null
    private var currentMetadata: MediaMetadata? = null

    private val onMessageReceived: (MessageEvent) -> Unit = {
        when (it.path) {
            WEAR_PATH_TRACK_INFO_GET -> onPulledFromWear()

            WEAR_PATH_POST_TWITTER -> {
                if (it.sourceNodeId != null)
                    launch { onRequestPostToTwitterFromWear(it.sourceNodeId) }
            }

            WEAR_PATH_SHARE_DELEGATE -> {
                if (it.sourceNodeId != null) onRequestDelegateShareFromWear(it.sourceNodeId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        job = Job()
        setCrashlytics()

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_DESTROY_NOTIFICATION)
            addAction(ACTION_INVOKE_UPDATE)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, intentFilter)
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
        }

        getSystemService(NotificationManager::class.java).destroyNotification()
        job.cancel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        if (currentSbn == null) {
            try {
                activeNotifications.sortedBy { it.postTime }
                    .lastOrNull { fetchMetadata(it.notification) != null }
                    .apply { onNotificationPosted(this) }
            } catch (t: Throwable) {
                Timber.e(t)
            }
        }

        Wearable.getMessageClient(this).addListener(onMessageReceived)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()

        Wearable.getMessageClient(this).removeListener(onMessageReceived)

        requestRebind()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn != null && sbn.packageName != packageName) {
            fetchMetadata(sbn.notification)?.apply {
                refreshMetadataJob?.cancel()
                refreshMetadataJob = launch {
                    currentSbn = sbn
                    onMetadataChanged(this@apply, sbn.packageName, sbn.notification)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        if (sbn.packageName == currentSbn?.packageName) onMetadataCleared()
    }

    private fun requestRebind() {
        requestRebind(ComponentName(applicationContext, NotificationService::class.java))
    }

    private fun fetchMetadata(notification: Notification): MediaMetadata? =
        (notification.extras[Notification.EXTRA_MEDIA_SESSION] as MediaSession.Token?)?.let {
            MediaController(this, it).metadata
        }

    private fun onMetadataCleared() {
        currentSbn = null
        currentMetadata = null

        refreshMetadataJob?.cancel()
        currentTrackClearJob?.cancel()

        sharedPreferences.refreshTempArtwork(null)

        val info = TrackInfo.empty
        currentTrackClearJob = launch { onUpdate(info) }

        getSystemService(NotificationManager::class.java).destroyNotification()
    }

    private suspend fun onMetadataChanged(
        metadata: MediaMetadata,
        playerPackageName: String,
        notification: Notification? = null
    ) {
        val coreElement = metadata.getTrackCoreElement()

        if ((currentMetadata == null && currentTrackSetJob?.isActive != true)
            || (currentMetadata != null && metadata != currentMetadata)
        ) {
            currentMetadata = metadata

            currentTrackClearJob?.cancelAndJoin()
            currentTrackSetJob?.cancelAndJoin()

            setCurrentTrack(metadata, playerPackageName, notification, coreElement)
        }
    }

    private fun setCurrentTrack(
        metadata: MediaMetadata,
        playerPackageName: String,
        notification: Notification?,
        coreElement: TrackInfo.TrackCoreElement = metadata.getTrackCoreElement()
    ) {
        currentTrackSetJob = launch {
            val contains = sharedPreferences
                .getFormatPattern(this@NotificationService)
                .containsPattern(FormatPattern.SPOTIFY_URL)
            val spotifyUrl =
                if (contains) {
                    FirebaseAnalytics.getInstance(application)
                        .logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT,
                            Bundle().apply {
                                putString(
                                    FirebaseAnalytics.Param.ITEM_NAME,
                                    "Generated share sentence contains Spotify specifier"
                                )
                            }
                        )
                    spotifyApiClient.getSpotifyUrl(coreElement)
                } else {
                    FirebaseAnalytics.getInstance(application)
                        .logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT,
                            Bundle().apply {
                                putString(
                                    FirebaseAnalytics.Param.ITEM_NAME,
                                    "Generated share sentence without Spotify specifier"
                                )
                            }
                        )
                    null
                }

            if (onQuickUpdate(coreElement, playerPackageName, spotifyUrl).not()) {
                onMetadataCleared()
                return@launch
            }

            val artworkUri = metadata.storeArtworkUri(
                coreElement,
                notification?.getArtworkBitmap()?.await()
            )
            onUpdate(
                TrackInfo(
                    coreElement, artworkUri?.toString(),
                    playerPackageName, playerPackageName.getAppName(this@NotificationService),
                    spotifyUrl
                )
            )
        }
    }

    private fun MediaMetadata.getTrackCoreElement(): TrackInfo.TrackCoreElement =
        this.let {
            val track: String? =
                if (it.containsKey(MediaMetadata.METADATA_KEY_TITLE))
                    it.getString(MediaMetadata.METADATA_KEY_TITLE)
                else null
            val artist: String? =
                when {
                    it.containsKey(MediaMetadata.METADATA_KEY_ARTIST) ->
                        it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    it.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ->
                        it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    else -> null
                }
            val album: String? =
                if (it.containsKey(MediaMetadata.METADATA_KEY_ALBUM))
                    it.getString(MediaMetadata.METADATA_KEY_ALBUM)
                else null
            val composer: String? =
                if (it.containsKey(MediaMetadata.METADATA_KEY_COMPOSER))
                    it.getString(MediaMetadata.METADATA_KEY_COMPOSER)
                else null

            TrackInfo.TrackCoreElement(track, artist, album, composer)
        }

    private suspend fun onQuickUpdate(
        coreElement: TrackInfo.TrackCoreElement,
        packageName: String,
        spotifyUrl: String?
    ): Boolean {
        sharedPreferences.refreshTempArtwork(null)
        val trackInfo = TrackInfo(
            coreElement, null,
            packageName, packageName.getAppName(this),
            spotifyUrl
        )

        if (sharedPreferences.readyForShare(this, trackInfo).not()) {
            return false
        }

        reflectTrackInfo(trackInfo, false)

        return true
    }

    private suspend fun onUpdate(trackInfo: TrackInfo) {
        reflectTrackInfo(trackInfo)
        postMastodon(trackInfo)
    }

    private suspend fun reflectTrackInfo(info: TrackInfo, withArtwork: Boolean = true) {
        updateSharedPreference(info)
        updateWidget(info)
        updateNotification(info)
        if (withArtwork) {
            updateWear(info)
        }
    }

    private fun updateSharedPreference(trackInfo: TrackInfo) {
        sharedPreferences.refreshCurrentTrackInfo(trackInfo)
    }

    private suspend fun updateNotification(trackInfo: TrackInfo) {
        getSystemService(NotificationManager::class.java).showNotification(trackInfo)
    }

    private suspend fun updateWidget(trackInfo: TrackInfo) {
        AppWidgetManager.getInstance(this).apply {
            val ids = getAppWidgetIds(
                ComponentName(this@NotificationService, ShareWidgetProvider::class.java)
            )

            ids.forEach { id ->
                val widgetOptions = this.getAppWidgetOptions(id)
                updateAppWidget(
                    id,
                    getShareWidgetViews(
                        this@NotificationService,
                        ShareWidgetProvider.isMin(widgetOptions), trackInfo
                    )
                )
            }
        }
    }

    private fun updateWear(trackInfo: TrackInfo) {
        val subject = sharedPreferences.getFormatPattern(this@NotificationService)
            .getSharingText(trackInfo, sharedPreferences.getFormatPatternModifiers())
        val artwork = trackInfo.artworkUriString?.getUri()

        Wearable.getDataClient(this@NotificationService)
            .putDataItem(
                PutDataMapRequest.create(WEAR_PATH_TRACK_INFO_POST)
                    .apply {
                        dataMap.apply {
                            putString(WEAR_KEY_SUBJECT, subject)
                            if (artwork != null) {
                                putAsset(
                                    WEAR_KEY_ARTWORK,
                                    Asset.createFromUri(artwork)
                                )
                            }
                        }
                    }.asPutDataRequest()
            )
    }

    private fun postMastodon(trackInfo: TrackInfo) {
        if (trackInfo != TrackInfo.empty &&
            sharedPreferences.getSwitchState(PrefKey.PREF_KEY_WHETHER_ENABLE_AUTO_POST_MASTODON)
        ) {
            postMastodonJob?.cancel()
            postMastodonJob = launch {
                delay(sharedPreferences.getDelayDurationPostMastodon())

                val subject = sharedPreferences.getSharingText(this@NotificationService, trackInfo)
                    ?: return@launch

                FirebaseAnalytics.getInstance(application).logEvent(
                    FirebaseAnalytics.Event.SELECT_CONTENT,
                    Bundle().apply {
                        putString(FirebaseAnalytics.Param.ITEM_NAME, "Invoked auto post")
                    }
                )

                val artwork =
                    if (sharedPreferences.getSwitchState(
                            PrefKey.PREF_KEY_WHETHER_BUNDLE_ARTWORK
                        )
                    ) {
                        trackInfo.artworkUriString?.let {
                            return@let try {
                                getBitmapFromUriString(this@NotificationService, it)?.let { bitmap ->
                                    ByteArrayOutputStream().apply {
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, this)
                                    }.toByteArray()
                                }
                            } catch (t: Throwable) {
                                Timber.e(t)
                                null
                            }
                        }
                    } else null

                val userInfo = sharedPreferences.getMastodonUserInfo() ?: return@launch

                val mastodonClient = MastodonClient.Builder(
                    userInfo.instanceName,
                    OkHttpProvider.clientBuilder, Gson()
                )
                    .accessToken(userInfo.accessToken.accessToken)
                    .build()

                val mediaId = artwork?.let {
                    Media(mastodonClient).postMedia(
                        MultipartBody.Part.createFormData(
                            "file", "artwork.jpg",
                            RequestBody.create(MediaType.get("image/jpeg"), it)
                        )
                    )
                        .toJob()
                        .await()
                        ?.id
                }
                val result = Statuses(mastodonClient).postStatus(
                    subject,
                    null,
                    mediaId?.let { listOf(it) },
                    false,
                    null,
                    sharedPreferences.getVisibilityMastodon().let {
                        when (it) {
                            Visibility.PUBLIC -> Status.Visibility.Public
                            Visibility.UNLISTED -> Status.Visibility.Unlisted
                            Visibility.PRIVATE -> Status.Visibility.Private
                        }
                    })
                    .toJob()
                    .await() ?: return@launch

                showShortNotify(result)
            }
        }
    }

    private fun showShortNotify(status: Status) {
        if (sharedPreferences.getSwitchState(PrefKey.PREF_KEY_SHOW_SUCCESS_NOTIFICATION_MASTODON)) {
            runBlocking {
                getSystemService(NotificationManager::class.java).apply {
                    showNotification(status)
                    delay(2500)
                    cancel(Channel.NOTIFICATION_CHANNEL_NOTIFY.id)
                }
            }
        }
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
                runBlocking { updateWear(trackInfo) }
            }
        }
    }

    private fun onRequestDelegateShareFromWear(
        sourceNodeId: String,
        invokeOnReleasedLock: Boolean = false
    ) {
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
                    putString(
                        FirebaseAnalytics.Param.ITEM_NAME,
                        "Invoked direct share to twitter"
                    )
                }
            )

        val trackInfo = sharedPreferences.getCurrentTrackInfo()

        val subject = sharedPreferences.getSharingText(this, trackInfo) ?: run {
            onFailureShareToTwitter(sourceNodeId)
            return
        }

        requireNotNull(trackInfo)

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

    private suspend fun MediaMetadata.storeArtworkUri(
        coreElement: TrackInfo.TrackCoreElement,
        notificationBitmap: Bitmap?
    ): Uri? {
        // Check whether arg metadata and current metadata are the same or not
        val cacheInfo = sharedPreferences.getCurrentTrackInfo()
        if (coreElement.isAllNonNull
            && cacheInfo?.artworkUriString != null
            && coreElement == cacheInfo.coreElement
        ) {
            return sharedPreferences.getTempArtworkUri(this@NotificationService)
        }

        sharedPreferences.getArtworkResolveOrder().forEach {
            if (it.enabled) {
                when (it.key) {
                    ArtworkResolveMethod.ArtworkResolveMethodKey.CONTENT_RESOLVER -> {
                        Timber.d("np4d artwork resolve method: $it")
                        getArtworkUriFromDevice(this@NotificationService, coreElement)?.apply {
                            sharedPreferences.refreshTempArtwork(this)
                            return this
                        }
                    }
                    ArtworkResolveMethod.ArtworkResolveMethodKey.MEDIA_METADATA_URI -> {
                        Timber.d("np4d artwork resolve method: $it")
                        if (this.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)) {
                            this.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.getUri()?.apply {
                                getBitmapFromUri(this@NotificationService, this)?.apply {
                                    refreshArtworkUriFromBitmap(this@NotificationService, this)?.apply {
                                        return this
                                    }
                                }
                            }
                        }
                    }
                    ArtworkResolveMethod.ArtworkResolveMethodKey.MEDIA_METADATA_BITMAP -> {
                        Timber.d("np4d artwork resolve method: $it")
                        val metadataBitmap =
                            if (this.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART))
                                this.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            else null

                        if (metadataBitmap != null) {
                            refreshArtworkUriFromBitmap(this@NotificationService, metadataBitmap)?.apply {
                                return this
                            }
                        }
                    }
                    ArtworkResolveMethod.ArtworkResolveMethodKey.NOTIFICATION_BITMAP -> {
                        Timber.d("np4d artwork resolve method: $it")
                        if (notificationBitmap != null) {
                            refreshArtworkUriFromBitmap(this@NotificationService, notificationBitmap)?.apply {
                                return this
                            }
                        }
                    }
                    ArtworkResolveMethod.ArtworkResolveMethodKey.LAST_FM -> {
                        Timber.d("np4d artwork resolve method: $it")
                        if (sharedPreferences.getSwitchState(PrefKey.PREF_KEY_WHETHER_USE_API)) {
                            refreshArtworkUriFromLastFmApi(
                                this@NotificationService,
                                lastFmApiClient, coreElement
                            )?.apply { return this }
                        }
                    }
                }
            }
        }

        return null
    }

    private fun Notification.getArtworkBitmap(): Deferred<Bitmap?> =
        async {
            return@async (getLargeIcon()?.loadDrawable(this@NotificationService)
                    as? BitmapDrawable)
                ?.bitmap
                ?.let {
                    try {
                        it.copy(it.config, false)
                    } catch (t: Throwable) {
                        Timber.e(t)
                        null
                    }
                }
        }

    private suspend fun NotificationManager.showNotification(trackInfo: TrackInfo) {
        if (sharedPreferences.getSwitchState(PrefKey.PREF_KEY_WHETHER_RESIDE)
            && sharedPreferences.readyForShare(this@NotificationService, trackInfo)
        ) {
            checkStoragePermissionAsync {
                getNotification(this@NotificationService, trackInfo)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForeground(Channel.NOTIFICATION_CHANNEL_SHARE.id, this)
                    } else {
                        notify(Channel.NOTIFICATION_CHANNEL_SHARE.id, this)
                    }
                }
            }
        } else this.destroyNotification()
    }

    private suspend fun NotificationManager.showNotification(status: Status) {
        if (sharedPreferences.getSwitchState(PrefKey.PREF_KEY_SHOW_SUCCESS_NOTIFICATION_MASTODON)) {
            checkStoragePermissionAsync {
                getNotification(this@NotificationService, status)?.apply {
                    notify(Channel.NOTIFICATION_CHANNEL_NOTIFY.id, this)
                }
            }
        }
    }

    private fun NotificationManager.destroyNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        } else {
            this.cancel(Channel.NOTIFICATION_CHANNEL_SHARE.id)
        }
        this.cancel(Channel.NOTIFICATION_CHANNEL_NOTIFY.id)
    }
}