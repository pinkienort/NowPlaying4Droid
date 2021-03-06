package com.geckour.nowplaying4gpm.ui.sharing

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.lifecycle.ViewModelProviders
import com.geckour.nowplaying4gpm.ui.WithCrashlyticsActivity
import com.geckour.nowplaying4gpm.util.getCurrentTrackInfo

class SharingActivity : WithCrashlyticsActivity() {

    enum class IntentRequestCode {
        SHARE
    }

    companion object {
        private const val ARGS_KEY_REQUIRE_UNLOCK = "args_key_require_unlock"

        fun getIntent(context: Context, requireUnlock: Boolean = true): Intent =
            Intent(context, SharingActivity::class.java).apply {
                putExtra(ARGS_KEY_REQUIRE_UNLOCK, requireUnlock)
            }
    }

    private val viewModel: SharingViewModel by lazy {
        ViewModelProviders.of(this)[SharingViewModel::class.java]
    }

    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        val trackInfo = sharedPreferences.getCurrentTrackInfo()
        viewModel.startShare(this, sharedPreferences, intent.requireUnlock(), trackInfo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onNewIntent(intent)
        finish()
    }

    private fun Intent?.requireUnlock(): Boolean {
        val default = true
        if (this == null) return default

        return if (this.hasExtra(ARGS_KEY_REQUIRE_UNLOCK))
            this.getBooleanExtra(ARGS_KEY_REQUIRE_UNLOCK, default)
        else default
    }
}