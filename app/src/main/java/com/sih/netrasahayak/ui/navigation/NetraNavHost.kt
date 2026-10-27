package com.sih.netrasahayak.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.sih.netrasahayak.R
import com.sih.netrasahayak.model.ScreeningResult
import com.sih.netrasahayak.ui.camera.CameraScreen
import com.sih.netrasahayak.ui.components.ErrorView
import com.sih.netrasahayak.ui.components.NetraScaffold
import com.sih.netrasahayak.ui.history.HistoryDetailScreen
import com.sih.netrasahayak.ui.history.HistoryDetailViewModel
import com.sih.netrasahayak.ui.history.HistoryScreen
import com.sih.netrasahayak.ui.history.HistoryViewModel
import com.sih.netrasahayak.ui.home.HomeScreen
import com.sih.netrasahayak.ui.imagesource.ImageSourceScreen
import com.sih.netrasahayak.ui.patient.PatientDetailsScreen
import com.sih.netrasahayak.ui.preview.ImagePreviewScreen
import com.sih.netrasahayak.ui.result.ResultScreen
import com.sih.netrasahayak.ui.screening.AnalysisState
import com.sih.netrasahayak.ui.screening.ScreeningViewModel
import com.sih.netrasahayak.ui.sync.SyncScreen
import com.sih.netrasahayak.ui.sync.SyncViewModel

/**
 * All navigation for the app.
 */
@Composable
fun NetraNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {

        // ------------------------------------------------------------- home
        composable(Routes.HOME) {
            HomeScreen(
                onStartScreening = { navController.navigate(Routes.SCREENING_GRAPH) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSync = { navController.navigate(Routes.SYNC) }
            )
        }

        // -------------------------------------------------- screening flow
        navigation(startDestination = Routes.PATIENT, route = Routes.SCREENING_GRAPH) {

            composable(Routes.PATIENT) { entry ->
                val viewModel = entry.sharedScreeningViewModel(navController)
                NetraScaffold(
                    title = stringResource(R.string.nav_new_screening),
                    onBack = { navController.popBackStack() }
                ) { padding ->
                    PatientDetailsScreen(
                        onContinue = { patientId, age, gender, diabetesYears ->
                            viewModel.setPatient(patientId, age, gender, diabetesYears)
                            navController.navigate(Routes.IMAGE_SOURCE)
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }

            composable(Routes.IMAGE_SOURCE) { entry ->
                val viewModel = entry.sharedScreeningViewModel(navController)
                NetraScaffold(
                    title = stringResource(R.string.nav_retinal_image),
                    onBack = { navController.popBackStack() }
                ) { padding ->
                    ImageSourceScreen(
                        onOpenCamera = { navController.navigate(Routes.CAMERA) },
                        onImageSelected = { uri ->
                            viewModel.setImage(uri)
                            navController.navigate(Routes.PREVIEW)
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }

            composable(Routes.CAMERA) { entry ->
                val viewModel = entry.sharedScreeningViewModel(navController)
                NetraScaffold(
                    title = stringResource(R.string.nav_take_photo),
                    onBack = { navController.popBackStack() }
                ) { padding ->
                    CameraScreen(
                        onCaptured = { uri ->
                            viewModel.setImage(uri)
                            navController.navigate(Routes.PREVIEW) {
                                popUpTo(Routes.IMAGE_SOURCE) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }

            composable(Routes.PREVIEW) { entry ->
                val viewModel = entry.sharedScreeningViewModel(navController)
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                LaunchedEffect(state.analysis) {
                    if (state.analysis is AnalysisState.Success) {
                        navController.navigate(Routes.RESULT)
                    }
                }

                NetraScaffold(
                    title = stringResource(R.string.nav_image_preview),
                    onBack = { navController.popBackStack() }
                ) { padding ->
                    ImagePreviewScreen(
                        imageUri = state.imageUri,
                        analysis = state.analysis,
                        onAnalyze = { viewModel.analyze() },
                        onRetake = {
                            viewModel.clearImage()
                            navController.navigate(Routes.CAMERA)
                        },
                        onChooseAnother = {
                            viewModel.clearImage()
                            navController.popBackStack(Routes.IMAGE_SOURCE, inclusive = false)
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }

            composable(Routes.RESULT) { entry ->
                val viewModel = entry.sharedScreeningViewModel(navController)
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                var shownResult by remember { mutableStateOf<ScreeningResult?>(null) }
                LaunchedEffect(state.analysis) {
                    (state.analysis as? AnalysisState.Success)?.let { shownResult = it.result }
                }

                val finish = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                    Unit
                }

                BackHandler { finish() }

                NetraScaffold(title = stringResource(R.string.nav_screening_result)) { padding ->
                    val result = shownResult
                    if (result == null) {
                        ErrorView(
                            message = stringResource(R.string.result_not_available),
                            onRetry = finish,
                            retryText = stringResource(R.string.go_home),
                            modifier = Modifier.padding(padding)
                        )
                    } else {
                        ResultScreen(
                            result = result,
                            imageUri = state.imageUri,
                            savedLocally = state.savedRecordId != null,
                            onDone = finish,
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------- history
        composable(Routes.HISTORY) {
            val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
            val screenings by viewModel.screenings.collectAsStateWithLifecycle()

            NetraScaffold(
                title = stringResource(R.string.nav_screening_history),
                onBack = { navController.popBackStack() }
            ) { padding ->
                HistoryScreen(
                    screenings = screenings,
                    onOpen = { id -> navController.navigate(Routes.historyDetail(id)) },
                    modifier = Modifier.padding(padding)
                )
            }
        }

        composable(
            route = Routes.HISTORY_DETAIL,
            arguments = listOf(navArgument(Routes.HISTORY_DETAIL_ARG) { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong(Routes.HISTORY_DETAIL_ARG) ?: 0L
            val viewModel: HistoryDetailViewModel =
                viewModel(factory = HistoryDetailViewModel.Factory)
            val screening by viewModel.screening.collectAsStateWithLifecycle()
            val loading by viewModel.loading.collectAsStateWithLifecycle()

            LaunchedEffect(id) { viewModel.load(id) }

            NetraScaffold(
                title = stringResource(R.string.nav_screening_details),
                onBack = { navController.popBackStack() }
            ) { padding ->
                HistoryDetailScreen(
                    screening = screening,
                    loading = loading,
                    modifier = Modifier.padding(padding)
                )
            }
        }

        // ------------------------------------------------------------- sync
        composable(Routes.SYNC) {
            val viewModel: SyncViewModel = viewModel(factory = SyncViewModel.Factory)
            val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
            val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
            val syncState by viewModel.syncState.collectAsStateWithLifecycle()

            NetraScaffold(
                title = stringResource(R.string.nav_sync_data),
                onBack = { navController.popBackStack() }
            ) { padding ->
                SyncScreen(
                    isOnline = isOnline,
                    pendingCount = pendingCount,
                    syncState = syncState,
                    onSyncNow = { viewModel.syncNow() },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

/**
 * Returns the ScreeningViewModel that belongs to the whole screening graph
 * rather than to one screen.
 */
@Composable
private fun NavBackStackEntry.sharedScreeningViewModel(
    navController: NavHostController
): ScreeningViewModel {
    val parentEntry = remember(this) { navController.getBackStackEntry(Routes.SCREENING_GRAPH) }
    return viewModel(
        viewModelStoreOwner = parentEntry,
        factory = ScreeningViewModel.Factory
    )
}
