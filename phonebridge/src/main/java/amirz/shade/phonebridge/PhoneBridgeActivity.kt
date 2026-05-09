package amirz.shade.phonebridge

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class PhoneBridgeActivity : ComponentActivity() {
    private lateinit var stateView: TextView
    private lateinit var endpointView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        stateView = TextView(this)
        endpointView = TextView(this)

        val start = Button(this).apply {
            text = getString(R.string.phone_bridge_start)
            setOnClickListener {
                startBridgeService()
                render()
            }
        }
        val stop = Button(this).apply {
            text = getString(R.string.phone_bridge_stop)
            setOnClickListener {
                stopService(Intent(this@PhoneBridgeActivity, PhoneBridgeService::class.java))
                render()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (resources.displayMetrics.density * 24f).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = getString(R.string.phone_bridge_title)
                textSize = 22f
            })
            addView(TextView(context).apply {
                text = getString(R.string.phone_bridge_summary)
            })
            addView(stateView)
            addView(endpointView)
            addView(start)
            addView(stop)
        }

        setContentView(root)
        startBridgeService()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        stateView.text = getString(
            if (PhoneBridgeService.isRunning) {
                R.string.phone_bridge_running
            } else {
                R.string.phone_bridge_stopped
            }
        )
        endpointView.text = getString(
            R.string.phone_bridge_endpoint,
            "ws://${NetworkUtils.findIpv4Address()}:${BridgeProtocol.DEFAULT_PORT} / ${ProjectionProtocol.RPC_SOCKET_NAME}"
        )
    }

    private fun startBridgeService() {
        val intent = Intent(this, PhoneBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
