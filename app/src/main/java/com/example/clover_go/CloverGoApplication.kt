package com.example.clover_go

import androidx.multidex.MultiDexApplication
import com.clover.sdk.gosdk.GoSdk
import com.clover.sdk.gosdk.GoSdkConfiguration
import com.clover.sdk.gosdk.GoSdkCreator
import timber.log.Timber

class CloverGoApplication : MultiDexApplication() {

    private lateinit var goSdk: GoSdk

    companion object {
        private const val APP_ID = "YOUR_APP_ID"
        private const val APP_VERSION = "1.0.0"
        private const val API_KEY = "YOUR_API_KEY"
        private const val API_SECRET = "YOUR_API_SECRET"
        private const val OAUTH_APP_SECRET = "YOUR_OAUTH_APP_SECRET"
        private const val OAUTH_REDIRECT_URI = "your-app-scheme://oauth-callback"

        private lateinit var instance: CloverGoApplication

        fun getInstance(): CloverGoApplication {
            return instance
        }
    }

    init {
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())

        // Initialize Clover Go SDK
        initializeCloverGoSDK()
    }

    private fun initializeCloverGoSDK() {
        try {
            val config = GoSdkConfiguration.Builder(
                context = applicationContext,
                appId = APP_ID,
                appVersion = APP_VERSION,
                apiKey = API_KEY,
                apiSecret = API_SECRET,
                oAuthFlowAppSecret = OAUTH_APP_SECRET,
                oAuthFlowRedirectURI = OAUTH_REDIRECT_URI,
                oAuthFlowAppID = APP_ID,
                environment = GoSdkConfiguration.Environment.SANDBOX,
                reconnectLastConnectedReader = true
            ).build()

            goSdk = GoSdkCreator.create(config)
            Timber.d("Clover Go SDK initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Clover Go SDK initialization failed")
        }
    }

    fun getGoSdk(): GoSdk {
        return goSdk
    }
}