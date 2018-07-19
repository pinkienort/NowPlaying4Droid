package com.geckour.nowplaying4gpm.api

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.geckour.nowplaying4gpm.util.asyncOrNull
import com.geckour.nowplaying4gpm.util.getUri
import kotlinx.coroutines.experimental.Deferred
import twitter4j.StatusUpdate
import twitter4j.Twitter
import twitter4j.TwitterFactory
import twitter4j.auth.AccessToken
import twitter4j.auth.RequestToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TwitterApiClient(private val context: Context, consumerKey: String, consumerSecret: String) : TwitterApiService {

    companion object {
        const val TWITTER_CALLBACK = "np4gpm://twitter.callback"
    }

    private val twitter: Twitter = TwitterFactory().instance.apply {
        setOAuthConsumer(consumerKey, consumerSecret)
    }

    private var requestToken: RequestToken? = null

    override fun getRequestOAuthUri(): Deferred<Uri?> = asyncOrNull(context) {
        requestToken = twitter.getOAuthRequestToken(TWITTER_CALLBACK)
        requestToken?.authorizationURL?.getUri()
    }

    override fun getAccessToken(verifier: String): Deferred<AccessToken?> = asyncOrNull(context) {
        if (requestToken == null) null
        else twitter.getOAuthAccessToken(requestToken, verifier)
    }

    override fun post(accessToken: AccessToken,
                      subject: String, artwork: Bitmap?, artworkTitle: String?) = asyncOrNull(context) {
        twitter.oAuthAccessToken = accessToken
        val status = StatusUpdate(subject)
                .apply {
                    if (artwork != null) {
                        val bytes =
                                ByteArrayOutputStream().apply {
                                    artwork.compress(Bitmap.CompressFormat.JPEG, 100, this)
                                }.toByteArray()
                        setMedia(artworkTitle, ByteArrayInputStream(bytes))
                    }
                }
        twitter.updateStatus(status)
    }
}