package com.hermes.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hermes.mobile.config.ServerEntry

/**
 * ServerListAdapter - 服务器列表适配器
 *
 * 显示服务器列表，支持单选、点击、长按编辑。
 *
 * @param servers 服务器列表
 * @param selectedId 当前选中的服务器 ID
 * @param onItemClick 点击事件（选中）
 * @param onItemLongClick 长按事件（编辑）
 */
class ServerListAdapter(
    private var servers: List<ServerEntry>,
    private var selectedId: String?,
    private val onItemClick: (ServerEntry) -> Unit,
    private val onItemLongClick: (ServerEntry) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.ViewHolder>() {

    /**
     * ViewHolder - 列表项视图持有者
     */
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val selectedIndicator: TextView = view.findViewById(R.id.selectedIndicator)
        val serverName: TextView = view.findViewById(R.id.serverName)
        val serverUrl: TextView = view.findViewById(R.id.serverUrl)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        val isSelected = server.id == selectedId

        // 服务器信息
        holder.serverName.text = server.name
        holder.serverUrl.text = server.url

        // 选中指示器
        holder.selectedIndicator.text = if (isSelected) "●" else "○"

        // 点击选中
        holder.itemView.setOnClickListener {
            onItemClick(server)
        }

        // 长按编辑
        holder.itemView.setOnLongClickListener {
            onItemLongClick(server)
            true
        }
    }

    override fun getItemCount(): Int = servers.size

    /**
     * 更新数据
     */
    fun updateData(newServers: List<ServerEntry>, newSelectedId: String?) {
        servers = newServers
        selectedId = newSelectedId
        notifyDataSetChanged()
    }
}