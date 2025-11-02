package com.example.flare_capstone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class FireFighterReportAdapter(
    private val onClick: (Report) -> Unit
) : ListAdapter<Report, ReportVH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Report>() {
            override fun areItemsTheSame(a: Report, b: Report) = a.id == b.id && a.kind == b.kind
            override fun areContentsTheSame(a: Report, b: Report) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_report, parent, false)
        return ReportVH(v)
    }

    override fun onBindViewHolder(holder: ReportVH, position: Int) {
        holder.bind(getItem(position), onClick)
    }
}

class ReportVH(v: View) : RecyclerView.ViewHolder(v) {
    private val title = v.findViewById<TextView>(R.id.txtTitle)
    private val sub   = v.findViewById<TextView>(R.id.txtSub)
    private val chipT = v.findViewById<TextView>(R.id.chipType)
    private val chipS = v.findViewById<TextView>(R.id.chipStatus)

    fun bind(r: Report, onClick: (Report) -> Unit) {
        title.text = r.location.ifBlank { "No location" }
        sub.text   = listOfNotNull(r.date.takeIf { !it.isNullOrBlank() }, r.time.takeIf { !it.isNullOrBlank() })
            .joinToString(" ")
        chipT.text = when (r.kind) {
            ReportKind.FIRE  -> "Fire"
            ReportKind.OTHER -> "Other"
            ReportKind.EMS   -> "EMS"
            ReportKind.SMS   -> "SMS"
            ReportKind.ALL   -> "All"
        }
        chipS.text = r.status
        itemView.setOnClickListener { onClick(r) }
    }
}
