package com.sih.netrasahayak.di

import android.content.Context
import com.sih.netrasahayak.AppConfig
import com.sih.netrasahayak.camera.PhoneCameraController
import com.sih.netrasahayak.camera.RetinalCameraController
import com.sih.netrasahayak.database.NetraDatabase
import com.sih.netrasahayak.network.ConnectivityObserver
import com.sih.netrasahayak.network.RetrofitProvider
import com.sih.netrasahayak.repository.InferenceRepository
import com.sih.netrasahayak.repository.LocalInferenceRepository
import com.sih.netrasahayak.repository.MockInferenceRepository
import com.sih.netrasahayak.repository.MockSyncDataSource
import com.sih.netrasahayak.repository.RemoteInferenceRepository
import com.sih.netrasahayak.repository.RemoteSyncDataSource
import com.sih.netrasahayak.repository.ScreeningRepository
import com.sih.netrasahayak.repository.SyncDataSource
import com.sih.netrasahayak.repository.SyncRepository

/**
 * A deliberately small hand-written dependency container - no Hilt/Dagger, so
 * the wiring stays readable for a student project.
 *
 * THIS IS THE ONE PLACE where mock vs. real implementations are chosen.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val database: NetraDatabase by lazy { NetraDatabase.getInstance(appContext) }

    val connectivityObserver: ConnectivityObserver by lazy { ConnectivityObserver(appContext) }

    /**
     * Which engine answers "what does the AI say about this image?".
     *
     * AppConfig.INFERENCE_MODE is a BuildConfig field (app/build.gradle.kts):
     *   "local"  -> LocalInferenceRepository: LiteRT model bundled in the APK,
     *               fully offline. Default for debug AND release.
     *   "remote" -> RemoteInferenceRepository: optional FastAPI backend (debug only;
     *               release builds have no INTERNET permission).
     *   "mock"   -> MockInferenceRepository: UI demo, fake results. Never in release.
     */
    val inferenceRepository: InferenceRepository by lazy {
        when (AppConfig.INFERENCE_MODE) {
            "mock" -> MockInferenceRepository(appContext)
            "remote" -> RemoteInferenceRepository(
                context = appContext,
                api = RetrofitProvider.apiService,
                connectivity = connectivityObserver
            )
            else -> LocalInferenceRepository(appContext)
        }
    }

    val screeningRepository: ScreeningRepository by lazy {
        ScreeningRepository(appContext, database.screeningDao())
    }

    private val syncDataSource: SyncDataSource by lazy {
        if (AppConfig.USE_MOCK_API) {
            MockSyncDataSource()
        } else {
            RemoteSyncDataSource(RetrofitProvider.apiService)
        }
    }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(database.screeningDao(), connectivityObserver, syncDataSource)
    }

    /** A fresh camera controller per camera screen. */
    fun createCameraController(): RetinalCameraController = PhoneCameraController(appContext)
}
