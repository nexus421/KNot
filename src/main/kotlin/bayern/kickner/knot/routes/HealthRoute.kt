package bayern.kickner.knot.routes

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /health`: unauthenticated liveness check for monitoring. Answers `ok` as long as the process runs.
 * A config that failed to load never gets this far, because KNot exits at startup instead.
 */
fun Route.healthRoute() {
    get("/health") {
        call.respondText("ok")
    }
}
