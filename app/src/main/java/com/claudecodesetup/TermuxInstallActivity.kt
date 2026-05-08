package com.claudecodesetup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.databinding.ActivityTermuxInstallBinding
import com.claudecodesetup.managers.BridgeManager

class TermuxInstallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTermuxInstallBinding
    private val bridge by lazy { BridgeManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTermuxInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenFdroid.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://f-droid.org/packages/com.termux/")))
        }

        binding.btnCheckInstalled.setOnClickListener {
            if (bridge.isTermuxInstalled()) {
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Termux not found yet — please install it first",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bridge.isTermuxInstalled()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }
}
