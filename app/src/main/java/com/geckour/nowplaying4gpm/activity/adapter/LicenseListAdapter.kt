package com.geckour.nowplaying4gpm.activity.adapter

import android.preference.PreferenceManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.geckour.nowplaying4gpm.R
import com.geckour.nowplaying4gpm.activity.SettingsActivity
import com.geckour.nowplaying4gpm.databinding.ItemLicenseBinding
import com.geckour.nowplaying4gpm.databinding.ItemLicenseFooterBinding
import com.geckour.nowplaying4gpm.util.getDonateBillingState
import com.geckour.nowplaying4gpm.util.ui
import kotlinx.coroutines.experimental.Job
import timber.log.Timber

class LicenseListAdapter(private val items: List<Pair<String, String>>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class ViewType {
        NORMAL,
        FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                ViewType.NORMAL.ordinal -> {
                    NormalItemViewHolder(
                            ItemLicenseBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                }
                ViewType.FOOTER.ordinal -> {
                    FooterItemViewHolder(
                            ItemLicenseFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                }
                else -> throw IllegalArgumentException()
            }

    override fun getItemCount(): Int = items.size + 1

    override fun getItemViewType(position: Int): Int =
            when (position) {
                items.size -> ViewType.FOOTER.ordinal
                else -> ViewType.NORMAL.ordinal
            }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is NormalItemViewHolder -> holder.bind(items[position].first, items[position].second)
            is FooterItemViewHolder -> holder.bind()
        }
    }

    private val jobs: ArrayList<Job> = ArrayList()

    class NormalItemViewHolder(val binding: ItemLicenseBinding): RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String, text: String) {
            binding.setName(name)
            binding.setText(text)
            binding.name.setOnClickListener {
                binding.text.apply {
                    visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
        }
    }

    inner class FooterItemViewHolder(val binding: ItemLicenseFooterBinding): RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            ui(jobs) { Glide.with(binding.button).load(binding.button.context.getString(R.string.easter_egg_icon_url)).into(binding.button) }
            binding.buttonCover.setOnClickListener { toggleDonateState() }
        }

        private fun toggleDonateState() {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(binding.root.context)

            sharedPreferences.edit().putBoolean(
                    SettingsActivity.PrefKey.PREF_KEY_BILLING_DONATE.name,
                    sharedPreferences.getDonateBillingState().not()
            ).apply()
        }
    }
}