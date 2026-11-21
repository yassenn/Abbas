package ai.alal.app

import android.app.Application
import ai.alal.app.net.NetworkPrivacy

/** Process-wide setup that must exist before any activity, service or worker runs. */
class AlalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NetworkPrivacy.init(this)
        ai.alal.app.inference.ModelCatalog.init(this)
        ai.alal.app.inference.CatalogWorker.schedule(this, ai.alal.app.inference.ModelCatalog.isAutoUpdate(this))
    }
}
