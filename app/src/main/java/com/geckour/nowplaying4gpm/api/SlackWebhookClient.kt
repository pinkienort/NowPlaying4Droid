package com.geckour.nowplaying4gpm.api

import com.crashlytics.android.Crashlytics
import com.geckour.nowplaying4gpm.api.model.slack.webhook.RequestParamsSimpleText
import com.geckour.nowplaying4gpm.api.model.slack.webhook.Response
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

class SlackWebhookClient {

    companion object {
        const val BASE_SLACK_WEBHOOK_URL = "https://hooks.slack.com"
    }

    private val service = Retrofit.Builder()
            .client(OkHttpProvider.slackWebhookClient)
            .baseUrl(BASE_SLACK_WEBHOOK_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(CoroutineCallAdapterFactory())
            .build()
            .create(SlackWebhookApiService::class.java)

    suspend fun postSimpleText(url: String, text: String): Response =
        try {
            service.postSimpleText(url, RequestParamsSimpleText(text)).await()
        } catch (t: Throwable) {
            Timber.e(t)
            Crashlytics.logException(t)
            Response.getEmpty()
        }
}