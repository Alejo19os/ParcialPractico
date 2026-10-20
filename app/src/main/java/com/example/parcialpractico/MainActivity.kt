package com.example.parcialpractico

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Poi(val id: Long, val path: String, val pos: LatLng, val time: Long)

private fun fmt(t: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(t))

/** Estado + sensores (contador de pasos, luz) + GPS */
class TrackVM(app: Application) : AndroidViewModel(app), SensorEventListener {
    private val sm = app.getSystemService(SensorManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(app)
    var tracking by mutableStateOf(false); private set
    var steps by mutableIntStateOf(0); private set
    var dark by mutableStateOf(false); private set
    var current by mutableStateOf<LatLng?>(null); private set
    val route = mutableStateListOf<LatLng>()
    val pois = mutableStateListOf<Poi>()
    val distance get() = steps * STEP_M   // metros estimados a partir de los pasos
    private var base = -1f

    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { val p = LatLng(it.latitude, it.longitude); current = p; if (tracking) route.add(p) }
        }
    }

    init { // el sensor de luz funciona siempre
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        steps = 0; base = -1f; route.clear(); tracking = true
        sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build(), locCb, null)
    }

    @SuppressLint("MissingPermission")
    fun locateOnly() { // muestra la ubicación aunque no haya recorrido activo
        fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000).build(), locCb, null)
    }

    fun stop() {
        tracking = false
        sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sm.unregisterListener(this, it) }
    }

    fun addPoi(path: String) {
        val p = current ?: return
        pois.add(0, Poi(System.currentTimeMillis(), path, p, System.currentTimeMillis()))
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> { if (base < 0) base = e.values[0]; steps = (e.values[0] - base).toInt() }
            Sensor.TYPE_LIGHT -> { val lux = e.values[0]; if (lux < 15f) dark = true else if (lux > 40f) dark = false } // histéresis
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onCleared() { sm?.unregisterListener(this); fused.removeLocationUpdates(locCb) }
    companion object { const val STEP_M = 0.75 }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: TrackVM = viewModel()
            MaterialTheme(colorScheme = if (vm.dark) darkColorScheme() else lightColorScheme()) { App(vm) }
        }
    }
}

@Composable
fun App(vm: TrackVM) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Map, null) }, label = { Text("Recorrido") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Dashboard, null) }, label = { Text("Puntos de Interés") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) { if (tab == 0) MapScreen(vm) else PoiScreen(vm) }
    }
}

@Composable
fun MapScreen(vm: TrackVM) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(hasLocation(ctx)) }
    val camState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(4.711, -74.072), 15f) }
    var pending by remember { mutableStateOf<File?>(null) }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pending?.let { if (ok) vm.addPoi(it.path) else it.delete() }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = hasLocation(ctx)
        if (granted && (r[Manifest.permission.CAMERA] == true)) {
            msg = null
            vm.start()
        } else {
            msg = "Se requieren los permisos de GPS y Cámara para iniciar el recorrido."
        }
    }

    LaunchedEffect(granted) { if (granted) vm.locateOnly() }
    LaunchedEffect(vm.current) { vm.current?.let { camState.animate(CameraUpdateFactory.newLatLngZoom(it, 17f)) } }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camState,
            properties = MapProperties(isMyLocationEnabled = granted),
            uiSettings = MapUiSettings(myLocationButtonEnabled = granted)
        )
        // Panel de métricas
        Card(Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(Icons.AutoMirrored.Filled.DirectionsWalk, vm.steps.toString(), "pasos", Modifier.weight(1f))
                Stat(Icons.Default.Straighten, "%.0f m".format(vm.distance), "distancia", Modifier.weight(1f))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            msg?.let {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(it)
                        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())) }) {
                            Text("Abrir ajustes")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!vm.tracking) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val permissions = mutableListOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.CAMERA
                            ).apply {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    add(Manifest.permission.ACTIVITY_RECOGNITION)
                                }
                            }.toTypedArray()
                            permLauncher.launch(permissions)
                        },
                        icon = { Icon(Icons.Default.PlayArrow, null) },
                        text = { Text("Iniciar Recorrido") }
                    )
                } else {
                    ExtendedFloatingActionButton(
                        onClick = { vm.stop() },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        icon = { Icon(Icons.Default.Stop, null) },
                        text = { Text("Detener") }
                    )
                    FloatingActionButton(onClick = {
                        val f = File(ctx.cacheDir, "poi_${System.currentTimeMillis()}.jpg")
                        pending = f
                        photoLauncher.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f))
                    }) {
                        Icon(Icons.Default.PhotoCamera, "Tomar foto")
                    }
                }
            }
        }
    }
}

private fun hasLocation(c: android.content.Context) =
    ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
fun Stat(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) =
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiScreen(vm: TrackVM) {
    LazyVerticalGrid(
        GridCells.Adaptive(165.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(Icons.Default.Place, vm.pois.size.toString(), "puntos")
                    Stat(Icons.AutoMirrored.Filled.DirectionsWalk, vm.steps.toString(), "pasos")
                    Stat(Icons.Default.Straighten, "%.0f m".format(vm.distance), "distancia")
                }
            }
        }
        if (vm.pois.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Aún no hay fotografías. Inicia un recorrido y toma una foto.", Modifier.padding(24.dp))
        }
        items(vm.pois, key = { it.id }) { p ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Column {
                    AsyncImage(
                        model = File(p.path),
                        contentDescription = "Foto del punto de interés",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.padding(10.dp)) {
                        Text(fmt(p.time), style = MaterialTheme.typography.labelLarge)
                        Text("Lat: %.5f".format(p.pos.latitude), style = MaterialTheme.typography.bodySmall)
                        Text("Lng: %.5f".format(p.pos.longitude), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
