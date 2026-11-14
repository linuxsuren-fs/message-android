package com.linuxsuren.message.ui

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.linuxsuren.message.R
import com.linuxsuren.message.data.MessageApi
import com.linuxsuren.message.data.MessageItem
import com.linuxsuren.message.databinding.ItemMessageBinding

/**
 * Card list of messages: title, body summary, topic chip, public badge,
 * relative time and a level color dot (info gray-green / warning orange /
 * error red).
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.ItemView>() {

    private val items = mutableListOf<MessageItem>()

    /** Replaces the whole list (manual refresh / scope switch). */
    fun submit(newItems: List<MessageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Inserts at the top unless the id already exists; true when inserted. */
    fun prepend(item: MessageItem): Boolean {
        if (items.any { it.id == item.id }) {
            return false
        }
        items.add(0, item)
        notifyItemInserted(0)
        return true
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemView {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ItemView(binding)
    }

    override fun onBindViewHolder(holder: ItemView, position: Int) {
        holder.bind(items[position])
    }

    class ItemView(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MessageItem) {
            val context = binding.root.context
            binding.msgTitle.text = item.title.ifBlank { context.getString(R.string.msg_no_title) }

            binding.msgBody.text = item.body
            binding.msgBody.visibility = if (item.body.isBlank()) View.GONE else View.VISIBLE

            if (item.createdAtMs > 0) {
                binding.msgTime.text = DateUtils.getRelativeTimeSpanString(context, item.createdAtMs, true)
                binding.msgTime.visibility = View.VISIBLE
            } else {
                binding.msgTime.visibility = View.GONE
            }

            binding.msgTopic.text = item.topic
            binding.msgTopic.visibility = if (item.topic.isBlank()) View.GONE else View.VISIBLE
            binding.msgPublicBadge.visibility = if (item.isPublic) View.VISIBLE else View.GONE

            val colorRes = when (item.level) {
                MessageApi.LEVEL_WARNING -> R.color.level_warning
                MessageApi.LEVEL_ERROR -> R.color.level_error
                else -> R.color.level_info
            }
            binding.msgLevelDot.backgroundTintList = ColorStateList.valueOf(context.getColor(colorRes))
        }
    }
}
