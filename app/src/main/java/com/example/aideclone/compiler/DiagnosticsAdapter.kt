package com.example.aideclone.compiler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aideclone.R
import java.io.File

class DiagnosticsAdapter(
    private val onClick: (CompileDiagnostic) -> Unit
) : RecyclerView.Adapter<DiagnosticsAdapter.DiagViewHolder>() {

    private var items: List<CompileDiagnostic> = emptyList()

    fun submitList(newItems: List<CompileDiagnostic>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiagViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_diagnostic, parent, false)
        return DiagViewHolder(view)
    }

    override fun onBindViewHolder(holder: DiagViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class DiagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val severityIcon: TextView = itemView.findViewById(R.id.severityIcon)
        private val location: TextView = itemView.findViewById(R.id.diagLocation)
        private val message: TextView = itemView.findViewById(R.id.diagMessage)

        fun bind(diag: CompileDiagnostic) {
            val isError = diag.severity == CompileDiagnostic.Severity.ERROR
            severityIcon.text = if (isError) "E" else "W"
            severityIcon.setTextColor(if (isError) 0xFFF44336.toInt() else 0xFFFFC107.toInt())
            location.text = "${File(diag.filePath).name}:${diag.line}"
            message.text = diag.message
        }
    }
}
