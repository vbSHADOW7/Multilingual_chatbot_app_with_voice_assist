package com.example.multilingualchatbot

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Message(val text: String, val isUser: Boolean)

class ChatAdapter(private val messages: List<Message>) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val userMessageLayout: LinearLayout = itemView.findViewById(R.id.userMessageLayout)
        val botMessageLayout: LinearLayout = itemView.findViewById(R.id.botMessageLayout)
        val userMessageText: TextView = itemView.findViewById(R.id.userMessageText)
        val botMessageText: TextView = itemView.findViewById(R.id.botMessageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.chat_item, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        if (message.isUser) {
            holder.userMessageLayout.visibility = View.VISIBLE
            holder.botMessageLayout.visibility = View.GONE
            holder.userMessageText.text = message.text
        } else {
            holder.userMessageLayout.visibility = View.GONE
            holder.botMessageLayout.visibility = View.VISIBLE
            holder.botMessageText.text = message.text
        }
    }

    override fun getItemCount(): Int {
        return messages.size
    }
}