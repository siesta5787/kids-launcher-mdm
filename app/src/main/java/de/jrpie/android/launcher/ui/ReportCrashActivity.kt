package de.jrpie.android.launcher.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.copyToClipboard
import de.jrpie.android.launcher.databinding.ActivityReportCrashBinding
import de.jrpie.android.launcher.getDeviceInfo

const val EXTRA_CRASH_LOG = "crashLog"

class ReportCrashActivity : AppCompatActivity() {
    // We don't know what caused the crash, so this Activity should use as little functionality as possible.
    // In particular it is not a UIObject (and hence looks quite ugly)
    private lateinit var binding: ActivityReportCrashBinding
    private var report: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise layout
        binding = ActivityReportCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setTitle(R.string.report_crash_title)
        setSupportActionBar(binding.reportCrashAppbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        report = intent.getStringExtra(EXTRA_CRASH_LOG)

        binding.reportCrashButtonCopy.setOnClickListener {
            copyToClipboard(
                this,
                "Device Info:\n${getDeviceInfo()}\n\nCrash Log:\n${report}"
            )
        }

    }
}