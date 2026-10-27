package com.sih.netrasahayak

/**
 * Single place where the app's backend configuration lives.
 *
 * The values themselves come from BuildConfig (see app/build.gradle.kts) so that
 * debug and release builds can point at different servers without touching code.
 *
 *  debug   -> http://10.0.2.2:8000/  (FastAPI running on the dev machine, emulator)
 *  release -> https://your-server.example.com/
 *
 * No API keys or credentials are stored in the app.
 */
object AppConfig {

    /** Root URL of the FastAPI backend. Must end with a trailing slash. */
    val BASE_URL: String = BuildConfig.BASE_URL

    /**
     * DEVELOPMENT SWITCH.
     *
     * true  -> the app uses [com.sih.netrasahayak.repository.MockInferenceRepository]
     *          and returns a fake screening result plus a generated placeholder
     *          heatmap. No server required.
     * false -> the app uses [com.sih.netrasahayak.repository.RemoteInferenceRepository]
     *          and performs a real POST /predict against BASE_URL.
     */
    val USE_MOCK_API: Boolean = BuildConfig.USE_MOCK_API

    /**
     * Screening engine: "local" (on-device LiteRT, offline - the default),
     * "remote" (optional dev backend) or "mock" (UI demo only).
     * USE_MOCK_API now only affects the Sync screen's data source.
     */
    val INFERENCE_MODE: String = BuildConfig.INFERENCE_MODE

    /** Network timeouts, in seconds. Generous because rural links are slow. */
    const val CONNECT_TIMEOUT_SECONDS = 20L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 60L

    /** Longest edge (px) an image is scaled down to before upload. */
    const val UPLOAD_MAX_DIMENSION = 1024

    /** JPEG quality used for the upload copy. The stored original is untouched. */
    const val UPLOAD_JPEG_QUALITY = 90
}
