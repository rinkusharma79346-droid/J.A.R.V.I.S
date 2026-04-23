package com.jarvis.agent

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private var validateJob: Job? = null
    private var relayTestJob: Job? = null
    private var relayStatusJob: Job? = null
    private var relayDetailJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        val spinnerProvider = findViewById<Spinner>(R.id.spinnerProvider)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val tvBaseUrlLabel = findViewById<TextView>(R.id.tvBaseUrlLabel)
        val btnDetect = findViewById<Button>(R.id.btnDetect)
        val btnValidate = findViewById<Button>(R.id.btnValidate)
        val tvValidationResult = findViewById<TextView>(R.id.tvValidationResult)
        val seekMaxSteps = findViewById<SeekBar>(R.id.seekMaxSteps)
        val tvMaxSteps = findViewById<TextView>(R.id.tvMaxStepsValue)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        // Relay settings
        val etRelayUrl = findViewById<EditText>(R.id.etRelayUrl)
        val switchRelay = findViewById<Switch>(R.id.switchRelay)
        val tvRelayStatus = findViewById<TextView>(R.id.tvRelayStatus)
        val btnTestRelay = findViewById<Button>(R.id.btnTestRelay)

        val providers = listOf("Gemini", "OpenAI", "NVIDIA NIM", "Custom (OpenAI-compatible)")
        val providerKeys = listOf("gemini", "openai", "nvidia", "custom")
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = providerAdapter

        val currentProvider = SettingsManager.getProvider(this)
        spinnerProvider.setSelection(providerKeys.indexOf(currentProvider).coerceAtLeast(0))
        etApiKey.setText(SettingsManager.getApiKey(this, currentProvider))

        fun updateModels(providerKey: String) {
            val models = when (providerKey) { "gemini" -> SettingsManager.GEMINI_MODELS; "openai" -> SettingsManager.OPENAI_MODELS; "nvidia" -> SettingsManager.NVIDIA_MODELS; else -> SettingsManager.CUSTOM_MODELS }
            val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
            modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerModel.adapter = modelAdapter
            spinnerModel.setSelection(models.indexOf(SettingsManager.getModel(this, providerKey)).coerceAtLeast(0))
        }
        updateModels(currentProvider)

        fun updateBaseUrlVisibility(providerKey: String) {
            val show = providerKey == "custom"
            etBaseUrl.visibility = if (show) View.VISIBLE else View.GONE
            tvBaseUrlLabel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) etBaseUrl.setText(SettingsManager.getCustomBaseUrl(this))
        }
        updateBaseUrlVisibility(currentProvider)

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pk = providerKeys[position]
                etApiKey.setText(SettingsManager.getApiKey(this@SettingsActivity, pk))
                updateModels(pk); updateBaseUrlVisibility(pk)
                tvValidationResult.visibility = View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDetect.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isBlank()) { tvValidationResult.text = "Paste an API key first"; tvValidationResult.setTextColor(getColor(R.color.red_accent)); tvValidationResult.visibility = View.VISIBLE; return@setOnClickListener }
            val detected = SettingsManager.detectProvider(key)
            val idx = providerKeys.indexOf(detected).coerceAtLeast(0)
            spinnerProvider.setSelection(idx)
            tvValidationResult.text = "Detected: ${providers[idx]}"; tvValidationResult.setTextColor(getColor(R.color.cyan_primary)); tvValidationResult.visibility = View.VISIBLE
        }

        btnValidate.setOnClickListener {
            val pk = providerKeys[spinnerProvider.selectedItemPosition]
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isBlank()) { tvValidationResult.text = "Enter an API key first"; tvValidationResult.setTextColor(getColor(R.color.red_accent)); tvValidationResult.visibility = View.VISIBLE; return@setOnClickListener }
            btnValidate.isEnabled = false; btnValidate.text = "Testing..."; tvValidationResult.text = "Connecting..."; tvValidationResult.setTextColor(getColor(R.color.amber_accent)); tvValidationResult.visibility = View.VISIBLE
            validateJob?.cancel()
            validateJob = CoroutineScope(Dispatchers.Main).launch {
                val config = ProviderConfig(pk, apiKey, spinnerModel.selectedItem?.toString() ?: "", etBaseUrl.text.toString().trim())
                val result = ProviderFactory.create(config).validate()
                btnValidate.isEnabled = true; btnValidate.text = "TEST CONNECTION"
                if (result.success) { tvValidationResult.text = "✓ ${result.message}"; tvValidationResult.setTextColor(getColor(R.color.green_accent)); SettingsManager.setApiKey(this@SettingsActivity, pk, apiKey); SettingsManager.setProvider(this@SettingsActivity, pk) }
                else { tvValidationResult.text = "✗ ${result.message}"; tvValidationResult.setTextColor(getColor(R.color.red_accent)) }
            }
        }

        val currentMaxSteps = SettingsManager.getMaxSteps(this)
        seekMaxSteps.progress = currentMaxSteps; tvMaxSteps.text = "$currentMaxSteps steps"
        seekMaxSteps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { tvMaxSteps.text = "$p steps" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { SettingsManager.setMaxSteps(this@SettingsActivity, s?.progress ?: 50) }
        })

        // ─── Relay settings ───
        val savedRelayUrl = RelayClient.getRelayUrl()
        etRelayUrl.setText(if (savedRelayUrl.isNotBlank()) savedRelayUrl else SettingsManager.DEFAULT_RELAY_URL)
        switchRelay.isChecked = RelayClient.isEnabled()

        // Observe relay connection status
        relayStatusJob = CoroutineScope(Dispatchers.Main).launch {
            RelayClient.isConnected.collectLatest { connected ->
                tvRelayStatus.text = if (connected) "● Connected" else "○ Disconnected"
                tvRelayStatus.setTextColor(getColor(if (connected) R.color.green_accent else R.color.gray))
            }
        }
        relayDetailJob = CoroutineScope(Dispatchers.Main).launch {
            RelayClient.relayStatus.collectLatest { status ->
                if (!RelayClient.isConnected.value) {
                    tvRelayStatus.text = "○ $status"
                    tvRelayStatus.setTextColor(getColor(R.color.gray))
                }
            }
        }

        switchRelay.setOnCheckedChangeListener { _, checked ->
            val url = etRelayUrl.text.toString().trim()
            if (url.isNotBlank()) RelayClient.setRelayUrl(url)
            RelayClient.setEnabled(checked)
            if (checked) {
                RelayClient.connect()
            } else {
                RelayClient.disconnect()
            }
        }

        etRelayUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val url = etRelayUrl.text.toString().trim()
                if (url.isNotBlank()) RelayClient.setRelayUrl(url)
            }
        }

        // Test relay connection
        btnTestRelay.setOnClickListener {
            val url = etRelayUrl.text.toString().trim()
            if (url.isBlank()) {
                tvRelayStatus.text = "○ Enter relay URL first"
                tvRelayStatus.setTextColor(getColor(R.color.red_accent))
                return@setOnClickListener
            }

            RelayClient.setRelayUrl(url)
            btnTestRelay.isEnabled = false
            btnTestRelay.text = "Connecting..."
            tvRelayStatus.text = "○ Testing..."
            tvRelayStatus.setTextColor(getColor(R.color.amber_accent))

            relayTestJob?.cancel()
            relayTestJob = CoroutineScope(Dispatchers.Main).launch {
                // Disconnect existing and try fresh
                RelayClient.disconnect()
                delay(500)
                switchRelay.isChecked = true
                RelayClient.setEnabled(true)
                RelayClient.connect()

                // Wait up to 10 seconds for connection
                var waited = 0
                while (waited < 10000 && !RelayClient.isConnected.value) {
                    delay(500)
                    waited += 500
                }

                btnTestRelay.isEnabled = true
                btnTestRelay.text = "TEST RELAY"

                if (RelayClient.isConnected.value) {
                    tvRelayStatus.text = "● Connected!"
                    tvRelayStatus.setTextColor(getColor(R.color.green_accent))
                } else {
                    tvRelayStatus.text = "✗ Connection failed"
                    tvRelayStatus.setTextColor(getColor(R.color.red_accent))
                }
            }
        }

        btnBack.setOnClickListener {
            val pk = providerKeys[spinnerProvider.selectedItemPosition]
            SettingsManager.setProvider(this, pk)
            SettingsManager.setApiKey(this, pk, etApiKey.text.toString().trim())
            SettingsManager.setModel(this, pk, spinnerModel.selectedItem?.toString() ?: "")
            if (pk == "custom") SettingsManager.setCustomBaseUrl(this, etBaseUrl.text.toString().trim())
            val relayUrl = etRelayUrl.text.toString().trim()
            if (relayUrl.isNotBlank()) RelayClient.setRelayUrl(relayUrl)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        validateJob?.cancel()
        relayTestJob?.cancel()
        relayStatusJob?.cancel()
        relayDetailJob?.cancel()
    }
}
