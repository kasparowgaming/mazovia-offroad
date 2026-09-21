package pl.mazovia.offroad.ui

import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import pl.mazovia.offroad.data.db.dao.*
import pl.mazovia.offroad.data.db.entity.*
import pl.mazovia.offroad.data.repository.*
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.ui.map.components.*
import pl.mazovia.offroad.ui.postride.PostRideViewModel
import pl.mazovia.offroad.ui.routes.SavedRoutesViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class TrustWorkflowsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }
    @After fun teardown() { Dispatchers.resetMain(); unmockkAll() }

    @Test fun `unclassified ride cannot display a fake 100 percent even with distance`() = runTest {
        val metrics = RideMetrics(1000.0, 1000.0, 0.0, 10.0, 20.0, 360)
        assertNull(metrics.measuredOffRoadPercentage)
        assertEquals(60.0, metrics.copy(offRoadDistanceMeters = 600.0,
            terrainClassificationAvailable = true).measuredOffRoadPercentage!!, 0.0)
        assertNull(metrics.copy(totalDistanceMeters = 0.0, terrainClassificationAvailable = true).measuredOffRoadPercentage)
        val dao = mockk<RideDao>(relaxed = true)
        val repository = RideRepository(dao, mockk(relaxed = true))
        val entity = slot<RideEntity>()
        coEvery { dao.insertRide(capture(entity)) } just Runs
        repository.saveRide(Ride("ride", 1, distanceMeters = 1000.0, durationSeconds = 360, metrics = metrics))
        coEvery { dao.getRideById("ride") } answers { entity.captured }
        assertNull(repository.getRideById("ride")!!.metrics!!.measuredOffRoadPercentage)
        repository.saveRide(Ride("ride", 1, distanceMeters = 1000.0, durationSeconds = 360,
            metrics = metrics.copy(terrainClassificationAvailable = true)))
        assertEquals(100.0, repository.getRideById("ride")!!.metrics!!.measuredOffRoadPercentage!!, 0.0)
    }

    @Test fun `feedback action persists answer clears pending question and completes without duplicate ride`() = runTest {
        val rideDao = mockk<RideDao>(relaxed = true)
        val feedbackDao = mockk<RoadFeedbackDao>()
        val points = mockk<TrackPointDao>(relaxed = true)
        var ride = RideEntity("ride", 1, 2, 1000.0, 60, "COMPLETED", pendingFeedback = listOf("q"))
        var question = RoadFeedbackEntity("q", "ride", null, 52.0, 22.0, 52.1, 22.1,
            "ROAD_EXISTS", null, 2, null)
        coEvery { rideDao.getRideById("ride") } answers { ride }
        coEvery { rideDao.updateRide(any()) } answers { ride = firstArg() }
        coEvery { feedbackDao.getFeedbackForRide("ride") } answers { listOf(question) }
        coEvery { feedbackDao.getFeedbackById("q") } answers { question }
        coEvery { feedbackDao.answerPending("q", "YES") } answers { question = question.copy(answer = "YES"); 1 }
        val model = PostRideViewModel(RideRepository(rideDao, points), FeedbackRepository(feedbackDao))
        model.load("ride"); runCurrent()
        model.openFeedback(); runCurrent()
        assertEquals("q", model.state.value.questions.single().id)
        model.answer(FeedbackAnswer.YES); runCurrent()
        assertEquals("YES", question.answer)
        assertTrue(ride.pendingFeedback!!.isEmpty())
        assertTrue(model.state.value.completed)
        assertTrue(model.state.value.questions.isEmpty())
        coVerify(exactly = 0) { rideDao.insertRide(any()) }
        model.closeFeedback(); model.openFeedback(); runCurrent()
        assertTrue(model.state.value.questions.isEmpty())
        coVerify(exactly = 1) { feedbackDao.answerPending(any(), any()) }
    }

    @Test fun `feedback failure leaves question pending with safe retry message`() = runTest {
        val rideDao = mockk<RideDao>(relaxed = true)
        coEvery { rideDao.getRideById("ride") } returns RideEntity("ride", 1, 2, 1000.0, 60, "COMPLETED")
        val feedbackDao = mockk<RoadFeedbackDao>()
        val question = RoadFeedbackEntity("q", "ride", null, 52.0, 22.0, 52.1, 22.1, "ROAD_EXISTS", null, 2, null)
        coEvery { feedbackDao.getFeedbackForRide(any()) } returns listOf(question)
        coEvery { feedbackDao.getFeedbackById(any()) } returns question
        coEvery { feedbackDao.answerPending(any(), any()) } throws IllegalStateException("SQL internal secret")
        val model = PostRideViewModel(RideRepository(rideDao, mockk(relaxed = true)), FeedbackRepository(feedbackDao))
        model.load("ride"); runCurrent(); model.openFeedback(); runCurrent()
        model.answer(FeedbackAnswer.YES); runCurrent()
        assertEquals("q", model.state.value.questions.single().id)
        assertEquals(RiderMessages.FEEDBACK, model.state.value.error)
        assertFalse(model.state.value.completed)
    }

    @Test fun `saved route reopens exact geometry exports and requires confirmed deletion`() = runTest {
        val start = GeoPoint(52.0, 22.0); val end = GeoPoint(52.1, 22.1)
        val segment = RouteSegment(listOf(start, end), 1000.0, Surface.GRAVEL, HighwayType.TRACK)
        val route = Route("route", start, end, listOf(segment), RouteMetrics.fromSegments(listOf(segment)), RoutingProfile.TERENOWY)
        val entity = SavedRouteEntity("route", "Trasa", 1, 52.0, 22.0, 52.1, 22.1,
            "TERENOWY", 1000.0, 100.0, kotlinx.serialization.json.Json.encodeToString(Route.serializer(), route))
        val dao = mockk<SavedRouteDao>(relaxed = true)
        every { dao.getAllRoutes() } returns flowOf(listOf(entity))
        coEvery { dao.getRouteById("route") } returns entity
        val model = SavedRoutesViewModel(RouteRepository(dao))
        var opened: Route? = null
        model.open("route") { opened = it }; runCurrent()
        assertEquals(route, opened)
        val out = java.io.ByteArrayOutputStream()
        pl.mazovia.offroad.domain.gpx.GpxWriter().writeRoute(out, opened!!)
        assertTrue(out.toString("UTF-8").contains("lat=\"52.0\""))
        model.confirmDelete(); runCurrent()
        model.requestDelete("route"); runCurrent()
        coVerify(exactly = 0) { dao.deleteRoute(any()) }
        model.cancelDelete(); model.confirmDelete(); runCurrent()
        coVerify(exactly = 0) { dao.deleteRoute(any()) }
        model.requestDelete("route"); model.confirmDelete(); runCurrent()
        coVerify(exactly = 1) { dao.deleteRoute(entity) }
        assertNull(model.deleteId.value)
        coEvery { dao.getRouteById("route") } returns entity.copy(routeDataJson = "internal invalid JSON")
        model.open("route") { fail("Corrupt route was opened") }; runCurrent()
        assertEquals(RiderMessages.SAVED_ROUTE, model.error.value)
    }

    @Test fun `riding zoom buttons change camera zoom exactly once and respect engine bounds`() {
        val state = MapZoomState(); val consumer = MapZoomConsumer()
        var zoom = 16.0; var calls = 0
        fun apply() = consumer.apply(state.steps, zoom, 2.0, 18.0) { zoom = it; calls++ }
        state.zoomIn(); apply(); assertEquals(17.0, zoom, 0.0)
        apply(); assertEquals(1, calls)
        state.zoomOut(); apply(); assertEquals(16.0, zoom, 0.0)
        repeat(5) { state.zoomIn() }; apply(); assertEquals(18.0, zoom, 0.0)
        repeat(30) { state.zoomOut() }; apply(); assertEquals(2.0, zoom, 0.0)
    }

    @Test fun `routing failures have rider safe explanations and actions`() {
        pl.mazovia.offroad.domain.routing.RoutingError.entries.forEach {
            val text = RiderMessages.routing(it)
            assertFalse(text.contains(it.name))
            assertFalse(text.contains("GraphHopper"))
            assertTrue(text.split('.').size >= 3)
        }
    }
}
