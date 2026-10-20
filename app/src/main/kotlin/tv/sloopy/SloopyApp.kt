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
        CrashLog.install(this)
        // Reads the system's exit records and may write a small file: off the main thread, so a
        // slow app drive (a Shield with apps on a USB stick) cannot hold up the first frame.
        Thread({ runCatching { CrashLog.noteSystemExit(this) } }, "exit-reasons").start()
        graph = Graph(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { AndroidHttp.client })) }
            .crossfade(false)   // hard cuts only; a fade per logo is work the guide does not need
            .build()

    companion object {
        fun graph(context: android.content.Context): Graph = (context.applicationContext as SloopyApp).graph
    }
}
