package com.geckour.nowplaying4gpm.api

import com.geckour.nowplaying4gpm.api.model.slack.webhook.RequestParamsSimpleText
import com.geckour.nowplaying4gpm.api.model.slack.webhook.Response
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
        data: RequestParamsSimpleText
    ): Deferred<Response>
}