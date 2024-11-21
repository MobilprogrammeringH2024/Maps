package com.example.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import java.util.*

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var address by remember { mutableStateOf("") }

    val placesClient = remember {
        Places.initialize(context, context.getString(R.string.google_maps_key))
        Places.createClient(context)
    }
    val locationProvider = LocationServices.getFusedLocationProviderClient(context)
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) getUserLocation(locationProvider, googleMap, context)
        else Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { mapView }) { view ->
                view.getMapAsync { googleMap = it.apply { uiSettings.isZoomControlsEnabled = true } }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        AddressField(address, onNewValue = { address = it }, googleMap, context, placesClient)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                getUserLocation(locationProvider, googleMap, context)
            } else {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }) {
            Text("Find My Location")
        }

        Spacer(modifier = Modifier.height(20.dp))

    }
}


@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                androidx.lifecycle.Lifecycle.Event.ON_START -> mapView.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> mapView.onStop()
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

@Composable
fun AddressField(
    value: String,
    onNewValue: (String) -> Unit,
    googleMap: GoogleMap?,
    context: Context,
    placesClient: PlacesClient,
    modifier: Modifier = Modifier.fillMaxWidth(0.9f)
) {
    var lastAddress by remember { mutableStateOf(value) }
    var predictions by remember { mutableStateOf(emptyList<AutocompletePrediction>()) }

    LaunchedEffect(value) {
        if (value.isEmpty()) {
            predictions = emptyList()
            googleMap?.clear()
            lastAddress = value
            return@LaunchedEffect
        }

        if (value != lastAddress) {
            updateMapLocation(value, googleMap, context)
            lastAddress = value
        }

        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(value)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                predictions = response.autocompletePredictions
            }
            .addOnFailureListener {
                predictions = emptyList()
            }
    }

    Column {
        OutlinedTextField(
            singleLine = true,
            modifier = modifier,
            value = value,
            onValueChange = { onNewValue(it) },
            placeholder = { Text(text = "Enter Address") }
        )

        predictions.forEach { prediction ->
            TextButton(onClick = {
                val placeId = prediction.placeId
                val placeRequest = com.google.android.libraries.places.api.net.FetchPlaceRequest.builder(
                    placeId,
                    listOf(
                        com.google.android.libraries.places.api.model.Place.Field.LAT_LNG,
                        com.google.android.libraries.places.api.model.Place.Field.ADDRESS
                    )
                ).build()

                placesClient.fetchPlace(placeRequest)
                    .addOnSuccessListener { response ->
                        val place = response.place
                        val latLng = place.latLng

                        if (latLng != null) {
                            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                            googleMap?.clear()
                            googleMap?.addMarker(MarkerOptions().position(latLng))
                            onNewValue(place.address ?: "")
                        }
                    }
            }) {
                Text(text = prediction.getPrimaryText(null).toString())
            }
        }
    }
}


fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}


fun getUserLocation(
    locationProvider: FusedLocationProviderClient,
    googleMap: GoogleMap?,
    context: Context
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        try {
            locationProvider.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    googleMap?.clear()
                    googleMap?.addMarker(MarkerOptions().position(latLng))
                } ?: Toast.makeText(context, "Location not found", Toast.LENGTH_SHORT).show()
            }.addOnFailureListener {
                Toast.makeText(context, "Failed to get location: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Toast.makeText(context, "Permission denied: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Location permission not granted", Toast.LENGTH_SHORT).show()
    }
}


fun updateMapLocation(address: String, googleMap: GoogleMap?, context: Context) {
    val geocoder = Geocoder(context, Locale.getDefault())
    try {
        val addresses = geocoder.getFromLocationName(address, 1)
        if (!addresses.isNullOrEmpty()) {
            val location = addresses[0]
            val latLng = LatLng(location.latitude, location.longitude)
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            googleMap?.clear()
            googleMap?.addMarker(MarkerOptions().position(latLng))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
