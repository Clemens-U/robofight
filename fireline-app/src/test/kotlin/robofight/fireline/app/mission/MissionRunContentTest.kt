package robofight.fireline.app.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.FailureReason
import robofight.fireline.Missions
import robofight.fireline.RunStatus
import robofight.fireline.app.view.EdgeWallGeometry
import robofight.fireline.session.MissionSession

/**
 * The run surface's accessibility lines (milestone 7 gate + commit 5edf5e3):
 * the MISSION GRID content description is composed by
 * `MissionRunSurface.gridContentDescription` from the session's
 * `TelemetrySnapshot` only - never from a raw tick 0 world and never from the
 * VM X/Y registers, which is exactly the defect the device regression found
 * ("rover x 0 y 0" for a whole moving run). Pinned at a real tick, on the same
 * engine anchors the engine-side `MissionSessionTest` pins.
 */
class MissionRunContentTest {

    private fun sessionAtTick(missionId: Int, ticks: Int): MissionSession {
        val mission = Missions.byId(missionId)!!
        val session = MissionSession(mission, "REFERENCE.ASM", mission.referenceSource)
        repeat(ticks) {
            if (!session.finished) session.step()
        }
        return session
    }

    @Test
    fun gridLineAnnouncesTheRoverFromTheSessionAtTick12() {
        val session = sessionAtTick(2, 12)
        val telemetry = session.telemetry()
        val description = MissionRunSurface.gridContentDescription(
            missionId = 2,
            finished = session.finished,
            telemetry = telemetry,
            thinWalls = EdgeWallGeometry
                .segmentsOf(session.currentWorld.wallVertRowMajor(), session.currentWorld.wallHorizRowMajor())
                .size,
        )
        // Engine anchor for mission 02 CORRIDOR at tick 12: rover (2, 7) facing S.
        assertEquals(12, telemetry.tick)
        assertEquals(2, telemetry.roverX)
        assertEquals(7, telemetry.roverY)
        assertEquals("S", telemetry.roverFacing)
        assertTrue(description.contains("rover at x 2 y 7 facing S"), description)
        assertTrue(description.contains("tick 12 of"), description)
        // The old broken shape must be gone: the VM registers are not the rover.
        assertFalse(description.contains("rover x "), description)
        assertEquals(session.currentWorld.rover.x, telemetry.roverX)
        assertEquals(session.currentWorld.rover.y, telemetry.roverY)
    }

    @Test
    fun gridLineCountsExactlyTheSegmentsTheViewStrokes() {
        val mission = Missions.byId(6)!!
        val session = MissionSession(mission, "REFERENCE.ASM", mission.referenceSource)
        val world = session.currentWorld
        val segments = EdgeWallGeometry.segmentsOf(world.wallVertRowMajor(), world.wallHorizRowMajor())
        assertEquals(13, segments.size, "mission 06 GO AROUND carries 13 vertical edge segments")
        val description = MissionRunSurface.gridContentDescription(
            missionId = mission.id,
            finished = false,
            telemetry = session.telemetry(),
            thinWalls = segments.size,
        )
        assertTrue(description.contains("thin walls 13"), description)
    }

    @Test
    fun statusBandUsesTheReleasedCopy() {
        assertEquals("SUCCESS", MissionRunSurface.statusText(RunStatus.SUCCESS, null, false, false))
        assertEquals(
            "FAILURE",
            MissionRunSurface.statusText(RunStatus.FAILURE, FailureReason.CIVILIAN_LOST, false, false),
        )
        assertEquals("FAILURE", MissionRunSurface.statusText(RunStatus.FAILURE, null, false, false))
        assertEquals("TICK GUARD REACHED", MissionRunSurface.statusText(null, null, true, false))
        assertEquals("RUNNING", MissionRunSurface.statusText(null, null, false, true))
        assertEquals("PAUSED", MissionRunSurface.statusText(null, null, false, false))
    }
}
