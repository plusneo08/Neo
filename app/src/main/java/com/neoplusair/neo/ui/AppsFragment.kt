
package com.neoplusair.neo.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neoplusair.neo.databinding.FragmentAppsBinding
import com.neoplusair.neo.databinding.ItemAppBinding
import com.neoplusair.neo.launcher.AppInfo
import com.neoplusair.neo.launcher.InstalledAppsRepository

/** Holographic application launcher: grid of installed apps, tap to launch. */
class AppsFragment : Fragment() {
    private var _b: FragmentAppsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAppsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val repo = InstalledAppsRepository(requireContext())
        b.recycler.layoutManager = GridLayoutManager(context, 4)
        b.recycler.adapter = AppAdapter(repo.apps) { app ->
            startActivity(app.launchIntent)
        }
        b.txtEmpty.visibility = if (repo.apps.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private class AppAdapter(
        private val apps: List<AppInfo>,
        private val onClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {
        class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)
        override fun getItemCount() = apps.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.binding.appIcon.setImageDrawable(app.icon)
            holder.binding.appLabel.text = app.label
            holder.binding.root.setOnClickListener { onClick(app) }
        }
    }
}
