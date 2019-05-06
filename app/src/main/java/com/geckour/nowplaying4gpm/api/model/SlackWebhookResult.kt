package com.geckour.nowplaying4gpm.api.model

data class SlackWebhookResult(
    val response: String
) {
    companion object {
        fun getEmpty(): SlackWebhookResult {
            return SlackWebhookResult("")
        }
    }
}