package org.mozilla.scryer.collectionview

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.fragment_collection.*
import org.mozilla.scryer.Observer
import org.mozilla.scryer.R
import org.mozilla.scryer.detailpage.DetailPageActivity
import org.mozilla.scryer.extension.getNavController
import org.mozilla.scryer.getSupportActionBar
import org.mozilla.scryer.persistence.ScreenshotModel
import org.mozilla.scryer.setSupportActionBar
import org.mozilla.scryer.ui.InnerSpaceDecoration
import org.mozilla.scryer.viewmodel.ScreenshotViewModel

class SearchFragment : androidx.fragment.app.Fragment() {

    companion object {
        const val ARG_SEARCH_KEYWORDS = "search_keywords"
        private const val SPAN_COUNT = 3
    }


    private lateinit var screenshotListView: androidx.recyclerview.widget.RecyclerView
    private lateinit var screenshotAdapter: ScreenshotAdapter

    private lateinit var liveData: LiveData<List<ScreenshotModel>>
    private lateinit var viewModel: ScreenshotViewModel


    private val keywords: String? by lazy {
        arguments?.getString(SearchFragment.ARG_SEARCH_KEYWORDS)
    }


    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        val layout = inflater.inflate(R.layout.fragment_collection, container, false)
        screenshotListView = layout.findViewById(R.id.screenshot_list)
        val subtitle = layout.findViewById<TextView>(R.id.subtitle)
        subtitle.text = getString(R.string.collection_separator_shots, 0)

        var toolbar = layout.findViewById<Toolbar>(R.id.toolbar)
        val editText = layout.findViewById<AppCompatEditText>(R.id.search_edittext)
        editText.visibility = View.VISIBLE
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                liveData.removeObservers(this@SearchFragment)
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val queryText = s.toString().split(" ").joinToString(" AND ", "*", "*")

                liveData = viewModel.getScreenshots(queryText)
                val count = if (liveData.value == null) {
                    0
                } else {
                    liveData.value?.size
                }

                subtitle.text = getString(R.string.collection_separator_shots, count)
            }

            override fun afterTextChanged(s: Editable?) {
                liveData.observe(this@SearchFragment, Observer { screenshots ->
                    if (screenshots.isNotEmpty()) {
                        empty_view.visibility = View.GONE
                    } else {
                        empty_view.visibility = View.VISIBLE
                    }

                    screenshots.sortedByDescending { it.lastModified }.let { sorted ->
                        screenshotAdapter.setScreenshotList(sorted)
                        screenshotAdapter.notifyDataSetChanged()
                    }

                })
            }
        })

        return layout
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val activity = activity ?: return

        screenshotAdapter = ScreenshotAdapter(context) { item, view ->
            val context = context ?: return@ScreenshotAdapter
            DetailPageActivity.showDetailPage(context, item, view)

        }

        setHasOptionsMenu(true)
        setupActionBar()
        
        initScreenshotList(activity)

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                getNavController()?.navigateUp()
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupActionBar() {
        view?.let {
            setSupportActionBar(activity, it.findViewById(R.id.toolbar))
        }
        getSupportActionBar(activity).apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
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

        viewModel = ScreenshotViewModel.get(this)
        liveData = viewModel.getScreenshots("")

        liveData.observe(this, Observer { screenshots ->
            if (screenshots.isNotEmpty()) {
                empty_view.visibility = View.GONE
            } else {
                empty_view.visibility = View.VISIBLE
            }

            screenshots.sortedByDescending { it.lastModified }.let { sorted ->
                screenshotAdapter.setScreenshotList(sorted)
                screenshotAdapter.notifyDataSetChanged()
            }

        })

    }

}