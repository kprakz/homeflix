package com.homeflix.app

import android.app.Application
import android.content.Context
import com.homeflix.app.data.ApiClient
import com.homeflix.app.data.HomeflixRepository
import com.homeflix.app.data.HomeflixStore
import com.homeflix.app.discovery.LanScanner
import com.homeflix.app.discovery.NsdDiscovery
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

class HomeflixApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val api = ApiClient()
    val store = HomeflixStore(appContext)
    val repository = HomeflixRepository(api, store)
    val discovery = NsdDiscovery(appContext)
    val lanScanner = LanScanner(appContext)
}
