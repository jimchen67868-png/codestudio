package com.example.aideclone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileTreeAdapter(
    private val onFileClick: (FileNode) -> Unit,
    private val onDirClick: (FileNode) -> Unit
) : RecyclerView.Adapter<FileTreeAdapter.NodeViewHolder>() {

    private var nodes: List<FileNode> = emptyList()

    fun submitList(newNodes: List<FileNode>) {
        nodes = newNodes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return NodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        val node = nodes[position]
        holder.bind(node)
        holder.itemView.setOnClickListener {
            if (node.isDirectory) onDirClick(node) else onFileClick(node)
        }
    }

    override fun getItemCount(): Int = nodes.size

    class NodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.iconView)
        private val name: TextView = itemView.findViewById(R.id.nameView)

        fun bind(node: FileNode) {
            name.text = node.name
            // Indent by depth so nested files read as a tree.
            val density = itemView.resources.displayMetrics.density
            itemView.setPadding(
                (16 * density * (node.depth + 1)).toInt(),
                itemView.paddingTop,
                itemView.paddingRight,
                itemView.paddingBottom
            )
            icon.setImageResource(
                if (node.isDirectory) android.R.drawable.ic_menu_agenda
                else android.R.drawable.ic_menu_edit
            )
        }
    }
}
