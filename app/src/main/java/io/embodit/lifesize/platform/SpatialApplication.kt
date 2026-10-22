package io.embodit.lifesize.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.lifesize.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
