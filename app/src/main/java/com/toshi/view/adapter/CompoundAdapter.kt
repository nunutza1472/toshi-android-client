/*
 * 	Copyright (c) 2018. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.adapter

import android.support.v7.widget.RecyclerView
import android.view.ViewGroup

/**
An interface which allows us to avoid generic hell but still combine different types
 */
interface CompoundableAdapter {

    /**
     * Passes a generically typed viewHolder. Implementers should validate that the passed-in viewHolder
     * can be cast to the proper type, then cast the properly-typed viewHolder to their own
     * `onBindViewHolder` implementations.
     */
    fun compoundableBindViewHolder(viewHolder: RecyclerView.ViewHolder, adapterIndex: Int)

    /**
     * Creates a generically typed ViewHolder for use by the compound adapter.
     * NOTE: Current limitation of the compound adapter is that its child compoundable adapters
     * should only have one type, so `itemViewType` is not passed through.
     *
     * @param parent: The ViewGroup to pass as the parent to `onCreateViewHolder`.
     * @return A generically typed viewHolder
     */
    fun compoundableCreateViewHolder(parent: ViewGroup): RecyclerView.ViewHolder

    /**
     * The count of all the items in the current adapter.
     * Should generally be a transparent call through to `getItemCount`.
     */
    fun getCompoundableItemCount(): Int

    /**
     * Passes in a reference to the parent compound adapter so it can be notified when children are updated.
     * Null is passed when a child is removed from the parent.
     */
    fun setCompoundParent(parent: CompoundAdapter?)

    /**
     * Remove the item at the given adapter index, and put it into the undo stack.
     */
    fun removeItemAtWithUndo(adapterIndex: Int, parentView: RecyclerView)

    /**
     * Delete any items still in the undo stack permanently
     */
    fun doDelete()
}

/**
An adapter which takes an array of adapters, so logic for things with different sections can be split
across multiple adapters.

TERMINOLOGY:
- The `compoundIndex` is the index of an item in this whole compound controller.
- The `adapterIndex` is the index of an item in its sub-adapter's array of items.
- The `sectionIndex` is the index of the section holding the current item in the list of sections.
*/
class CompoundAdapter(
        private var adapters: List<CompoundableAdapter>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        for (adapter in adapters) {
            adapter.setCompoundParent(this)
        }
    }

    /**
     * Determines if an adapter is in the list of adapters, and if so, where it is
     *
     * @param adapter The adapter to determine the index of.
     * @return The index of the adapter in the list of adapters, or null if the adapter is not in the current list.
     */
    fun indexOf(adapter: CompoundableAdapter): Int? {
        return if (adapters.contains(adapter)) {
            adapters.indexOf(adapter)
        } else {
            null
        }
    }

    /**
     * Adds the given adapter to the end of the list.
     *
     * @param adapter: The adapter to add.
     */
    fun appendAdapter(adapter: CompoundableAdapter) {
        mutateAdapters { it.add(adapter) }
        adapter.setCompoundParent(this)
    }

    /**
     * Inserts an adapter at a specified index in the list
     *
     * @param adapter: The adapter to insert.
     * @param insertionIndex: The index at which to insert it.
     */
    fun insertAdapter(adapter: CompoundableAdapter, insertionIndex: Int) {
        mutateAdapters { it.add(insertionIndex, adapter) }
        adapter.setCompoundParent(this)
    }

    /**
     * Removes the given adapter from the list.
     * If the adapter is not in the list, this has no effect.
     *
     * @param adapter: The adapter to remove
     */
    fun removeAdapter(adapter: CompoundableAdapter) {
        if (!adapters.contains(adapter)) {
            // Not much to do here
            return
        }

        // Null out the parent before removing it from the array so we don't get stray callbacks.
        adapter.setCompoundParent(null)
        mutateAdapters { it.remove(adapter) }
    }

    override fun getItemCount(): Int {
        return adapters.fold(0, { acc, adapter -> acc + adapter.getCompoundableItemCount() })
    }

    override fun getItemViewType(position: Int): Int {
        // Use the index of the section adapter to route the view type to the proper place
        return indexOf(sectionAdapterForCompoundIndex(position)) ?: -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val sectionAdapter = adapters[viewType]

        // NOTE: Each section should only handle one particular type of item, or this is gonna cause some screwiness.
        return sectionAdapter.compoundableCreateViewHolder(parent)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val sectionAdapter = sectionAdapterForCompoundIndex(position)
        val sectionIndex = indexOf(sectionAdapter) ?: return

        when (sectionIndex) {
            0 -> sectionAdapter.compoundableBindViewHolder(holder, position)
            in (1..(adapters.count() - 1)) -> sectionAdapter.compoundableBindViewHolder(holder, adapterIndexOfItem(sectionAdapter, position))
            else -> throw AssertionError("Section index $sectionIndex out of bounds for adapter with ${adapters.count()} sections")
        }
    }

    fun removeItemAtWithUndo(compoundIndex: Int, parentView: RecyclerView) {
        val adapter = sectionAdapterForCompoundIndex(compoundIndex)
        val adapterIndex = adapterIndexOfItem(adapter, compoundIndex)

        adapter.removeItemAtWithUndo(adapterIndex, parentView)
    }

    fun notifiyItemChanged(childAdapter: CompoundableAdapter, adapterIndex: Int) {
        notifyItemChanged(compoundIndexOfItem(childAdapter, adapterIndex))
    }

    fun notifyItemRemoved(childAdapter: CompoundableAdapter, adapterIndex: Int) {
        notifyItemRemoved(compoundIndexOfItem(childAdapter, adapterIndex))
    }

    fun notifyItemInserted(childAdapter: CompoundableAdapter, adapterIndex: Int) {
        notifyItemInserted(compoundIndexOfItem(childAdapter, adapterIndex))
    }

    fun notifyDataSetChanged(childAdapter: CompoundableAdapter) {
        notifyDataSetChanged()
    }

    fun scrollToPosition(childAdapter: CompoundableAdapter, adapterIndex: Int, parentView: RecyclerView) {
        val positionToScrollTo = compoundIndexOfItem(childAdapter, adapterIndex)
        parentView.scrollToPosition(positionToScrollTo)
    }

    private fun mutateAdapters(action: (MutableList<CompoundableAdapter>) -> Unit) {
        val mutableCopy = adapters.toMutableList()
        action(mutableCopy)
        adapters = mutableCopy
        notifyDataSetChanged()
    }

    private fun compoundIndexOfItem(adapter: CompoundableAdapter, adapterIndex: Int): Int {
        val sectionIndex = adapters.indexOf(adapter)
        val previousItems = totalItemsBeforeSection(sectionIndex)
        return previousItems + adapterIndex
    }

    private fun adapterIndexOfItem(adapter: CompoundableAdapter, compoundIndex: Int): Int {
        val sectionIndex = adapters.indexOf(adapter)
        val previousItems = totalItemsBeforeSection(sectionIndex)
        return compoundIndex - previousItems
    }

    private fun totalItemsBeforeSection(sectionIndex: Int): Int {
        val sectionCount = adapters.size

        when (sectionIndex) {
            in Int.MIN_VALUE..-1 -> throw AssertionError("No sections at negative indexes!")
            0 -> /* There wouldn't be any items before section 0 */ return 0
            in 1..(sectionCount - 1) -> {
                val previousAdapters = adapters.subList(0, sectionIndex)
                return previousAdapters.fold(0, { acc, adapter -> acc + adapter.getCompoundableItemCount() })
            }
        }

        throw AssertionError("Looking for section at $sectionIndex but there are only $sectionCount sections")
    }

    private fun sectionAdapterForCompoundIndex(compoundIndex: Int): CompoundableAdapter {
        var previousCount = 0
        adapters.forEach { adapter ->
            if (compoundIndex >= (previousCount + adapter.getCompoundableItemCount())) {
                previousCount += adapter.getCompoundableItemCount()
            } else {
                return adapter
            }
        }

        // If we've gotten here, we've gone through all the adapters and haven't found anything
        throw AssertionError("No adapter for position $compoundIndex")
    }

    fun doDelete() {
        for (adapter in adapters) {
            adapter.doDelete()
        }
    }
}
