package pl.mazovia.offroad.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.mazovia.offroad.domain.location.LocationClient

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLocationClientTest {

    private val context = mockk<Context>()
    private val locationManager = mockk<LocationManager>()
    private val fused = mockk<FusedLocationProviderClient>()
    private val registration = mockk<Task<Void>>()
    private val failureListener = slot<OnFailureListener>()

    @Before
    fun setUp() {
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { locationManager.isProviderEnabled(any()) } returns true
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fused
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk()
        every {
            fused.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>())
        } returns registration
        every { registration.addOnFailureListener(capture(failureListener)) } returns registration
        every { fused.removeLocationUpdates(any<LocationCallback>()) } returns mockk()
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `asynchronous registration failure closes the flow and removes the callback`() = runTest {
        val result = async { runCatching { AndroidLocationClient(context).getLocationUpdates(1_000L).collect() } }
        runCurrent()
        assertFalse(result.isCompleted)

        failureListener.captured.onFailure(IllegalStateException("registration rejected"))
        runCurrent()

        val error = result.await().exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("registration rejected", error!!.message)
        verify(exactly = 1) { fused.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
        verify(exactly = 1) { registration.addOnFailureListener(any<OnFailureListener>()) }
        verify(exactly = 1) { fused.removeLocationUpdates(any<LocationCallback>()) }
    }

    @Test
    fun `successful registration keeps the flow open until cancelled and then removes the callback`() = runTest {
        val job = launch { AndroidLocationClient(context).getLocationUpdates(1_000L).collect() }
        runCurrent()
        assertTrue(job.isActive)
        verify(exactly = 0) { fused.removeLocationUpdates(any<LocationCallback>()) }

        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        verify(exactly = 1) { fused.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
        verify(exactly = 1) { fused.removeLocationUpdates(any<LocationCallback>()) }
    }

    @Test
    fun `missing permission still fails before any registration`() = runTest {
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
        val error = runCatching { AndroidLocationClient(context).getLocationUpdates(1_000L).collect() }.exceptionOrNull()
        assertTrue(error is LocationClient.LocationException)
        verify(exactly = 0) { fused.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
    }

    @Test
    fun `disabled providers still fail before any registration`() = runTest {
        every { locationManager.isProviderEnabled(any()) } returns false
        val error = runCatching { AndroidLocationClient(context).getLocationUpdates(1_000L).collect() }.exceptionOrNull()
        assertTrue(error is LocationClient.LocationException)
        verify(exactly = 0) { fused.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
    }
}
