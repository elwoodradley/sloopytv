package tv.tube

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import tv.tube.data.AndroidHttp
import tv.tube.data.Catalog
import tv.tube.data.Prefs
import tv.tube.data.ProviderStore
import tv.tube.data.Vault
import tv.tube.data.db.TubeDb

/** Plain service locator. Small app, no DI framework. */
class Graph(app: Application) {
    val vault = Vault(app)
    val prefs = Prefs(app)
    val providers = ProviderStore(vault)
    val db = TubeDb.open(app)
    val catalog = Catalog(db, providers, prefs)
}

class TubeApp : Application(), SingletonImageLoader.Factory {
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
        fun graph(context: android.content.Context): Graph = (context.applicationContext as TubeApp).graph
    }
}
