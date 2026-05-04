package com.vayu.agent

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "vayu_settings"
    private const val KEY_PROVIDER = "api_provider"
    private const val KEY_API_KEY_GEMINI = "api_key_gemini"
    private const val KEY_API_KEY_OPENAI = "api_key_openai"
    private const val KEY_API_KEY_NVIDIA = "api_key_nvidia"
    private const val KEY_API_KEY_CUSTOM = "api_key_custom"
    private const val KEY_MODEL_GEMINI = "model_gemini"
    private const val KEY_MODEL_OPENAI = "model_openai"
    private const val KEY_MODEL_NVIDIA = "model_nvidia"
    private const val KEY_MODEL_CUSTOM = "model_custom"
    private const val KEY_BASE_URL_CUSTOM = "base_url_custom"
    private const val KEY_MAX_STEPS = "max_steps"
    private const val KEY_ACTION_DELAY = "action_delay"

    const val DEFAULT_RELAY_URL = "https://j-a-r-v-i-s-ktlh.onrender.com"

    val GEMINI_MODELS = listOf("gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-pro", "gemini-1.5-flash")
    val OPENAI_MODELS = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano")
    val NVIDIA_MODELS = listOf("nvidia/llama-3.1-nemotron-70b-instruct", "meta/llama-3.1-405b-instruct", "google/gemma-2-27b-it", "mistralai/mixtral-8x22b-instruct-v0.1")
    val CUSTOM_MODELS = listOf("default")

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProvider(context: Context): String = prefs(context).getString(KEY_PROVIDER, "gemini") ?: "gemini"
    fun setProvider(context: Context, provider: String) = prefs(context).edit().putString(KEY_PROVIDER, provider).apply()

    fun getApiKey(context: Context, provider: String? = null): String {
        val p = provider ?: getProvider(context)
        val key = when (p) { "gemini" -> KEY_API_KEY_GEMINI; "openai" -> KEY_API_KEY_OPENAI; "nvidia" -> KEY_API_KEY_NVIDIA; "custom" -> KEY_API_KEY_CUSTOM; else -> KEY_API_KEY_GEMINI }
        return prefs(context).getString(key, "") ?: ""
    }

    fun setApiKey(context: Context, provider: String, apiKey: String) {
        val key = when (provider) { "gemini" -> KEY_API_KEY_GEMINI; "openai" -> KEY_API_KEY_OPENAI; "nvidia" -> KEY_API_KEY_NVIDIA; "custom" -> KEY_API_KEY_CUSTOM; else -> KEY_API_KEY_GEMINI }
        prefs(context).edit().putString(key, apiKey).apply()
    }

    fun getModel(context: Context, provider: String? = null): String {
        val p = provider ?: getProvider(context)
        val key = when (p) { "gemini" -> KEY_MODEL_GEMINI; "openai" -> KEY_MODEL_OPENAI; "nvidia" -> KEY_MODEL_NVIDIA; "custom" -> KEY_MODEL_CUSTOM; else -> KEY_MODEL_GEMINI }
        val default = when (p) { "gemini" -> GEMINI_MODELS[0]; "openai" -> OPENAI_MODELS[0]; "nvidia" -> NVIDIA_MODELS[0]; else -> "default" }
        return prefs(context).getString(key, default) ?: default
    }

    fun setModel(context: Context, provider: String, model: String) {
        val key = when (provider) { "gemini" -> KEY_MODEL_GEMINI; "openai" -> KEY_MODEL_OPENAI; "nvidia" -> KEY_MODEL_NVIDIA; "custom" -> KEY_MODEL_CUSTOM; else -> KEY_MODEL_GEMINI }
        prefs(context).edit().putString(key, model).apply()
    }

    fun getCustomBaseUrl(context: Context): String = prefs(context).getString(KEY_BASE_URL_CUSTOM, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    fun setCustomBaseUrl(context: Context, url: String) = prefs(context).edit().putString(KEY_BASE_URL_CUSTOM, url).apply()

    fun getMaxSteps(context: Context): Int = prefs(context).getInt(KEY_MAX_STEPS, 50)
    fun setMaxSteps(context: Context, steps: Int) = prefs(context).edit().putInt(KEY_MAX_STEPS, steps).apply()

    fun getActionDelay(context: Context): Long = prefs(context).getLong(KEY_ACTION_DELAY, 800L)
    fun setActionDelay(context: Context, delay: Long) = prefs(context).edit().putLong(KEY_ACTION_DELAY, delay).apply()

    fun getConfig(context: Context): ProviderConfig {
        val provider = getProvider(context)
        val apiKey = getApiKey(context, provider)
        val model = getModel(context, provider)
        val baseUrl = if (provider == "custom") getCustomBaseUrl(context) else ""
        val supportsVision = when (provider) { "gemini" -> true; "openai" -> model.contains("4o") || model.contains("4-turbo"); "nvidia" -> false; else -> true }
        return ProviderConfig(provider, apiKey, model, baseUrl, supportsVision)
    }

    fun detectProvider(apiKey: String): String = when {
        apiKey.startsWith("AIza", ignoreCase = true) -> "gemini"
        apiKey.startsWith("sk-", ignoreCase = true) -> "openai"
        apiKey.startsWith("nvapi-", ignoreCase = true) -> "nvidia"
        else -> "custom"
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context, getProvider(context)).isNotBlank()
}
