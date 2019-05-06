package com.geckour.nowplaying4gpm.api

import com.geckour.nowplaying4gpm.api.model.SlackWebhookResult
import kotlinx.coroutines.Deferred
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface SlackWebhookApiService {

    @POST
    fun postSimpleText(
        @Url
        url: String,
        @Body
        text: String
    ): Deferred<SlackWebhookResult>
}