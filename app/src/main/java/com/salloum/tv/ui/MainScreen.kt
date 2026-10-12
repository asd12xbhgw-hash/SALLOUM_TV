package com.salloum.tv.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import android.widget.Toast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.salloum.tv.data.Channel
import com.salloum.tv.update.AppUpdateManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val activityContext = LocalContext.current
    val window = (activityContext as? android.app.Activity)?.window

    val viewModel: MainViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(activityContext.applicationContext as Application) as T
            }
        }
    )
    val categories by viewModel.categories.collectAsState()
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()

    var isFullScreen by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    // حفظ موضع التمرير حتى يرجع المؤشر لآخر قناة عند الخروج من المشغل
    val gridState = rememberLazyGridState()

    // ===== حالة التحديث الذاتي =====
    val updateManager = remember { AppUpdateManager(activityContext.applicationContext) }
    val updateScope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    // رابط ملف update.json على GitHub Releases — عدّله حسب مستودعك
    val UPDATE_JSON_URL = "https://raw.githubusercontent.com/asd12xbhgw-hash/SALLOUM_TV/master/update.json"

    fun checkForUpdate() {
        if (isCheckingUpdate || isDownloading) return
        updateScope.launch {
            isCheckingUpdate = true
            updateMessage = "جارٍ التحقق من التحديث..."
            val info = updateManager.checkForUpdate(UPDATE_JSON_URL)
            if (info.available) {
                updateMessage = "يوجد تحديث جديد (v${info.latestVersion})"
                showUpdateDialog = true
                val url = info.downloadUrl
                if (url.isNotEmpty()) {
                    isCheckingUpdate = false
                    isDownloading = true
                    val apk = updateManager.downloadApk(url) { progress ->
                        downloadProgress = progress
                    }
                    isDownloading = false
                    if (apk != null) {
                        updateMessage = null
                        showUpdateDialog = false
                        val activity = activityContext as? android.app.Activity
                        if (activity != null) {
                            if (updateManager.canInstallPackages()) {
                                // افتح نافذة التثبيت ثم أغلق التطبيق حتى تظهر بوضوح
                                updateManager.installApk(activity, apk)
                                activity.finish()
                            } else {
                                updateManager.openInstallPermissionSettings(activity)
                            }
                        }
                    } else {
                        updateMessage = "فشل تنزيل التحديث، حاول لاحقاً"
                    }
                }
            } else {
                updateMessage = "أنت تستخدم أحدث نسخة ✅"
            }
            isCheckingUpdate = false
        }
    }

    // فحص التحديث تلقائيًا عند فتح التطبيق (بدون حاجة المستخدم يضغط زر)
    LaunchedEffect(Unit) {
        checkForUpdate()
    }

    // Control Status Bar and Navigation Bar (Hide when playing video)
    LaunchedEffect(isFullScreen) {
        window?.let {
            val controller = WindowCompat.getInsetsController(it, it.decorView)
            if (isFullScreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val categoryList = remember(categories) {
        val base = mutableListOf<String>()
        if (categories.containsKey("Recent")) base.add("Recent")
        base.addAll(listOf("GlobalSports", "AlwanSport", "MBC", "Rotana", "DramaSeries", "MoviesSeries", "Documentaries", "News", "Religious", "Kids", "Iraqi", "Sports", "الكل"))
        base.distinct()
    }

    BackHandler(enabled = !isFullScreen) {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            onConfirmation = { (activityContext as? android.app.Activity)?.finish() },
            dialogTitle = "تنبيه الخروج",
            dialogText = "هل أنت متأكد من أنك تريد الخروج من التطبيق؟"
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false },
            context = activityContext
        )
    }

    // نافذة التحديث — تظهر تلقائيًا عند وجود نسخة جديدة
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            onConfirmation = { showUpdateDialog = false },
            dialogTitle = "تحديث متاح",
            dialogText = "يوجد إصدار جديد من التطبيق. سيتم تنزيله وتثبيته الآن."
        )
    }

    // رسالة التحديث (تظهر كـ Toast)
    LaunchedEffect(updateMessage) {
        updateMessage?.let { msg ->
            Toast.makeText(activityContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isTv = configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val useSidebar = isTv || (isLandscape && configuration.screenWidthDp > 600)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0D1B2A), Color(0xFF000000)),
                    center = androidx.compose.ui.geometry.Offset(0.5f, 0f)
                )
            )
    ) {
        if (useSidebar) {
            // TV / Tablet Landscape Layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .graphicsLayer(alpha = if (isFullScreen) 0f else 1f)
            ) {
                Sidebar(
                    categoryList = categoryList,
                    selectedCategory = selectedCategory,
                    onCategorySelect = { viewModel.selectCategory(it) },
                    onAboutClick = { showAboutDialog = true },
                    onCheckUpdate = { checkForUpdate() }
                )

                MainContent(
                    viewModel = viewModel,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    isLoading = isLoading,
                    gridState = gridState,
                    onChannelClick = { channel ->
                        viewModel.selectChannel(channel)
                        isFullScreen = true
                    }
                )
            }
        } else {
            // Mobile Portrait / Small Screen Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .graphicsLayer(alpha = if (isFullScreen) 0f else 1f)
            ) {
                HeaderMobile(onCheckUpdate = { checkForUpdate() })

                CategoryRow(
                    categoryList = categoryList,
                    selectedCategory = selectedCategory,
                    onCategorySelect = { viewModel.selectCategory(it) }
                )

                MainContent(
                    viewModel = viewModel,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    isLoading = isLoading,
                    gridState = gridState,
                    onChannelClick = { channel ->
                        viewModel.selectChannel(channel)
                        isFullScreen = true
                    }
                )
            }
        }

        selectedChannel?.let { channel ->
            if (isFullScreen) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    FullScreenPlayer(
                        channelName = channel.name,
                        url = channel.url,
                        onBack = { isFullScreen = false }
                    )
                }
            }
        }
    }
}

@Composable
fun Sidebar(
    categoryList: List<String>,
    selectedCategory: String,
    onCategorySelect: (String) -> Unit,
    onAboutClick: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(220.dp),
        color = Color.Black.copy(alpha = 0.4f),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SALLOUM TV",
                color = Color.Cyan,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categoryList) { category ->
                    CategoryItem(
                        category = category,
                        isSelected = selectedCategory == category,
                        onClick = { onCategorySelect(category) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                onClick = onAboutClick,
                color = Color.Transparent,
                contentColor = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("حول التطبيق", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Surface(
                onClick = onCheckUpdate,
                color = Color.Transparent,
                contentColor = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("تحقق من التحديث", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun CategoryRow(
    categoryList: List<String>,
    selectedCategory: String,
    onCategorySelect: (String) -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categoryList) { category ->
            val isSelected = selectedCategory == category
            Surface(
                onClick = { onCategorySelect(category) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.1f),
                contentColor = if (isSelected) Color.Black else Color.White
            ) {
                Text(
                    text = translateCategory(category),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun CategoryItem(
    category: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.05f else 1f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        shape = RoundedCornerShape(12.dp),
        color = when {
            isSelected -> Color.Cyan
            isFocused -> Color(0xFF333333)
            else -> Color.Transparent
        },
        contentColor = if (isSelected) Color.Black else Color.White
    ) {
        Text(
            text = translateCategory(category),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start
        )
    }
}

@Composable
fun HeaderMobile(onCheckUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "SALLOUM TV",
            color = Color.Cyan,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black
        )
        IconButton(onClick = onCheckUpdate) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "تحقق من التحديث",
                tint = Color.Cyan
            )
        }
    }
}

@Composable
fun MainContent(
    viewModel: MainViewModel,
    categories: Map<String, List<Channel>>,
    selectedCategory: String,
    isLoading: Boolean,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onChannelClick: (Channel) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        if (isLoading && categories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan)
            }
        } else {
            val currentChannels = remember(selectedCategory, categories) {
                if (selectedCategory == "الكل") {
                    categories.values.flatten().distinctBy { it.url }
                } else {
                    categories[selectedCategory] ?: emptyList()
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(currentChannels, key = { it.url + it.name }) { channel ->
                    ChannelItem(
                        channel = channel,
                        onClick = { onChannelClick(channel) }
                    )
                }
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit, context: android.content.Context) {
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) { "1.0.0" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // شعار التطبيق
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.Cyan.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📺", fontSize = 36.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("SALLOUM TV", fontWeight = FontWeight.Black, color = Color.Cyan, fontSize = 22.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("الإصدار: $versionName", color = Color.Gray, fontSize = 14.sp)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "مشغل IPTV بسيط وسريع. التطبيق لا يستضيف أي محتوى مرئي، وإنما يوفر واجهة لتشغيل الروابط المتاحة علنًا. نحن غير مسؤولين عن المحتوى الذي يتم بثه.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                // زر مشاركة التطبيق
                Surface(
                    onClick = {
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "شاهد SALLOUM TV — مشغل IPTV مجاني")
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "مشاركة التطبيق"))
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Cyan.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مشاركة التطبيق", color = Color.Cyan, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        }
    )
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    dialogTitle: String,
    dialogText: String,
) {
    AlertDialog(
        title = { Text(text = dialogTitle) },
        text = { Text(text = dialogText) },
        onDismissRequest = { onDismissRequest() },
        confirmButton = {
            TextButton(onClick = { onConfirmation() }) {
                Text("نعم")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismissRequest() }) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun FullScreenPlayer(channelName: String, url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }

    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(5000)
            showControls = false
        }
    }

    LaunchedEffect(url) {
        isBuffering = true
        errorMessage = null
        kotlinx.coroutines.delay(2000)
        isBuffering = false
    }

    BackHandler {
        onBack()
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            showControls = !showControls
                            true
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusRequester(focusRequester)
            .focusable()
            .clickable { showControls = !showControls }
    ) {
        VideoPlayer(
            url = url,
            modifier = Modifier.fillMaxSize(),
            onError = {
                errorMessage = it
                isBuffering = false
            }
        )

        if (isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan, strokeWidth = 6.dp)
            }
        }

        errorMessage?.let { msg ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "القناة حالياً غير متوفرة", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(text = "يرجى المحاولة لاحقاً", color = Color.Gray, fontSize = 16.sp)
                }
            }
        }

        if (showControls) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)
                        )
                    )
                    .align(Alignment.TopCenter)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.Cyan,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = {
                    Toast.makeText(context, "شكراً لك، تم إرسال البلاغ للمراجعة.", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "إبلاغ",
                        tint = Color.Red
                    )
                }

                IconButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "بث عبر تطبيق خارجي (LG/Samsung)"))
                }) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "مشاركة",
                        tint = Color.Cyan
                    )
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "اضغط OK لإظهار/إخفاء القائمة",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontSize = 14.sp
                )
            }
        }
    }
}

fun translateCategory(category: String): String {
    return when (category) {
        "Recent" -> "المشاهدة مؤخراً"
        "الكل" -> "الكل"
        "GlobalSports" -> "الرياضة العالمية"
        "AlwanSport" -> "ألوان رياضية"
        "MBC" -> "باقة MBC"
        "Rotana" -> "روتانا"
        "DramaSeries" -> "مسلسلات ودراما"
        "MoviesSeries" -> "أفلام"
        "Documentaries" -> "وثائقيات"
        "Iraqi" -> "عراقية"
        "Kids" -> "كارتون"
        "Religious" -> "ديني"
        "News" -> "أخبار"
        "Sports" -> "رياضة متنوعة"
        else -> category
    }
}