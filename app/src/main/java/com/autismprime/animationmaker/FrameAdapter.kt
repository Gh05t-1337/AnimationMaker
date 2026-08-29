package com.autismprime.animationmaker

import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** One image in the sequence. [id] is stable across reorders (unlike position). */
data class FrameItem(val id: Long, val uri: Uri)

class FrameAdapter(
    private val onRemove: (position: Int) -> Unit,
    private val onStartDrag: (holder: RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<FrameAdapter.ViewHolder>() {

    private val items = mutableListOf<FrameItem>()

    fun submit(newItems: List<FrameItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun currentItems(): List<FrameItem> = items.toList()

    fun moveItem(from: Int, to: Int) {
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
        // Index labels ("1", "2", ...) for the moved range need refreshing.
        notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(to - from) + 1)
    }

    fun removeAt(position: Int) {
        items.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, items.size - position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_frame, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.index.text = (position + 1).toString()
        holder.thumb.setImageBitmap(null)
        holder.thumb.tag = item.uri
        holder.removeButton.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }

        // Load thumbnail off the main thread; guard against recycled views.
        val context = holder.thumb.context
        Thread {
            val bmp = BitmapUtils.decodeSampled(context, item.uri, 160)
            holder.thumb.post {
                if (holder.thumb.tag == item.uri) {
                    holder.thumb.setImageBitmap(bmp)
                }
            }
        }.start()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id

    init {
        setHasStableIds(true)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.textIndex)
        val thumb: ImageView = view.findViewById(R.id.imageThumb)
        val removeButton: ImageView = view.findViewById(R.id.buttonRemove)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
    }
}

/** Wires up drag-to-reorder on the RecyclerView. */
class DragCallback(private val adapter: FrameAdapter) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
    0
) {
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Swipe-to-delete isn't enabled (swipe dirs = 0); nothing to do.
    }
}
