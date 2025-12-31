package com.bsci.medlink.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bsci.medlink.R
import com.bsci.medlink.data.model.Client
import com.bsci.medlink.utils.ImageUtils

class ClientListAdapter(
    private val clients: List<Client>,
    private val onClientSelected: (Client) -> Unit
) : RecyclerView.Adapter<ClientListAdapter.ClientViewHolder>() {

    private var selectedPosition = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClientViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_client, parent, false)
        return ClientViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClientViewHolder, position: Int) {
        val client = clients[position]
        holder.bind(client, position == selectedPosition)
        holder.itemView.setOnClickListener {
            selectedPosition = position
            notifyDataSetChanged()
            onClientSelected(client)
        }
    }

    override fun getItemCount(): Int = clients.size

    class ClientViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: ImageView = itemView.findViewById(R.id.iv_avatar)
        private val name: TextView = itemView.findViewById(R.id.tv_name)
        private val telephone: TextView = itemView.findViewById(R.id.tv_telephone)
        private val statusIndicator: View = itemView.findViewById(R.id.view_status)

        fun bind(client: Client, isSelected: Boolean) {
            // 显示客户端名称
            name.text = client.name
            
            // 显示电话号码（如果有）
            if (!client.telephone.isNullOrEmpty()) {
                telephone.text = client.telephone
                telephone.visibility = View.VISIBLE
            } else {
                telephone.visibility = View.GONE
            }

            // 设置在线状态
            if (client.isOnline) {
                statusIndicator.setBackgroundColor(itemView.context.getColor(R.color.online_green))
                name.setTextColor(itemView.context.getColor(R.color.black))
                itemView.alpha = 1.0f
            } else {
                statusIndicator.setBackgroundColor(itemView.context.getColor(R.color.offline_gray))
                name.setTextColor(itemView.context.getColor(R.color.offline_gray))
                itemView.alpha = 0.5f
            }

            // 设置选中状态
            itemView.setBackgroundColor(
                if (isSelected) itemView.context.getColor(R.color.selected_background)
                else itemView.context.getColor(android.R.color.transparent)
            )

            // 加载头像（从 base64 字符串或使用默认头像）
            if (!client.avatar.isNullOrEmpty()) {
                ImageUtils.loadBase64Image(avatar, client.avatar, R.drawable.ic_default_avatar)
            } else {
                avatar.setImageResource(R.drawable.ic_default_avatar)
            }
        }
    }
}

