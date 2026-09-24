package com.skydroid.fpv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.core.content.ContextCompat
import com.skydroid.fpv.R
import com.skydroid.fpv.telemetry.MavlinkTelemetryEngine
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Controller for OSMDroid Map, handling drone telemetry markers,
 * flight path trail, home location, and satellite/street layer switching.
 */
class DroneMapController(
    private val context: Context,
    private val mapView: MapView
) {
    private val TAG = "DroneMapController"

    // High-resolution ESRI World Imagery (Satellite) Tile Source - Free & Keyless
    private val esriSatelliteTileSource = object : OnlineTileSourceBase(
        "EsriSatellite",
        0, 19, 256, ".jpg",
        arrayOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/",
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) + "/" +
                    MapTileIndex.getX(pMapTileIndex)
        }
    }

    private var isSatelliteMode = true
    private var droneMarker: Marker? = null
    private var homeMarker: Marker? = null
    private var flightPathPolyline: Polyline? = null
    private var homeBearingLine: Polyline? = null

    private var hasHomePoint = false
    private var homePoint: GeoPoint? = null
    private var lastDronePoint: GeoPoint? = null
    private var autoFollowDrone = true

    init {
        initOsmConfiguration()
        setupMapView()
        setupOverlays()
    }

    private fun initOsmConfiguration() {
        val prefs = context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().osmdroidTileCache = context.cacheDir
    }

    private fun setupMapView() {
        mapView.apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setTileSource(esriSatelliteTileSource) // Default to high-res satellite
            controller.setZoom(17.5)
            // Default center before GPS lock (neutral location)
            controller.setCenter(GeoPoint(0.0, 0.0))
        }
    }

    private fun setupOverlays() {
        // Flight path breadcrumb trail
        flightPathPolyline = Polyline(mapView).apply {
            outlinePaint.color = Color.parseColor("#00F0FF")
            outlinePaint.strokeWidth = 5f
            outlinePaint.style = Paint.Style.STROKE
            outlinePaint.isAntiAlias = true
        }
        mapView.overlays.add(flightPathPolyline)

        // Line from Home to Drone
        homeBearingLine = Polyline(mapView).apply {
            outlinePaint.color = Color.parseColor("#8000FF66")
            outlinePaint.strokeWidth = 3f
            outlinePaint.style = Paint.Style.STROKE
            outlinePaint.isAntiAlias = true
        }
        mapView.overlays.add(homeBearingLine)

        // Home Location Marker
        homeMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_home_marker)
            title = "HOME"
        }

        // Drone Location Marker
        droneMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_drone_marker)
            title = "DRONE"
        }
    }

    /**
     * Updates drone location on the map from MAVLink telemetry.
     */
    fun updateTelemetry(telemetry: MavlinkTelemetryEngine.TelemetryData) {
        if (telemetry.droneLat == 0.0 && telemetry.droneLon == 0.0) return

        val dronePoint = GeoPoint(telemetry.droneLat, telemetry.droneLon)
        lastDronePoint = dronePoint

        // Set Home position on first valid 3D GPS fix or when armed
        if (!hasHomePoint && telemetry.hasGpsLock) {
            hasHomePoint = true
            homePoint = dronePoint
            homeMarker?.let {
                it.position = dronePoint
                if (!mapView.overlays.contains(it)) {
                    mapView.overlays.add(it)
                }
            }
            mapView.controller.setCenter(dronePoint)
            mapView.controller.setZoom(18.0)
            Log.i(TAG, "Home point established at ${dronePoint.latitude}, ${dronePoint.longitude}")
        }

        // Update Drone Marker
        droneMarker?.let { marker ->
            marker.position = dronePoint
            marker.rotation = telemetry.headingDeg
            if (!mapView.overlays.contains(marker)) {
                mapView.overlays.add(marker)
            }
        }

        // Update Flight Path Trail
        flightPathPolyline?.addPoint(dronePoint)

        // Update Line from Home to Drone
        homePoint?.let { home ->
            homeBearingLine?.setPoints(listOf(home, dronePoint))
        }

        // Follow drone if auto-follow enabled
        if (autoFollowDrone) {
            mapView.controller.setCenter(dronePoint)
        }

        mapView.invalidate()
    }

    /**
     * Center map immediately on current drone coordinates.
     */
    fun centerOnDrone() {
        lastDronePoint?.let {
            autoFollowDrone = true
            mapView.controller.animateTo(it, 18.0, 500L)
        }
    }

    /**
     * Center map on Home point.
     */
    fun centerOnHome() {
        homePoint?.let {
            autoFollowDrone = false
            mapView.controller.animateTo(it, 18.0, 500L)
        }
    }

    /**
     * Toggle between Satellite imagery and Street/Topo map.
     */
    fun toggleMapLayer(): Boolean {
        isSatelliteMode = !isSatelliteMode
        if (isSatelliteMode) {
            mapView.setTileSource(esriSatelliteTileSource)
        } else {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }
        mapView.invalidate()
        return isSatelliteMode
    }

    fun zoomIn() {
        mapView.controller.zoomIn()
    }

    fun zoomOut() {
        mapView.controller.zoomOut()
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }
}
