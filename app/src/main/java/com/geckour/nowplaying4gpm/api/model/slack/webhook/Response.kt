package com.geckour.nowplaying4gpm.api.model.slack.webhook

data class Response(
    val response: String
) {
    companion object {
        fun getEmpty(): Response {
            return Response("")
        }
    }
}