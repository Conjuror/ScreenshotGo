/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.detailpage

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent

class GraphicOverlayTouchHelper(context: Context, val blocks: List<TextBlockGraphic>) {

    var callback: Callback? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.OnGestureListener {
        override fun onShowPress(e: MotionEvent?) {}

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            var selected: TextBlockGraphic? = null
            blocks.forEach {
                it.isSelected = it.boundingBox.contains(e.x, e.y)
                if (it.isSelected) {
                    selected = it
                }
            }
            selected?.let {
                callback?.onBlockSelectStateChanged(it)
            } ?: callback?.onBlockSelectStateChanged(null)
            return true
        }

        override fun onDown(e: MotionEvent?): Boolean {
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
            return false
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean {
            return false
        }

        override fun onLongPress(e: MotionEvent?) {

        }
    })

    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    interface Callback {
        fun onBlockSelectStateChanged(block: TextBlockGraphic?)
    }
}
