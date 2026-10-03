package ru.kubgau.attendance.terminal.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.kubgau.attendance.terminal.R
import ru.kubgau.attendance.terminal.databinding.ItemPresentBinding
import ru.kubgau.attendance.terminal.net.PresentDto

/** Список отметившихся: ФИО, время, подтверждено ли касанием терминала. */
class PresentAdapter : RecyclerView.Adapter<PresentAdapter.Holder>() {

    private val items = mutableListOf<PresentDto>()

    fun submit(newItems: List<PresentDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemPresentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPresentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.binding.studentName.text = item.studentName ?: item.studentId
        holder.binding.studentId.text = item.studentId
        holder.binding.markTime.text = item.timestamp.orEmpty().substringAfter('T')
        holder.binding.verifiedMark.text = if (item.verified == 1) {
            context.getString(R.string.verified_yes)
        } else {
            context.getString(R.string.verified_no)
        }
        holder.binding.verifiedMark.setTextColor(
            context.getColor(if (item.verified == 1) R.color.ok_green else R.color.kubgau_accent),
        )
    }
}
