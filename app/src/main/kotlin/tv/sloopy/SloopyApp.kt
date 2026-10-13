package tv.sloopy

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import tv.sloopy.data.AndroidHttp
import tv.sloopy.data.Catalog
import tv.sloopy.data.Prefs
import tv.sloopy.data.ProviderStore
import tv.sloopy.data.Vault
import tv.sloopy.data.db.SloopyDb

/** Plain service locator. Small app, no DI framework. */
class Graph(app: Application) {
    val vault = Vault(app)
    val prefs = Prefs(app)
    val providers = ProviderStore(vault)
    val db = SloopyDb.open(app)
    val catalog = Catalog(db, providers, prefs)
    val updater = tv.sloopy.update.Updater(app)
}

class SloopyApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { AndroidHttp.client })) }
            .crossfade(true)
            .build()

    companion object {
        fun graph(context: android.content.Context): Graph = (context.applicationContext as SloopyApp).graph
    }
}
