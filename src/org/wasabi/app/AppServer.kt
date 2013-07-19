package org.wasabi.app

import org.wasabi.http.HttpServer
import org.wasabi.configuration.ConfigurationStorage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.wasabi.routing.InterceptOn
import java.util.ArrayList
import org.wasabi.routing.Route
import io.netty.handler.codec.http.HttpMethod
import org.wasabi.routing.RouteHandler
import org.wasabi.routing.RouteAlreadyExistsException
import java.util.HashMap
import org.wasabi.routing.InterceptorEntry
import org.wasabi.interceptors.LoggingInterceptor
import org.wasabi.interceptors.Interceptor

public class AppServer(val configuration: AppConfiguration = AppConfiguration()) {

    private val logger = LoggerFactory.getLogger(javaClass<AppServer>())
    private val httpServer: HttpServer
    private var running = false

    public val routes: ArrayList<Route> = ArrayList<Route>()
    public val interceptors : ArrayList<InterceptorEntry>  = ArrayList<InterceptorEntry>()


    private fun addRoute(method: HttpMethod, path: String, vararg handler: RouteHandler.() -> Unit) {
        val existingRoute = routes.filter { it.path == path && it.method == method }
        if (existingRoute.count() >= 1) {
            throw RouteAlreadyExistsException(existingRoute.first!!)
        }
        routes.add(Route(path, method, HashMap<String, String>(), *handler))
    }

    {
        httpServer = HttpServer(this)
        if (configuration.enableLogging) {
            intercept(LoggingInterceptor())
        }
    }

    public val isRunning: Boolean
        get ()
        {
            return running
        }

    public fun start(wait: Boolean = true) {
        logger!!.info(configuration.welcomeMessage)

        running = true
        httpServer.start(wait)

    }

    public fun stop() {
        httpServer.stop()
        logger!!.info("Server Stopped")
        running = false
    }


    public fun getx(path: String, vararg handlers: Pair<String, (RouteHandler.() -> Unit)>) {

    }

    public fun get(path: String, vararg handlers: RouteHandler.() -> Unit) {
        addRoute(HttpMethod.GET, path, *handlers)
    }

    public fun post(path: String, vararg handlers: RouteHandler.() -> Unit) {
        addRoute(HttpMethod.POST, path, *handlers)
    }

    public fun put(path: String, vararg handlers: RouteHandler.() -> Unit) {
        addRoute(HttpMethod.PUT, path, *handlers)
    }

    public fun head(path: String, vararg handlers: RouteHandler.() -> Unit) {
        addRoute(HttpMethod.HEAD, path, *handlers)
    }

    public fun delete(path: String, vararg handlers: RouteHandler.() -> Unit) {
        addRoute(HttpMethod.DELETE, path, *handlers)
    }

    public fun options(path: String, vararg handler: RouteHandler.() -> Unit) {
        addRoute(HttpMethod.OPTIONS, path, *handler)
    }

    public fun intercept(interceptor: Interceptor, path: String = "*", interceptOn: InterceptOn = InterceptOn.PreRequest) {
        interceptors.add(InterceptorEntry(interceptor, path, interceptOn))
    }


}


