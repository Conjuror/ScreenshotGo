/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.collectionview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.dialog_collection_info.view.*
import kotlinx.android.synthetic.main.dialog_screenshot_info.view.*
import kotlinx.android.synthetic.main.fragment_collection.*
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import org.mozilla.scryer.*
import org.mozilla.scryer.Observer
import org.mozilla.scryer.detailpage.DetailPageActivity
import org.mozilla.scryer.extension.getNavController
import org.mozilla.scryer.persistence.CollectionModel
import org.mozilla.scryer.persistence.ScreenshotModel
import org.mozilla.scryer.persistence.SuggestCollectionHelper
import org.mozilla.scryer.sortingpanel.SortingPanelActivity
import org.mozilla.scryer.telemetry.TelemetryWrapper
import org.mozilla.scryer.ui.CollectionNameDialog
import org.mozilla.scryer.ui.ConfirmationDialog
import org.mozilla.scryer.ui.InnerSpaceDecoration
import org.mozilla.scryer.util.launchIO
import org.mozilla.scryer.viewmodel.ScreenshotViewModel
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class CollectionFragment : Fragment() {
    companion object {
        const val ARG_COLLECTION_ID = "collection_id"
        const val ARG_COLLECTION_NAME = "collection_name"

        private const val SPAN_COUNT = 3
    }

    private lateinit var screenshotListView: RecyclerView
    private lateinit var subtitleView: TextView
    private lateinit var selectAllCheckbox: AppCompatCheckBox

    private lateinit var screenshotAdapter: ScreenshotAdapter

    private val selectActionModeCallback: ActionMode.Callback = object : ActionMode.Callback {
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val activity = activity ?: run {
                mode.finish()
                return false
            }

            when (item.itemId) {
                R.id.action_move -> {
                    val dialog = SortingPanelDialog(activity, selector.selected.toList())
                    dialog.setOnDismissListener {
                        mode.finish()
                    }
                    dialog.show()
                }

                R.id.action_delete -> {
                    showDeleteScreenshotDialog(activity, selector.selected.toList(),
                            object : OnDeleteScreenshotListener {
                                override fun onDeleteScreenshot() {
                                    mode.finish()
                                }
                            })
                }

                R.id.action_share -> {
                    showShareScreenshotDialog(activity, selector.selected.toList())
                }
            }

            return true
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val activity = activity ?: return false
            activity.menuInflater.inflate(R.menu.menu_collection_view_select_action_mode, menu)
            actionModeMenu = menu

            (0 until menu.size()).map {
                menu.getItem(it)
            }.forEach { item ->
                item.icon = DrawableCompat.wrap(item.icon).mutate().apply {
                    DrawableCompat.setTint(this, Color.WHITE)
                }
                if (selector.selected.isEmpty()) {
                    item.isVisible = false
                }
            }

            activity.window?.let {
                it.statusBarColor = ContextCompat.getColor(activity, R.color.primaryTeal)
            }

            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            screenshotAdapter.exitSelectionMode()
            val activity = activity ?: return

            activity.findViewById<View>(R.id.action_mode_bar).visibility = View.INVISIBLE
            activity.window?.let {
                it.statusBarColor = ContextCompat.getColor(activity, R.color.statusBarColor)
            }
        }
    }

    private var actionModeMenu: Menu? = null

    private var selector = object : ListSelector<ScreenshotModel>() {
        private var actionMode: ActionMode? = null

        override fun onSelectChanged() {
            if (selected.isEmpty()) {
                screenshotAdapter.exitSelectionMode()
                return
            }

            actionMode?.title = if (selected.size == screenshotAdapter.itemCount) {
                getString(R.string.collection_header_select_all)
            } else {
                "${selected.size}"
            }

            selectAllCheckbox.isChecked = screenshotAdapter.getScreenshotList().all {
                isSelected(it)
            }
            selectAllCheckbox.invalidate()

            actionModeMenu?.let { menu ->
                (0 until menu.size()).map {
                    menu.getItem(it)
                }.forEach { item ->
                    if (selected.isNotEmpty()) {
                        item.isVisible = true
                    }
                }
            }
        }

        override fun onEnterSelectMode() {
            val activity = (activity as? AppCompatActivity) ?: return
            actionMode = activity.startSupportActionMode(selectActionModeCallback)
            selectAllCheckbox.visibility = View.VISIBLE
            actionMode?.title = getString(R.string.collection_header_select_none)
            selectAllCheckbox.isChecked = false
        }

        override fun onExitSelectMode() {
            actionMode?.finish()
            selectAllCheckbox.visibility = View.GONE
        }
    }

    private val collectionId: String? by lazy {
        arguments?.getString(ARG_COLLECTION_ID)
    }

    private val collectionName: String? by lazy {
        arguments?.getString(ARG_COLLECTION_NAME)
    }

    private var sortMenuItem: MenuItem? = null
    private var selectMenuItem: MenuItem? = null

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        val layout = inflater.inflate(R.layout.fragment_collection, container, false)
        screenshotListView = layout.findViewById(R.id.screenshot_list)
        subtitleView = layout.findViewById(R.id.subtitle)
        selectAllCheckbox = layout.findViewById(R.id.select_all_checkbox)
        selectAllCheckbox.setOnClickListener { _ ->
            val isChecked = selectAllCheckbox.isChecked
            selectAllCheckbox.invalidate()
            screenshotAdapter.getScreenshotList().forEach {
                if (isChecked != selector.isSelected(it)) {
                    selector.toggleSelection(it)
                }
            }
            screenshotAdapter.notifyDataSetChanged()
        }
        return layout
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val activity = activity ?: return

        screenshotAdapter = ScreenshotAdapter(context, selector) { item, view ->
            val context = context ?: return@ScreenshotAdapter
            DetailPageActivity.showDetailPage(context, item, view, collectionId)

            TelemetryWrapper.collectionItem(
                    SuggestCollectionHelper.getSuggestCollectionNameForTelemetry(context, collectionName))
        }

        setHasOptionsMenu(true)
        setupActionBar()

        initScreenshotList(activity)

        TelemetryWrapper.visitCollectionPage(
                SuggestCollectionHelper.getSuggestCollectionNameForTelemetry(context, collectionName))

        setupWindowInsets()
    }

    private fun setupWindowInsets() {
        val rootView = view ?: return
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            toolbar_holder.setPadding(toolbar_holder.paddingLeft,
                    insets.systemWindowInsetTop,
                    toolbar_holder.paddingRight,
                    toolbar_holder.paddingBottom)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight,
                    insets.systemWindowInsetBottom)
            insets
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_collection, menu)
        sortMenuItem = if (collectionId == CollectionModel.CATEGORY_NONE) {
            menu.findItem(R.id.action_sort).apply { updateSortMenuItem(this) }
        } else {
            null
        }

        selectMenuItem = menu.findItem(R.id.action_select).apply { updateSortMenuItem(this) }

        val renameItem = menu.findItem(R.id.action_collection_rename)
        if (collectionId == null || collectionId == CollectionModel.CATEGORY_NONE) {
            renameItem.isVisible = false
        }

        val infoItem = menu.findItem(R.id.action_collection_info)
        if (collectionId == null) {
            infoItem.isVisible = false
        }

        val deleteItem = menu.findItem(R.id.action_collection_delete)
        if (collectionId == null || collectionId == CollectionModel.CATEGORY_NONE) {
            deleteItem.isVisible = false
        }
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                getNavController()?.navigateUp()
            }

            R.id.action_select -> {
                screenshotAdapter.enterSelectionMode()
            }

            R.id.action_sort -> {
                collectionId?.takeIf {
                    screenshotAdapter.getScreenshotList().isNotEmpty()
                }?.let {
                    startSortingActivity(it)
                    TelemetryWrapper.clickOnSortingButton()
                }
            }

            R.id.action_search -> {
                context?.let {
                    getNavController()?.navigate(R.id.action_navigate_to_search, Bundle())
                }
            }

            R.id.action_collection_rename -> {
                context?.let {
                    CollectionNameDialog.renameCollection(it, ScreenshotViewModel.get(this), collectionId)
                }
            }

            R.id.action_collection_info -> {
                context?.let {
                    showCollectionInfo(it, ScreenshotViewModel.get(this), collectionId)
                }
            }

            R.id.action_collection_delete -> {
                context?.let {
                    showDeleteCollectionDialog(it, ScreenshotViewModel.get(this), collectionId,
                            object : OnDeleteCollectionListener {
                                override fun onDeleteCollection() {
                                    getNavController()?.navigateUp()
                                }
                            })
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }

        return super.onOptionsItemSelected(item)
    }

    private fun startSortingActivity(collectionId: String) {
        context?.let {
            startActivity(SortingPanelActivity.sortCollection(it, collectionId))
        }
    }

    private fun setupActionBar() {
        view?.let {
            setSupportActionBar(activity, it.findViewById(R.id.toolbar))
        }
        getSupportActionBar(activity).apply {
            setDisplayHomeAsUpEnabled(true)
            updateActionBarTitle(this)
        }
    }

    private fun updateActionBarTitle(actionBar: ActionBar) {
        actionBar.title = collectionName?.let { it } ?: getString(R.string.collection_header_viewall_all)
    }

    private fun updateSortMenuItem(item: MenuItem?) {
        item?.isVisible = screenshotAdapter.getScreenshotList().isNotEmpty()
    }

    private fun initScreenshotList(context: Context) {
        val manager = GridLayoutManager(context, SPAN_COUNT,
                RecyclerView.VERTICAL, false)
        screenshotListView.itemAnimator = null
        screenshotListView.layoutManager = manager
        screenshotListView.adapter = screenshotAdapter

        val itemSpace = context.resources.getDimensionPixelSize(R.dimen.collection_item_space)

        screenshotListView.addItemDecoration(InnerSpaceDecoration(itemSpace) {
            SPAN_COUNT
        })

        val viewModel = ScreenshotViewModel.get(this)
        val liveData = collectionId?.let {
            val idList = if (it == CollectionModel.CATEGORY_NONE) {
                listOf(CollectionModel.UNCATEGORIZED, CollectionModel.CATEGORY_NONE)
            } else {
                listOf(it)
            }
            viewModel.getScreenshots(idList)

        } ?: viewModel.getScreenshots()

        liveData.observe(this, Observer { screenshots ->
            if (screenshots.isNotEmpty()) {
                subtitleView.visibility = View.VISIBLE
                subtitleView.text = getString(R.string.collection_separator_shots, screenshots.size)
                empty_view.visibility = View.GONE
            } else {
                subtitleView.visibility = View.INVISIBLE
                empty_view.visibility = View.VISIBLE
            }

            screenshots.sortedByDescending { it.lastModified }.let { sorted ->
                screenshotAdapter.setScreenshotList(sorted)
                screenshotAdapter.notifyDataSetChanged()
            }

            updateSortMenuItem(sortMenuItem)
            updateSortMenuItem(selectMenuItem)
        })

        viewModel.getCollections().observe(this, Observer { collections ->
            collections.find { it.id == collectionId }?.let {
                getSupportActionBar(activity).apply {
                    setDisplayHomeAsUpEnabled(true)
                    this.title = it.name
                }
            }
        })
    }
}

const val CONTEXT_MENU_ID_MOVE_TO = 0
const val CONTEXT_MENU_ID_INFO = 1
const val CONTEXT_MENU_ID_SHARE = 2
const val CONTEXT_MENU_ID_DELETE = 3

interface OnContextMenuActionListener {
    fun onContextMenuAction(item: MenuItem?, itemPosition: Int)
}

interface OnDeleteScreenshotListener {
    fun onDeleteScreenshot()
}

interface OnDeleteCollectionListener {
    fun onDeleteCollection()
}

fun showScreenshotInfoDialog(context: Context, screenshotModel: ScreenshotModel) {
    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_screenshot_info, null as ViewGroup?)
    dialogView.screenshot_info_name_content.text = getFileNameText(screenshotModel.absolutePath)
    dialogView.screenshot_info_file_size_amount.text = getFileSizeText(File(screenshotModel.absolutePath).length())
    dialogView.screenshot_info_last_edit_time.text = getFileDateText(File(screenshotModel.absolutePath).lastModified())

    AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.info_info))
            .setView(dialogView)
            .setPositiveButton(context.getString(android.R.string.ok)) {
                dialog: DialogInterface?, _: Int -> dialog?.dismiss()
            }
            .show()
}

fun getFileNameText(fullPath: String): String {
    val lastSeparatorIndex = fullPath.lastIndexOf(File.separatorChar)
    return if (lastSeparatorIndex != -1) {
        fullPath.substring(lastSeparatorIndex + 1)
    } else {
        fullPath
    }
}

fun getFileSizeText(size: Long): String {
    val df = DecimalFormat("0.00")
    val sizeKb = 1024.0f
    val sizeMo = sizeKb * sizeKb
    val sizeGo = sizeMo * sizeKb
    val sizeTerra = sizeGo * sizeKb

    return when {
        size < sizeMo -> df.format(size / sizeKb) + " KB"
        size < sizeGo -> df.format(size / sizeMo) + " MB"
        size < sizeTerra -> df.format(size / sizeGo) + " GB"
        else -> ""
    }
}

fun getFileDateText(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestamp

    return dateFormat.format(cal.time)
}

fun showDeleteScreenshotDialog(
        context: Context,
        screenshotModel: ScreenshotModel,
        listener: OnDeleteScreenshotListener? = null
) {
    showDeleteScreenshotDialog(context, listOf(screenshotModel), listener)
}

fun showDeleteScreenshotDialog(
        context: Context,
        screenshotModels: List<ScreenshotModel>,
        listener: OnDeleteScreenshotListener? = null
) {
    val dialog = ConfirmationDialog.build(context,
            context.getString(R.string.dialogue_deleteshot_title_delete),
            context.getString(R.string.action_delete),
            DialogInterface.OnClickListener { dialog, _ ->
                launchIO {
                    screenshotModels.forEach {
                        ScryerApplication.getScreenshotRepository().deleteScreenshot(it)
                        File(it.absolutePath).delete()
                    }
                }
                dialog?.dismiss()
                listener?.onDeleteScreenshot()
            },
            context.getString(android.R.string.cancel),
            DialogInterface.OnClickListener { dialog, _ ->
                dialog.dismiss()
            })
    dialog.viewHolder.message?.text = context.getString(R.string.dialogue_deleteshot_content_delete)
    dialog.viewHolder.subMessage?.visibility = View.VISIBLE

    GlobalScope.launch(Dispatchers.Main) {
        val size = withContext(Dispatchers.Default) {
            var totalSize = 0L
            screenshotModels.forEach {
                totalSize += File(it.absolutePath).length()
            }
            totalSize
        }

        dialog.viewHolder.subMessage?.text = getFileSizeText(size)
    }
    dialog.asAlertDialog().show()
}

fun showShareScreenshotDialog(context: Context, screenshotModel: ScreenshotModel) {
    showShareScreenshotDialog(context, listOf(screenshotModel))
}

fun showShareScreenshotDialog(context: Context, screenshotModels: List<ScreenshotModel>) {
    if (screenshotModels.isEmpty()) {
        return
    }

    GlobalScope.launch(Dispatchers.IO) {
        val authorities = BuildConfig.APPLICATION_ID + ".provider.fileprovider"
        val share = Intent()
        if (screenshotModels.size == 1) {
            val file = File(screenshotModels[0].absolutePath)
            val fileUri = FileProvider.getUriForFile(context, authorities, file)
            share.action = Intent.ACTION_SEND
            share.putExtra(Intent.EXTRA_STREAM, fileUri)
        } else {
            val uriList = ArrayList<Uri>()
            screenshotModels.forEach {
                val file = File(it.absolutePath)
                uriList.add(FileProvider.getUriForFile(context, authorities, file))
            }
            share.action = Intent.ACTION_SEND_MULTIPLE
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
        }
        share.type = "image/*"
        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(share, null))
        } catch (e: ActivityNotFoundException) {
        }
    }
}

fun showCollectionInfo(context: Context, viewModel: ScreenshotViewModel, collectionId: String?) {
    GlobalScope.launch(Dispatchers.IO) {
        collectionId ?: return@launch

        val idList = if (collectionId == CollectionModel.CATEGORY_NONE) {
            listOf(CollectionModel.UNCATEGORIZED, CollectionModel.CATEGORY_NONE)
        } else {
            listOf(collectionId)
        }

        val screenshots = viewModel.getScreenshotList(idList)
        var totalFileSize = 0L
        for (screenshot in screenshots) {
            val file = File(screenshot.absolutePath)
            totalFileSize += file.length()
        }

        val collection = viewModel.getCollection(collectionId)

        withContext(Dispatchers.Main) {
            collection ?: return@withContext
            showCollectionInfoDialog(context, collection, screenshots, totalFileSize)
        }
    }
}

private fun showCollectionInfoDialog(
        context: Context,
        collection: CollectionModel,
        screenshots: List<ScreenshotModel>,
        totalFileSize: Long
) {
    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_collection_info, null as ViewGroup?)
    dialogView.collection_info_name_content.text = getFileNameText(collection.name)
    dialogView.collection_info_total_screenshots_count.text = screenshots.size.toString()
    dialogView.collection_info_storage_used_amount.text = getFileSizeText(totalFileSize)

    AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialogue_collecitioninfo_title_info))
            .setView(dialogView)
            .setPositiveButton(context.getString(android.R.string.ok)) {
                dialog: DialogInterface?, _: Int -> dialog?.dismiss()
            }
            .show()
}

fun showDeleteCollectionDialog(
        context: Context,
        viewModel: ScreenshotViewModel,
        collectionId: String?,
        listener: OnDeleteCollectionListener?
) {
    GlobalScope.launch(Dispatchers.Main) {
        collectionId ?: return@launch

        withContext(Dispatchers.Default) {
            viewModel.getCollection(collectionId)

        }?.let { collection ->
            val screenshots = withContext(Dispatchers.Default) {
                viewModel.getScreenshotList(listOf(collection.id))
            }

            val totalFileSize = withContext(Dispatchers.Default) {
                var totalFileSize = 0L
                for (screenshot in screenshots) {
                    val file = File(screenshot.absolutePath)
                    totalFileSize += file.length()
                }
                totalFileSize
            }

            // fool the lint
            val screenshotCount: Int = screenshots.size

            val dialog = ConfirmationDialog.build(context,
                    context.getString(R.string.dialogue_deletecollection_title_delete),
                    context.getString(R.string.action_delete),
                    DialogInterface.OnClickListener { dialog, _ ->
                        GlobalScope.launch(Dispatchers.IO) {
                            viewModel.deleteCollection(collection)
                            screenshots.forEach { screenshot ->
                                File(screenshot.absolutePath).delete()
                                viewModel.deleteScreenshot(screenshot)
                            }
                        }
                        dialog?.dismiss()
                        listener?.onDeleteCollection()
                    },
                    context.getString(android.R.string.cancel),
                    DialogInterface.OnClickListener { dialog, _ ->
                        dialog.dismiss()
                    })
            dialog.viewHolder.message?.text = context.getString(R.string.dialogue_delete_content_cantundo)
            dialog.viewHolder.subMessage?.apply {
                visibility = View.VISIBLE
                text = context.getString(R.string.dialogue_deletecollection_content_shots, screenshotCount)
            }
            dialog.viewHolder.subMessage2?.apply {
                visibility = View.VISIBLE
                text = getFileSizeText(totalFileSize)
            }
            dialog.asAlertDialog().show()
        }
    }
}

