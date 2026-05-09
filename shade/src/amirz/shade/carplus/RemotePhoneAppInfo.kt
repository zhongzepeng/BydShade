package amirz.shade.carplus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Base64
import com.android.launcher3.AppInfo
import com.android.launcher3.ItemInfo
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.LauncherIcons

class RemotePhoneAppInfo private constructor(
    val descriptor: PhoneAppDescriptor
) : AppInfo() {

    init {
        title = descriptor.windowTitle
        contentDescription = descriptor.windowTitle
        user = Process.myUserHandle()
        container = ItemInfo.NO_ID
        componentName = ComponentName(
            REMOTE_COMPONENT_PACKAGE,
            "$REMOTE_COMPONENT_PACKAGE.RemoteApp${descriptor.key.hashCode().toString().replace('-', '_')}"
        )
        intent = Intent(ACTION_LAUNCH_REMOTE_APP)
            .setComponent(componentName)
            .putExtra(EXTRA_REMOTE_PACKAGE, descriptor.packageName)
            .putExtra(EXTRA_REMOTE_CLASS, descriptor.className)
    }

    override fun clone(): AppInfo {
        return RemotePhoneAppInfo(descriptor).also {
            it.iconBitmap = iconBitmap
            it.iconColor = iconColor
            it.title = title
            it.contentDescription = contentDescription
            it.user = user
            it.intent = Intent(intent)
            it.componentName = componentName
        }
    }

    companion object {
        const val ACTION_LAUNCH_REMOTE_APP = "amirz.shade.carplus.action.LAUNCH_REMOTE_APP"
        const val EXTRA_REMOTE_PACKAGE = "extra_remote_package"
        const val EXTRA_REMOTE_CLASS = "extra_remote_class"

        private const val REMOTE_COMPONENT_PACKAGE = "amirz.shade.remote"

        fun fromDescriptor(context: Context, descriptor: PhoneAppDescriptor): RemotePhoneAppInfo {
            val info = RemotePhoneAppInfo(descriptor)
            info.applyFrom(loadBitmapInfo(context, descriptor))
            return info
        }

        private fun loadBitmapInfo(context: Context, descriptor: PhoneAppDescriptor): BitmapInfo {
            val decoded = tryDecodeBitmap(descriptor.iconBase64)
            val icons = LauncherIcons.obtain(context)
            try {
                val base = decoded ?: drawableToBitmap(context.packageManager.defaultActivityIcon)
                return icons.createIconBitmap(addRemoteBadge(base))
            } finally {
                icons.recycle()
            }
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            return when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
        }

        private fun addRemoteBadge(source: Bitmap): Bitmap {
            val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)
            val minSize = minOf(mutable.width, mutable.height).toFloat()
            val outerRadius = minSize * 0.14f
            val innerRadius = minSize * 0.11f
            val centerX = mutable.width - outerRadius - minSize * 0.06f
            val centerY = outerRadius + minSize * 0.06f
            canvas.drawCircle(centerX, centerY, outerRadius, BADGE_STROKE_PAINT)
            canvas.drawCircle(centerX, centerY, innerRadius, BADGE_FILL_PAINT)
            return mutable
        }

        private fun tryDecodeBitmap(base64: String) =
            if (base64.isBlank()) {
                null
            } else {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (invalid: IllegalArgumentException) {
                    null
                }
            }

        private val BADGE_STROKE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }

        private val BADGE_FILL_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt()
            style = Paint.Style.FILL
        }
    }
}
