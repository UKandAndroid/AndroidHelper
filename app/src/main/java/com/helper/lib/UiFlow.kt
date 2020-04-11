package com.helper.lib

import android.app.Activity
import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleObserver
import android.arch.lifecycle.OnLifecycleEvent
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.*

class UiFlow:LifecycleObserver {
        private var code:Code ? = null
        private var viewActRoot:View? =null
        private var iSoftInputMode = -1
        private var rLast = Rect()
        private var iThreadCount = 0
        private var bPause = false
        private var bKeybVisible = false
        private var hThread = HThread()
        private var keyList = ArrayList<KeyboardState>()
        private var flowListeners = ArrayList<FlowListener>()
        private var listTextListeners = hashMapOf<Any, Any>() // list of text change listeners for a text field
        private var listKBListeners = hashMapOf<Any, Any>() // list of keyboard state change listeners
        private interface KeyboardState {
                fun onStateChange(bVisible:Boolean)
        }

        enum class EventListener {
                // EVENTS for which listeners are set
                 TOUCH, ON_CLICK,
                 TEXT_CHANGED,
                 TEXT_ENTERED,
                 CHECKBOX_STATE,
                 LIST_ITEM_SELECT,
                 SPINNER_ITEM_SELECT,
                 KEYBOARD_STATE_CHANGE, // works only for android:windowSoftInputMode="adjustResize" or adjustPan
                 LOAD_LAYOUT // called when a view is loaded with width and height set
        }

        interface Code {
                fun onAction(action:Int, bSuccess:Boolean, iExtra:Int, tag:Any)
        }

        private abstract inner class FlowListener internal constructor(view:View, iAction:Int, bRunOnUI:Boolean) {
                protected var view:View
                protected var iAction:Int = 0
                protected var bRunOnUI:Boolean = false
                init{
                        this.view = view
                        this.iAction = iAction
                        this.bRunOnUI = bRunOnUI
                }
                internal abstract fun register():FlowListener
                internal abstract fun unRegister()
        }
        constructor() {}

        constructor(codeCallback:Code) {
                this.code = codeCallback
        }

        // METHODS registers/un registers UI events for Action
        fun unRegisterUIEvent(view:View, iEvent:EventListener) {
                unRegisterListener(view, iEvent)
        }
        fun registerClick(view:View) {
                registerListener(false, -1, view, EventListener.ON_CLICK)
        }
        fun registerClick(iAction:Int, view:View) {
                registerListener(false, iAction, view, EventListener.ON_CLICK)
        }
        fun registerUiEvent(view:View, iEvent:EventListener):UiFlow {
                registerListener(false, -1, view, iEvent)
                return this
        }
        fun registerUiEvent(iAction:Int, view:View, iEvent:EventListener):UiFlow {
                registerListener(false, iAction, view, iEvent)
                return this
        }
        fun registerUiEvent(iAction:Int, bRunOnUI:Boolean, view:View) {
                registerListener(bRunOnUI, iAction, view, EventListener.ON_CLICK)
        }
        fun registerUiEvent(bRunOnUI:Boolean, view:View, iEvent:EventListener):UiFlow {
                registerListener(bRunOnUI, -1, view, iEvent)
                return this
        }
        fun registerUiEvent(iAction:Int, bRunOnUI:Boolean, view:View, iEvent:EventListener):UiFlow {
                registerListener(bRunOnUI, iAction, view, iEvent)
                return this
        }
        // VIEW LISTENERS set event listeners for View objects
        private fun registerListener(bRunOnUI:Boolean, iAction:Int, view:View, iListener:EventListener) {
                when (iListener) {
                        // Triggered when Text entered in text field, i.e when text field loses focus, enter button is pressed on keyboard
                        // for text entered to work with keyboard hide, set android:windowSoftInputMode="adjustResize" or "adjustPan"
                        // and setup KEYBOARD_STATE UiEvent, provided with main activity root decor view
                        EventListener.TEXT_ENTERED -> flowListeners.add(TextEntered(view, iAction, bRunOnUI).register())
                        // Triggered when text changes
                        EventListener.TEXT_CHANGED -> flowListeners.add(TextChanged(view, iAction, bRunOnUI).register())
                        // Triggered when ui layout changes with width/height values > 0 and called only once
                        EventListener.LOAD_LAYOUT -> flowListeners.add(LoadLayout(view, iAction, bRunOnUI).register())
                        // triggered listener when view is clicked
                        EventListener.ON_CLICK -> flowListeners.add(OnClick(view, iAction, bRunOnUI).register())
                        EventListener.KEYBOARD_STATE_CHANGE // Method reports keyboard state change, should be provided with root activity view (activity.window.decorView)
                        -> flowListeners.add(KeyboardStateChange(view, iAction, bRunOnUI).register())
                        EventListener.LIST_ITEM_SELECT -> flowListeners.add(ListItemSelect(view, iAction, bRunOnUI).register())
                        EventListener.SPINNER_ITEM_SELECT -> flowListeners.add(SpinnerItemSelect(view, iAction, bRunOnUI).register())
                        EventListener.CHECKBOX_STATE -> flowListeners.add(CheckboxState(view, iAction, bRunOnUI).register())
                        EventListener.TOUCH // Listener returns true for Touch down and Move, false when finger is lifted up
                        -> flowListeners.add(TouchListener(view, iAction, bRunOnUI).register())
                }
        }
        // VIEW LISTENERS set event listeners for View objects
        private fun unRegisterListener(view:View, iListener:EventListener) {
                when (iListener) {
                        EventListener.ON_CLICK -> {
                                if (view is EditText) {
                                        view.setOnFocusChangeListener(null)
                                }
                                view.setOnClickListener(null)
                        }
                        EventListener.TEXT_ENTERED -> {
                                listKBListeners.remove(view)
                                view.setOnFocusChangeListener(null)
                                (view as EditText).setOnEditorActionListener(null)
                        }
                        // case UiEvent.KEYBOARD_STATE_CHANGE: removeKeybListener(); break;
                        EventListener.TEXT_CHANGED -> listTextListeners.remove(view)
                        EventListener.LIST_ITEM_SELECT -> (view as ListView).setOnItemClickListener(null)
                        EventListener.SPINNER_ITEM_SELECT -> (view as Spinner).setOnItemSelectedListener(null)
                        EventListener.CHECKBOX_STATE -> (view as CheckBox).setOnCheckedChangeListener(null)
                        EventListener.TOUCH -> view.setOnTouchListener(null)
                        EventListener.LOAD_LAYOUT -> view.removeOnLayoutChangeListener(null)
                }
        }
        @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        fun pause() {
                bPause = true
        }
        @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
        fun resume() {
                bPause = false
        }
        @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
        fun stop() {
                hThread.stop()
                for (listener in flowListeners) {
                        listener.unRegister()
                }

                flowListeners = arrayListOf()
        }
        // CLASS for thread handler
        private inner class HThread internal constructor():Handler.Callback {
                private val mHandler:Handler
                private val mUiHandler:Handler
                init{
                        val ht = HandlerThread("BGThread_" + ++iThreadCount)
                        ht.start()
                        mHandler = Handler(ht.getLooper(), this)
                        mUiHandler = Handler(Looper.getMainLooper(), this)
                }

                fun run(iStep: Int) {
                        run(iStep, false)
                }

                fun run(iStep: Int, bRunUI: Boolean) {
                        if (bRunUI) {
                                runOnUI(iStep, true, 0, null!!)
                        } else {
                                run(iStep, true, 0, null!!)
                        }
                }

                fun run(iStep: Int, bSuccess: Boolean, iExtra: Int, obj: Any) {
                        if (!bPause) {
                                val msg = Message.obtain()
                                msg.what = iStep
                                msg.arg1 = iExtra
                                msg.arg2 = if (bSuccess) 1 else 0
                                msg.obj = obj
                                mHandler.sendMessage(msg)
                        }
                }
                 fun runOnUI(iStep:Int, bSuccess:Boolean, iExtra:Int, obj:Any) {
                        if (!bPause) {
                                val msg = Message.obtain()
                                msg.what = iStep
                                msg.arg1 = iExtra
                                msg.arg2 = if (bSuccess) 1 else 0
                                msg.obj = obj
                                mUiHandler.sendMessage(msg)
                        }
                }

                // METHOD MESSAGE HANDLER
                override fun handleMessage(msg:Message):Boolean {
                        code!!.onAction(msg.what, msg.arg2 == 1, msg.arg1, msg.obj)
                        return true
                }

                fun stop() {
                        mHandler.removeCallbacksAndMessages(null)
                        mUiHandler.removeCallbacksAndMessages(null)
                        mHandler.getLooper().quit()
                }
        }
        // LIST_ITEM_SELECT
        private inner class SpinnerItemSelect internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                internal override fun register():FlowListener {
                        (view as Spinner).onItemSelectedListener = object:AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(parent:AdapterView<*>, view:View, position:Int, id:Long) {
                                        if (bRunOnUI) {
                                                hThread.runOnUI(iAction, true, position, view)
                                        } else {
                                                hThread.run(iAction, true, position, view)
                                        }
                                }

                                override fun onNothingSelected(parent:AdapterView<*>) {
                                        if (bRunOnUI) {
                                                hThread.runOnUI(iAction, false, -1, view)
                                        } else {
                                                hThread.run(iAction, false, -1, view)
                                        }
                                }
                        }
                        return this
                }
                internal override fun unRegister() {
                        (view as Spinner).setOnItemClickListener(null)
                }
        }

        // CHECK_BOX_STATE
        private inner class TouchListener internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                internal override fun register():FlowListener {
                        view.setOnTouchListener { v, event ->
                                if (bRunOnUI) {
                                        hThread.runOnUI(iAction, event.getAction() !== MotionEvent.ACTION_UP, event.getAction(), event)
                                } else {
                                        hThread.run(iAction, event.getAction() !== MotionEvent.ACTION_UP, event.getAction(), event)
                                }
                                false
                        }
                        return this
                }
                internal override fun unRegister() {
                        view.setOnTouchListener(null)
                }
        }

        // CHECK_BOX_STATE
        private inner class CheckboxState internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                internal override fun register():FlowListener {
                        (view as CheckBox).setOnCheckedChangeListener { buttonView, isChecked-> if (bRunOnUI) {
                                hThread.runOnUI(iAction, isChecked, 0, view)
                        } else {
                                hThread.run(iAction, isChecked, 0, view)
                        } }
                        return this
                }
                internal override fun unRegister() {
                        (view as CheckBox).setOnCheckedChangeListener(null)
                }
        }

        // LIST_ITEM_SELECT
        private inner class ListItemSelect internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                 override fun register():FlowListener {
                        (view as ListView).setOnItemClickListener { parent, view12, position, id-> if (bRunOnUI) {
                                hThread.runOnUI(iAction, true, position, view12)
                        } else {
                                hThread.run(iAction, true, position, view12)
                        } }
                         return this
                }

                override fun unRegister() {
                        (view as ListView).onItemClickListener = null
                }
        }

        // TEXT_ENTERED
        private inner class TextChanged internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                private lateinit var listener:TextWatcher

                override fun register():FlowListener {
                        listener = object:TextWatcher {
                                override  fun afterTextChanged(s:Editable) {}
                                override fun beforeTextChanged(s:CharSequence, start:Int, count:Int, after:Int) {}
                                override fun onTextChanged(s:CharSequence, start:Int, before:Int, count:Int) {
                                        if (bRunOnUI)
                                        {
                                                hThread.runOnUI(iAction, true, 0, view)
                                        }
                                        else
                                        {
                                                hThread.run(iAction, true, 0, view)
                                        }
                                }
                        }
                        listTextListeners.put(view, listener)
                        (view as EditText).addTextChangedListener(listener)
                        return this
                }

                override fun unRegister() {
                        (view as EditText).removeTextChangedListener(listener)
                }
        }

        // TEXT_ENTERED
        private inner class TextEntered internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {

                override fun register(): FlowListener {
                        val listKb = object : KeyboardState {
                                override fun onStateChange(bVisible: Boolean) {
                                        if (view.hasFocus() && !bVisible) {
                                                if (bRunOnUI) {
                                                        hThread.runOnUI(iAction, bVisible, 0, view)
                                                } else {
                                                        hThread.run(iAction, bVisible, 0, view)
                                                }
                                        }
                                }
                        }

                        listKBListeners.put(view, listKb)
                        addKeybListener(listKb)

                        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                                if (!hasFocus) {
                                        Log.w("UiFlow", "Text ENTERED on Lost focus")
                                        if (bRunOnUI) {
                                                hThread.runOnUI(iAction, true, 0, view)
                                        } else {
                                                hThread.run(iAction, true, 1, view)
                                        }
                                }
                        }

                        (view as EditText).setOnEditorActionListener { v, actionId, event->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                        Log.w("UiFlow", "Text ENTERED on KB Done")
                                        if (bRunOnUI) {
                                                hThread.runOnUI(iAction, true, 0, view)
                                        } else {
                                                hThread.run(iAction, true, 3, view)
                                        }
                                }
                                false }
                        return this
                }

                override fun unRegister() {
                        view.onFocusChangeListener = null
                        (view as EditText).setOnEditorActionListener(null)
                }

                private fun addKeybListener(keyListener:KeyboardState) {
                        keyList.add(keyListener)
                }
        }
        // LOAD_LAYOUT
        private inner class LoadLayout internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                private lateinit var listener:View.OnLayoutChangeListener

                public override fun register():FlowListener {
                        listener = object:View.OnLayoutChangeListener {
                                override fun onLayoutChange(view:View, i:Int, i1:Int, i2:Int, i3:Int, i4:Int, i5:Int, i6:Int, i7:Int) {
                                        if ((i + i1 + i2 + i3) > 0) {
                                                view.removeOnLayoutChangeListener(this)
                                                if (bRunOnUI) {
                                                        hThread.runOnUI(iAction, true, 0, view)
                                                } else {
                                                        hThread.run(iAction, true, 0, view)
                                                }
                                        }
                                }
                        }
                        view.addOnLayoutChangeListener(listener)
                        return this
                }

                public override fun unRegister() {
                        view.removeOnLayoutChangeListener(listener)
                }
        }
        // ON_CLICK
        private inner class OnClick internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                private var bIsFocusListener = false

                 override fun register():FlowListener {
                        if (view is EditText) { // NOTE: for editText first tap get focus, 2nd to trigger onClick, unless focusable is setfalse()
                                bIsFocusListener = true
                                view.setOnFocusChangeListener { v, hasFocus-> if (hasFocus) {
                                        if (bRunOnUI) {
                                                hThread.runOnUI(iAction, true, 0, view)
                                        } else {
                                                hThread.run(iAction, true, 0, view)
                                        }
                                } }
                        }

                        view.setOnClickListener { view1->
                                bIsFocusListener = false
                                if (bRunOnUI) {
                                        hThread.runOnUI(iAction, true, 0, view1)
                                } else {
                                        hThread.run(iAction, true, 0, view1)
                                } }
                        return this
                }

                 override fun unRegister() {
                        if (bIsFocusListener) {
                                view.onFocusChangeListener = null
                        } else {
                                view.setOnClickListener(null)
                        }
                }
        }

        // LOAD_LAYOUT
        private inner class KeyboardStateChange internal constructor(view:View, iAction:Int, bRunOnUI:Boolean):FlowListener(view, iAction, bRunOnUI) {
                private val keybListener = ViewTreeObserver.OnGlobalLayoutListener {
                        val rCur = Rect()
                        viewActRoot?.getWindowVisibleDisplayFrame(rCur)
                        if (rLast.bottom == 0) {
                                rLast.bottom = viewActRoot!!.height
                        } // just get size of window, something to start with

                        if ((rLast.bottom - rCur.bottom) > 200) { // means keyboard is visible
                                if (!bKeybVisible) { // if its not already set set it
                                        bKeybVisible = true
                                        rLast = rCur
                                        for (i in keyList.indices)
                                                keyList[i].onStateChange(true)
                                }
                        } else if ((rCur.bottom - rLast.bottom) > 200) {
                                if (bKeybVisible) {
                                        bKeybVisible = false
                                        rLast = rCur
                                        for (i in keyList.indices)
                                                keyList[i].onStateChange(false)
                                }
                        }
                }

                 override fun register():FlowListener {
                        val list = object:KeyboardState {
                                 override fun onStateChange(bVisible:Boolean) {
                                        if (view.hasFocus()) {
                                                if (bRunOnUI) {
                                                        hThread.runOnUI(iAction, bVisible, 0, view)
                                                } else {
                                                        hThread.run(iAction, bVisible, 0, view)
                                                }
                                        }
                                }
                        }
                        setUpKeybListener(list, view)
                        return this
                }

                // METHOD - sets up a listener for keyboard state change, also change SoftInputMode if its not correct
                private fun setUpKeybListener(keyListener:KeyboardState, view:View) {
                        val act = (view as ViewGroup).getChildAt(0).getContext() as Activity // Change soft input mode to SOFT_INPUT_ADJUST_PAN or SOFT_INPUT_ADJUST_RESIZE, for it to work
                        val window = act.getWindow()
                        iSoftInputMode = window.getAttributes().softInputMode // save it so we can restore, when keyboard listener is removed
                        if (iSoftInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN && iSoftInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
                        rLast = Rect() // set a new rect for storing screen state
                        viewActRoot = view
                        keyList.add(keyListener)
                        view.getViewTreeObserver().addOnGlobalLayoutListener(keybListener)
                }
                 override fun unRegister() {
                        removeKeybListener()
                }
                
                // METHOD - sets/removes global keyboard listener, also sets resets SoftInputMode
                private fun removeKeybListener() {
                        viewActRoot?.viewTreeObserver?.removeOnGlobalLayoutListener(keybListener)
                        val act = (viewActRoot as ViewGroup).getChildAt(0).context as Activity
                        val window = act.window
                        iSoftInputMode = window.attributes.softInputMode // save it so we can restore, when keyboard listener is removed
                        if (iSoftInputMode != -1)
                                window.setSoftInputMode(iSoftInputMode)
                }
        }
}