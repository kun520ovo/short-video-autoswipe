package com.shortvideo.autoswipe.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.model.TargetApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class InstalledIcon(
    context: Context,
    private val scope: CoroutineScope,
    private val loader: suspend (TargetApp) -> Bitmap?,
) : FrameLayout(context) {
    private val image = ImageView(context)
    private val fallback = context.label("", 12f, context.appPalette().ink)
    private var app: TargetApp? = null
    private var job: Job? = null
    private var loaded = false

    init {
        background = context.rounded(context.appPalette().field, 5)
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fallback.gravity = Gravity.CENTER
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(value: TargetApp) {
        if (app?.component != value.component) {
            job?.cancel()
            app = value
            loaded = false
            image.setImageDrawable(null)
            image.show(false)
        }
        fallback.showText(value.label.take(1))
        loadIcon()
    }

    private fun loadIcon() {
        val target = app ?: return
        if (!isAttachedToWindow || loaded || job?.isActive == true) return
        job = scope.launch {
            val bitmap = try { loader(target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (app?.component == target.component) {
                loaded = true
                image.setImageBitmap(bitmap)
                image.show(bitmap != null)
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); loadIcon() }
    override fun onDetachedFromWindow() {
        job?.cancel()
        job = null
        super.onDetachedFromWindow()
    }
}

internal class AppPickerDialog(
    context: Context,
    private val scope: CoroutineScope,
    private val loader: suspend (TargetApp) -> Bitmap?,
    private val selected: (TargetApp) -> Unit,
    private val dismissed: () -> Unit,
) : Dialog(context) {
    private var apps: List<TargetApp> = emptyList()
    private var programmaticDismiss = false
    private val palette = context.appPalette()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? AppRow ?: AppRow(context)).apply { bind(apps[position]) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(16))
            background = context.rounded(palette.field, 28)
        }
        content.addView(context.label("选择目标应用", 24f, palette.ink), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = context.dp(16)
        })
        val list = object : ListView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = (resources.displayMetrics.heightPixels - context.dp(200)).coerceAtLeast(context.dp(48))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(context.dp(400), available), MeasureSpec.AT_MOST))
            }
        }.apply {
            id = R.id.picker_list
            divider = null
            adapter = this@AppPickerDialog.adapter
            setOnItemClickListener { _, _, position, _ -> selected(apps[position]) }
        }
        content.addView(list, LinearLayout.LayoutParams(-1, -2))
        val cancel = context.label("取消", 14f, palette.accent, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            ripple((palette.accent and 0x00FFFFFF) or 0x22000000, 20)
            setOnClickListener { cancel() }
        }
        content.addView(cancel, LinearLayout.LayoutParams(context.dp(64), context.dp(48)).apply {
            gravity = Gravity.END
            topMargin = context.dp(8)
        })
        setContentView(content)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setOnDismissListener { if (!programmaticDismiss) dismissed() }
    }

    override fun onStart() {
        super.onStart()
        val width = (context.resources.displayMetrics.widthPixels - context.dp(48)).coerceAtLeast(context.dp(160))
        window?.setLayout(minOf(width, context.dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun updateApps(values: List<TargetApp>) {
        if (apps == values) return
        apps = values
        adapter.notifyDataSetChanged()
    }

    fun closeFromState() {
        programmaticDismiss = true
        dismiss()
    }

    private inner class AppRow(context: Context) : LinearLayout(context) {
        private val icon = InstalledIcon(context, scope, loader)
        private val title = context.label("", 16f, palette.ink)
        private val packageLabel = context.label("", 11f, palette.muted)
        private val componentLabel = context.label("", 11f, palette.muted)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(12), context.dp(8), context.dp(12))
            addView(icon, LayoutParams(context.dp(28), context.dp(28)).apply { marginEnd = context.dp(10) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title)
                addView(packageLabel)
                addView(componentLabel)
            }, LayoutParams(0, -2, 1f))
        }

        fun bind(app: TargetApp) {
            icon.bind(app)
            title.showText(app.label)
            packageLabel.showText(app.packageName)
            componentLabel.show(apps.count { it.packageName == app.packageName } > 1)
            componentLabel.showText(app.component.substringAfterLast('.'))
        }
    }
}
