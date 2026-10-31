
package com.neoplusair.neo.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class SectionsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    companion object {
        val TITLES = listOf("DASHBOARD", "APPLICATIONS", "GESTURE")
    }
    override fun getItemCount() = 3
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> DashboardFragment()
        1 -> AppsFragment()
        else -> GestureFragment()
    }
}
