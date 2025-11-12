package com.example.lamforgallery.ui

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.ImageSearch // --- ADDED ---
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search // --- ADDED ---
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable // <-- NEW IMPORT
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.ImageEncoder
import com.example.lamforgallery.ml.TextEncoder
import android.graphics.Bitmap // <-- Import Bitmap
import android.graphics.Color // <-- Import Color
import com.example.lamforgallery.database.AppDatabase
import com.example.lamforgallery.database.ImageEmbedding
import com.example.lamforgallery.ui.EmbeddingScreen
import com.example.lamforgallery.ui.EmbeddingViewModel

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // --- Factory ---
    private val factory by lazy { ViewModelFactory(application) }

    // --- ViewModels (Activity-Scoped) ---
    // These are the *single source of truth*.
    // We will pass these down to our Composables.
    private val agentViewModel: AgentViewModel by viewModels { factory }
    private val photosViewModel: PhotosViewModel by viewModels { factory }
    private val albumsViewModel: AlbumsViewModel by viewModels { factory }
    private val searchViewModel: SearchViewModel by viewModels { factory }
    // --- End ViewModels ---
    val embeddingViewModel: EmbeddingViewModel by viewModels { factory }
    // --- READ PERMISSION ---
    private val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    private var isPermissionGranted by mutableStateOf(false)
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            isPermissionGranted = isGranted
            if (isGranted) {
                Log.d(TAG, "Permission granted. Loading ViewModels.")
                loadAllViewModels()
            }
        }
    // --- END READ PERMISSION ---


    // --- MODIFY PERMISSIONS (GENERALIZED) ---
    private var currentPermissionType: PermissionType? = null
    private val permissionRequestLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { activityResult ->
            val type = currentPermissionType
            if (type == null) {
                Log.e(TAG, "permissionRequestLauncher result but currentPermissionType is null!")
                return@registerForActivityResult
            }

            val wasSuccessful = activityResult.resultCode == RESULT_OK
            Log.d(TAG, "Permission result for $type: ${if (wasSuccessful) "GRANTED" else "DENIED"}")
            agentViewModel.handlePermissionResult(wasSuccessful) // Pass to Agent VM
            currentPermissionType = null
        }
    // --- END MODIFY PERMISSIONS ---


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermission()

        lifecycleScope.launch {
            agentViewModel.galleryDidChange.collect {
                Log.d(TAG, "Agent reported gallery change. Refreshing Photos and Albums.")
                photosViewModel.loadPhotos() // Refresh photos
                albumsViewModel.loadAlbums() // Refresh albums
            }
        }


        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isPermissionGranted) {
                        // --- Show the main App Navigation Host ---
                        AppNavigationHost(
                            factory = factory,
                            // --- PASS THE VIEWMODELS DOWN ---
                            agentViewModel = agentViewModel,
                            photosViewModel = photosViewModel,
                            albumsViewModel = albumsViewModel,
                            searchViewModel = searchViewModel,
                            embeddingViewModel = embeddingViewModel,
                            // --- END PASS ---
                            onLaunchPermissionRequest = { intentSender, type ->
                                Log.d(TAG, "Launching permissionRequestLauncher for $type...")
                                currentPermissionType = type
                                permissionRequestLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }
                        )
                    } else {
                        PermissionDeniedScreen {
                            requestPermissionLauncher.launch(permissionToRequest)
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                permissionToRequest
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Permission already granted.")
                isPermissionGranted = true
                // --- THIS IS THE FIX for blank photos ---
                loadAllViewModels()
                // --- END FIX ---
            }
            shouldShowRequestPermissionRationale(permissionToRequest) -> {
                requestPermissionLauncher.launch(permissionToRequest)
            }
            else -> {
                requestPermissionLauncher.launch(permissionToRequest)
            }
        }
    }

    /**
     * Helper to load data into all main ViewModels.
     * Syncs MediaStore to database first, then loads photos and albums.
     */
    private fun loadAllViewModels() {
        // Sync MediaStore to database first
        lifecycleScope.launch {
            try {
                val galleryTools = (factory as ViewModelFactory).provideGalleryTools()
                galleryTools.syncMediaStoreToDatabase()
                
                // After sync, load the data
                photosViewModel.loadPhotos()
                albumsViewModel.loadAlbums()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to sync database", e)
                // Still try to load even if sync fails
                photosViewModel.loadPhotos()
                albumsViewModel.loadAlbums()
            }
        }
        // AgentViewModel loads on its own, no need to call it here.
    }
}

/**
 * Sets up the app's navigation graph.
 */
@Composable
fun AppNavigationHost(
    factory: ViewModelProvider.Factory,
    // --- RECEIVE THE VIEWMODELS ---
    agentViewModel: AgentViewModel,
    photosViewModel: PhotosViewModel,
    albumsViewModel: AlbumsViewModel,
    searchViewModel: SearchViewModel,
    embeddingViewModel: EmbeddingViewModel,
    // --- END RECEIVE ---
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    val navController = rememberNavController()

    // --- FIX 1: HOIST THE SELECTED TAB STATE ---
    // We use rememberSaveable to remember the tab even if the app rotates
    // or navigates away.
    var selectedTab by rememberSaveable { mutableStateOf("photos") }
    // --- END FIX 1 ---

    NavHost(navController = navController, startDestination = "main") {

        // Route 1: The main 3-tab screen
        composable("main") {
            AppShell(
                // Pass ViewModels down
                agentViewModel = agentViewModel,
                photosViewModel = photosViewModel,
                albumsViewModel = albumsViewModel,
                searchViewModel = searchViewModel,
                embeddingViewModel = embeddingViewModel,
                // Pass tab state down
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                // Pass navigation/permission helpers down
                onAlbumClick = { encodedAlbumName ->
                    navController.navigate("album_detail/$encodedAlbumName")
                },
                onLaunchPermissionRequest = onLaunchPermissionRequest
            )
        }

        // Route 2: The Album Detail screen
        composable(
            route = "album_detail/{albumName}",
            arguments = listOf(navArgument("albumName") { type = NavType.StringType })
        ) { backStackEntry ->
            val albumName = backStackEntry.arguments?.getString("albumName") ?: "Unknown"

            // This ViewModel is temporary, so we create it here.
            val albumDetailViewModel: AlbumDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)

            AlbumDetailScreen(
                albumName = albumName,
                viewModel = albumDetailViewModel,
                onNavigateBack = { navController.popBackStack() }, // Standard back action
                onPhotosDeleted = { 
                    albumsViewModel.loadAlbums()
                    photosViewModel.refreshPhotos()
                }
            )
        }
    }
}


/**
 * The main app shell, containing the Bottom Navigation and the
 * content area that switches between screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    // --- RECEIVE VIEWMODELS AND STATE ---
    agentViewModel: AgentViewModel,
    photosViewModel: PhotosViewModel,
    albumsViewModel: AlbumsViewModel,
    searchViewModel: SearchViewModel,
    embeddingViewModel: EmbeddingViewModel,
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    // --- END RECEIVE ---
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    // Note: We no longer create ViewModels here! We use the ones passed in.

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == "photos",
                    onClick = { onTabSelected("photos") }, // Use the lambda
                    icon = { Icon(Icons.Default.Photo, contentDescription = "Photos") },
                    label = { Text("Photos") }
                )
                NavigationBarItem(
                    selected = selectedTab == "albums",
                    onClick = { onTabSelected("albums") }, // Use the lambda
                    icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Albums") },
                    label = { Text("Albums") }
                )
                NavigationBarItem(
                    selected = selectedTab == "search",
                    onClick = { onTabSelected("search") },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    label = { Text("Search") }
                )
                NavigationBarItem(
                    selected = selectedTab == "agent",
                    onClick = { onTabSelected("agent") }, // Use the lambda
                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = "Agent") },
                    label = { Text("Agent") }
                )
                NavigationBarItem(
                    selected = selectedTab == "indexing",
                    onClick = { onTabSelected("indexing") },
                    icon = { Icon(Icons.Default.ImageSearch, contentDescription = "Indexing") },
                    label = { Text("Indexing") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            // Switch content based on the selected tab
            when (selectedTab) {
                "photos" -> PhotosScreen(
                    viewModel = photosViewModel,
                    onPhotosDeleted = { albumsViewModel.loadAlbums() }
                )
                "albums" -> AlbumsScreen(
                    viewModel = albumsViewModel,
                    onAlbumClick = onAlbumClick // Pass the navigation click
                )
                "search" -> SearchScreen(
                    viewModel = searchViewModel,
                    onPhotosDeleted = {
                        albumsViewModel.loadAlbums()
                        photosViewModel.refreshPhotos()
                    }
                )
                "agent" -> AgentScreen(
                    viewModel = agentViewModel,
                    onLaunchPermissionRequest = onLaunchPermissionRequest
                )
                "indexing" -> EmbeddingScreen(viewModel = embeddingViewModel)
            }
        }
    }
}